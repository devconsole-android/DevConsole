package io.devconsole.network.okhttp

import io.devconsole.network.BodyPreview
import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCaptureContext
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkTransaction
import io.devconsole.network.NetworkTransactionQuery
import io.devconsole.network.NetworkTransactionRecorder
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.BufferedSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class DevConsoleOkHttpInterceptorTest {
    @Test
    fun `returns the host response unchanged while recording a bounded copy`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("response body"))
            val transactions =
                InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            val client = clientRecordingTo(transactions)

            val response = client.newCall(taggedPostRequest(server)).execute()
            assertEquals("response body", response.body!!.string())
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
            server.enqueue(MockResponse().setBody("ok"))
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
            server.enqueue(MockResponse().setBody("ok"))
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
            server.enqueue(MockResponse().setBody("ok"))
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
            server.enqueue(MockResponse().setBody("response body"))
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
            assertEquals("response body", response.body!!.string())
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
                MockResponse()
                    .setChunkedBody("data: hello\n\n", 16)
                    .addHeader("Content-Type", "text/event-stream"),
            )
            val transactions =
                InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            val client = clientRecordingTo(transactions)

            val response = client.newCall(Request.Builder().url(server.url("/events")).build()).execute()
            // The host's own read of the real body must be completely unaffected by skipping the peek.
            assertEquals("data: hello\n\n", response.body!!.string())

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

    @Test
    fun `captures a finite chunked response body through the tee as the host consumes it`() {
        val server = MockWebServer()
        server.start()
        try {
            // No Content-Length header: chunkedBody makes OkHttp report an unknown content length,
            // the shape of a finite chunked JSON response. Before the tee this was recorded
            // metadata-only as "streaming"; now the body is captured as the host reads it.
            val chunkedJson = "{\"event\":\"tick\"}\n"
            server.enqueue(
                MockResponse()
                    .setChunkedBody(chunkedJson, 16)
                    .addHeader("Content-Type", "application/json"),
            )
            val transactions =
                InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            val client = clientRecordingTo(transactions)

            val started = System.currentTimeMillis()
            val response = client.newCall(Request.Builder().url(server.url("/chunked")).build()).execute()
            val elapsedMs = System.currentTimeMillis() - started
            assertTrue("intercept() must return promptly instead of blocking on a peek", elapsedMs < 2000L)
            // The host's own read of the real body must see exactly the network's bytes.
            assertEquals(chunkedJson, response.body!!.string())

            val transaction =
                awaitTransactions(transactions) { list ->
                    list
                        .singleOrNull()
                        ?.capture
                        ?.response
                        ?.body is BodyPreview.Text
                }.single()
            val recordedResponse = transaction.capture.response!!
            assertEquals(chunkedJson, (recordedResponse.body as BodyPreview.Text).value)
            assertNull(recordedResponse.metadata.body.omittedReason)
            assertEquals(chunkedJson.length.toLong(), recordedResponse.metadata.body.declaredLength)
        } finally {
            server.close()
        }
    }

    @Test
    fun `passes every byte to the host while truncating the tee capture at the bound`() {
        val server = MockWebServer()
        server.start()
        try {
            val totalBytes = 700 * 1024
            val oversizedBody = "a".repeat(totalBytes)
            server.enqueue(
                MockResponse()
                    .setChunkedBody(oversizedBody, 64 * 1024)
                    .addHeader("Content-Type", "text/plain"),
            )
            val transactions =
                InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            val client = clientRecordingTo(transactions)

            val response = client.newCall(Request.Builder().url(server.url("/oversized-chunked")).build()).execute()
            val hostBody = response.body!!.string()
            assertEquals("the host must receive every byte, capped capture or not", oversizedBody, hostBody)

            val transaction =
                awaitTransactions(transactions) { list ->
                    list
                        .singleOrNull()
                        ?.capture
                        ?.response
                        ?.metadata
                        ?.body
                        ?.omittedReason == "truncated"
                }.single()
            val recordedResponse = transaction.capture.response!!
            assertEquals("truncated", recordedResponse.metadata.body.omittedReason)
            assertEquals(totalBytes.toLong(), recordedResponse.metadata.body.declaredLength)
            val capturedText = (recordedResponse.body as BodyPreview.Text).value
            // 512KiB: the interceptor's MAX_RESPONSE_PEEK_BYTES tee-capture bound.
            assertEquals(512L * 1024L, capturedText.length.toLong())
            assertTrue(oversizedBody.startsWith(capturedText))
        } finally {
            server.close()
        }
    }

    @Test
    fun `records exactly once with the whole body when the host closes the teed body early`() {
        val server = MockWebServer()
        server.start()
        try {
            val fullBody = "b".repeat(100 * 1024)
            server.enqueue(
                MockResponse()
                    .setChunkedBody(fullBody, 8 * 1024)
                    .addHeader("Content-Type", "text/plain"),
            )
            val transactions =
                InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            val client = clientRecordingTo(transactions)

            val response = client.newCall(Request.Builder().url(server.url("/abandoned")).build()).execute()
            val firstBytes = response.body!!.source().readUtf8(5)
            assertEquals("bbbbb", firstBytes)
            response.close()

            val transaction =
                awaitTransactions(transactions) { list ->
                    list
                        .singleOrNull()
                        ?.capture
                        ?.response
                        ?.body is BodyPreview.Text
                }.single()
            val recordedResponse = transaction.capture.response!!
            assertEquals(200, recordedResponse.statusCode)
            // The host walked away after 5 bytes; the close-time drain must still recover the rest,
            // so what DevConsole shows never depends on how much of the body the host wanted.
            assertEquals(fullBody, (recordedResponse.body as BodyPreview.Text).value)
            assertNull(recordedResponse.metadata.body.omittedReason)
            assertEquals(fullBody.length.toLong(), recordedResponse.metadata.body.declaredLength)
            // Close raced EOF on nothing here, but the once-guard must still have recorded a single
            // transaction; give the async recorder a beat to surface any duplicate before asserting.
            Thread.sleep(100)
            assertEquals(1, awaitTransactions(transactions).size)
        } finally {
            server.close()
        }
    }

    @Test
    fun `captures the whole body of a response the host closes without reading at all`() {
        val server = MockWebServer()
        server.start()
        try {
            // The shape every sample and plenty of real call sites have: read the status code,
            // close, never touch the body. Before the close-time drain this recorded an empty body
            // for exactly the responses OkHttp reports no Content-Length for -- chunked, and every
            // transparently-gzipped response -- while the same body *with* a Content-Length was
            // captured whole by the eager peek.
            val json = "{\"userId\":1,\"id\":1,\"title\":\"delectus aut autem\"}"
            server.enqueue(
                MockResponse()
                    .setChunkedBody(json, 16)
                    .addHeader("Content-Type", "application/json; charset=utf-8"),
            )
            val transactions =
                InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            val client = clientRecordingTo(transactions)

            client.newCall(Request.Builder().url(server.url("/todos/1")).build()).execute().use { it.code }

            val transaction =
                awaitTransactions(transactions) { list ->
                    list
                        .singleOrNull()
                        ?.capture
                        ?.response
                        ?.body is BodyPreview.Text
                }.single()
            val recordedResponse = transaction.capture.response!!
            assertEquals(json, (recordedResponse.body as BodyPreview.Text).value)
            assertNull(recordedResponse.metadata.body.omittedReason)
            assertEquals(json.length.toLong(), recordedResponse.metadata.body.declaredLength)
            assertNotNull(transaction.completedAtEpochMs)
        } finally {
            server.close()
        }
    }

    @Test
    fun `bounds the drain so abandoning a live stream never stalls the host's close`() {
        val server = MockWebServer()
        server.start()
        try {
            // 8 bytes per 400ms: a stream that could never be drained inside the 300ms budget.
            server.enqueue(
                MockResponse()
                    .setChunkedBody("x".repeat(64 * 1024), 8)
                    .throttleBody(8, 400, TimeUnit.MILLISECONDS)
                    .addHeader("Content-Type", "application/x-ndjson"),
            )
            val transactions =
                InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            val client = clientRecordingTo(transactions)

            val response = client.newCall(Request.Builder().url(server.url("/live")).build()).execute()
            val closeStartedAtMs = System.currentTimeMillis()
            response.close()
            val closeDurationMs = System.currentTimeMillis() - closeStartedAtMs

            assertTrue(
                "close() must return on the drain budget, not on the stream's own pace: ${closeDurationMs}ms",
                closeDurationMs < 3_000L,
            )
            val transaction =
                awaitTransactions(transactions) { list ->
                    list
                        .singleOrNull()
                        ?.capture
                        ?.response
                        ?.metadata
                        ?.body
                        ?.omittedReason == "partial"
                }.single()
            // A drain that ran out of budget must never present its fragment as the whole response.
            assertEquals(
                "partial",
                transaction.capture.response!!
                    .metadata.body.omittedReason,
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `records a long-lived teed stream provisionally while it is still open`() {
        val server = MockWebServer()
        server.start()
        try {
            // 8 bytes per 500ms keeps this NDJSON stream open for roughly two seconds -- long
            // enough that the provisional record must appear while bytes are still arriving.
            val ndjsonBody = "{\"tick\":1}\n{\"tick\":2}\n"
            server.enqueue(
                MockResponse()
                    .setChunkedBody(ndjsonBody, 8)
                    .throttleBody(8, 500, TimeUnit.MILLISECONDS)
                    .addHeader("Content-Type", "application/x-ndjson"),
            )
            val transactions =
                InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            val client = clientRecordingTo(transactions)

            val response = client.newCall(Request.Builder().url(server.url("/ndjson")).build()).execute()
            // Nothing of the body has been read yet: the stream is open, and the transaction must
            // already be visible as a metadata-only "streaming" record with no completion time.
            val provisional = awaitTransactions(transactions, maxWaitMs = 3000L).single()
            val provisionalResponse = provisional.capture.response!!
            assertEquals("streaming", provisionalResponse.metadata.body.omittedReason)
            assertEquals(BodyPreview.Absent, provisionalResponse.body)
            assertNull("an open stream has no completion moment yet", provisional.completedAtEpochMs)

            // Draining the stream to EOF must upgrade that same transaction in place -- same id,
            // now with the captured body and a real completion time, never a second entry.
            assertEquals(ndjsonBody, response.body!!.string())
            val upgraded =
                awaitTransactions(transactions, maxWaitMs = 5000L) { list ->
                    list
                        .singleOrNull()
                        ?.capture
                        ?.response
                        ?.body is BodyPreview.Text
                }.single()
            assertEquals(provisional.id, upgraded.id)
            assertEquals(ndjsonBody, (upgraded.capture.response!!.body as BodyPreview.Text).value)
            assertNull(
                upgraded.capture.response!!
                    .metadata.body.omittedReason,
            )
            assertNotNull(upgraded.completedAtEpochMs)
        } finally {
            server.close()
        }
    }

    @Test
    fun `still records a teed response the host neither reads nor closes`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setChunkedBody("{\"ok\":true}", 8)
                    .addHeader("Content-Type", "application/json"),
            )
            val transactions =
                InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            val client = clientRecordingTo(transactions)

            val response = client.newCall(Request.Builder().url(server.url("/abandoned-unread")).build()).execute()
            try {
                // The host walks away without touching the body: no EOF, no close. The transaction
                // must still surface -- as the provisional metadata-only record -- instead of
                // silently never existing.
                val transaction = awaitTransactions(transactions, maxWaitMs = 3000L).single()
                val recordedResponse = transaction.capture.response!!
                assertEquals(200, recordedResponse.statusCode)
                assertEquals("streaming", recordedResponse.metadata.body.omittedReason)
                assertEquals(BodyPreview.Absent, recordedResponse.body)
            } finally {
                response.close()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `teed capture still records real timings after the factory has evicted its entry`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setChunkedBody("{\"ok\":true}", 8)
                    .addHeader("Content-Type", "application/json"),
            )
            val transactions =
                InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            val listenerFactory = DevConsoleOkHttpEventListenerFactory()
            val client =
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

            client.newCall(Request.Builder().url(server.url("/timed")).build()).execute().use {
                assertEquals("{\"ok\":true}", it.body!!.string())
            }

            val timings =
                awaitTransactions(transactions) { list ->
                    list
                        .singleOrNull()
                        ?.capture
                        ?.response
                        ?.metadata
                        ?.timings
                        ?.receiveMs != null
                }.single()
                    .capture.response!!
                    .metadata.timings
            // The deferred record runs after both the interceptor's forget() and callEnd's own
            // eviction; the live CallPhaseTimestamps reference must still yield complete phases,
            // including receiveMs whose end mark is only written as the body is exhausted.
            assertNotNull("dnsMs must survive eviction of the factory's map entry", timings.dnsMs)
            assertNotNull("waitMs must survive eviction of the factory's map entry", timings.waitMs)
            assertNotNull("receiveMs is only observable at body exhaustion", timings.receiveMs)
            assertEquals("leak safety must be unchanged by deferred capture", 0, listenerFactory.trackedCallCount())
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

    /**
     * Polls until [until] accepts the store's transactions (default: any transaction at all). A
     * teed response may legitimately be stored twice under one id -- provisional then final -- so
     * tests that assert on the final state pass a predicate matching it instead of grabbing
     * whichever record happens to have landed first.
     */
    private fun awaitTransactions(
        store: InMemoryNetworkTransactionStore,
        maxWaitMs: Long = 2000L,
        until: (List<NetworkTransaction>) -> Boolean = { it.isNotEmpty() },
    ): List<NetworkTransaction> {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            val list = store.page(NetworkTransactionQuery()).transactions
            if (until(list)) return list
            Thread.sleep(10)
        }
        return store.page(NetworkTransactionQuery()).transactions
    }
}
