/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole.network.ktor

import io.devconsole.mocks.MockAction
import io.devconsole.mocks.MockRule
import io.devconsole.network.BodyPreview
import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkTransaction
import io.devconsole.network.NetworkTransactionQuery
import io.devconsole.network.NetworkTransactionRecorder
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import io.devconsole.mocks.MockEngine as DevConsoleMockEngine
import io.ktor.client.engine.mock.MockEngine as KtorMockEngine

private const val MOCKED_BODY = "{\"mocked\":true}"
private const val NETWORK_BODY = "network"
private const val ORDER_PATH = "/orders"
private const val ORDER_URL = "https://api.test/orders"
private const val ORDER_RULE_PRIORITY = 10
private const val DEFAULT_AWAIT_MAX_WAIT_MS = 2_000L
private const val AWAIT_POLL_INTERVAL_MS = 10L
private const val THROTTLE_BYTES_PER_SECOND = 1_024L

class DevConsoleKtorMocksTest {
    @Test
    fun `static rule returns the mocked response without touching the network`() =
        runBlocking {
            var networkCalls = 0
            val networkEngine =
                KtorMockEngine { _ ->
                    networkCalls++
                    respond(
                        content = NETWORK_BODY,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                    )
                }
            val client = httpClient(networkEngine, DevConsoleMockEngine(listOf(staticOrderRule())))

            val response = client.get(ORDER_URL)

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(MOCKED_BODY, response.bodyAsText())
            assertEquals("application/json", response.headers[HttpHeaders.ContentType])
            assertEquals(0, networkCalls)
        }

    @Test
    fun `unmatched request reaches the network`() =
        runBlocking {
            var networkCalls = 0
            val networkEngine =
                KtorMockEngine { _ ->
                    networkCalls++
                    respond(content = NETWORK_BODY, status = HttpStatusCode.OK)
                }
            val client =
                httpClient(
                    networkEngine,
                    DevConsoleMockEngine(listOf(staticOrderRule(host = "mocked.test"))),
                )

            val response = client.get(ORDER_URL)

            assertEquals(NETWORK_BODY, response.bodyAsText())
            assertEquals(1, networkCalls)
        }

    @Test
    fun `disabled engine reaches the network`() =
        runBlocking {
            var networkCalls = 0
            val networkEngine =
                KtorMockEngine { _ ->
                    networkCalls++
                    respond(content = NETWORK_BODY, status = HttpStatusCode.OK)
                }
            val client =
                httpClient(
                    networkEngine,
                    DevConsoleMockEngine(initialRules = listOf(staticOrderRule()), enabled = false),
                )

            val response = client.get(ORDER_URL)

            assertEquals(NETWORK_BODY, response.bodyAsText())
            assertEquals(1, networkCalls)
        }

    @Test
    fun `failure action throws an IOException`() {
        val client =
            httpClient(
                KtorMockEngine { _ -> respond(content = NETWORK_BODY, status = HttpStatusCode.OK) },
                DevConsoleMockEngine(
                    listOf(
                        staticOrderRule(action = MockAction.ConnectionFailure("simulated failure")),
                    ),
                ),
            )

        assertThrows(IOException::class.java) {
            runBlocking { client.get(ORDER_URL) }
        }
    }

    @Test
    fun `map remote rewrites the host the network engine sees`() =
        runBlocking {
            var seenHost: String? = null
            val networkEngine =
                KtorMockEngine { request ->
                    seenHost = request.url.host
                    respond(content = "remapped", status = HttpStatusCode.OK)
                }
            val client =
                httpClient(
                    networkEngine,
                    DevConsoleMockEngine(
                        listOf(staticOrderRule(action = MockAction.MapRemote(host = "remapped.test"))),
                    ),
                )

            val response = client.get(ORDER_URL)

            assertEquals("remapped", response.bodyAsText())
            assertEquals("remapped.test", seenHost)
        }

