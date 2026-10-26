package io.devconsole.agent

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RequestExecutorTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val seen = ConcurrentHashMap<String, String>()
    private val base get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/echo") { exchange ->
            seen["method"] = exchange.requestMethod
            seen["authorization"] = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            seen["contentType"] = exchange.requestHeaders.getFirst("Content-Type").orEmpty()
            seen["body"] = exchange.requestBody.readBytes().decodeToString()
            val reply = "{\"ok\":true}".encodeToByteArray()
            exchange.responseHeaders.add("X-Reply", "yes")
            exchange.sendResponseHeaders(201, if (exchange.requestMethod == "HEAD") -1 else reply.size.toLong())
            if (exchange.requestMethod != "HEAD") exchange.responseBody.use { it.write(reply) }
            exchange.close()
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "$base/echo")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/big") { exchange ->
            val body = ByteArray(3_000) { 'a'.code.toByte() }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/slow") { exchange ->
            Thread.sleep(2_000)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun job(
        method: String,
        path: String,
        headers: List<Pair<String, String>> = emptyList(),
        body: AgentBody? = null,
        timeoutMs: Int = 5_000,
    ) = AgentJob("job-1", method, base + path, timeoutMs, headers, body)

    private fun text(
        value: String,
        contentType: String? = null,
    ) = AgentBody(value.encodeToByteArray(), contentType)

    @Test
    fun `GET carries the bearer token and returns status headers and body`() {
        val outcome = RequestExecutor().execute(job("GET", "/echo", listOf("Authorization" to "Bearer abc123")))

        assertIs<AgentOutcome.Result>(outcome)
        assertEquals(201, outcome.statusCode)
        assertEquals("{\"ok\":true}", outcome.body)
        assertEquals(
            "yes",
            outcome.headers.entries
                .first { it.key.equals("X-Reply", true) }
                .value,
        )
        assertEquals("Bearer abc123", seen["authorization"])
        assertEquals("GET", seen["method"])
    }

    @Test
    fun `POST sends the body with the default content type`() {
        val outcome =
            RequestExecutor().execute(
                job("POST", "/echo", body = text("{\"a\":1}", "application/json")),
            )

        assertIs<AgentOutcome.Result>(outcome)
        assertEquals("{\"a\":1}", seen["body"])
        assertEquals("application/json", seen["contentType"])
    }

    @Test
    fun `an explicit Content-Type header wins over the default`() {
        RequestExecutor().execute(
            job("POST", "/echo", listOf("content-type" to "text/csv"), body = text("a,b", "application/json")),
        )

        assertEquals("text/csv", seen["contentType"])
    }

    @Test
    fun `DELETE PUT and PATCH reach the server with their own method`() {
        listOf("DELETE", "PUT", "PATCH").forEach { method ->
            val outcome = RequestExecutor().execute(job(method, "/echo", body = text("x", "text/plain")))

            assertIs<AgentOutcome.Result>(outcome)
            assertEquals(method, seen["method"])
        }
    }

    @Test
    fun `a redirect is returned as is, never followed`() {
        val outcome = RequestExecutor().execute(job("GET", "/redirect"))

        assertIs<AgentOutcome.Result>(outcome)
        assertEquals(302, outcome.statusCode)
        assertTrue(outcome.headers.keys.any { it.equals("Location", true) })
    }

    @Test
    fun `the response body is bounded`() {
        val outcome = RequestExecutor(maxResponseBytes = 1_000).execute(job("GET", "/big"))

        assertIs<AgentOutcome.Result>(outcome)
        assertEquals(1_000, outcome.body.length)
    }

    @Test
    fun `a slow server is reported as a timeout`() {
        val outcome = RequestExecutor().execute(job("GET", "/slow", timeoutMs = 300))

        assertIs<AgentOutcome.Failure>(outcome)
        assertEquals(AgentFrame.ERROR_FAILED, outcome.code)
        assertTrue(outcome.message.contains("timed out"), outcome.message)
    }

    @Test
    fun `a closed port is reported as unreachable`() {
        val port = server.address.port
        server.stop(0)

        val outcome =
            RequestExecutor().execute(
                AgentJob("j", "GET", "http://127.0.0.1:$port/", 2_000, emptyList(), null),
            )

        assertIs<AgentOutcome.Failure>(outcome)
        assertTrue(outcome.message.contains("connect", ignoreCase = true), outcome.message)
    }

    @Test
    fun `the local allow-host list refuses other hosts before any I-O`() {
        val policy = HostPolicy(allowedHosts = setOf("api.corp.example"))

        val outcome = RequestExecutor(policy).execute(job("GET", "/echo"))

        assertIs<AgentOutcome.Failure>(outcome)
        assertEquals(AgentFrame.ERROR_HOST_REJECTED, outcome.code)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `block-private refuses loopback targets`() {
        val outcome = RequestExecutor(HostPolicy(blockPrivateNetworks = true)).execute(job("GET", "/echo"))

        assertIs<AgentOutcome.Failure>(outcome)
        assertEquals(AgentFrame.ERROR_HOST_REJECTED, outcome.code)
    }

    @Test
    fun `an execute frame decodes into a job`() {
        val frame =
            AgentFrame.decode(
                AgentFrame.encode(
                    "type" to AgentFrame.EXECUTE,
                    "jobId" to "j9",
                    "method" to "post",
                    "url" to "https://api.example.test/a?b=c d",
                    "timeoutMs" to "2500",
                    "header" to "Authorization:Bearer a:b",
                    "header" to "X-One:1",
                    "bodyBase64" to Base64.getEncoder().encodeToString("héllo".encodeToByteArray()),
                    "contentType" to "text/plain",
                ),
            )

        val decoded = AgentJob.from(frame)!!

        assertEquals("POST", decoded.method)
        assertEquals("https://api.example.test/a?b=c d", decoded.url)
        assertEquals(2_500, decoded.timeoutMs)
        assertEquals(listOf("Authorization" to "Bearer a:b", "X-One" to "1"), decoded.headers)
        assertEquals("héllo", decoded.body!!.bytes.decodeToString())
        assertEquals("text/plain", decoded.body!!.contentType)
    }
}
