/**
 * @author Shakib
 * @since 03/08/26
 */
package io.devconsole.server.ktor

import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkRequestInput
import io.devconsole.network.NetworkRequestMetadata
import io.devconsole.network.NetworkResponseInput
import io.devconsole.network.NetworkTransaction
import io.devconsole.network.NetworkTransactionFilters
import io.devconsole.network.NetworkTransactionQuery
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.server.api.BrowserSession
import io.devconsole.server.api.SessionAuthority
import io.devconsole.server.api.SessionCodeAuthority
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/**
 * Query equivalence: `GET /api/v1/network/transactions`'s query-param parsing
 * (`ApplicationCall.networkTransactionQuery` in `DevConsoleKtorModule.kt`, private to this module)
 * must resolve to a [NetworkTransactionQuery] that behaves identically to one a caller builds by
 * hand and hands straight to [io.devconsole.network.NetworkTransactionStore.page] --
 * `InMemoryNetworkTransactionStoreTest` already covers the store's filter semantics directly, and
 * `DevConsoleKtorModuleTest` exercises the route with one large combined query string, but neither
 * proves the two paths agree row-for-row, in order, across the individual filter dimensions. This
 * test drives both paths against the same store and the same filter set and asserts they return
 * the exact same ordered transaction ids -- for every dimension named in the task (method, host,
 * status, path, contentType, duration, hasError, tag, free-text, and an inclusive time range), a
 * multi-dimension combination, and cursor pagination.
 */
