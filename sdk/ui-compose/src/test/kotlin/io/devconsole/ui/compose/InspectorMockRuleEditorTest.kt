package io.devconsole.ui.compose

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorMockRuleEditorTest {
    private fun transaction(
        responsePreview: String?,
        responseHeaders: Map<String, String> = emptyMap(),
        statusCode: Int? = 200,
    ) = InspectorTransactionUi(
        id = "tx-1",
        method = "GET",
        host = "api.example.test",
        path = "/v1/cart",
        statusCode = statusCode,
        durationMs = 42,
        responsePreview = responsePreview,
        responseHeaders = responseHeaders,
    )

    @Test
    fun `draft strips transport framing headers case-insensitively`() {
        val headers =
            mapOf(
                "Content-Type" to "application/json",
                "Content-Length" to "42",
                "content-encoding" to "gzip",
                "TRANSFER-ENCODING" to "chunked",
                "Connection" to "keep-alive",
                "X-Trace" to "1",
            )

        val target = mockRuleDraftFromTransaction(transaction("{}", headers))

        assertEquals(mapOf("Content-Type" to "application/json", "X-Trace" to "1"), target.draft.headers)
    }

    @Test
    fun `a normal text preview prefills the body and carries the redaction caveat note`() {
        val target = mockRuleDraftFromTransaction(transaction("""{"ok":true}"""))

        assertEquals("""{"ok":true}""", target.draft.body)
        assertTrue(target.prefillNote.orEmpty().contains("redacted"))
    }

    @Test
    fun `a binary placeholder prefills an empty body and says so instead`() {
        val target = mockRuleDraftFromTransaction(transaction("[binary, 12345 bytes]"))

        assertEquals("", target.draft.body)
        assertTrue(target.prefillNote.orEmpty().contains("binary"))
    }

    @Test
    fun `a null preview is not mistaken for the binary placeholder`() {
        val target = mockRuleDraftFromTransaction(transaction(null))

        assertEquals("", target.draft.body)
        assertFalse(target.prefillNote.orEmpty().contains("binary"))
    }

    @Test
    fun `existing rule ids are threaded through to avoid an id collision`() {
        val base = suggestMockRuleId("GET", "/v1/cart")

        val target = mockRuleDraftFromTransaction(transaction("{}"), existingIds = setOf(base))

        assertEquals("$base-2", target.draft.id)
    }

    @Test
    fun `an out-of-list initial method is appended as an extra chip, not coerced to ALL`() {
        val expected = listOf("ALL", "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")

        assertEquals(expected, chipsIncluding(MOCK_RULE_METHODS, "HEAD"))
        assertEquals(MOCK_RULE_METHODS, chipsIncluding(MOCK_RULE_METHODS, "ALL"))
    }

    @Test
    fun `an out-of-list scope is appended rather than dropped`() {
        assertEquals(MOCK_RULE_SCOPES + "WEIRD_SCOPE", chipsIncluding(MOCK_RULE_SCOPES, "WEIRD_SCOPE"))
        assertNull(mockRuleIdError("weird-but-fine-id")) // sanity: id validation is independent of scope
    }

    // The draft's sourceKey must identify which transaction it came from, so re-opening the
    // sheet on a different transaction resets rememberSaveable's form fields instead of restoring it.
    @Test
    fun `mock rule draft from a transaction is keyed by that transaction's id`() {
        val target = mockRuleDraftFromTransaction(transaction("{}"))

        assertEquals("tx-1", target.sourceKey)
    }

    // The sheet's open state must survive a configuration change like rotation, not just its
    // individual field values -- verifies MockRuleEditorNewSaver round-trips a draft, and null, intact.
    @Test
    fun `MockRuleEditorNewSaver round-trips a draft and null`() {
        val scope = SaverScope { true }
        val original = mockRuleDraftFromTransaction(transaction("""{"ok":true}"""))

        val saved = with(MockRuleEditorNewSaver) { scope.save(original) }
        assertEquals(original, saved?.let(MockRuleEditorNewSaver::restore))

        val savedNull = with(MockRuleEditorNewSaver) { scope.save(null) }
        assertNull(savedNull?.let(MockRuleEditorNewSaver::restore))
    }
}
