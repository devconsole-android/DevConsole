package io.devconsole.ui.compose

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorMockRuleEditorTest {
    @Test
    fun `match everything warning requires all methods and a blank or wildcard path`() {
        assertTrue(mockRuleMatchesEverything("ALL", ""))
        assertTrue(mockRuleMatchesEverything("ALL", "  "))
        assertTrue(mockRuleMatchesEverything("ALL", ".*"))
        assertFalse(mockRuleMatchesEverything("GET", ".*"))
        assertFalse(mockRuleMatchesEverything("ALL", "/v1/orders"))
        assertTrue(mockRuleMatchesEverything("ALL", " .* "))
    }

    @Test
    fun `literal paths remain readable and regex characters are escaped`() {
        assertEquals("/v1/orders", mockRulePathPatternForLiteral("/v1/orders"))
        val path = "/v1/file.json"
        val pattern = mockRulePathPatternForLiteral(path)
        assertEquals(Regex.escape(path), pattern)
        assertTrue(Regex(pattern).matches(path))
        assertFalse(Regex(pattern).matches("/v1/fileXjson"))
        "\\.[]{}()*+?^$|".forEach { character ->
            assertEquals(Regex.escape("/a${character}b"), mockRulePathPatternForLiteral("/a${character}b"))
        }
        assertEquals("/v1/cart", mockRuleDraftFromTransaction(transaction("{}")).draft.pathPattern)
    }

    @Test
    fun `all action types have plain labels`() {
        val labels =
            mapOf(
                InspectorMockActionType.STATIC to "Return a response",
                InspectorMockActionType.STATUS_OVERRIDE to "Change status only",
                InspectorMockActionType.BODY_REPLACEMENT to "Change body only",
                InspectorMockActionType.CONNECTION_FAILURE to "Fail the connection",
                InspectorMockActionType.TIMEOUT to "Time out",
                InspectorMockActionType.THROTTLE to "Slow it down",
                InspectorMockActionType.MAP_REMOTE to "Use another server",
                InspectorMockActionType.OTHER to "Other (read-only)",
            )
        assertEquals(InspectorMockActionType.entries.toSet(), labels.keys)
        labels.forEach { (type, label) -> assertEquals(label, type.plainLabel()) }
    }

    private fun transaction(
        responsePreview: String?,
        responseHeaders: Map<String, String> = emptyMap(),
        statusCode: Int? = 200,
        responseBodyTruncated: Boolean = false,
    ) = InspectorTransactionUi(
        id = "tx-1",
        method = "GET",
        host = "api.example.test",
        path = "/v1/cart",
        statusCode = statusCode,
        durationMs = 42,
        responsePreview = responsePreview,
        responseHeaders = responseHeaders,
        responseBodyTruncated = responseBodyTruncated,
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

    // A prefilled body is editable in the sheet, so an ordinary capture carries no note at all --
    // only the binary case, which genuinely can't be prefilled, still explains itself.
    @Test
    fun `a normal text preview prefills the body with no note`() {
        val target = mockRuleDraftFromTransaction(transaction("""{"ok":true}"""))

        assertEquals("""{"ok":true}""", target.draft.body)
        assertNull(target.prefillNote)
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
    fun `truncated capture preview is identified on the editable draft`() {
        val target = mockRuleDraftFromTransaction(transaction("partial", responseBodyTruncated = true))
        assertEquals("partial", target.draft.body)
        assertTrue(target.draft.responseBodyTruncated)
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
    fun `MockRuleEditorNewSaver round-trips a truncated draft and null`() {
        val scope = SaverScope { true }
        val original = mockRuleDraftFromTransaction(transaction("""{"ok":true}""", responseBodyTruncated = true))

        val saved = with(MockRuleEditorNewSaver) { scope.save(original) }
        assertEquals(original, saved?.let(MockRuleEditorNewSaver::restore))

        val savedNull = with(MockRuleEditorNewSaver) { scope.save(null) }
        assertNull(savedNull?.let(MockRuleEditorNewSaver::restore))
    }
}
