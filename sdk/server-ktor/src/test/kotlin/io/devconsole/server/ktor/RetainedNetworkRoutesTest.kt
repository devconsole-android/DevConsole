/**
 * @author Shakib
 * @since 03/11/26
 */
@file:Suppress("MatchingDeclarationName")

package io.devconsole.server.ktor

import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkRequestInput
import io.devconsole.network.NetworkResponseInput
import io.devconsole.network.NetworkTransaction
import io.devconsole.network.NetworkTransactionStore
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.server.api.BrowserSession
import io.devconsole.server.api.SessionAuthority
import io.devconsole.server.api.SessionCodeAuthority
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `?sessionId=<past run>` on the network list, detail, cURL and HAR/Postman routes. */
class RetainedNetworkRoutesTest {
    private val live = transactionStore("live-tx")
    private val past = transactionStore("past-tx")

    private fun ApplicationTestBuilder.installModule(
        sessions: SessionAuthority,
        codes: SessionCodeAuthority,
    ) {
        application {
            protectedDevConsoleModule(sessions, codes) {
                networkTransactions = live
                currentSessionId = { "run-live" }
                retainedNetworkTransactions = { id -> past.takeIf { id == "run-past" } }
            }
        }
    }

    @Test
    fun `list serves the live run by default and a past run on request`() =
        testApplication {
            val sessions = SessionAuthority()
            val codes = SessionCodeAuthority(sessions)
            installModule(sessions, codes)
            val session = approvedSession(sessions, codes)

            val liveBody = client.get("/api/v1/network/transactions") { authHeaders(session) }.bodyAsText()
            val pastBody =
                client.get("/api/v1/network/transactions?sessionId=run-past") { authHeaders(session) }.bodyAsText()
            val currentBody =
                client.get("/api/v1/network/transactions?sessionId=run-live") { authHeaders(session) }.bodyAsText()

            assertTrue(liveBody.contains("live-tx"))
            assertFalse(liveBody.contains("past-tx"))
            assertTrue(pastBody.contains("past-tx"))
            assertFalse(pastBody.contains("live-tx"))
            assertTrue(currentBody.contains("live-tx"))
        }

    @Test
    fun `detail curl and har read the requested past run`() =
        testApplication {
            val sessions = SessionAuthority()
            val codes = SessionCodeAuthority(sessions)
            installModule(sessions, codes)
            val session = approvedSession(sessions, codes)

            val detail =
                client.get("/api/v1/network/transactions/past-tx?sessionId=run-past") { authHeaders(session) }
            val liveMiss = client.get("/api/v1/network/transactions/past-tx") { authHeaders(session) }
            val curl =
                client.get(
                    "/api/v1/network/transactions/past-tx/curl?sessionId=run-past",
                ) { authHeaders(session) }
            val har = client.get("/api/v1/network/har?sessionId=run-past") { authHeaders(session) }

            assertEquals(HttpStatusCode.OK, detail.status)
            assertEquals(HttpStatusCode.NotFound, liveMiss.status)
            assertTrue(curl.bodyAsText().contains("api.test/past-tx"))
            assertTrue(har.bodyAsText().contains("api.test/past-tx"))
            assertFalse(har.bodyAsText().contains("api.test/live-tx"))
        }

    @Test
    fun `an unknown run answers session not found`() =
        testApplication {
            val sessions = SessionAuthority()
            val codes = SessionCodeAuthority(sessions)
            installModule(sessions, codes)
            val session = approvedSession(sessions, codes)

            val response = client.get("/api/v1/network/transactions?sessionId=run-gone") { authHeaders(session) }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("SESSION_NOT_FOUND"))
        }

    @Test
    fun `post export checks auth before it looks the run up`() =
        testApplication {
            val sessions = SessionAuthority()
            val codes = SessionCodeAuthority(sessions)
            var lookups = 0
            application {
                protectedDevConsoleModule(sessions, codes) {
                    networkTransactions = live
                    currentSessionId = { "run-live" }
                    retainedNetworkTransactions = { id ->
                        lookups++
                        past.takeIf { id == "run-past" }
                    }
                }
            }

            val response =
                client.post("/api/v1/network/har?sessionId=run-past") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("id=past-tx")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(0, lookups)
        }
}

