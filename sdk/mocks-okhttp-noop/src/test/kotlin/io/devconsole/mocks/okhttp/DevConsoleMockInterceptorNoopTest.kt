package io.devconsole.mocks.okhttp

import io.devconsole.mocks.MockAction
import io.devconsole.mocks.MockEngine
import io.devconsole.mocks.MockRule
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class DevConsoleMockInterceptorNoopTest {
    @Test
    fun `ignores matching mock rules and forwards the host call exactly once`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().code(204).build())
            val engine =
                MockEngine(
                    listOf(
                        MockRule(
                            id = "must-not-run",
                            priority = 100,
                            path = "/orders",
                            action = MockAction.StaticResponse(599, "mocked"),
                        ),
                    ),
                )
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor(DevConsoleMockInterceptor(engine))
                    .build()

            val response = client.newCall(Request.Builder().url(server.url("/orders")).build()).execute()

            assertEquals(204, response.code)
            assertEquals(1, server.requestCount)
        } finally {
            server.close()
        }
    }
}
