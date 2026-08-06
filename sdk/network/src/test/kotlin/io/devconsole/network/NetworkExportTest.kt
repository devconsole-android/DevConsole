package io.devconsole.network

import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkExportTest {
    @Test
    fun `curl and har use only redacted capture fields`() {
        val capture =
            NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                NetworkRequestInput(
                    method = "POST",
                    url = "https://example.test/orders?access_token=query-secret",
                    headers = mapOf("Authorization" to "Bearer header-secret"),
                    body = "Bearer body-secret".encodeToByteArray(),
                    contentType = "application/json",
                ),
                NetworkResponseInput(statusCode = 201),
            )

        val curl = NetworkExport.toCurl(capture)
        val har = NetworkExport.toHar(listOf(capture))

        listOf(curl, har).forEach { output ->
            assertFalse(output.contains("query-secret"))
            assertFalse(output.contains("header-secret"))
            assertFalse(output.contains("body-secret"))
        }
        assertTrue(har.contains("\"version\":\"1.2\""))
    }

    @Test
    fun `curl export emits the captured request body via data-raw`() {
        val capture =
            NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                NetworkRequestInput(
                    method = "POST",
                    url = "https://example.test/orders",
                    body = "{\"id\":1}".encodeToByteArray(),
                    contentType = "application/json",
                ),
                null,
            )

        val curl = NetworkExport.toCurl(capture)

        assertTrue(curl.contains("--data-raw '{\"id\":1}'"))
    }

    @Test
    fun `curl export shell-escapes single quotes in the body`() {
        val capture =
            NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                NetworkRequestInput(
                    method = "POST",
                    url = "https://example.test/orders",
                    body = "it's a test".encodeToByteArray(),
                    contentType = "text/plain",
                ),
                null,
            )

        val curl = NetworkExport.toCurl(capture)

        // The previous escaping replaced `'` with an escaped double-quote sequence, which is not
        // valid shell syntax; this asserts the standard `'\''` (close, escaped-quote, reopen) form.
        assertTrue(curl.contains("--data-raw 'it'\\''s a test'"))
    }

    @Test
    fun `har request cookies are parsed from the Cookie header`() {
        val unredacted = RedactionEngine(RedactionPolicy(sensitiveFieldNames = emptySet(), textPatterns = emptyList()))
        val capture =
            NetworkCaptureFactory(unredacted).capture(
                NetworkRequestInput(
                    method = "GET",
                    url = "https://example.test/orders",
                    headers = mapOf("Cookie" to "session=abc123; theme=dark"),
                ),
                NetworkResponseInput(statusCode = 200),
            )

        val har = NetworkExport.toHar(listOf(capture))

        har.assertIsValidJson()
        assertTrue(har.contains("\"name\":\"session\",\"value\":\"abc123\""))
        assertTrue(har.contains("\"name\":\"theme\",\"value\":\"dark\""))
    }

    @Test
    fun `har response cookies split multiple folded Set-Cookie headers and keep a comma-bearing Expires intact`() {
        val unredacted = RedactionEngine(RedactionPolicy(sensitiveFieldNames = emptySet(), textPatterns = emptyList()))
        // Multiple Set-Cookie response headers are folded into one comma-joined value upstream (the
        // OkHttp/Ktor adapters can only carry a single string per header name); this simulates that
        // already-folded shape, including an Expires attribute whose own comma must NOT be treated as
        // a cookie boundary.
        val foldedSetCookie =
            "sid=abc123; Path=/; Domain=example.test; Expires=Wed, 21 Oct 2026 07:28:00 GMT, uid=42; Path=/api"
        val capture =
            NetworkCaptureFactory(unredacted).capture(
                NetworkRequestInput(method = "GET", url = "https://example.test/orders"),
                NetworkResponseInput(statusCode = 200, headers = mapOf("Set-Cookie" to foldedSetCookie)),
            )

        val har = NetworkExport.toHar(listOf(capture))

        har.assertIsValidJson()
        assertTrue(har.contains("\"name\":\"sid\",\"value\":\"abc123\""))
        assertTrue(har.contains("\"path\":\"/\""))
        assertTrue(har.contains("\"domain\":\"example.test\""))
        assertTrue(har.contains("\"expires\":\"Wed, 21 Oct 2026 07:28:00 GMT\""))
        assertTrue(har.contains("\"name\":\"uid\",\"value\":\"42\""))
        assertTrue(har.contains("\"path\":\"/api\""))
    }

    @Test
    fun `har cookies never attempt to un-redact an already-redacted Cookie header`() {
        // Under the default policy, "cookie" and "set-cookie" are sensitive field names, so the whole
        // header value is already replaced with the redaction marker before NetworkExport ever sees
        // it. Cookie parsing must format whatever that redacted string is, not crash or fabricate.
        val capture =
            NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                NetworkRequestInput(
                    method = "GET",
                    url = "https://example.test/orders",
                    headers = mapOf("Cookie" to "session=raw-secret"),
                ),
                NetworkResponseInput(statusCode = 200, headers = mapOf("Set-Cookie" to "session=raw-secret")),
            )

        val har = NetworkExport.toHar(listOf(capture))

        har.assertIsValidJson()
        assertFalse(har.contains("raw-secret"))
        assertTrue(har.contains("<redacted>"))
    }

    @Test
    fun `transaction HAR uses real start duration and timing phases`() {
        val capture =
            NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                NetworkRequestInput("GET", "https://example.test/orders"),
                NetworkResponseInput(200, protocol = "h2")
                    .withMetadata(
                        NetworkResponseMetadata(
                            timings = NetworkTimingPhases(sendMs = 2, waitMs = 15, receiveMs = 3),
                        ),
                    ),
            )
        val transaction = NetworkTransaction("tx", 1_000, 1_020, capture)

        val har = NetworkExport.toHarTransactions(listOf(transaction))

        assertTrue(har.contains("\"startedDateTime\":\"1970-01-01T00:00:01.000Z\""))
        assertTrue(har.contains("\"time\":20"))
        assertTrue(har.contains("\"send\":2"))
        assertTrue(har.contains("\"wait\":15"))
        assertTrue(har.contains("\"receive\":3"))
        assertEquals(1, "\"startedDateTime\"".toRegex().findAll(har).count())
    }

    @Test
    fun `postman export includes request and response bodies with no raw secret`() {
        val capture =
            NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                NetworkRequestInput(
                    method = "POST",
                    url = "https://example.test/orders",
                    headers = mapOf("Authorization" to "Bearer header-secret"),
                    body = "{\"token\":\"body-secret\"}".encodeToByteArray(),
                    contentType = "application/json",
                ),
                NetworkResponseInput(
                    statusCode = 201,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = "{\"id\":42}".encodeToByteArray(),
                    contentType = "application/json",
                ),
            )
        val transaction = NetworkTransaction("tx-1", 1_000, 1_020, capture)

        val postman = NetworkExport.toPostman(listOf(transaction))

        assertTrue(postman.contains("https://schema.getpostman.com/json/collection/v2.1.0/collection.json"))
        assertTrue(postman.contains("\"POST\""))
        assertTrue(postman.contains("example.test/orders"))
        assertTrue(postman.contains("\"mode\":\"raw\""))
        // Body text is itself embedded as an escaped JSON string, so its inner quotes are backslash-escaped.
        assertTrue(postman.contains("id\\\":42"))
        assertTrue(postman.contains("\"code\":201"))
        assertFalse(postman.contains("header-secret"))
        assertFalse(postman.contains("body-secret"))
    }

    @Test
    fun `postman export deduplicates identical repeated requests`() {
        val factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default()))

        fun capture() =
            factory.capture(
                NetworkRequestInput(method = "GET", url = "https://example.test/health"),
                NetworkResponseInput(statusCode = 200),
            )
        val transactions =
            listOf(
                NetworkTransaction("tx-1", 1_000, 1_010, capture()),
                NetworkTransaction("tx-2", 2_000, 2_010, capture()),
                NetworkTransaction("tx-3", 3_000, 3_010, capture()),
            )

        val postman = NetworkExport.toPostman(transactions)

        assertEquals(1, postman.occurrencesOf("\"name\":\"GET https://example.test/health\""))
    }

    @Test
    fun `postman export keeps a 200 and a 401 for the same request separate`() {
        val factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default()))

        fun capture(statusCode: Int) =
            factory.capture(
                NetworkRequestInput(method = "GET", url = "https://example.test/health"),
                NetworkResponseInput(statusCode = statusCode),
            )
        val transactions =
            listOf(
                NetworkTransaction("tx-1", 1_000, 1_010, capture(200)),
                NetworkTransaction("tx-2", 2_000, 2_010, capture(401)),
            )

        val postman = NetworkExport.toPostman(transactions)

        assertEquals(2, postman.occurrencesOf("\"name\":\"GET https://example.test/health\""))
        assertTrue(postman.contains("\"code\":200"))
        assertTrue(postman.contains("\"code\":401"))
    }

    @Test
    fun `postman export still collapses the same request repeated with the same status`() {
        val factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default()))

        fun capture() =
            factory.capture(
                NetworkRequestInput(method = "GET", url = "https://example.test/health"),
                NetworkResponseInput(statusCode = 200),
            )
        val transactions =
            listOf(
                NetworkTransaction("tx-1", 1_000, 1_010, capture()),
                NetworkTransaction("tx-2", 2_000, 2_010, capture()),
            )

        val postman = NetworkExport.toPostman(transactions)

        assertEquals(1, postman.occurrencesOf("\"name\":\"GET https://example.test/health\""))
    }

    @Test
    fun `postman export keeps distinct requests separate`() {
        val factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default()))
        val transactions =
            listOf(
                NetworkTransaction(
                    "tx-1",
                    1_000,
                    1_010,
                    factory.capture(
                        NetworkRequestInput(method = "GET", url = "https://example.test/orders"),
                        NetworkResponseInput(statusCode = 200),
                    ),
                ),
                NetworkTransaction(
                    "tx-2",
                    2_000,
                    2_010,
                    factory.capture(
                        NetworkRequestInput(method = "GET", url = "https://example.test/invoices"),
                        NetworkResponseInput(statusCode = 200),
                    ),
                ),
            )

        val postman = NetworkExport.toPostman(transactions)

        assertTrue(postman.contains("example.test/orders"))
        assertTrue(postman.contains("example.test/invoices"))
    }

    @Test
    fun `har export of quote- and backslash-laden values is still one well-formed json document`() {
        // Every other test here checks specific substrings, which a string-escaping bug (e.g. an
        // unescaped quote in a URL, header, or body value) could defeat -- the substring might
        // still be present while the surrounding document is corrupted. Parsing the whole output
        // as JSON is the only way to catch that class of bug.
        val factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default()))
        val hostile =
            factory.capture(
                NetworkRequestInput(
                    method = "POST",
                    url = "https://example.test/orders?q=value",
                    headers = mapOf("X-Custom" to "line\nbreak\tand\"quote\\backslash"),
                    body = "{\"note\":\"a \\\"nested\\\" quote and a backslash \\\\ here\"}".encodeToByteArray(),
                    contentType = "application/json",
                ),
                NetworkResponseInput(statusCode = 200, headers = mapOf("X-Reply" to "\"also quoted\"")),
            )
        val transactions =
            listOf(
                NetworkTransaction("tx-1", 1_000, 1_010, hostile),
                NetworkTransaction("tx-2", 2_000, 2_010, hostile),
            )

        val har = NetworkExport.toHarTransactions(transactions)

        har.assertIsValidJson()
        assertEquals(2, "\"startedDateTime\"".toRegex().findAll(har).count())
    }

    @Test
    fun `postman export of quote- and backslash-laden values is still one well-formed json document`() {
        val factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default()))
        val hostile =
            factory.capture(
                NetworkRequestInput(
                    method = "POST",
                    url = "https://example.test/orders?q=value",
                    headers = mapOf("X-Custom" to "line\nbreak\tand\"quote\\backslash"),
                    body = "{\"note\":\"a \\\"nested\\\" quote and a backslash \\\\ here\"}".encodeToByteArray(),
                    contentType = "application/json",
                ),
                NetworkResponseInput(statusCode = 200, headers = mapOf("X-Reply" to "\"also quoted\"")),
            )
        val transactions =
            listOf(
                NetworkTransaction("tx-1", 1_000, 1_010, hostile),
                NetworkTransaction(
                    "tx-2",
                    2_000,
                    2_010,
                    factory.capture(
                        NetworkRequestInput(method = "GET", url = "https://example.test/plain"),
                        NetworkResponseInput(statusCode = 204),
                    ),
                ),
            )

        val postman = NetworkExport.toPostman(transactions)

        postman.assertIsValidJson()
        assertEquals(2, postman.occurrencesOf("\"code\""))
    }

    private fun String.occurrencesOf(needle: String): Int {
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
    }
}

