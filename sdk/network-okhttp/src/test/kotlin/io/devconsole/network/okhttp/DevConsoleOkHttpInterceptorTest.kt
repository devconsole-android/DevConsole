package io.devconsole.network.okhttp

import io.devconsole.network.BodyPreview
import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCaptureContext
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkTransactionRecorder
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DevConsoleOkHttpInterceptorTest {
    @Test
    fun `returns the host response unchanged while recording a bounded copy`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().body("response body").build())
            val transactions =
                InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            val client = clientRecordingTo(transactions)

            val response = client.newCall(taggedPostRequest(server)).execute()
            assertEquals("response body", response.body.string())
            val recordedList = awaitTransactions(transactions)
            assertEquals(1, recordedList.size)
            val recorded = recordedList.single()
            assertTrue(
                recorded.capture.request.url.display
                    .contains("<redacted>"),
            )
            assertTrue(
                recorded.capture.request.metadata.threadName!!
                    .isNotBlank(),
            )
            assertEquals(12L, recorded.capture.request.metadata.body.declaredLength)
            assertEquals("request body", (recorded.capture.request.body as BodyPreview.Text).value)
            assertNull(recorded.capture.request.metadata.body.omittedReason)
            assertEquals(
                13L,
                recorded.capture.response!!
                    .metadata.body.declaredLength,
            )
            assertEquals("orders", recorded.capture.request.metadata.tags["mockRuleId"])
        } finally {
            server.close()
        }
    }

    @Test
    fun `skips capturing a one-shot request body with an accurate reason`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().body("ok").build())
            val transactions =
                InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            val client = clientRecordingTo(transactions)
            val oneShotBody =
                object : RequestBody() {
                    override fun contentType() = "text/plain".toMediaType()

                    override fun contentLength() = 4L

                    override fun isOneShot() = true

                    override fun writeTo(sink: BufferedSink) {
                        sink.writeUtf8("body")
                    }
                }

            client
                .newCall(
                    Request
                        .Builder()
                        .url(server.url("/one-shot"))
                        .post(oneShotBody)
                        .build(),
                ).execute()
                .use {
                    assertEquals(200, it.code)
                }

            val transaction = awaitTransactions(transactions).single()
            assertNull(transaction.capture.request.body as? BodyPreview.Text)
            assertEquals("one-shot", transaction.capture.request.metadata.body.omittedReason)
        } finally {
            server.close()
        }
    }

    @Test
    fun `skips capturing a duplex request body with an accurate reason`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().body("ok").build())
            val transactions =
                InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            val client = clientRecordingTo(transactions)
            val duplexBody =
                object : RequestBody() {
                    override fun contentType() = "text/plain".toMediaType()

                    override fun contentLength() = 4L

                    override fun isDuplex() = true

                    override fun writeTo(sink: BufferedSink) {
                        sink.writeUtf8("body")
                    }
                }

            // Whether OkHttp accepts a duplex body over this connection is a protocol-negotiation
            // detail this test doesn't care about; the interceptor must classify it as "duplex" -- and,
            // above all, never attempt to read it a second time for capture -- on either outcome.
            runCatching {
                client
                    .newCall(
                        Request
                            .Builder()
                            .url(server.url("/duplex"))
                            .post(duplexBody)
                            .build(),
                    ).execute()
                    .close()
            }

            val transaction = awaitTransactions(transactions).single()
            assertEquals("duplex", transaction.capture.request.metadata.body.omittedReason)
        } finally {
            server.close()
        }
    }

    @Test
    fun `skips capturing a request body larger than the capture bound`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().body("ok").build())
            val transactions =
                InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            val client = clientRecordingTo(transactions)
            val oversized = "a".repeat(300 * 1024).toRequestBody("text/plain".toMediaType())

            client
                .newCall(
                    Request
                        .Builder()
                        .url(server.url("/oversized"))
                        .post(oversized)
                        .build(),
                ).execute()
                .use {
                    assertEquals(200, it.code)
                }

            val transaction = awaitTransactions(transactions).single()
            assertEquals("too-large", transaction.capture.request.metadata.body.omittedReason)
        } finally {
            server.close()
        }
    }

    @Test
    fun `returns the real response even when recorder capture throws after proceed succeeds`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().body("response body").build())
            val transactions =
                InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            val client = clientRecordingTo(transactions)
            // Reports itself as safely repeatable (isOneShot/isDuplex both false) so the interceptor
            // attempts to capture it, but only actually behaves that way on the *first* read -- OkHttp's
            // own network write, which must succeed so chain.proceed() returns a real response -- and
            // throws on any further read, simulating a body that breaks its own repeatability contract.
            // The interceptor's own post-proceed capture (a second, separate read) must then hit this
            // failure without it ever reaching the host.
            var writeCount = 0
            val flakyBody =
                object : RequestBody() {
                    override fun contentType() = "text/plain".toMediaType()

                    override fun contentLength() = 4L

                    override fun writeTo(sink: BufferedSink) {
                        writeCount++
                        if (writeCount > 1) throw IOException("boom: body source vanished on second read")
                        sink.writeUtf8("body")
                    }
                }

            val response =
                client
                    .newCall(
                        Request
                            .Builder()
                            .url(server.url("/flaky-body"))
                            .post(flakyBody)
                            .build(),
                    ).execute()

            // The host must see exactly what the network actually returned, regardless of the capture
            // failure triggered afterwards while building the transaction to record.
            assertEquals(200, response.code)
            assertEquals("response body", response.body.string())
            assertTrue("expected the interceptor to have attempted a second (capture) read", writeCount > 1)
        } finally {
            server.close()
        }
    }

    @Test
    fun `skips peeking a streaming event-stream response body`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse
                    .Builder()
                    .chunkedBody("data: hello\n\n", 16)
                    .addHeader("Content-Type", "text/event-stream")
                    .build(),
            )
            val transactions =
                InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            val client = clientRecordingTo(transactions)

            val response = client.newCall(Request.Builder().url(server.url("/events")).build()).execute()
            // The host's own read of the real body must be completely unaffected by skipping the peek.
            assertEquals("data: hello\n\n", response.body.string())

            val transaction = awaitTransactions(transactions).single()
            assertEquals(
                "streaming",
                transaction.capture.response!!
                    .metadata.body.omittedReason,
            )
            assertEquals(BodyPreview.Absent, transaction.capture.response!!.body)
        } finally {
            server.close()
        }
    }

    private fun taggedPostRequest(server: MockWebServer): Request =
        Request
            .Builder()
            .url(server.url("/orders?access_token=secret"))
            .tag(
                NetworkCaptureContext::class.java,
                NetworkCaptureContext(mapOf("mockRuleId" to "orders")),
            ).post("request body".toRequestBody())
            .build()

    private fun clientRecordingTo(transactions: InMemoryNetworkTransactionStore): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(
                DevConsoleOkHttpInterceptor(
                    NetworkTransactionRecorder(
                        NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                        transactions,
                    ),
                ),
            ).build()

    private fun awaitTransactions(
        store: InMemoryNetworkTransactionStore,
        maxWaitMs: Long = 2000L,
    ): List<io.devconsole.network.NetworkTransaction> {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            val list = store.page(io.devconsole.network.NetworkTransactionQuery()).transactions
            if (list.isNotEmpty()) return list
            Thread.sleep(10)
        }
        return store.page(io.devconsole.network.NetworkTransactionQuery()).transactions
    }
}
