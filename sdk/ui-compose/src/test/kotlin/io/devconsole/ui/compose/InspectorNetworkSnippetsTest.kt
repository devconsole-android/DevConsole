package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "share as JSON" snippets are the one place a captured payload leaves the app as a document
 * someone else will read, so what matters here is that the result is valid JSON *and* that a JSON
 * body arrives as JSON rather than as a wall of `\"`.
 */
class InspectorNetworkSnippetsTest {
    private fun transaction(
        responsePreview: String? = null,
        requestPreview: String? = null,
    ) = InspectorTransactionUi(
        id = "tx-1",
        method = "GET",
        host = "api.example.test",
        path = "/todos/1",
        statusCode = 200,
        durationMs = 42,
        url = "https://api.example.test/todos/1",
        requestPreview = requestPreview,
        responsePreview = responsePreview,
    )

    /** Parsing the snippet back is the real assertion: a body embedded wrong breaks the document. */
    private fun parse(snippet: String): JsonValue.Obj = MinimalJsonParser(snippet).parseDocument() as JsonValue.Obj

    private fun JsonValue.Obj.field(name: String): JsonValue? = entries.firstOrNull { it.first == name }?.second

    @Test
    fun `a json response body is embedded as json, not as an escaped string`() {
        val snippet = transaction(responsePreview = """{"userId": 1, "title": "delectus aut autem"}""").toJsonSnippet()

        val body = parse(snippet).field("responseBody")
        assertTrue("expected an object, got $body", body is JsonValue.Obj)
        assertEquals(JsonValue.Num("1"), (body as JsonValue.Obj).entries.first { it.first == "userId" }.second)
        // The escaped-string form is exactly what this fixes -- the snippet must not contain it.
        assertFalse(snippet.contains("""\"userId\""""))
    }

    @Test
    fun `a json request body is embedded too`() {
        val snippet = transaction(requestPreview = """[{"id": 1}]""").toJsonSnippet()

        assertTrue(parse(snippet).field("requestBody") is JsonValue.Arr)
    }

    /**
     * Only containers are embedded. A text body that happens to read `false` (or `12`, or `null`)
     * is still text, and turning it into a JSON literal would stop it round-tripping.
     */
    @Test
    fun `a scalar body stays a quoted string`() {
        val snippet = transaction(responsePreview = "false").toJsonSnippet()

        assertEquals(JsonValue.Str("false"), parse(snippet).field("responseBody"))
    }

    @Test
    fun `a non-json body stays a quoted string`() {
        val snippet = transaction(responsePreview = "<html><body>nope</body></html>").toJsonSnippet()

        assertEquals(JsonValue.Str("<html><body>nope</body></html>"), parse(snippet).field("responseBody"))
    }

    /** A capture cut at the body limit is not parseable, so it falls back on its own. */
    @Test
    fun `a truncated json body stays a quoted string`() {
        val truncated = """{"userId": 1, "title": "delec"""
        val snippet = transaction(responsePreview = truncated).toJsonSnippet()

        assertEquals(JsonValue.Str(truncated), parse(snippet).field("responseBody"))
    }

    @Test
    fun `an absent body stays null`() {
        assertEquals(JsonValue.Null, parse(transaction().toJsonSnippet()).field("responseBody"))
    }

    /**
     * Header values that contain quotes (`etag`, `alt-svc`, the NEL report) keep their escaping --
     * removing it would produce a document nothing can parse.
     */
    @Test
    fun `header values keep the escaping that keeps the document valid`() {
        val snippet =
            InspectorTransactionUi(
                id = "tx-2",
                method = "GET",
                host = "api.example.test",
                path = "/todos/1",
                statusCode = 200,
                durationMs = 42,
                url = "https://api.example.test/todos/1",
                responseHeaders = mapOf("etag" to """W/"53-hfEnume"""),
            ).toJsonSnippet()

        val headers = parse(snippet).field("responseHeaders") as JsonValue.Obj
        assertEquals(JsonValue.Str("""W/"53-hfEnume"""), headers.entries.single().second)
    }
}
