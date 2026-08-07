package io.devconsole.mocks.okhttp

import io.devconsole.mocks.MockAction
import io.devconsole.mocks.MockEngine
import io.devconsole.mocks.MockOutcome
import io.devconsole.mocks.MockRule
import io.devconsole.network.NetworkCaptureContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class DevConsoleMockInterceptorTest {
    @Test fun `returns a static response for a matching rule`() {
        val outcomes = mutableListOf<MockOutcome>()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(
                    DevConsoleMockInterceptor(
                        MockEngine(
                            listOf(
                                MockRule(
                                    "orders",
                                    1,
                                    path = "/orders",
                                    action = MockAction.StaticResponse(201, "mocked"),
                                ),
                            ),
                        ).withOutcomeSink(outcomes::add),
                    ),
                ).build()
        val response = client.newCall(Request.Builder().url("https://api.test/orders").build()).execute()
        assertEquals(201, response.code)
        assertEquals("mocked", response.body!!.string())
        assertEquals("orders", (outcomes.single() as MockOutcome.Matched).ruleId)
        assertEquals("orders", response.request.tag(NetworkCaptureContext::class.java)!!.tags["mockRuleId"])
    }

    @Test fun `can transform a host response without bypassing the host call`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(202)
                    .setBody("original"),
            )
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor(
                        DevConsoleMockInterceptor(
                            MockEngine(
                                listOf(
                                    MockRule("override", 1, path = "/orders", action = MockAction.StatusOverride(299)),
                                ),
                            ),
                        ),
                    ).build()

            val response = client.newCall(Request.Builder().url(server.url("/orders")).build()).execute()

            assertEquals(299, response.code)
            assertEquals("original", response.body!!.string())
            assertEquals(1, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test fun `replaces a host response body after passthrough`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("original"))
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor(
                        DevConsoleMockInterceptor(
                            MockEngine(
                                listOf(
                                    MockRule(
                                        "replace",
                                        1,
                                        path = "/orders",
                                        action = MockAction.BodyReplacement("replacement"),
                                    ),
                                ),
                            ),
                        ),
                    ).build()

            val response = client.newCall(Request.Builder().url(server.url("/orders")).build()).execute()

            assertEquals("replacement", response.body!!.string())
            assertEquals(1, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test fun `repeated headers are folded but a repeated query param keeps its first value`() {
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(
                    DevConsoleMockInterceptor(
                        MockEngine(
                            listOf(
                                MockRule(
                                    "template",
                                    1,
                                    path = "/orders",
                                    action =
                                        MockAction.TemplateResponse(
                                            200,
                                            "{{header.X-Tag}}|{{query.tag}}",
                                        ),
                                ),
                            ),
                        ),
                    ),
                ).build()

        val response =
            client
                .newCall(
                    Request
                        .Builder()
                        .url("https://api.test/orders?tag=a&tag=b")
                        .addHeader("X-Tag", "a")
                        .addHeader("X-Tag", "b")
                        .build(),
                ).execute()

        assertEquals("a, b|a", response.body!!.string())
    }
}
