package io.devconsole.composer

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UrlConnectionComposerTransportTest {
    @Test
    fun `sdk owned transport executes form request with configured timeout and redirect policy`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(201).setBody("created"))

            val request =
                ComposerRequest(
                    method = "POST",
                    url = server.url("/orders").toString(),
                    bodyType = ComposerBodyType.FORM_URL_ENCODED,
                    formFields = mapOf("order" to "42"),
                    timeoutMs = 2_000,
                    followRedirects = false,
                ).resolve()

            val response =
                UrlConnectionComposerTransport(permitPrivateNetworkTargets = true).execute(request)

            assertEquals(201, response.statusCode)
            assertEquals("created", response.body)

            val recordedRequest = server.takeRequest()
            assertEquals("POST", recordedRequest.method)
            assertEquals("order=42", recordedRequest.body.readUtf8())
        } finally {
            server.close()
        }
    }

    @Test
    fun `real redirect is rejected before the second disposable server receives a request`() {
        val target = MockWebServer()
        target.start()
        val source = MockWebServer()
        source.start()
        try {
            target.enqueue(MockResponse().setResponseCode(204))
            source.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", target.url("/private").toString()),
            )
            val permittedPort = source.port

            assertThrows(ComposerDestinationRejectedException::class.java) {
                ComposerExecutor(UrlConnectionComposerTransport(permitPrivateNetworkTargets = true)).execute(
                    ComposerRequest(
                        method = "GET",
                        url = source.url("/redirect").toString(),
                    ),
                    permitsDestination = { destination ->
                        java.net.URI(destination).port == permittedPort
                    },
                )
            }

            assertEquals(0, target.requestCount)
        } finally {
            source.close()
            target.close()
        }
    }

    @Test
    fun `request to a loopback address is rejected without an explicit override`() {
        assertThrows(ComposerDestinationRejectedException::class.java) {
            UrlConnectionComposerTransport().execute(
                ComposerRequest(method = "GET", url = "http://127.0.0.1:1/blocked").resolve(),
            )
        }
    }

    @Test
    fun `request to the link-local cloud metadata address is rejected without an explicit override`() {
        assertThrows(ComposerDestinationRejectedException::class.java) {
            UrlConnectionComposerTransport().execute(
                ComposerRequest(method = "GET", url = "http://169.254.169.254/latest/meta-data/").resolve(),
            )
        }
    }
}
