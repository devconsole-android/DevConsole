/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole.server.ktor

import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkRequestInput
import io.devconsole.network.NetworkResponseInput
import io.devconsole.network.NetworkTransaction
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.server.api.BrowserSession
import io.devconsole.server.api.ServerMetadata
import io.devconsole.server.api.SessionAuthority
import io.devconsole.server.api.SessionCodeAuthority
import io.ktor.client.request.HttpRequestBuilder
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

class NetworkEndpointStatsRoutesTest {
    @Test
    fun `endpoints route requires an authenticated session`() =
        testApplication {
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16)))
            application { protectedDevConsoleModule(SessionAuthority()) { networkTransactions = store } }

            val response = client.get("/api/v1/network/endpoints") { header(HttpHeaders.Host, "localhost") }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("AUTH_REQUIRED"))
        }

    @Test
    fun `endpoints route returns aggregated latency statistics for two templated endpoints`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store =
                endpointStore(
                    Triple("GET", "https://api.test/v1/orders/101", 200) to (100L to 120L),
                    Triple("GET", "https://api.test/v1/orders/202", 500) to (200L to 280L),
                    Triple("POST", "https://api.test/v1/checkout", 201) to (300L to 350L),
                )
            application { protectedDevConsoleModule(sessions, sessionCodes) { networkTransactions = store } }
            val session = approvedSession(sessions, sessionCodes)

            val response = client.get("/api/v1/network/endpoints") { authHeaders(session) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"sampleSize\":3"))
            assertTrue(
                body.contains(
                    "\"method\":\"GET\",\"host\":\"api.test\",\"pathTemplate\":\"/v1/orders/{id}\"," +
                        "\"count\":2,\"errorCount\":1,\"p50Ms\":20,\"p95Ms\":80,\"p99Ms\":80,\"maxMs\":80",
                ),
            )
            assertTrue(
                body.contains(
                    "\"method\":\"POST\",\"host\":\"api.test\",\"pathTemplate\":\"/v1/checkout\"," +
                        "\"count\":1,\"errorCount\":0,\"p50Ms\":50,\"p95Ms\":50,\"p99Ms\":50,\"maxMs\":50",
                ),
            )
            assertTrue(body.indexOf("/v1/orders/{id}") < body.indexOf("/v1/checkout"))
        }

    @Test
    fun `endpoints route respects categoryEnabled and sessionId query parameter`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.recommended()))
            val liveStore = InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16)))
            val pastStore =
                InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16))).also { s ->
                    s.record(
                        NetworkTransaction(
                            id = "past-1",
                            startedAtEpochMs = 10,
                            completedAtEpochMs = 45,
                            capture =
                                factory.capture(
                                    NetworkRequestInput("GET", "https://api.test/v1/past/99"),
                                    NetworkResponseInput(200),
                                ),
                        ),
                    )
                }
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    networkTransactions = liveStore
                    retainedNetworkTransactions = { sessionId -> if (sessionId == "run-1") pastStore else null }
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val okPast = client.get("/api/v1/network/endpoints?sessionId=run-1") { authHeaders(session) }
            assertEquals(HttpStatusCode.OK, okPast.status)
            assertTrue(okPast.bodyAsText().contains("\"/v1/past/{id}\""))

            val missingPast = client.get("/api/v1/network/endpoints?sessionId=unknown") { authHeaders(session) }
            assertEquals(HttpStatusCode.NotFound, missingPast.status)
            assertTrue(missingPast.bodyAsText().contains("SESSION_NOT_FOUND"))
        }

    @Test
    fun `endpoints route is refused when network category is disabled`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    metadata = ServerMetadata(captureCategories = listOf("logs"))
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response = client.get("/api/v1/network/endpoints") { authHeaders(session) }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("CATEGORY_DISABLED"))
        }

    @Test
    fun `transactions route filters by body query parameter and rejects overlong body query`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.recommended()))
            val store =
                InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16))).also { s ->
                    s.record(
                        NetworkTransaction(
                            id = "tx-body-match",
                            startedAtEpochMs = 10,
                            completedAtEpochMs = 20,
                            capture =
                                factory.capture(
                                    NetworkRequestInput("POST", "https://api.test/v1/items"),
                                    NetworkResponseInput(
                                        200,
                                        body = "{\"orderId\":\"ORD-7788\"}".encodeToByteArray(),
                                        contentType = "application/json",
                                    ),
                                ),
                        ),
                    )
                    s.record(
                        NetworkTransaction(
                            id = "tx-body-other",
                            startedAtEpochMs = 30,
                            completedAtEpochMs = 40,
                            capture =
                                factory.capture(
                                    NetworkRequestInput("POST", "https://api.test/v1/items"),
                                    NetworkResponseInput(
                                        200,
                                        body = "{\"orderId\":\"ORD-0000\"}".encodeToByteArray(),
                                        contentType = "application/json",
                                    ),
                                ),
                        ),
                    )
                }
            application { protectedDevConsoleModule(sessions, sessionCodes) { networkTransactions = store } }
            val session = approvedSession(sessions, sessionCodes)

            val filtered = client.get("/api/v1/network/transactions?body=ord-7788") { authHeaders(session) }
            assertEquals(HttpStatusCode.OK, filtered.status)
            val text = filtered.bodyAsText()
            assertTrue(text.contains("tx-body-match"))
            assertFalse(text.contains("tx-body-other"))

            val overlong =
                client.get("/api/v1/network/transactions?body=${"x".repeat(257)}") { authHeaders(session) }
            assertEquals(HttpStatusCode.BadRequest, overlong.status)
            assertTrue(overlong.bodyAsText().contains("VALIDATION_FAILED"))
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

private fun endpointStore(
    vararg calls: Pair<Triple<String, String, Int>, Pair<Long, Long>>,
): InMemoryNetworkTransactionStore {
    val factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.recommended()))
    return InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16))).also { store ->
        calls.forEachIndexed { index, (call, timing) ->
            val (method, url, status) = call
            store.record(
                NetworkTransaction(
                    id = "tx-${index + 1}",
                    startedAtEpochMs = timing.first,
                    completedAtEpochMs = timing.second,
                    capture = factory.capture(NetworkRequestInput(method, url), NetworkResponseInput(status)),
                ),
            )
        }
    }
}
