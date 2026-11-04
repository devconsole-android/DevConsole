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
    fun `every action type except other is editable on device`() {
        InspectorMockActionType.entries.filter { it != InspectorMockActionType.OTHER }.forEach { type ->
            assertTrue(type.name, InspectorMockRuleUi(id = "r", actionType = type).isEditableOnDevice())
        }
        assertFalse(
            InspectorMockRuleUi(id = "r", actionType = InspectorMockActionType.OTHER).isEditableOnDevice(),
        )
    }

    @Test
    fun `action label fallback recognises the editable labels but not templates or sequences`() {
        assertTrue(isEditableMockActionLabel("Static response (200)"))
        assertTrue(isEditableMockActionLabel("Delay (500ms) → Static response (200)"))
        assertTrue(isEditableMockActionLabel("Connection failure"))
        assertTrue(isEditableMockActionLabel("Timeout (500ms)"))
        assertTrue(isEditableMockActionLabel("Status override (200)"))
        assertTrue(isEditableMockActionLabel("Body replacement"))
        assertTrue(isEditableMockActionLabel("Throttle (1024 B/s) → Passthrough"))
        assertTrue(isEditableMockActionLabel("Throttle 64 KB/s · +1500 ms"))
        assertTrue(isEditableMockActionLabel("Map remote (https:example.test)"))
        assertFalse(isEditableMockActionLabel("Delay (500ms) → Template response (200)"))
        assertFalse(isEditableMockActionLabel("Delay (500ms) → Connection failure"))
        assertFalse(isEditableMockActionLabel("Template response (200)"))
        assertFalse(isEditableMockActionLabel("Sequence (2 steps)"))
        assertFalse(isEditableMockActionLabel("Passthrough"))
    }

    @Test
    fun `timeout error requires a number inside the engine range`() {
        assertNull(mockRuleTimeoutError("1000", 1_000))
        assertNull(mockRuleTimeoutError("0", 0))
        assertNull(mockRuleTimeoutError("30000", 30_000))
        assertNotNull(mockRuleTimeoutError("", null))
        assertNotNull(mockRuleTimeoutError("abc", null))
        assertNotNull(mockRuleTimeoutError("30001", 30_001))
    }

    @Test
    fun `throttle rate error validates against the engine byte range`() {
        assertNull(mockRuleThrottleRateError("64", 65_536))
        assertNull(mockRuleThrottleRateError("1", 1_024))
        assertNotNull(mockRuleThrottleRateError("", null))
        assertNotNull(mockRuleThrottleRateError("0", 0))
        assertNotNull(mockRuleThrottleRateError("999999", 102_400L * 1_024 + 1_024))
    }

    @Test
    fun `throttle latency is optional and bounded like a delay`() {
        assertNull(mockRuleThrottleLatencyError("", null))
        assertNull(mockRuleThrottleLatencyError("0", 0))
        assertNull(mockRuleThrottleLatencyError("30000", 30_000))
        assertNotNull(mockRuleThrottleLatencyError("abc", null))
        assertNotNull(mockRuleThrottleLatencyError("30001", 30_001))
    }

    @Test
    fun `map remote validation mirrors the engine constraints`() {
        assertNotNull(mockRuleMapRemoteError(hasAnyField = false))
        assertNull(mockRuleMapRemoteError(hasAnyField = true))
        assertNull(mockRuleRemoteSchemeError("https"))
        assertNull(mockRuleRemoteSchemeError(""))
        assertNotNull(mockRuleRemoteSchemeError("ftp"))
        assertNull(mockRuleRemoteHostError("staging.example.test"))
        assertNotNull(mockRuleRemoteHostError("bad/host"))
        assertNull(mockRuleRemotePathError("/v2/orders"))
        assertNull(mockRuleRemotePathError(""))
        assertNotNull(mockRuleRemotePathError("v2/orders"))
        assertNull(mockRuleRemotePortError("", null))
        assertNull(mockRuleRemotePortError("443", 443))
        assertNotNull(mockRuleRemotePortError("0", 0))
        assertNotNull(mockRuleRemotePortError("70000", 70_000))
        assertNotNull(mockRuleRemotePortError("abc", null))
    }

    @Test
    fun `predicate lines parse key=value and skip malformed lines`() {
        val result = parseMockRulePredicateLines("page=1\nnot-a-predicate\nX-Trace=abc\n\n")

        assertEquals(mapOf("page" to "1", "X-Trace" to "abc"), result.predicates)
        assertEquals(1, result.skippedLines)
    }

    @Test
    fun `predicate map round-trips through toMockRulePredicateLines`() {
        val predicates = linkedMapOf("page" to "1", "X-Trace" to "abc")

        val roundTripped = parseMockRulePredicateLines(predicates.toMockRulePredicateLines()).predicates

        assertEquals(predicates, roundTripped)
    }

    @Test
    fun `mock delay reads as ms under a second and trimmed seconds above`() {
        assertEquals("800 ms", formatMockDelay(800))
        assertEquals("1.2 s", formatMockDelay(1_200))
        assertEquals("1.05 s", formatMockDelay(1_050))
        assertEquals("3 s", formatMockDelay(3_000))
    }

    @Test
    fun `condition hint names the first predicate and counts the rest`() {
        val base = InspectorMockRuleUi(id = "r", method = "GET", pathPattern = "/x", actionLabel = "Passthrough")
        assertNull(mockRuleConditionHint(base))
        assertEquals("if debug=1", mockRuleConditionHint(base.copy(queryPredicates = mapOf("debug" to "1"))))
        assertEquals(
            "if debug=1 +2",
            mockRuleConditionHint(
                base.copy(
                    queryPredicates = mapOf("debug" to "1"),
                    headerPredicates = mapOf("X-Env" to "qa"),
                    bodyPredicate = ".*paid.*",
                ),
            ),
        )
        assertEquals("if body matches", mockRuleConditionHint(base.copy(bodyPredicate = ".*")))
    }
}
