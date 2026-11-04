package io.devconsole.mocks.okhttp

import io.devconsole.mocks.MockAction
import io.devconsole.mocks.MockEngine
import io.devconsole.mocks.MockOutcome
import io.devconsole.mocks.MockRule
import io.devconsole.network.NetworkCaptureContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DevConsoleMockInterceptorTest {
    @Test fun `invalid response header does not crash and real call proceeds`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("real"))
            val engine = MockEngine(emptyList())
            val upsert =
                runCatching {
                    engine.upsert(
                        MockRule(
                            "bad",
                            1,
                            path = "/",
                            action = MockAction.StaticResponse(200, "mock", mapOf("X-Bad" to "café")),
                        ),
                    )
                }
            assertTrue(upsert.isFailure)
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor(
                        DevConsoleMockInterceptor(engine),
                    ).build()
            val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            assertEquals("real", response.body!!.string())
            assertEquals(1, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test fun `cancellation during delay aborts promptly`() {
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(
                    DevConsoleMockInterceptor(
                        MockEngine(listOf(MockRule("slow", 1, path = "/", action = MockAction.Delay(30_000)))),
                    ),
                ).build()
        val finished = CountDownLatch(1)
        val call = client.newCall(Request.Builder().url("https://api.test/").build())
        call.enqueue(
            object : okhttp3.Callback {
                override fun onFailure(
                    call: okhttp3.Call,
                    e: java.io.IOException,
                ) {
                    finished.countDown()
                }

                override fun onResponse(
                    call: okhttp3.Call,
                    response: okhttp3.Response,
                ) {
                    response.close()
                    finished.countDown()
                }
            },
        )
        Thread.sleep(100)
        call.cancel()
        assertTrue(finished.await(2, TimeUnit.SECONDS))
    }

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

    @Test fun `mapRemote rewrites host port and path while preserving query and tags`() {
        val targetServer = MockWebServer()
        targetServer.start()
        try {
            targetServer.enqueue(MockResponse().setResponseCode(200).setBody("from-remote"))
            val targetUrl = targetServer.url("/v2/orders")
            val engine =
                MockEngine(
                    listOf(
                        MockRule(
                            id = "remap",
                            priority = 1,
                            path = "/v1/orders",
                            action =
                                MockAction.MapRemote(
                                    scheme = targetUrl.scheme,
                                    host = targetUrl.host,
                                    port = targetUrl.port,
                                    path = "/v2/orders",
                                ),
                        ),
                    ),
                )
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor(DevConsoleMockInterceptor(engine))
                    .build()

            val response =
                client
                    .newCall(
                        Request
                            .Builder()
                            .url("http://original.invalid:9999/v1/orders?page=2&filter=active")
                            .build(),
                    ).execute()

            assertEquals(200, response.code)
            assertEquals("from-remote", response.body!!.string())
            val recorded = targetServer.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/v2/orders?page=2&filter=active", recorded.path)
            val tags = response.request.tag(NetworkCaptureContext::class.java)!!.tags
            assertEquals("true", tags["mocked"])
            assertEquals("remap", tags["mockRuleId"])
            assertEquals("$targetUrl?page=2&filter=active", tags["mapRemote"])
        } finally {
            targetServer.close()
        }
    }

    @Test fun `throttle delivers full body intact and enforces bandwidth rate`() {
        val bodyText = "x".repeat(200)
        val engine =
            MockEngine(
                listOf(
                    MockRule(
                        id = "throttled",
                        priority = 1,
                        path = "/download",
                        action =
                            MockAction.Throttle(
                                bytesPerSecond = 1_000,
                                latencyMs = 20,
                                next = MockAction.StaticResponse(200, bodyText, mapOf("Content-Type" to "text/plain")),
                            ),
                    ),
                ),
            )
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(DevConsoleMockInterceptor(engine))
                .build()

        val startNanos = System.nanoTime()
        val response = client.newCall(Request.Builder().url("https://api.test/download").build()).execute()
        val responseBody = response.body!!
        assertEquals(bodyText.length.toLong(), responseBody.contentLength())
        assertEquals("text/plain", responseBody.contentType().toString())
        val content = responseBody.string()
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L

        assertEquals(bodyText, content)
        assertTrue("Expected at least 150ms for 200B at 1000B/s + 20ms latency, got ${elapsedMs}ms", elapsedMs >= 150L)
    }

    @Test fun `body predicate sees the request body and leaves it intact for the real call`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("real"))
            val engine =
                MockEngine(
                    listOf(
                        MockRule(
                            "paid",
                            1,
                            method = "POST",
                            path = "/orders",
                            bodyPredicate = ".*\"paid\":true.*",
                            action = MockAction.StaticResponse(402, "mocked"),
                        ),
                    ),
                    bodyMatchingEnabled = true,
                )
            val client = OkHttpClient.Builder().addInterceptor(DevConsoleMockInterceptor(engine)).build()
            val json = "application/json".toMediaType()

            val matched =
                client
                    .newCall(
                        Request
                            .Builder()
                            .url(
                                server.url("/orders"),
                            ).post("{\"paid\":true}".toRequestBody(json))
                            .build(),
                    ).execute()
            assertEquals(402, matched.code)
            assertEquals("mocked", matched.body!!.string())
            assertEquals(0, server.requestCount)

            val passed =
                client
                    .newCall(
                        Request
                            .Builder()
                            .url(
                                server.url("/orders"),
                            ).post("{\"paid\":false}".toRequestBody(json))
                            .build(),
                    ).execute()
            assertEquals("real", passed.body!!.string())
            assertEquals("{\"paid\":false}", server.takeRequest().body.readUtf8())
        } finally {
            server.close()
        }
    }
}
