/**
 * @author Shakib
 * @since 03/08/26
 */
package io.devconsole.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the capture-exclusion engine and its rule model: host-shape validation, the hot-path
 * matcher and its tolerant URL fallback, multi-rule precedence, enable/disable, the rule cap, and
 * write-through persistence. The engine gates whether a request is ever captured, so a rule that
 * silently never fires (or one that fires when it should not) is a correctness and privacy problem.
 */
class CaptureRuleEngineTest {
    @Test
    fun `wildcard, scheme, port, and path hosts are rejected at construction`() {
        val invalidHosts =
            listOf(
                "*.example.com",
                "http://example.com",
                "example.com:8080",
                "example.com/orders",
                "example.com?x=1",
                "user@example.com",
                "-bad-.example.com",
                "host!name.example.com",
                "example..com",
                " example.com",
            )
        invalidHosts.forEach { host ->
            val failure = runCatching { CaptureRule(id = "rule", host = host) }.exceptionOrNull()
            assertTrue("expected $host to be rejected", failure is IllegalArgumentException)
        }
    }

    @Test
    fun `underscore and IDN hosts are accepted because the matcher can resolve them`() {
        // These defeat java.net.URI's host parser but the tolerant fallback recovers them, so the
        // rule must be constructible -- otherwise a legitimate host could never be excluded.
        listOf("api_internal.example.com", "müller.example.com", "10.0.0.1").forEach { host ->
            val rule = CaptureRule(id = "rule", host = host)
            assertEquals(host, rule.host)
        }
    }

    @Test
    fun `normalization lowercases host and uppercases method`() {
        val rule = CaptureRule.of(id = "rule", host = "API.Example.COM", method = "post", pathPrefix = "/Orders")
        assertEquals("api.example.com", rule.host)
        assertEquals("POST", rule.method)
        // Paths are case-sensitive, so the prefix is left untouched.
        assertEquals("/Orders", rule.pathPrefix)
    }

    @Test
    fun `of collapses blank optional fields to null`() {
        val rule = CaptureRule.of(id = " rule ", host = " example.com ", method = "  ", pathPrefix = "")
        assertEquals("rule", rule.id)
        assertEquals("example.com", rule.host)
        assertNull(rule.method)
        assertNull(rule.pathPrefix)
    }

    @Test
    fun `matcher requires exact host, optional method, and literal path prefix`() {
        val rule = CaptureRule.of(id = "r", host = "api.example.com", method = "POST", pathPrefix = "/orders")

        assertTrue(rule.matches("post", "api.example.com", "/orders/42"))
        assertTrue("host match is case-insensitive", rule.matches("POST", "API.EXAMPLE.COM", "/orders"))
        assertFalse("wrong method", rule.matches("GET", "api.example.com", "/orders"))
        assertFalse("different host, no subdomain wildcarding", rule.matches("POST", "sub.api.example.com", "/orders"))
        assertFalse("path outside prefix", rule.matches("POST", "api.example.com", "/users"))
        assertFalse("prefix is a literal string prefix", rule.matches("POST", "api.example.com", "/order"))
    }

    @Test
    fun `a host-only rule matches every method and path for that host`() {
        val rule = CaptureRule.of(id = "r", host = "analytics.example.com")
        assertTrue(rule.matches("GET", "analytics.example.com", "/collect"))
        assertTrue(rule.matches("DELETE", "analytics.example.com", "/"))
        assertFalse(rule.matches("GET", "other.example.com", "/collect"))
    }

    @Test
    fun `allowsCapture excludes when any single enabled rule matches`() {
        val engine =
            CaptureRuleEngine(
                listOf(
                    CaptureRule.of(id = "broad", host = "api.example.com"),
                    CaptureRule.of(id = "narrow", host = "api.example.com", pathPrefix = "/health"),
                ),
            )
        // Precedence is OR: the broad host rule already excludes everything on the host, so the
        // narrow rule neither adds nor subtracts. Capture is refused for any match.
        assertFalse(engine.allowsCapture("GET", "https://api.example.com/health"))
        assertFalse(engine.allowsCapture("GET", "https://api.example.com/orders"))
        assertTrue(engine.allowsCapture("GET", "https://other.example.com/orders"))
    }

    @Test
    fun `an empty engine captures everything`() {
        val engine = CaptureRuleEngine()
        assertTrue(engine.allowsCapture("GET", "https://api.example.com/orders"))
    }

