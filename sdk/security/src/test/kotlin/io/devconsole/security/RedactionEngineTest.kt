package io.devconsole.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactionEngineTest {
    private val engine = RedactionEngine(RedactionPolicy.recommended())

    @Test
    fun `redacts default sensitive headers case insensitively`() {
        val redacted = engine.redactFields(mapOf("Cookie" to "session=secret", "Accept" to "application/json"))
        assertEquals("<redacted>", redacted["Cookie"])
        assertEquals("application/json", redacted["Accept"])
    }

    @Test
    fun `recommended policy preserves historical authorization header behavior`() {
        val fields = engine.redactFields(mapOf("Authorization" to "Bearer secret-token-123"))
        assertEquals("Bearer secret-token-123", fields["Authorization"])
    }

    @Test
    fun `default and none policies mask no fields or text including empty JSON keys`() {
        assertEquals(RedactionPolicy.none(), RedactionPolicy.default())
        val engine = RedactionEngine(RedactionPolicy.default())
        val fields = mapOf("Cookie" to "session=raw", "Authorization" to "Bearer raw", "token" to "raw")
        val text = """{"":"raw","token":"raw"}?access_token=raw"""

        assertFalse(engine.isEnabled())
        assertEquals(fields, engine.redactFields(fields))
        assertEquals(text, engine.redactText(text))
        engine.updatePolicy(RedactionPolicy.recommended())
        assertTrue(engine.isEnabled())
        assertEquals("<redacted>", engine.redactFields(fields)["token"])
        engine.updatePolicy(RedactionPolicy.none())
        assertEquals(text, engine.redactText(text))
    }

    @Test
    fun `redacts access token query values before storage`() {
        assertEquals("<redacted>", engine.redactFields(mapOf("access_token" to "abc"))["access_token"])
    }

    @Test
    fun `bounds text before applying regex rules`() {
        val regexEngine =
            RedactionEngine(
                RedactionPolicy(
                    sensitiveFieldNames = emptySet(),
                    textPatterns = listOf(Regex("Bearer\\s+[A-Za-z0-9._~-]+", RegexOption.IGNORE_CASE)),
                ),
            )
        val result = regexEngine.redactText("Bearer abc.def", maxLength = 8)
        assertFalse(result.contains("abc.def"))
    }

    @Test
    fun `applies redaction before enforcing preview limit`() {
        val regexEngine =
            RedactionEngine(
                RedactionPolicy(
                    sensitiveFieldNames = emptySet(),
                    textPatterns = listOf(Regex("Bearer\\s+[A-Za-z0-9._~-]+", RegexOption.IGNORE_CASE)),
                ),
            )
        val result = regexEngine.redactText("Bearer very-secret-token", maxLength = 10)

        assertFalse(result.contains("very-secret-token"))
        assertEquals("<redacted>", result)
    }

    @Test
    fun `redacts query parameter starting with question mark in url`() {
        val url = "https://api.test/orders?access_token=super-secret&param=value"
        val redacted = engine.redactText(url)
        assertEquals("https://api.test/orders?access_token=<redacted>&param=value", redacted)
    }

    @Test
    fun `redacts newly covered auth header names`() {
        val engine = RedactionEngine(RedactionPolicy.recommended())
        val redacted =
            engine.redactFields(
                mapOf(
                    "X-Auth-Token" to "abc123",
                    "x-access-token" to "def456",
                    "x-api-key" to "ghi789",
                    "jwt" to "eyJhbGciOi",
                    "token" to "opaque",
                ),
            )
        assertTrue("expected all values redacted, got $redacted", redacted.values.all { it == "<redacted>" })
    }

    @Test
    fun `custom policy field names match case-insensitively`() {
        val engine =
            RedactionEngine(
                RedactionPolicy(sensitiveFieldNames = setOf("X-Custom-Secret"), textPatterns = emptyList()),
            )
        assertEquals("<redacted>", engine.redactFields(mapOf("x-custom-secret" to "value"))["x-custom-secret"])
        assertEquals("<redacted>", engine.redactFields(mapOf("X-CUSTOM-SECRET" to "value"))["X-CUSTOM-SECRET"])
    }

    @Test
    fun `custom patterns use a linear engine and reject unsupported backtracking features`() {
        val policy =
            RedactionPolicy(
                sensitiveFieldNames = emptySet(),
                textPatterns = listOf(Regex("secret(?=value)")),
            )

        assertTrue(runCatching { RedactionEngine(policy) }.isFailure)
    }

    @Test(timeout = 1_000)
    fun `pathological custom pattern remains bounded on a long non match`() {
        val engine =
            RedactionEngine(
                RedactionPolicy(
                    sensitiveFieldNames = emptySet(),
                    textPatterns = listOf(Regex("(a+)+$")),
                ),
            )

        assertEquals("<redacted>", engine.redactText("a".repeat(32_768) + "!", maxLength = 4_096))
    }
}
