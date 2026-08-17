package io.devconsole.network.okhttp

import io.devconsole.mocks.MockAction
import io.devconsole.mocks.MockEngine
import io.devconsole.mocks.MockEngineRegistry
import io.devconsole.mocks.MockRule
import io.devconsole.mocks.okhttp.DevConsoleMockInterceptor
import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkTransactionRecorder
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mock rules used to need a second line at every call site --
 * `.addInterceptor(DevConsoleMockInterceptor(DevConsole.mockEngine()))` -- and the Mocks screen sat
 * inert for anyone who never read that far. [installDevConsole] now wires it from the engine the
 * running DevConsole published to [MockEngineRegistry].
 *
 * Every assertion here is about a real call through a real [MockWebServer]: a served mock is one the
 * upstream server never sees, which no amount of interceptor-list inspection would actually prove.
 */
class DevConsoleOkHttpMockInstallTest {
    @After fun tearDown() = MockEngineRegistry.clear()

    @Test
    fun `installDevConsole serves the published engine's rules without a second interceptor`() {
        MockEngineRegistry.publish(
            MockEngine(
                listOf(
                    MockRule(
                        id = "orders",
                        priority = 1,
                        path = "/orders",
                        action = MockAction.StaticResponse(202, "mocked-body"),
                    ),
                ),
            ),
        )
        withServerAndClient { server, client ->
            server.enqueue(MockResponse().setBody("upstream"))

            client.newCall(Request.Builder().url(server.url("/orders")).build()).execute().use {
                assertEquals(202, it.code)
                assertEquals("mocked-body", it.body!!.string())
            }

            assertEquals("the mock must short-circuit before the network", 0, server.requestCount)
        }
    }

    /**
     * The default has to stay invisible until a rule matches. A host with mocking wired but no rules
     * -- which is every host on first run now -- must see its traffic untouched.
     */
    @Test
    fun `an engine with no matching rule passes the call through untouched`() {
        MockEngineRegistry.publish(MockEngine(listOf(MockRule("other", 1, path = "/somewhere-else"))))
        withServerAndClient { server, client ->
            server.enqueue(MockResponse().setBody("upstream"))

            client.newCall(Request.Builder().url(server.url("/orders")).build()).execute().use {
                assertEquals("upstream", it.body!!.string())
            }

            assertEquals(1, server.requestCount)
        }
    }

    /** Before `DevConsole.initialize` there is no engine, and the installer must still build a client. */
    @Test
    fun `no published engine means no mock interceptor`() {
        MockEngineRegistry.clear()
        withServerAndClient { server, client ->
            server.enqueue(MockResponse().setBody("upstream"))

            client.newCall(Request.Builder().url(server.url("/orders")).build()).execute().use {
                assertEquals("upstream", it.body!!.string())
            }

            assertEquals(1, server.requestCount)
        }
    }

    /**
     * A host that keeps its old manual `.addInterceptor(DevConsoleMockInterceptor(...))` line now has
     * two of them in the chain. Most actions short-circuit, so the second never runs -- but a
     * `Delay` whose next action proceeds up the chain reaches both, and each would sleep the full
     * duration, silently doubling every simulated latency the moment the host upgraded. The second
     * pass must recognise the first and stand down.
     */
    @Test
    fun `a hosts own mock interceptor does not double apply a delay on top of the installed one`() {
        val engine =
            MockEngine(
                listOf(
                    MockRule(
                        id = "slow",
                        priority = 1,
                        path = "/orders",
                        action = MockAction.Delay(DELAY_MS, MockAction.Passthrough),
                    ),
                ),
            )
        MockEngineRegistry.publish(engine)
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("upstream"))
            val client =
                OkHttpClient
                    .Builder()
                    .installDevConsole(recorder())
                    .addInterceptor(DevConsoleMockInterceptor(engine))
                    .build()

            val startedAt = System.nanoTime()
            client.newCall(Request.Builder().url(server.url("/orders")).build()).execute().use {
                assertEquals("upstream", it.body!!.string())
            }
            val elapsedMs = (System.nanoTime() - startedAt) / NANOS_PER_MS

            assertTrue(
                "the delay must be applied once, not twice (took ${elapsedMs}ms)",
                elapsedMs < DELAY_MS * 2,
            )
        } finally {
            server.shutdown()
        }
    }

    private fun recorder() =
        NetworkTransactionRecorder(
            NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
            InMemoryNetworkTransactionStore(NetworkCursorCodec("mock-install-test-key".encodeToByteArray())),
        )

    private fun withServerAndClient(block: (MockWebServer, OkHttpClient) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            block(server, OkHttpClient.Builder().installDevConsole(recorder()).build())
        } finally {
            server.shutdown()
        }
    }

    private companion object {
        const val DELAY_MS = 300L
        const val NANOS_PER_MS = 1_000_000L
    }
}
