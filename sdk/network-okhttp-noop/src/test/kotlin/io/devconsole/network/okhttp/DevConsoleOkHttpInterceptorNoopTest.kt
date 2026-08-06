package io.devconsole.network.okhttp

import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkTransactionQuery
import io.devconsole.network.NetworkTransactionRecorder
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class DevConsoleOkHttpInterceptorNoopTest {
    @Test
    fun `forwards exactly once without recording or inspecting payloads`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(203)
                    .body("host-body")
                    .build(),
            )
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16) { it.toByte() }))
            val recorder =
                NetworkTransactionRecorder(
                    factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                    store = store,
                    executor = Executor(Runnable::run),
                )
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor(DevConsoleOkHttpInterceptor(recorder))
                    .build()

            val request = Request.Builder().url(server.url("/orders?access_token=canary")).build()
            val response = client.newCall(request).execute()

            assertEquals(203, response.code)
            assertEquals("host-body", response.body.string())
            assertEquals(1, server.requestCount)
            assertTrue(store.page(NetworkTransactionQuery()).transactions.isEmpty())
        } finally {
            server.close()
        }
    }
}
