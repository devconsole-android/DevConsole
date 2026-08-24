/**
 * @author Shakib
 * @since 24/08/26
 */
package io.devconsole.server.ktor

import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkRequestInput
import io.devconsole.network.NetworkResponseInput
import io.devconsole.network.NetworkTransaction
import io.devconsole.network.NetworkTransactionQuery
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.server.api.BrowserSession
import io.devconsole.server.api.CommandAuditResult
import io.devconsole.server.api.InMemoryCommandAuditLog
import io.devconsole.server.api.ServerMetadata
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers `DELETE /api/v1/network/transactions` -- the dashboard's "clear captures" action. Follows
 * [EvidenceAndScreenshotRoutesTest]'s fixture conventions (same `approvedSession`/`controlHeaders`
 * pattern, same `testApplication` setup), since this route shares that family's gate: authenticated
 * bearer session plus origin/CSRF, with no separate editing capability.
 */
class NetworkClearRouteTest {
    @Test
    fun `clearing requires an authenticated session`() =
        testApplication {
            val store = networkStore("tx-1")
            application { devConsoleModule(SessionAuthority()) { networkTransactions = store } }

            val response = client.delete("/api/v1/network/transactions") { header(HttpHeaders.Host, "localhost") }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("AUTH_REQUIRED"))
            assertEquals(1, store.page(NetworkTransactionQuery(limit = 10)).transactions.size)
        }

    @Test
    fun `clearing requires csrf and is audited on rejection`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val audit = InMemoryCommandAuditLog()
            val store = networkStore("tx-1")
            application {
                devConsoleModule(sessions, sessionCodes) {
                    networkTransactions = store
                    commandAuditLog = audit
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.delete("/api/v1/network/transactions") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("CSRF_INVALID"))
            assertTrue(audit.events().any { it.commandType == "network.clear" })
            assertEquals(1, store.page(NetworkTransactionQuery(limit = 10)).transactions.size)
        }

    @Test
    fun `clearing empties the store and reports success`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val audit = InMemoryCommandAuditLog()
            val store = networkStore("tx-1", "tx-2")
            application {
                devConsoleModule(sessions, sessionCodes) {
                    networkTransactions = store
                    commandAuditLog = audit
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response = client.delete("/api/v1/network/transactions") { controlHeaders(session) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("cleared"))
            assertTrue(store.page(NetworkTransactionQuery(limit = 10)).transactions.isEmpty())
            assertNull(store.find("tx-1"))
            assertTrue(
                audit.events().any {
                    it.commandType == "network.clear" && it.result == CommandAuditResult.SUCCESS
                },
            )
        }

    /** The listing route is what the dashboard re-reads after a clear; it must agree with the store. */
    @Test
    fun `the listing route reports no transactions after a clear`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = networkStore("tx-1", "tx-2")
            application { devConsoleModule(sessions, sessionCodes) { networkTransactions = store } }
            val session = approvedSession(sessions, sessionCodes)
            val before = client.get("/api/v1/network/transactions") { authHeaders(session) }
            assertTrue(before.bodyAsText().contains("tx-1"))

            client.delete("/api/v1/network/transactions") { controlHeaders(session) }
            val after = client.get("/api/v1/network/transactions") { authHeaders(session) }

            assertEquals(HttpStatusCode.OK, after.status)
            assertFalse(after.bodyAsText().contains("tx-1"))
        }

    /**
     * Same category gate every other network route carries: a host that never enabled
     * network capture must not expose a route that mutates the network buffer either.
     */
    @Test
    fun `clearing is refused while the network category is disabled`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val audit = InMemoryCommandAuditLog()
            val store = networkStore("tx-1")
            application {
                devConsoleModule(sessions, sessionCodes) {
                    networkTransactions = store
                    commandAuditLog = audit
                    metadata = ServerMetadata(captureCategories = listOf("logs"))
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response = client.delete("/api/v1/network/transactions") { controlHeaders(session) }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("CATEGORY_DISABLED"))
            assertEquals(1, store.page(NetworkTransactionQuery(limit = 10)).transactions.size)
            assertTrue(
                audit.events().any {
                    it.commandType == "network.clear" && it.result == CommandAuditResult.REJECTED
                },
            )
        }

    /** Clearing an already-empty store is a no-op success, not a 404 -- the button stays idempotent. */
    @Test
    fun `clearing an empty store still succeeds`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = networkStore()
            application { devConsoleModule(sessions, sessionCodes) { networkTransactions = store } }
            val session = approvedSession(sessions, sessionCodes)

            val response = client.delete("/api/v1/network/transactions") { controlHeaders(session) }

            assertEquals(HttpStatusCode.OK, response.status)
        }
}

private fun networkStore(vararg ids: String): InMemoryNetworkTransactionStore =
    InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16))).also { store ->
        ids.forEachIndexed { index, id ->
            store.record(
                NetworkTransaction(
                    id = id,
                    startedAtEpochMs = index.toLong() + 1,
                    completedAtEpochMs = index.toLong() + 2,
                    capture =
                        NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                            NetworkRequestInput("GET", "https://api.test/$id"),
                            NetworkResponseInput(200),
                        ),
                ),
            )
        }
    }

private fun HttpRequestBuilder.controlHeaders(session: BrowserSession) {
    header(HttpHeaders.Host, "localhost")
    header(HttpHeaders.Authorization, "Bearer ${session.token}")
    header(HttpHeaders.Origin, "http://localhost")
    header("X-DevConsole-CSRF", session.csrfToken)
}

/** Bearer-only headers for a plain GET -- no Origin/CSRF, since a read route never needs them. */
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