class NetworkTransactionQueryEquivalenceTest {
    private val captureFactory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default()))

    private fun store(): InMemoryNetworkTransactionStore =
        InMemoryNetworkTransactionStore(NetworkCursorCodec("query-equivalence-key".encodeToByteArray())).apply {
            record(
                transaction(
                    TransactionFixture(
                        "t1",
                        "POST",
                        "https://api.test/v1/orders",
                        300,
                        310,
                        503,
                        error = "timeout",
                        tag = "source" to "composer",
                    ),
                ),
            )
            record(transaction(TransactionFixture("t2", "POST", "https://api.test/v1/orders", 200, 210, 201)))
            record(transaction(TransactionFixture("t3", "GET", "https://api.test/v1/profile", 250, 253, 200)))
            record(transaction(TransactionFixture("t4", "POST", "https://other.test/v1/orders", 280, 290, 503)))
        }

    /** Groups [transaction]'s fixture fields so the helper itself stays under the parameter-count limit. */
    private data class TransactionFixture(
        val id: String,
        val method: String,
        val url: String,
        val startedAt: Long,
        val completedAt: Long,
        val status: Int,
        val error: String? = null,
        val tag: Pair<String, String>? = null,
    )

    private fun transaction(fixture: TransactionFixture): NetworkTransaction {
        val request =
            NetworkRequestInput(fixture.method, fixture.url).let { input ->
                if (fixture.tag != null) {
                    input.withMetadata(NetworkRequestMetadata(tags = mapOf(fixture.tag)))
                } else {
                    input
                }
            }
        val response = NetworkResponseInput(fixture.status, contentType = "application/json", error = fixture.error)
        return NetworkTransaction(
            id = fixture.id,
            startedAtEpochMs = fixture.startedAt,
            completedAtEpochMs = fixture.completedAt,
            capture = captureFactory.capture(request, response),
        )
    }

    /** Local copy of `DevConsoleKtorModuleTest`'s file-private helper of the same shape; not visible across files. */
    private suspend fun HttpClient.exchangeSession(
        sessions: SessionAuthority,
        sessionCodes: SessionCodeAuthority,
    ): BrowserSession {
        val response =
            post("/api/v1/auth/session-code/exchange") {
                header(HttpHeaders.Host, "localhost")
                header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                setBody("code=${sessionCodes.issueCode().code}&browserLabel=Test+Browser")
            }
        val token = Regex("\"accessToken\":\"([^\"]+)\"").find(response.bodyAsText())!!.groupValues[1]
        return sessions.sessionForToken(token)!!
    }

    private fun transactionIds(responseBody: String): List<String> =
        Regex("\"id\":\"([^\"]+)\"")
            .findAll(responseBody.substringBefore("\"page\""))
            .map { it.groupValues[1] }
            .toList()

    /**
     * Runs the same filter set through the store directly and through the route, asserting an
     * identical ordered id list.
     */
    private fun assertRouteMatchesStore(
        query: NetworkTransactionQuery,
        routeQueryString: String,
        expectedIds: List<String>,
    ) {
        val transactionStore = store()
        val direct = transactionStore.page(query).transactions.map(NetworkTransaction::id)
        assertEquals("direct store.page() did not return the expected rows", expectedIds, direct)

        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    networkTransactions = transactionStore
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val response =
                client.get("/api/v1/network/transactions?$routeQueryString") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                "route parsing of '$routeQueryString' did not resolve to the same NetworkTransactionQuery " +
                    "the store was queried with directly",
                expectedIds,
                transactionIds(response.bodyAsText()),
            )
        }
    }

    @Test
    fun `method and host filters agree between the store and the route`() {
        assertRouteMatchesStore(
            query = NetworkTransactionQuery(methods = setOf("POST"), hosts = setOf("api.test")),
            routeQueryString = "method=POST&host=api.test",
            expectedIds = listOf("t1", "t2"),
        )
    }

    @Test
    fun `an exact status set agrees between the store and the route`() {
        assertRouteMatchesStore(
            query = NetworkTransactionQuery(statuses = setOf(503)),
            routeQueryString = "status=503",
            expectedIds = listOf("t1", "t4"),
        )
    }

    @Test
    fun `path and contentType filters agree between the store and the route`() {
        assertRouteMatchesStore(
            query =
                NetworkTransactionQuery()
                    .withFilters(
                        NetworkTransactionFilters(
                            paths = setOf("/v1/orders"),
                            contentTypes = setOf("application/json"),
                        ),
                    ),
            routeQueryString = "path=%2Fv1%2Forders&contentType=application%2Fjson",
            expectedIds = listOf("t1", "t4", "t2"),
        )
    }

    @Test
    fun `duration bounds agree between the store and the route`() {
        assertRouteMatchesStore(
            query =
                NetworkTransactionQuery().withFilters(NetworkTransactionFilters(minDurationMs = 5, maxDurationMs = 15)),
            routeQueryString = "minDurationMs=5&maxDurationMs=15",
            expectedIds = listOf("t1", "t4", "t2"),
        )
    }

    @Test
    fun `hasError agrees between the store and the route`() {
        assertRouteMatchesStore(
            query = NetworkTransactionQuery().withFilters(NetworkTransactionFilters(hasError = true)),
            routeQueryString = "error=true",
            expectedIds = listOf("t1"),
        )
    }

    @Test
    fun `a tag filter agrees between the store and the route`() {
        assertRouteMatchesStore(
            query =
                NetworkTransactionQuery().withFilters(NetworkTransactionFilters(tags = mapOf("source" to "composer"))),
            routeQueryString = "tag=source%3Dcomposer",
            expectedIds = listOf("t1"),
        )
    }

    @Test
    fun `free-text search agrees between the store and the route`() {
        assertRouteMatchesStore(
            query = NetworkTransactionQuery().withFilters(NetworkTransactionFilters(query = "orders")),
            routeQueryString = "query=orders",
            expectedIds = listOf("t1", "t4", "t2"),
        )
    }

    @Test
    fun `an inclusive time range agrees between the store and the route`() {
        assertRouteMatchesStore(
            query =
                NetworkTransactionQuery().withFilters(NetworkTransactionFilters(fromEpochMs = 200, toEpochMs = 290)),
            routeQueryString = "from=200&to=290",
            expectedIds = listOf("t4", "t3", "t2"),
        )
    }

    @Test
    fun `method host status path contentType duration hasError tag and free-text combine identically`() {
        assertRouteMatchesStore(
            query =
                NetworkTransactionQuery(methods = setOf("POST"), hosts = setOf("api.test"))
                    .withFilters(
                        NetworkTransactionFilters(
                            statusFrom = 500,
                            statusTo = 599,
                            paths = setOf("/v1/orders"),
                            contentTypes = setOf("application/json"),
                            minDurationMs = 1,
                            maxDurationMs = 20,
                            hasError = true,
                            tags = mapOf("source" to "composer"),
                            query = "timeout",
                        ),
                    ),
            routeQueryString =
                "method=POST&host=api.test&statusFrom=500&statusTo=599&path=%2Fv1%2Forders" +
                    "&contentType=application%2Fjson&minDurationMs=1&maxDurationMs=20&error=true" +
                    "&tag=source%3Dcomposer&query=timeout",
            expectedIds = listOf("t1"),
        )
    }

    @Test
    fun `a cursor minted by a direct store query is honored by the route under the same filters`() =
        testApplication {
            val transactionStore = store()
            val query = NetworkTransactionQuery(limit = 1, methods = setOf("POST"), hosts = setOf("api.test"))
            val firstPage = transactionStore.page(query)
            assertEquals(listOf("t1"), firstPage.transactions.map(NetworkTransaction::id))
            assertTrue(firstPage.hasMore)
            val mintedCursor = requireNotNull(firstPage.nextCursor) { "expected a cursor for a page with more rows" }

            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    networkTransactions = transactionStore
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val response =
                client.get(
                    "/api/v1/network/transactions?limit=1&method=POST&host=api.test&cursor=" +
                        URLEncoder.encode(mintedCursor, "UTF-8"),
                ) {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val responseBody = response.bodyAsText()
            assertFalse(
                "a cursor minted directly against the store must not be rejected by the route",
                responseBody.contains("VALIDATION_FAILED"),
            )
            assertEquals(listOf("t2"), transactionIds(responseBody))
        }
}
