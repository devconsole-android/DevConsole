package io.devconsole.composer

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class UrlConnectionComposerTransportTest {
    @Test
    fun `sdk owned transport executes form request with configured timeout and redirect policy`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/orders") { exchange ->
            val payload = exchange.requestBody.bufferedReader().readText()
            val response = "${exchange.requestMethod}|$payload".encodeToByteArray()
            exchange.sendResponseHeaders(201, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val request =
                ComposerRequest(
                    method = "POST",
                    url = "http://127.0.0.1:${server.address.port}/orders",
                    bodyType = ComposerBodyType.FORM_URL_ENCODED,
                    formFields = mapOf("order" to "42"),
                    timeoutMs = 2_000,
                    followRedirects = false,
                ).resolve()

            val response = UrlConnectionComposerTransport().execute(request)

            assertEquals(201, response.statusCode)
            assertEquals("POST|order=42", response.body)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `real redirect is rejected before the second disposable server receives a request`() {
        val targetRequests = AtomicInteger()
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        target.createContext("/private") { exchange ->
            targetRequests.incrementAndGet()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        target.start()
        val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        source.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${target.address.port}/private")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        source.start()
        try {
            val permittedPort = source.address.port

            assertThrows(ComposerDestinationRejectedException::class.java) {
                ComposerExecutor(UrlConnectionComposerTransport()).execute(
                    ComposerRequest(
                        method = "GET",
                        url = "http://127.0.0.1:$permittedPort/redirect",
                    ),
                    permitsDestination = { destination ->
                        java.net.URI(destination).port == permittedPort
                    },
                )
            }

            assertEquals(0, targetRequests.get())
        } finally {
            source.stop(0)
            target.stop(0)
        }
    }
}
