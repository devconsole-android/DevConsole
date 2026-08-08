package io.devconsole.network.ktor

import io.devconsole.network.BodyPreview
import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkTransactionQuery
import io.devconsole.network.NetworkTransactionRecorder
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.SaveBodyPlugin
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevConsoleKtorClientPluginTest {
    @Test
    fun `records network transaction whenktor client plugin is installed`() =
        runBlocking {
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("1234567890123456".encodeToByteArray()))
            val recorder =
                NetworkTransactionRecorder(
                    factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                    store = store,
                )

            val mockEngine =
                MockEngine { _ ->
                    respond(
                        content = "{\"status\":\"ok\"}",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val client =
                HttpClient(mockEngine) {
                    install(DevConsoleKtorClientPlugin) {
                        this.recorder = recorder
                    }
                }

            client.get("https://api.test/orders?access_token=secret")

            val transactions = awaitTransactions(store)
            assertEquals(1, transactions.size)
            val transaction = transactions.single()
            assertEquals("GET", transaction.capture.request.method)
            assertTrue(
                transaction.capture.request.url.display
                    .contains("<redacted>"),
            )
            assertTrue(
                transaction.capture.request.metadata.threadName!!
                    .isNotBlank(),
            )
            assertEquals("ktor-pipeline-metadata-only", transaction.capture.request.metadata.body.omittedReason)
            assertEquals("application/json", transaction.capture.response!!.contentType)
        }

    @Test
    fun `captures a textual request body`() =
        runBlocking {
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("1234567890123456".encodeToByteArray()))
            val recorder =
                NetworkTransactionRecorder(
                    factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                    store = store,
                )
            val mockEngine = MockEngine { _ -> respond(content = "ok", status = HttpStatusCode.OK) }
            val client =
                HttpClient(mockEngine) {
                    install(DevConsoleKtorClientPlugin) { this.recorder = recorder }
                }

            client.post("https://api.test/orders") {
                contentType(ContentType.Text.Plain)
                setBody("plain text body")
            }

            val transaction = awaitTransactions(store).single()
            assertEquals(
                "plain text body",
                (transaction.capture.request.body as BodyPreview.Text).value,
            )
            assertNull(transaction.capture.request.metadata.body.omittedReason)
        }

    @Test
    fun `does not capture a non-textual request body`() =
        runBlocking {
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("1234567890123456".encodeToByteArray()))
            val recorder =
                NetworkTransactionRecorder(
                    factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                    store = store,
                )
            val mockEngine = MockEngine { _ -> respond(content = "ok", status = HttpStatusCode.OK) }
            val client =
                HttpClient(mockEngine) {
                    install(DevConsoleKtorClientPlugin) { this.recorder = recorder }
                }

            client.post("https://api.test/upload") {
                contentType(ContentType.Application.OctetStream)
                setBody(byteArrayOf(1, 2, 3, 4))
            }

            val transaction = awaitTransactions(store).single()
            assertEquals("ktor-pipeline-metadata-only", transaction.capture.request.metadata.body.omittedReason)
        }

    @Test
    fun `swallows a capture exception and still delivers the response to the host`() =
        runBlocking {
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("1234567890123456".encodeToByteArray()))
            val recorder =
                NetworkTransactionRecorder(
                    factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                    store = store,
                )
            val mockEngine = MockEngine { _ -> respond(content = "response body", status = HttpStatusCode.OK) }
            val client =
                HttpClient(mockEngine) {
                    install(DevConsoleKtorClientPlugin) { this.recorder = recorder }
                }
            // MockEngine never re-reads a ByteArrayContent's bytes() itself (it works from the
            // OutgoingContent reference directly), so the plugin's own post-hoc capture read is the
            // only caller of bytes() here. This directly exercises the single runCatching wrapping the
            // whole onResponse body: the host's own client.post(...) call must still complete and
            // return the real response even though building the transaction to record fails outright.
            val throwingContent =
                object : OutgoingContent.ByteArrayContent() {
                    override val contentType = ContentType.Text.Plain
                    override val contentLength = 4L

                    override fun bytes(): ByteArray = error("boom: capture body read failed")
                }

            val response =
                client.post("https://api.test/flaky") {
                    setBody(throwingContent)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("response body", response.bodyAsText())

            // No transaction should have been recorded either -- the whole capture for this request
            // failed and was swallowed, rather than a partial/corrupt transaction being stored.
            Thread.sleep(200)
            assertTrue(store.page(NetworkTransactionQuery()).transactions.isEmpty())
        }

    @Test
    fun `captures a textual response body and keeps double receive working`() =
        runBlocking {
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("1234567890123456".encodeToByteArray()))
            val recorder =
                NetworkTransactionRecorder(
                    factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                    store = store,
                )
            val payload = "{\"status\":\"ok\",\"items\":[1,2,3]}"
            val mockEngine =
                MockEngine { _ ->
                    respond(
                        content = payload,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client =
                HttpClient(mockEngine) {
                    install(DevConsoleKtorClientPlugin) { this.recorder = recorder }
                }

            val response = client.get("https://api.test/orders")

            // Default configuration: SaveBodyPlugin is active, and the rebuilt response delegates
            // re-reads back to the saved copy, so the host's response stays genuinely re-readable.
            assertEquals(payload, response.bodyAsText())
            assertEquals(payload, response.bodyAsText())

            val transactions = awaitTransactions(store)
            assertEquals(1, transactions.size)
            val captured = transactions.single().capture.response!!
            assertEquals(payload, (captured.body as BodyPreview.Text).value)
            assertNull(captured.metadata.body.omittedReason)
        }

    @Test
    fun `keeps the host body intact when a download listener has rewrapped the response`() =
        runBlocking {
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("1234567890123456".encodeToByteArray()))
            val recorder =
                NetworkTransactionRecorder(
                    factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                    store = store,
                )
            val payload = "{\"status\":\"ok\"}"
            val mockEngine =
                MockEngine { _ ->
                    respond(
                        content = payload,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client =
                HttpClient(mockEngine) {
                    install(DevConsoleKtorClientPlugin) { this.recorder = recorder }
                }

            // The default-installed BodyProgress plugin runs at HttpReceivePipeline.After *before*
            // user plugins and, when a download listener is present, replaces the response with a
            // fixed single-shot channel while isSaved stays true. The capture must split that
            // channel -- never read or cancel it outright -- or the host's only body is destroyed.
            val response = client.get("https://api.test/orders") { onDownload { _, _ -> } }

            assertEquals(payload, response.bodyAsText())

            val captured = awaitTransactions(store).single().capture.response!!
            assertEquals(payload, (captured.body as BodyPreview.Text).value)
            assertNull(captured.metadata.body.omittedReason)
        }

    @Test
    fun `streams the response to the host while the origin is still open`() =
        runBlocking {
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("1234567890123456".encodeToByteArray()))
            val recorder =
                NetworkTransactionRecorder(
                    factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                    store = store,
                )
            val originBody = ByteChannel()
            val mockEngine =
                MockEngine { _ ->
                    respond(
                        content = originBody,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                    )
                }
            val client =
                HttpClient(mockEngine) {
                    install(DevConsoleKtorClientPlugin) { this.recorder = recorder }
                }

            originBody.writeStringUtf8("first-line\n")
            originBody.flush()

            // The streaming path (`prepareGet` keeps the connection open for the block) must see
            // each line as the origin flushes it: a capture that buffers the host's body until the
            // origin closes -- e.g. by claiming a saved-body replay -- would time out here, because
            // the origin is deliberately still open at every read below.
            client.prepareGet("https://api.test/stream").execute { response ->
                val bodyChannel = response.bodyAsChannel()
                assertEquals("first-line", withTimeout(2_000) { bodyChannel.readUTF8Line() })

                originBody.writeStringUtf8("second-line\n")
                originBody.flushAndClose()
                assertEquals("second-line", withTimeout(2_000) { bodyChannel.readUTF8Line() })
            }

            val captured = awaitTransactions(store).single().capture.response!!
            assertEquals("first-line\nsecond-line\n", (captured.body as BodyPreview.Text).value)
            assertNull(captured.metadata.body.omittedReason)
        }

    @Test
    fun `captures a chunked response body when body saving is disabled`() =
        runBlocking {
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("1234567890123456".encodeToByteArray()))
            val recorder =
                NetworkTransactionRecorder(
                    factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                    store = store,
                )
            val mockEngine =
                MockEngine { _ ->
                    // A raw channel with no Content-Length header models a chunked transfer.
                    respond(
                        content = ByteReadChannel("chunked payload"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                    )
                }
            val client =
                HttpClient(mockEngine) {
                    install(SaveBodyPlugin) { disabled = true }
                    install(DevConsoleKtorClientPlugin) { this.recorder = recorder }
                }

            val response = client.get("https://api.test/chunked")
            assertEquals("chunked payload", response.bodyAsText())

            val transactions = awaitTransactions(store)
            assertEquals(1, transactions.size)
            val captured = transactions.single().capture.response!!
            assertEquals("chunked payload", (captured.body as BodyPreview.Text).value)
            assertNull(captured.metadata.body.omittedReason)
        }

    @Test
    fun `omits an oversized declared-length response body without reading it`() =
        runBlocking {
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("1234567890123456".encodeToByteArray()))
            val recorder =
                NetworkTransactionRecorder(
                    factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                    store = store,
                )
            val bigBody = "a".repeat(300 * 1024)
            val mockEngine =
                MockEngine { _ ->
                    respond(
                        content = bigBody,
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType to listOf("text/plain"),
                                HttpHeaders.ContentLength to listOf(bigBody.length.toString()),
                            ),
                    )
                }
            val client =
                HttpClient(mockEngine) {
                    install(DevConsoleKtorClientPlugin) { this.recorder = recorder }
                }

            val response = client.get("https://api.test/big")
            assertEquals(bigBody, response.bodyAsText())

            val captured = awaitTransactions(store).single().capture.response!!
            assertTrue(captured.body is BodyPreview.Absent)
            assertEquals("too-large", captured.metadata.body.omittedReason)
            assertEquals(bigBody.length.toLong(), captured.metadata.body.declaredLength)
        }

    @Test
    fun `omits an oversized chunked response body at the capture cap`() =
        runBlocking {
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("1234567890123456".encodeToByteArray()))
            val recorder =
                NetworkTransactionRecorder(
                    factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                    store = store,
                )
            val bigBody = "b".repeat(300 * 1024)
            val mockEngine =
                MockEngine { _ ->
                    respond(
                        content = ByteReadChannel(bigBody),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                    )
                }
            val client =
                HttpClient(mockEngine) {
                    install(SaveBodyPlugin) { disabled = true }
                    install(DevConsoleKtorClientPlugin) { this.recorder = recorder }
                }

            // The capture side hits its cap mid-stream and must record and then keep draining --
            // the host's read of the other split half must still see every byte.
            val response = client.get("https://api.test/big-chunked")
            assertEquals(bigBody, response.bodyAsText())

            val captured = awaitTransactions(store).single().capture.response!!
            assertTrue(captured.body is BodyPreview.Absent)
            assertEquals("too-large", captured.metadata.body.omittedReason)
        }

    @Test
    fun `leaves a binary response body as metadata only`() =
        runBlocking {
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("1234567890123456".encodeToByteArray()))
            val recorder =
                NetworkTransactionRecorder(
                    factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                    store = store,
                )
            val binaryBody = ByteArray(64) { it.toByte() }
            val mockEngine =
                MockEngine { _ ->
                    respond(
                        content = binaryBody,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
                    )
                }
            val client =
                HttpClient(mockEngine) {
                    install(DevConsoleKtorClientPlugin) { this.recorder = recorder }
                }

            val response = client.get("https://api.test/download")
            assertArrayEquals(binaryBody, response.body<ByteArray>())

            val captured = awaitTransactions(store).single().capture.response!!
            assertTrue(captured.body is BodyPreview.Absent)
            assertEquals("binary", captured.metadata.body.omittedReason)
        }

    private fun awaitTransactions(
        store: InMemoryNetworkTransactionStore,
        maxWaitMs: Long = 2000L,
    ): List<io.devconsole.network.NetworkTransaction> {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            val list = store.page(NetworkTransactionQuery()).transactions
            if (list.isNotEmpty()) return list
            Thread.sleep(10)
        }
        return store.page(NetworkTransactionQuery()).transactions
    }
}