/** `DELETE /api/v1/runs/{id}` and `POST /api/v1/runs/{id}/pin`: CSRF-gated, and never the live run. */
class RunControlRoutesTest {
    @Test
    fun `deleting a finished run calls the store and the live run is refused`() =
        testApplication {
            val sessions = SessionAuthority()
            val codes = SessionCodeAuthority(sessions)
            val deleted = mutableListOf<String>()
            application {
                protectedDevConsoleModule(sessions, codes) {
                    currentSessionId = { "run-live" }
                    deleteRun = { id ->
                        deleted += id
                        true
                    }
                }
            }
            val session = approvedSession(sessions, codes)

            val ok = client.delete("/api/v1/runs/run-old") { controlHeaders(session) }
            val live = client.delete("/api/v1/runs/run-live") { controlHeaders(session) }
            val noCsrf = client.delete("/api/v1/runs/run-old") { authHeaders(session) }

            assertEquals(HttpStatusCode.OK, ok.status)
            assertEquals(HttpStatusCode.Conflict, live.status)
            assertEquals(HttpStatusCode.Forbidden, noCsrf.status)
            assertEquals(listOf("run-old"), deleted)
        }

    @Test
    fun `pinning passes the requested state and reports a refusal`() =
        testApplication {
            val sessions = SessionAuthority()
            val codes = SessionCodeAuthority(sessions)
            val calls = mutableListOf<Pair<String, Boolean>>()
            application {
                protectedDevConsoleModule(sessions, codes) {
                    pinRun = { id, pinned ->
                        calls += id to pinned
                        id == "run-old"
                    }
                }
            }
            val session = approvedSession(sessions, codes)

            val pin =
                client.post("/api/v1/runs/run-old/pin") {
                    controlHeaders(session)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("pinned=true")
                }
            val unpinUnknown =
                client.post("/api/v1/runs/run-gone/pin") {
                    controlHeaders(session)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("pinned=false")
                }

            assertEquals(HttpStatusCode.OK, pin.status)
            assertTrue(pin.bodyAsText().contains("\"pinned\":true"))
            assertEquals(HttpStatusCode.Conflict, unpinUnknown.status)
            assertEquals(listOf("run-old" to true, "run-gone" to false), calls)
        }
}

private fun HttpRequestBuilder.controlHeaders(session: BrowserSession) {
    header(HttpHeaders.Host, "localhost")
    header(HttpHeaders.Authorization, "Bearer ${session.token}")
    header(HttpHeaders.Origin, "http://localhost")
    header("X-DevConsole-CSRF", session.csrfToken)
}

private fun transactionStore(vararg ids: String): NetworkTransactionStore =
    InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16))).also { store ->
        ids.forEachIndexed { index, id ->
            store.record(
                NetworkTransaction(
                    id = id,
                    startedAtEpochMs = index.toLong() + 1,
                    completedAtEpochMs = index.toLong() + 2,
                    capture =
                        NetworkCaptureFactory(RedactionEngine(RedactionPolicy.recommended())).capture(
                            NetworkRequestInput("GET", "https://api.test/$id"),
                            NetworkResponseInput(200),
                        ),
                ),
            )
        }
    }

private fun HttpRequestBuilder.authHeaders(session: BrowserSession) {
    header(HttpHeaders.Host, "localhost")
    header(HttpHeaders.Authorization, "Bearer ${session.token}")
}

private suspend fun ApplicationTestBuilder.approvedSession(
    sessions: SessionAuthority,
    sessionCodes: SessionCodeAuthority,
): BrowserSession {
    val code = sessionCodes.issueCode().code
    val response =
        client.post("/api/v1/auth/session-code/exchange") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            setBody("code=$code")
        }
    val token = Regex("\"accessToken\":\"([^\"]+)\"").find(response.bodyAsText())!!.groupValues[1]
    return sessions.sessionForToken(token)!!
}
