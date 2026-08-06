package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorMockRuleBodyFormatTest {
    @Test
    fun `formats a compact object with 2-space indentation`() {
        val result = formatMockRuleBodyJson("""{"id":1,"name":"cart","items":[1,2],"ok":true,"note":null}""")

        check(result is JsonFormatResult.Formatted)
        assertEquals(
            """
            {
              "id": 1,
              "name": "cart",
              "items": [
                1,
                2
              ],
              "ok": true,
              "note": null
            }
            """.trimIndent(),
            result.text,
        )
    }

    @Test
    fun `formats empty objects and arrays without exploding onto extra lines`() {
        val result = formatMockRuleBodyJson("""{"a":{},"b":[]}""")

        check(result is JsonFormatResult.Formatted)
        assertEquals("{\n  \"a\": {},\n  \"b\": []\n}", result.text)
    }

    @Test
    fun `round-trips escaped characters through re-serialization`() {
        val result = formatMockRuleBodyJson(""""line one\nline two \"quoted\""""")

        check(result is JsonFormatResult.Formatted)
        assertEquals(""""line one\nline two \"quoted\""""", result.text)
    }

    @Test
    fun `top-level scalars format without wrapping`() {
        assertEquals(JsonFormatResult.Formatted("42"), formatMockRuleBodyJson("42"))
        assertEquals(JsonFormatResult.Formatted("true"), formatMockRuleBodyJson("  true  "))
        assertEquals(JsonFormatResult.Formatted("null"), formatMockRuleBodyJson("null"))
    }

    @Test
    fun `invalid JSON surfaces a non-blocking error instead of throwing`() {
        val result = formatMockRuleBodyJson("<html>not json</html>")

        assertTrue(result is JsonFormatResult.Error)
    }

    @Test
    fun `trailing comma is rejected as invalid JSON`() {
        val result = formatMockRuleBodyJson("""{"a":1,}""")

        assertTrue(result is JsonFormatResult.Error)
    }

    @Test
    fun `trailing garbage after a valid document is rejected`() {
        val result = formatMockRuleBodyJson("""{"a":1} extra""")

        assertTrue(result is JsonFormatResult.Error)
    }

    @Test
    fun `blank body is rejected rather than silently accepted`() {
        val result = formatMockRuleBodyJson("")

        assertTrue(result is JsonFormatResult.Error)
    }
}
