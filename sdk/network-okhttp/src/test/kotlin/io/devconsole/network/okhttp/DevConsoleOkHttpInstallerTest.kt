/**
 * @author Shakib
 * @since 05/08/26
 */
package io.devconsole.network.okhttp

import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkTransaction
import io.devconsole.network.NetworkTransactionQuery
import io.devconsole.network.NetworkTransactionRecorder
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [OkHttpClient.Builder.installDevConsole] (and its Java-friendly mirror [DevConsoleOkHttp.install])
 * end-to-end against a real [MockWebServer]: that the one-call installer actually produces populated
 * timing phases the way the equivalent three-step manual wiring on
 * [DevConsoleOkHttpEventListenerFactoryTest] does, and that a host's own pre-existing
 * `eventListenerFactory` keeps receiving every callback once DevConsole is installed alongside it.
 */
class DevConsoleOkHttpInstallerTest {
    @Test
    fun `installDevConsole populates timing phases on a real call`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("installed"))
            val transactions =
                InMemoryNetworkTransactionStore(NetworkCursorCodec("installer-test-key-1".encodeToByteArray()))
            val recorder =
                NetworkTransactionRecorder(
                    NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                    transactions,
                )
            val client =
                OkHttpClient
                    .Builder()
                    .installDevConsole(recorder)
                    .build()

            client.newCall(Request.Builder().url(server.url("/installed")).build()).execute().use {
                assertEquals("installed", it.body!!.string())
            }

            val timings =
                awaitTransactions(transactions)
                    .single()
                    .capture.response!!
                    .metadata.timings
            assertNotNull("dnsMs should be observed via the installed listener", timings.dnsMs)
            assertNotNull("connectMs should be observed via the installed listener", timings.connectMs)
            assertNotNull("sendMs should be observed via the installed listener", timings.sendMs)
            assertNotNull("waitMs should be observed via the installed listener", timings.waitMs)
        } finally {
            server.close()
        }
    }

    @Test
    fun `DevConsoleOkHttp install, the Java-friendly form, also populates timing phases`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("installed-java"))
            val transactions =
                InMemoryNetworkTransactionStore(NetworkCursorCodec("installer-test-key-2".encodeToByteArray()))
            val recorder =
                NetworkTransactionRecorder(
                    NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                    transactions,
                )
            val client =
                DevConsoleOkHttp
                    .install(OkHttpClient.Builder(), recorder)
                    .build()

            client.newCall(Request.Builder().url(server.url("/installed-java")).build()).execute().use {
                assertEquals("installed-java", it.body!!.string())
            }

            val timings =
                awaitTransactions(transactions)
                    .single()
                    .capture.response!!
                    .metadata.timings
            assertNotNull("dnsMs should be observed via DevConsoleOkHttp.install", timings.dnsMs)
            assertNotNull("connectMs should be observed via DevConsoleOkHttp.install", timings.connectMs)
        } finally {
            server.close()
        }
    }

    @Test
    fun `a host's own eventListenerFactory keeps receiving callbacks after installDevConsole`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("delegated"))
            val transactions =
                InMemoryNetworkTransactionStore(NetworkCursorCodec("installer-test-key-3".encodeToByteArray()))
            val recorder =
                NetworkTransactionRecorder(
                    NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                    transactions,
                )
            val hostListener = RecordingEventListenerFactory()

            val client =
                OkHttpClient
                    .Builder()
                    .installDevConsole(recorder, existingEventListenerFactory = hostListener)
                    .build()

            client.newCall(Request.Builder().url(server.url("/delegated")).build()).execute().use {
                assertEquals("delegated", it.body!!.string())
            }
            awaitTransactions(transactions)

            assertTrue(
                "the host's own listener should still see callStart",
                hostListener.observedCallbacks.contains("callStart"),
            )
            assertTrue(
                "the host's own listener should still see callEnd",
                hostListener.observedCallbacks.contains("callEnd"),
            )
            assertTrue(
                "the host's own listener should still see dnsStart/dnsEnd",
                hostListener.observedCallbacks.containsAll(listOf("dnsStart", "dnsEnd")),
            )
        } finally {
            server.close()
        }
    }

    /** Records the name of every [EventListener] callback it receives, per call. */
    private class RecordingEventListenerFactory : EventListener.Factory {
        val observedCallbacks = CopyOnWriteArrayList<String>()

        override fun create(call: Call): EventListener =
            object : EventListener() {
                override fun callStart(call: Call) {
                    observedCallbacks += "callStart"
                }

                override fun dnsStart(
                    call: Call,
                    domainName: String,
                ) {
                    observedCallbacks += "dnsStart"
                }

                override fun dnsEnd(
                    call: Call,
                    domainName: String,
                    inetAddressList: List<java.net.InetAddress>,
                ) {
                    observedCallbacks += "dnsEnd"
                }

                override fun callEnd(call: Call) {
                    observedCallbacks += "callEnd"
                }
            }
    }

    private fun awaitTransactions(
        store: InMemoryNetworkTransactionStore,
        expectedCount: Int = 1,
        maxWaitMs: Long = 2000L,
    ): List<NetworkTransaction> {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            val list = store.page(NetworkTransactionQuery()).transactions
            if (list.size >= expectedCount) return list
            Thread.sleep(10)
        }
        return store.page(NetworkTransactionQuery()).transactions
    }
}
