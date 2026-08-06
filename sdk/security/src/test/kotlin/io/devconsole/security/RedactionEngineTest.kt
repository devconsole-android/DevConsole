package io.devconsole.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactionEngineTest {
    private val engine = RedactionEngine(RedactionPolicy.default())

    @Test
    fun `redacts default sensitive headers case insensitively`() {
        val redacted = engine.redactFields(mapOf("Authorization" to "Bearer secret", "Accept" to "application/json"))
        assertEquals("<redacted>", redacted["Authorization"])
        assertEquals("application/json", redacted["Accept"])
    }

    @Test
    fun `redacts access token query values before storage`() {
        assertEquals("<redacted>", engine.redactFields(mapOf("access_token" to "abc"))["access_token"])
    }

    @Test
    fun `bounds text before applying regex rules`() {
        val result = engine.redactText("Bearer abc.def", maxLength = 8)
        assertFalse(result.contains("abc.def"))
    }

    @Test
    fun `applies redaction before enforcing preview limit`() {
        val result = engine.redactText("Bearer very-secret-token", maxLength = 10)

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
        val engine = RedactionEngine(RedactionPolicy.default())
        val redacted =
            engine.redactFields(
                mapOf(
                    "X-Auth-Token" to "abc123",
                    "x-access-token" to "def456",
                    "Authentication" to "ghi789",
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
