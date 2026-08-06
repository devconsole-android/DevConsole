/**
 * @author Shakib
 * @since 04/08/26
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
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket

/**
 * [DevConsoleOkHttpEventListenerFactory] wired end-to-end against a real [MockWebServer]: this
 * covers what the arithmetic-level [CallPhaseTimestampsTest] cannot -- that the factory is actually
 * keyed per [okhttp3.Call], that state does not leak past a call's lifetime (success, failure, and
 * "listener installed without the interceptor" all clean up), and that a pooled connection honestly
 * reports null DNS/connect rather than a fabricated zero.
 */
class DevConsoleOkHttpEventListenerFactoryTest {
    @Test
    fun `a real call populates dns, connect, send, and wait phases with no TLS phase over plaintext`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().body("first").build())
            val transactions =
                InMemoryNetworkTransactionStore(NetworkCursorCodec("event-listener-key-1".encodeToByteArray()))
            val listenerFactory = DevConsoleOkHttpEventListenerFactory()
            val client = clientRecordingTo(transactions, listenerFactory)

            client.newCall(Request.Builder().url(server.url("/first")).build()).execute().use {
                assertEquals("first", it.body.string())
            }

            val timings =
                awaitTransactions(transactions)
                    .single()
                    .capture.response!!
                    .metadata.timings
            assertNotNull("dnsMs should be observed on a fresh connection", timings.dnsMs)
            assertNotNull("connectMs should be observed on a fresh connection", timings.connectMs)
            assertNull("plaintext http has no TLS phase", timings.tlsMs)
            assertNotNull("sendMs should be observed", timings.sendMs)
            assertNotNull("waitMs (TTFB) should be observed", timings.waitMs)
            assertTrue(timings.dnsMs!! >= 0)
            assertTrue(timings.connectMs!! >= 0)
            assertTrue(timings.sendMs!! >= 0)
            assertTrue(timings.waitMs!! >= 0)
        } finally {
            server.close()
        }
    }

    @Test
    fun `a pooled second call to the same host reports null dns and connect instead of a fabricated zero`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().body("first").build())
            server.enqueue(MockResponse.Builder().body("second").build())
            val transactions =
                InMemoryNetworkTransactionStore(NetworkCursorCodec("event-listener-key-2".encodeToByteArray()))
            val listenerFactory = DevConsoleOkHttpEventListenerFactory()
            val client = clientRecordingTo(transactions, listenerFactory)

            client.newCall(Request.Builder().url(server.url("/first")).build()).execute().use {
                assertEquals("first", it.body.string())
            }
            client.newCall(Request.Builder().url(server.url("/second")).build()).execute().use {
                assertEquals("second", it.body.string())
            }

            val recorded = awaitTransactions(transactions, expectedCount = 2)
            val pooledTimings =
                recorded
                    .sortedBy { it.startedAtEpochMs }[1]
                    .capture.response!!
                    .metadata.timings

            assertNull("a reused pooled connection performs no DNS lookup", pooledTimings.dnsMs)
            assertNull("a reused pooled connection performs no TCP handshake", pooledTimings.connectMs)
            assertNull("a reused pooled connection performs no TLS handshake", pooledTimings.tlsMs)
        } finally {
            server.close()
        }
    }

    @Test
    fun `factory state is removed once the interceptor has read it for a successful call`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().body("ok").build())
            val transactions =
                InMemoryNetworkTransactionStore(NetworkCursorCodec("event-listener-key-3".encodeToByteArray()))
            val listenerFactory = DevConsoleOkHttpEventListenerFactory()
            val client = clientRecordingTo(transactions, listenerFactory)

            client.newCall(Request.Builder().url(server.url("/ok")).build()).execute().use {
                assertEquals("ok", it.body.string())
            }
            awaitTransactions(transactions)

            assertEquals(
                "no per-call state should remain once the interceptor has recorded the transaction",
                0,
                listenerFactory.trackedCallCount(),
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `factory state is removed after a failed call even though the interceptor also cleans up`() {
        // Connect to a port nothing is listening on so the call fails before a response exists,
        // exercising DevConsoleOkHttpInterceptor's catch branch and callFailed() together.
        val deadPort = ServerSocket(0).use { it.localPort }
        val transactions =
            InMemoryNetworkTransactionStore(NetworkCursorCodec("event-listener-key-4".encodeToByteArray()))
        val listenerFactory = DevConsoleOkHttpEventListenerFactory()
        val client = clientRecordingTo(transactions, listenerFactory)

        try {
            client.newCall(Request.Builder().url("http://127.0.0.1:$deadPort/unreachable").build()).execute()
        } catch (_: IOException) {
            // Expected: nothing is listening on this port.
        }

        awaitTransactions(transactions)
        assertEquals(
            "no per-call state should remain after a failed call",
            0,
            listenerFactory.trackedCallCount(),
        )
    }

    @Test
    fun `factory state is removed on callEnd even when installed without the interceptor`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().body("no interceptor").build())
            val listenerFactory = DevConsoleOkHttpEventListenerFactory()
            val client =
                OkHttpClient
                    .Builder()
                    .eventListenerFactory(listenerFactory)
                    .build()

            client.newCall(Request.Builder().url(server.url("/no-interceptor")).build()).execute().use {
                assertEquals("no interceptor", it.body.string())
            }

            // callEnd only fires once the response body is exhausted or closed; `.use {}` above
            // guarantees that happened before this assertion runs.
            assertEquals(0, listenerFactory.trackedCallCount())
        } finally {
            server.close()
        }
    }

    @Test
    fun `two concurrent calls each get their own independent timings`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().body("a").build())
            server.enqueue(MockResponse.Builder().body("b").build())
            val transactions =
                InMemoryNetworkTransactionStore(NetworkCursorCodec("event-listener-key-5".encodeToByteArray()))
            val listenerFactory = DevConsoleOkHttpEventListenerFactory()
            val client = clientRecordingTo(transactions, listenerFactory)

            val callA = client.newCall(Request.Builder().url(server.url("/a")).build())
            val callB = client.newCall(Request.Builder().url(server.url("/b")).build())

            val threadA = Thread { callA.execute().use { it.body.string() } }
            val threadB = Thread { callB.execute().use { it.body.string() } }
            threadA.start()
            threadB.start()
            threadA.join()
            threadB.join()

            val recorded = awaitTransactions(transactions, expectedCount = 2)
            assertEquals(2, recorded.size)
            assertEquals(0, listenerFactory.trackedCallCount())
        } finally {
            server.close()
        }
    }

    private fun clientRecordingTo(
        transactions: InMemoryNetworkTransactionStore,
        listenerFactory: DevConsoleOkHttpEventListenerFactory,
    ): OkHttpClient =
        OkHttpClient
            .Builder()
            .eventListenerFactory(listenerFactory)
            .addInterceptor(
                DevConsoleOkHttpInterceptor(
                    NetworkTransactionRecorder(
                        NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                        transactions,
                    ),
                    listenerFactory,
                ),
            ).build()

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