    @Test
    fun `disabled rules do not exclude, and re-enabling restores exclusion`() {
        val engine = CaptureRuleEngine(listOf(CaptureRule.of(id = "r", host = "api.example.com")))
        assertFalse(engine.allowsCapture("GET", "https://api.example.com/x"))

        assertTrue(engine.setEnabled("r", false))
        assertTrue("disabled rule must not exclude", engine.allowsCapture("GET", "https://api.example.com/x"))

        assertTrue(engine.setEnabled("r", true))
        assertFalse(engine.allowsCapture("GET", "https://api.example.com/x"))
    }

    @Test
    fun `setEnabled on an unknown id returns false`() {
        val engine = CaptureRuleEngine()
        assertFalse(engine.setEnabled("missing", false))
    }

    @Test
    fun `allowsCapture recovers host and path from an unencoded URL that URI cannot parse`() {
        // A raw space in the query makes java.net.URI throw; the fallback parser must still recover
        // the host so a legitimate rule is not defeated by an unrelated encoding problem.
        val rule = CaptureRule.of(id = "r", host = "api.example.com", pathPrefix = "/orders")
        val engine = CaptureRuleEngine(listOf(rule))
        assertFalse(engine.allowsCapture("GET", "https://api.example.com/orders?q=two words"))
        assertTrue(engine.allowsCapture("GET", "https://api.example.com/users?q=two words"))
    }

    @Test
    fun `allowsCapture captures when no host can be recovered from the url`() {
        val engine = CaptureRuleEngine(listOf(CaptureRule.of(id = "r", host = "api.example.com")))
        assertTrue("no host means the rule cannot apply, so capture proceeds", engine.allowsCapture("GET", "not-a-url"))
    }

    @Test
    fun `upsert replaces a rule with the same id rather than duplicating`() {
        val engine = CaptureRuleEngine(listOf(CaptureRule.of(id = "r", host = "api.example.com")))
        engine.upsert(CaptureRule.of(id = "r", host = "other.example.com"))

        assertEquals(1, engine.rules().size)
        assertTrue(engine.allowsCapture("GET", "https://api.example.com/x"))
        assertFalse(engine.allowsCapture("GET", "https://other.example.com/x"))
    }

    @Test
    fun `remove reports whether the id existed`() {
        val engine = CaptureRuleEngine(listOf(CaptureRule.of(id = "r", host = "api.example.com")))
        assertTrue(engine.remove("r"))
        assertFalse(engine.remove("r"))
        assertTrue(engine.allowsCapture("GET", "https://api.example.com/x"))
    }

    @Test
    fun `upsert past the rule cap is rejected`() {
        val engine = CaptureRuleEngine()
        repeat(CaptureRuleEngine.MAX_RULES) { index ->
            engine.upsert(CaptureRule.of(id = "rule$index", host = "h$index.example.com"))
        }
        val failure =
            runCatching { engine.upsert(CaptureRule.of(id = "overflow", host = "over.example.com")) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(CaptureRuleEngine.MAX_RULES, engine.rules().size)
    }

    @Test
    fun `mutations write through the bound store before the snapshot changes`() {
        val store = InMemoryCaptureRuleStore()
        val engine = CaptureRuleEngine()
        engine.bindPersistence(store)

        engine.upsert(CaptureRule.of(id = "r", host = "api.example.com"))
        assertEquals(listOf("r"), store.load().map { it.id })

        engine.setEnabled("r", false)
        assertFalse("persisted rule reflects the disable", store.load().single().enabled)

        engine.remove("r")
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `bindPersistence restores durable rules without writing back during the restore`() {
        // A save during restore would risk rewriting (and possibly corrupting) the durable rows the
        // engine was just told to trust. Count saves to prove the restore path is read-only.
        val saveCounter = SaveCountingStore(listOf(CaptureRule.of(id = "seed", host = "seed.example.com")))
        val engine = CaptureRuleEngine()
        engine.bindPersistence(saveCounter)

        assertEquals(listOf("seed"), engine.rules().map { it.id })
        assertFalse(engine.allowsCapture("GET", "https://seed.example.com/x"))
        assertEquals("restore must not write back to the store", 0, saveCounter.saveCount)

        // But once bound, a subsequent mutation does write through.
        engine.upsert(CaptureRule.of(id = "added", host = "added.example.com"))
        assertEquals(1, saveCounter.saveCount)
    }

    private class SaveCountingStore(
        initial: List<CaptureRule>,
    ) : CaptureRuleStore {
        private val rules = initial.toList()
        var saveCount = 0
            private set

        override fun load(): List<CaptureRule> = rules

        override fun save(rules: List<CaptureRule>) {
            saveCount++
        }
    }
}