/**
 * Confirms [this] parses as one well-formed JSON document (object, array, string, number, boolean,
 * or null, with a proper string-escape grammar), throwing with the failing offset otherwise. No JSON
 * library is a dependency of `sdk:network`'s tests, so this stays intentionally minimal -- it is a
 * well-formedness check, not a schema validator.
 */
private fun String.assertIsValidJson() = MinimalJsonValidator(this).parseDocument()

private class MinimalJsonValidator(
    private val text: String,
) {
    private var pos = 0

    fun parseDocument() {
        skipWhitespace()
        parseValue()
        skipWhitespace()
        check(pos == text.length) { "trailing content at offset $pos: ${text.excerptAt(pos)}" }
    }

    private fun parseValue() {
        skipWhitespace()
        when (val c = peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true")
            'f' -> parseLiteral("false")
            'n' -> parseLiteral("null")
            else ->
                if (c != null && (c.isDigit() || c == '-')) {
                    parseNumber()
                } else {
                    error("unexpected character at offset $pos: ${text.excerptAt(pos)}")
                }
        }
    }

    private fun parseObject() {
        expect('{')
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return
        }
        while (true) {
            skipWhitespace()
            parseString()
            skipWhitespace()
            expect(':')
            parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return
                }
                else -> error("expected ',' or '}' at offset $pos: ${text.excerptAt(pos)}")
            }
        }
    }

    private fun parseArray() {
        expect('[')
        skipWhitespace()
        if (peek() == ']') {
            pos++
            return
        }
        while (true) {
            parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return
                }
                else -> error("expected ',' or ']' at offset $pos: ${text.excerptAt(pos)}")
            }
        }
    }

    private fun parseString() {
        expect('"')
        while (true) {
            val c = text.getOrNull(pos) ?: error("unterminated string starting before offset $pos")
            pos++
            when (c) {
                '"' -> return
                '\\' -> {
                    val escaped = text.getOrNull(pos) ?: error("unterminated escape at offset $pos")
                    pos++
                    if (escaped == 'u') pos += UNICODE_ESCAPE_HEX_DIGITS
                }
            }
        }
    }

    private fun parseNumber() {
        val start = pos
        if (peek() == '-') pos++
        while (text.getOrNull(pos)?.isDigit() == true) pos++
        if (peek() == '.') {
            pos++
            while (text.getOrNull(pos)?.isDigit() == true) pos++
        }
        if (peek() == 'e' || peek() == 'E') {
            pos++
            if (peek() == '+' || peek() == '-') pos++
            while (text.getOrNull(pos)?.isDigit() == true) pos++
        }
        check(pos > start) { "invalid number at offset $start: ${text.excerptAt(start)}" }
    }

    private fun parseLiteral(literal: String) {
        check(text.startsWith(literal, pos)) { "expected '$literal' at offset $pos: ${text.excerptAt(pos)}" }
        pos += literal.length
    }

    private fun skipWhitespace() {
        while (text.getOrNull(pos)?.isWhitespace() == true) pos++
    }

    private fun peek(): Char? = text.getOrNull(pos)

    private fun expect(c: Char) {
        check(peek() == c) { "expected '$c' at offset $pos: ${text.excerptAt(pos)}" }
        pos++
    }

    private fun String.excerptAt(offset: Int): String {
        val start = offset.coerceAtLeast(0)
        val end = (offset + EXCERPT_LENGTH).coerceAtMost(length)
        return substring(start, end)
    }

    private companion object {
        const val UNICODE_ESCAPE_HEX_DIGITS = 4
        const val EXCERPT_LENGTH = 40
    }
}
