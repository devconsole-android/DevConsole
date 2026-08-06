package io.devconsole.composer

import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerRequestTest {
    @Test
    fun `redirect destination is authorized before the next transport call`() {
        val requests = mutableListOf<ResolvedComposerRequest>()
        val executor =
            ComposerExecutor { request ->
                requests += request
                ComposerResponse(302, headers = mapOf("Location" to "http://blocked.test/internal"))
            }

        val result =
            runCatching {
                executor.execute(ComposerRequest("GET", "https://allowed.test/start")) { destination ->
                    java.net.URI(destination).host == "allowed.test"
                }
            }

        assertTrue(result.exceptionOrNull() is ComposerDestinationRejectedException)
        assertEquals(listOf("https://allowed.test/start"), requests.map { it.url })
        assertTrue(requests.none { it.followRedirects })
    }

    @Test
    fun `same host redirect chain succeeds while 307 preserves method body and credentials`() {
        val requests = mutableListOf<ResolvedComposerRequest>()
        val executor =
            ComposerExecutor { request ->
                requests += request
                if (requests.size == 1) {
                    ComposerResponse(307, headers = mapOf("location" to "/next"))
                } else {
                    ComposerResponse(201, body = "created")
                }
            }

        val execution =
            executor.execute(
                ComposerRequest(
                    method = "POST",
                    url = "https://allowed.test/start",
                    headers = mapOf("Authorization" to "Bearer secret"),
                    body = "payload",
                ),
            ) { destination -> java.net.URI(destination).host == "allowed.test" }

        assertEquals(201, execution.response.statusCode)
        assertEquals(listOf("https://allowed.test/start", "https://allowed.test/next"), requests.map { it.url })
        assertEquals("POST", requests.last().method)
        assertEquals("payload", requests.last().body)
        assertEquals("Bearer secret", requests.last().headers["Authorization"])
    }

    @Test
    fun `redacted projection removes secrets from every persisted composer field`() {
        val engine = RedactionEngine(RedactionPolicy.default())
        val request =
            ComposerRequest(
                method = "POST",
                url = "https://api.test/orders?access_token=url-canary",
                headers = mapOf("Authorization" to "Bearer header-canary", "Accept" to "application/json"),
                body = """{"password":"body-canary","keep":"visible"}""",
                query = listOf(ComposerQueryParameter("token", "query-canary")),
                formFields = mapOf("client_secret" to "form-canary"),
                multipartParts = listOf(ComposerMultipartPart("secret", "part-canary")),
                binaryBody = ComposerBinaryBody("token=binary-canary", "application/octet-stream", byteArrayOf(1)),
                variables = listOf(ComposerVariable("apiKey", "variable-canary", secret = true)),
            )

        val projected = request.redacted(engine)
        val metadata = projected.toRedactedMetadata(engine)

        listOf(
            "url-canary",
            "header-canary",
            "body-canary",
            "query-canary",
            "form-canary",
            "part-canary",
            "binary-canary",
            "variable-canary",
        ).forEach { assertFalse("$it leaked in $metadata", metadata.contains(it)) }
        assertFalse(projected.toString().contains("canary"))
        assertTrue(projected.body.orEmpty().contains("visible"))
        assertEquals("application/json", projected.headers["Accept"])
    }

    @Test fun `resolves regular variables while excluding secrets from metadata`() {
        val request =
            ComposerRequest(
                "GET",
                "${'$'}{baseUrl}/orders",
                variables =
                    listOf(
                        ComposerVariable("baseUrl", "https://api.test"),
                        ComposerVariable("token", "top-private-value", secret = true),
                    ),
            )
        assertEquals("https://api.test/orders", request.resolve().url)
        assertFalse(request.toRedactedMetadata().contains("top-private-value"))
    }

    @Test fun `resolves query and form bodies with transport policy while preserving redacted metadata`() {
        val request =
            ComposerRequest(
                method = "POST",
                url = "https://api.test/orders",
                query = listOf(ComposerQueryParameter("tenant", "${'$'}{tenant}")),
                bodyType = ComposerBodyType.FORM_URL_ENCODED,
                formFields = mapOf("token" to "${'$'}{token}", "title" to "order"),
                timeoutMs = 2_500,
                followRedirects = false,
                variables =
                    listOf(
                        ComposerVariable("tenant", "alpha"),
                        ComposerVariable("token", "private", secret = true),
                    ),
            )

        val resolved = request.resolve()

        assertEquals("https://api.test/orders?tenant=alpha", resolved.url)
        assertEquals("token=private&title=order", resolved.body)
        assertEquals(ComposerBodyType.FORM_URL_ENCODED, resolved.bodyType)
        assertEquals(2_500, resolved.timeoutMs)
        assertFalse(resolved.followRedirects)
        assertFalse(request.toRedactedMetadata().contains("private"))
    }

    @Test fun `imports the documented safe cURL subset without shell execution`() {
        val result =
            ComposerCurlImporter.import(
                "curl -X POST -H 'Content-Type: application/json' --data '{\"order\":42}' https://api.test/orders",
            )

        val request = result.getOrThrow()
        assertEquals("POST", request.method)
        assertEquals("https://api.test/orders", request.url)
        assertEquals("application/json", request.headers.getValue("Content-Type"))
        assertEquals("{\"order\":42}", request.body)
        assertEquals(ComposerBodyType.JSON, request.bodyType)
    }

    @Test fun `stores collections without persisting variables marked secret`() {
        val store = InMemoryComposerCollectionStore()
        val saved =
            store.save(
                "smoke",
                ComposerRequest(
                    "GET",
                    "https://api.test",
                    variables = listOf(ComposerVariable("token", "secret", secret = true)),
                ),
            )

        assertEquals("smoke", store.collections().single().name)
        assertEquals(
            "",
            store
                .collections()
                .single()
                .request.variables
                .single()
                .value,
        )
        assertEquals(saved.id, store.collections().single().id)
    }
}