    @Test
    fun `mocked call is captured and tagged when the capture plugin is installed`() =
        runBlocking {
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("1234567890123456".encodeToByteArray()))
            val recorder =
                NetworkTransactionRecorder(
                    factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.recommended())),
                    store = store,
                )
            var networkCalls = 0
            val networkEngine =
                KtorMockEngine { _ ->
                    networkCalls++
                    respond(content = NETWORK_BODY, status = HttpStatusCode.OK)
                }
            val client = httpClient(networkEngine, DevConsoleMockEngine(listOf(staticOrderRule())), recorder)

            val response = client.get(ORDER_URL)
            assertEquals(MOCKED_BODY, response.bodyAsText())

            val transaction = awaitTransaction(store)
            assertEquals("true", transaction.capture.request.metadata.tags["mocked"])
            assertEquals("orders", transaction.capture.request.metadata.tags["mockRuleId"])
            assertEquals(MOCKED_BODY, (transaction.capture.response!!.body as BodyPreview.Text).value)
            assertEquals(0, networkCalls)
        }

    @Test
    fun `status override reaches the app and the capture`() =
        runBlocking {
            val (store, recorder) = captureStore()
            val networkEngine = KtorMockEngine { respond(content = NETWORK_BODY, status = HttpStatusCode.OK) }
            val rule = staticOrderRule(action = MockAction.StatusOverride(HttpStatusCode.ServiceUnavailable.value))
            val client = httpClient(networkEngine, DevConsoleMockEngine(listOf(rule)), recorder)

            val response = client.get(ORDER_URL)

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals(NETWORK_BODY, response.bodyAsText())
            val transaction = awaitTransaction(store)
            assertEquals(HttpStatusCode.ServiceUnavailable.value, transaction.capture.response!!.statusCode)
            assertEquals("orders", transaction.capture.request.metadata.tags["mockRuleId"])
        }

    @Test
    fun `body replacement reaches the app and the capture with a matching length`() =
        runBlocking {
            val (store, recorder) = captureStore()
            val networkEngine =
                KtorMockEngine {
                    respond(
                        content = NETWORK_BODY,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentLength, NETWORK_BODY.length.toString()),
                    )
                }
            val rule = staticOrderRule(action = MockAction.BodyReplacement(MOCKED_BODY))
            val client = httpClient(networkEngine, DevConsoleMockEngine(listOf(rule)), recorder)

            val response = client.get(ORDER_URL)

            assertEquals(MOCKED_BODY, response.bodyAsText())
            assertEquals(MOCKED_BODY.length.toString(), response.headers[HttpHeaders.ContentLength])
            val transaction = awaitTransaction(store)
            assertEquals(MOCKED_BODY, (transaction.capture.response!!.body as BodyPreview.Text).value)
        }

    @Test
    fun `throttled static response is delivered in full and captured`() =
        runBlocking {
            val (store, recorder) = captureStore()
            var networkCalls = 0
            val networkEngine =
                KtorMockEngine {
                    networkCalls++
                    respond(content = NETWORK_BODY)
                }
            val throttle =
                MockAction.Throttle(
                    bytesPerSecond = THROTTLE_BYTES_PER_SECOND,
                    next = MockAction.StaticResponse(statusCode = HttpStatusCode.OK.value, body = MOCKED_BODY),
                )
            val client =
                httpClient(networkEngine, DevConsoleMockEngine(listOf(staticOrderRule(action = throttle))), recorder)

            assertEquals(MOCKED_BODY, client.get(ORDER_URL).bodyAsText())
            assertEquals(
                "true",
                awaitTransaction(store)
                    .capture.request.metadata.tags["mocked"],
            )
            assertEquals(0, networkCalls)
        }

    private fun captureStore(): Pair<InMemoryNetworkTransactionStore, NetworkTransactionRecorder> {
        val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("1234567890123456".encodeToByteArray()))
        val recorder =
            NetworkTransactionRecorder(
                factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.recommended())),
                store = store,
            )
        return store to recorder
    }

    private fun httpClient(
        networkEngine: KtorMockEngine,
        mockEngine: DevConsoleMockEngine,
        recorder: NetworkTransactionRecorder? = null,
    ): HttpClient =
        HttpClient(networkEngine) {
            recorder?.let { install(DevConsoleKtorClientPlugin) { this.recorder = it } }
            install(DevConsoleKtorMocks) { this.mockEngine = mockEngine }
        }

    private fun staticOrderRule(
        host: String = "api.test",
        action: MockAction =
            MockAction.StaticResponse(
                statusCode = HttpStatusCode.Created.value,
                body = MOCKED_BODY,
                headers = mapOf(HttpHeaders.ContentType to "application/json"),
            ),
    ): MockRule =
        MockRule(
            id = "orders",
            priority = ORDER_RULE_PRIORITY,
            method = "GET",
            host = host,
            path = ORDER_PATH,
            action = action,
        )

    private fun awaitTransaction(
        store: InMemoryNetworkTransactionStore,
        maxWaitMs: Long = DEFAULT_AWAIT_MAX_WAIT_MS,
    ): NetworkTransaction {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            val transactions = store.page(NetworkTransactionQuery()).transactions
            if (transactions.isNotEmpty()) return transactions.single()
            Thread.sleep(AWAIT_POLL_INTERVAL_MS)
        }
        return store.page(NetworkTransactionQuery()).transactions.single()
    }
}
