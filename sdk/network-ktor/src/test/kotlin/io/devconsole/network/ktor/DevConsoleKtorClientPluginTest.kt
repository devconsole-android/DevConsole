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
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
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
