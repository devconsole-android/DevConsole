package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorMockRuleFormTest {
    @Test
    fun `id error is null for a valid id and set for an invalid one`() {
        assertNull(mockRuleIdError("rule-checkout"))
        assertNull(mockRuleIdError("a"))
        assertNotNull(mockRuleIdError(""))
        assertNotNull(mockRuleIdError("-leading-dash"))
        assertNotNull(mockRuleIdError("has space"))
    }

    @Test
    fun `status error validates the 100-599 range like FullInspectorDataSource`() {
        assertNull(mockRuleStatusError(200))
        assertNull(mockRuleStatusError(100))
        assertNull(mockRuleStatusError(599))
        assertNotNull(mockRuleStatusError(99))
        assertNotNull(mockRuleStatusError(600))
        assertNotNull(mockRuleStatusError(null))
    }

    @Test
    fun `delay error is null when blank and validates 0-30000 otherwise`() {
        assertNull(mockRuleDelayError("", null))
        assertNull(mockRuleDelayError("0", 0))
        assertNull(mockRuleDelayError("30000", 30_000))
        assertNotNull(mockRuleDelayError("30001", 30_001))
        assertNotNull(mockRuleDelayError("abc", null))
    }

    @Test
    fun `header lines parse Name colon value and skip malformed lines`() {
        val result = parseMockRuleHeaderLines("Content-Type: application/json\nnot-a-header\nX-Trace: 1\n\n")

        assertEquals(mapOf("Content-Type" to "application/json", "X-Trace" to "1"), result.headers)
        assertEquals(1, result.skippedLines)
    }

    @Test
    fun `header map round-trips through toMockRuleHeaderLines`() {
        val headers = linkedMapOf("Content-Type" to "application/json", "X-Trace" to "1")

        val roundTripped = parseMockRuleHeaderLines(headers.toMockRuleHeaderLines()).headers

        assertEquals(headers, roundTripped)
    }

    @Test
    fun `suggested mock rule id is regex-safe and derived from method and path`() {
        val id = suggestMockRuleId("GET", "/v1/menu/items?id=42")

        assertEquals("mock-get-v1-menu-items", id)
        assertNull(mockRuleIdError(id))
    }

    @Test
    fun `suggested mock rule id falls back to root for a bare path`() {
        val id = suggestMockRuleId("POST", "/")

        assertEquals("mock-post-root", id)
        assertNull(mockRuleIdError(id))
    }

    @Test
    fun `suggested mock rule id avoids colliding with an existing rule id`() {
        val base = suggestMockRuleId("GET", "/v1/cart")

        val second = suggestMockRuleId("GET", "/v1/cart", existingIds = setOf(base))
        val third = suggestMockRuleId("GET", "/v1/cart", existingIds = setOf(base, second))

        assertEquals("$base-2", second)
        assertEquals("$base-3", third)
        assertNull(mockRuleIdError(second))
        assertNull(mockRuleIdError(third))
    }

    @Test
    fun `a static response or delayed static response rule is editable on device`() {
        assertTrue(InspectorMockRuleUi(id = "r", actionLabel = "Static response (200)").isEditableOnDevice())
        assertTrue(InspectorMockRuleUi(id = "r", actionLabel = "Delay (500ms)").isEditableOnDevice())
    }

    @Test
    fun `fault-injection and template rules are not editable on device`() {
        assertFalse(InspectorMockRuleUi(id = "r", actionLabel = "Connection failure").isEditableOnDevice())
        assertFalse(InspectorMockRuleUi(id = "r", actionLabel = "Timeout (500ms)").isEditableOnDevice())
        assertFalse(InspectorMockRuleUi(id = "r", actionLabel = "Template response (200)").isEditableOnDevice())
        assertFalse(InspectorMockRuleUi(id = "r", actionLabel = "Status override (200)").isEditableOnDevice())
        assertFalse(InspectorMockRuleUi(id = "r", actionLabel = "Body replacement").isEditableOnDevice())
        assertFalse(InspectorMockRuleUi(id = "r", actionLabel = "Passthrough").isEditableOnDevice())
    }
}
