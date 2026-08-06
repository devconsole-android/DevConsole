package io.devconsole.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ComposerExecutorTest {
    @Test
    fun `legacy one argument execute entry point remains available to JVM callers`() {
        val method = ComposerExecutor::class.java.getMethod("execute", ComposerRequest::class.java)

        assertEquals(ComposerExecution::class.java, method.returnType)
    }

    @Test
    fun `executor resolves in-memory variables for its isolated transport without returning secret values`() {
        var dispatched: ResolvedComposerRequest? = null
        val executor =
            ComposerExecutor(
                ComposerTransport { request ->
                    dispatched = request
                    ComposerResponse(
                        statusCode = 201,
                        headers = mapOf("Content-Type" to "application/json"),
                        body = "{\"ok\":true}",
                    )
                },
            )
        val request =
            ComposerRequest(
                method = "POST",
                url = "https://api.example.test/orders?token=${'$'}{token}",
                body = "{\"note\":\"${'$'}{note}\"}",
                variables =
                    listOf(
                        ComposerVariable("token", "private-token", secret = true),
                        ComposerVariable("note", "hello"),
                    ),
            )

        val result = executor.execute(request)

        assertEquals("https://api.example.test/orders?token=private-token", dispatched!!.url)
        assertEquals("{\"note\":\"hello\"}", dispatched!!.body)
        assertEquals(201, result.response.statusCode)
        assertFalse(result.requestMetadata.contains("private-token"))
    }

    @Test
    fun `cross origin redirect strips credentials before dispatching the next hop`() {
        val dispatched = mutableListOf<ResolvedComposerRequest>()
        val executor =
            ComposerExecutor(
                ComposerTransport { request ->
                    dispatched += request
                    if (dispatched.size == 1) {
                        ComposerResponse(
                            statusCode = 302,
                            headers = mapOf("Location" to "https://other.example.test/next"),
                        )
                    } else {
                        ComposerResponse(statusCode = 200)
                    }
                },
            )

        executor.execute(
            ComposerRequest(
                method = "GET",
                url = "https://api.example.test/start",
                headers =
                    mapOf(
                        "Authorization" to "Bearer secret",
                        "Proxy-Authorization" to "Basic secret",
                        "Cookie" to "session=secret",
                        "X-Trace-Id" to "trace",
                    ),
            ),
            permitsDestination = { true },
        )

        assertEquals(2, dispatched.size)
        assertEquals("trace", dispatched[1].headers["X-Trace-Id"])
        assertNull(dispatched[1].headers["Authorization"])
        assertNull(dispatched[1].headers["Proxy-Authorization"])
        assertNull(dispatched[1].headers["Cookie"])
    }

    @Test
    fun `303 redirect converts post to get and drops every body representation`() {
        val dispatched = mutableListOf<ResolvedComposerRequest>()
        val executor =
            ComposerExecutor(
                ComposerTransport { request ->
                    dispatched += request
                    if (dispatched.size == 1) {
                        ComposerResponse(statusCode = 303, headers = mapOf("Location" to "/done"))
                    } else {
                        ComposerResponse(statusCode = 204)
                    }
                },
            )

        executor.execute(
            ComposerRequest(
                method = "POST",
                url = "https://api.example.test/start",
                body = "secret body",
            ),
            permitsDestination = { true },
        )

        assertEquals("GET", dispatched[1].method)
        assertEquals("https://api.example.test/done", dispatched[1].url)
        assertEquals(ComposerBodyType.NONE, dispatched[1].bodyType)
        assertNull(dispatched[1].body)
        assertNull(dispatched[1].binaryBody)
        assertEquals(emptyList<ComposerMultipartPart>(), dispatched[1].multipartParts)
    }

    @Test
    fun `redirect traversal is bounded to five followed hops`() {
        var dispatchCount = 0
        val executor =
            ComposerExecutor(
                ComposerTransport {
                    dispatchCount += 1
                    ComposerResponse(statusCode = 302, headers = mapOf("Location" to "/again"))
                },
            )

        assertThrows(IllegalStateException::class.java) {
            executor.execute(
                ComposerRequest(method = "GET", url = "https://api.example.test/start"),
                permitsDestination = { true },
            )
        }

        assertEquals(6, dispatchCount)
    }
}
