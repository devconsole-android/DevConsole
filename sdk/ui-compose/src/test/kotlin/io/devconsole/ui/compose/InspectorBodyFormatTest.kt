/**
 * @author Shakib
 * @since 05/08/26
 */
package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorBodyFormatTest {
    private fun parse(json: String): JsonValue = MinimalJsonParser(json).parseDocument()

    // --- sniffBodyFormat -------------------------------------------------------------------

    @Test
    fun `sniffs json from a leading brace or bracket, ignoring leading whitespace`() {
        assertEquals(SniffedBodyFormat.JSON, sniffBodyFormat("""  {"a":1}"""))
        assertEquals(SniffedBodyFormat.JSON, sniffBodyFormat("\n\t[1,2]"))
    }

    @Test
    fun `sniffs xml from a leading angle bracket`() {
        assertEquals(SniffedBodyFormat.XML, sniffBodyFormat("<root/>"))
    }

    @Test
    fun `sniffs plain text otherwise, including blank input`() {
        assertEquals(SniffedBodyFormat.PLAIN, sniffBodyFormat("just some text"))
        assertEquals(SniffedBodyFormat.PLAIN, sniffBodyFormat("   "))
    }

    // --- analyzeBodyFormat -------------------------------------------------------------------

    @Test
    fun `formats a valid json body`() {
        val outcome = analyzeBodyFormat("""{"a":1}""")
        check(outcome is BodyFormatOutcome.Formatted)
        assertTrue(outcome.body is FormattedBody.Json)
    }

    @Test
    fun `falls back to not-formattable for a body that sniffs as json but fails to parse`() {
        val outcome = analyzeBodyFormat("""{"a": }""")
        assertEquals(BodyFormatOutcome.NotFormattable, outcome)
    }

    @Test
    fun `plain text is never formattable`() {
        assertEquals(BodyFormatOutcome.NotFormattable, analyzeBodyFormat("hello world"))
    }

    @Test
    fun `a json body over the size guard is too-large rather than formatted`() {
        val huge = "{\"a\":\"" + "x".repeat(MAX_FORMATTABLE_BODY_BYTES) + "\"}"
        assertEquals(BodyFormatOutcome.TooLarge, analyzeBodyFormat(huge))
    }

    @Test
    fun `parsing json nested past the depth cap fails cleanly rather than overflowing the stack`() {
        val deeplyNested = "[".repeat(MAX_NESTING_DEPTH + 1) + "]".repeat(MAX_NESTING_DEPTH + 1)

        val failure = runCatching { parse(deeplyNested) }

        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is JsonSyntaxException)
    }

    @Test
    fun `json nested right up to the depth cap still parses`() {
        val atTheLimit = "[".repeat(MAX_NESTING_DEPTH) + "]".repeat(MAX_NESTING_DEPTH)

        val value = parse(atTheLimit)

        assertTrue(value is JsonValue.Arr)
    }

    @Test
    fun `analyzeBodyFormat falls back to not-formattable for json nested past the depth cap`() {
        val deeplyNested = "[".repeat(MAX_NESTING_DEPTH + 1) + "]".repeat(MAX_NESTING_DEPTH + 1)

        assertEquals(BodyFormatOutcome.NotFormattable, analyzeBodyFormat(deeplyNested))
    }

    // --- formatXml -----------------------------------------------------------------------------

    @Test
    fun `indents nested xml elements`() {
        val formatted = formatXml("<root><a>1</a><b><c/></b></root>")
        assertEquals(
            """
            <root>
              <a>
                1
              </a>
              <b>
                <c/>
              </b>
            </root>
            """.trimIndent(),
            formatted,
        )
    }

    @Test
    fun `falls back to null for mismatched closing tags`() {
        assertNull(formatXml("<a><b></a></b>"))
    }

    @Test
    fun `falls back to null for unclosed tags`() {
        assertNull(formatXml("<a><b></b>"))
    }

    @Test
    fun `falls back to null for content that does not start with a tag`() {
        assertNull(formatXml("not xml at all"))
    }

    // --- flattenJsonTree ---------------------------------------------------------------------

    private fun allExpanded(): (String) -> Boolean = { true }

    @Test
    fun `fully expanded object flattens to one row per node plus its closing brace`() {
        val root = parse("""{"a":1,"b":true}""")

        val rows = flattenJsonTree(root, allExpanded())

        assertEquals(
            listOf(
                "$" to JsonTreeRowContent.ContainerStart(false),
                "$/0:a" to JsonTreeRowContent.Scalar("1", JsonScalarType.NUMBER),
                "$/1:b" to JsonTreeRowContent.Scalar("true", JsonScalarType.BOOLEAN),
                "$" to JsonTreeRowContent.ContainerEnd(false),
            ),
            rows.map { it.path to it.content },
        )
    }

    @Test
    fun `trailing comma is set on every row except the last child of its parent`() {
        val root = parse("""{"a":1,"b":2}""")

        val rows = flattenJsonTree(root, allExpanded())
        val byPath = rows.associateBy { it.path }

        assertTrue(byPath.getValue("$/0:a").trailingComma)
        assertEquals(false, byPath.getValue("$/1:b").trailingComma)
    }

    @Test
    fun `collapsing an object node replaces its subtree with a single summary row`() {
        val root = parse("""{"a":{"x":1,"y":2},"b":3}""")
        val collapsed = setOf("$/0:a")

        val rows = flattenJsonTree(root, isExpanded = { path -> path !in collapsed })

        assertEquals(
            listOf(
                "$" to JsonTreeRowContent.ContainerStart(false),
                "$/0:a" to JsonTreeRowContent.ContainerCollapsed(false, 2),
                "$/1:b" to JsonTreeRowContent.Scalar("3", JsonScalarType.NUMBER),
                "$" to JsonTreeRowContent.ContainerEnd(false),
            ),
            rows.map { it.path to it.content },
        )
    }

    @Test
    fun `collapsing a node never composes its descendants' rows at all`() {
        val root = parse("""{"a":{"x":{"deeply":{"nested":1}}}}""")
        val collapsed = setOf("$/0:a")

        val rows = flattenJsonTree(root, isExpanded = { path -> path !in collapsed })

        // Only root-start, the one collapsed summary row, and root-end -- nothing from "x" downward.
        assertEquals(3, rows.size)
        assertTrue(rows.none { it.path.startsWith("$/0:a/") })
    }

    @Test
    fun `expanding a previously collapsed node restores its full subtree`() {
        val root = parse("""{"a":{"x":1}}""")

        val collapsedRows = flattenJsonTree(root, isExpanded = { path -> path != "$/0:a" })
        val expandedRows = flattenJsonTree(root, allExpanded())

        assertEquals(3, collapsedRows.size) // root start, collapsed "a", root end
        // root start, "a" start, "x", "a" end, root end
        assertEquals(5, expandedRows.size)
        assertTrue(expandedRows.any { it.path == "$/0:a/0:x" })
    }

    @Test
    fun `array items have no key label and index-based paths`() {
        val root = parse("""[10,20]""")

        val rows = flattenJsonTree(root, allExpanded())

        assertEquals(
            listOf("$" to null, "$[0]" to null, "$[1]" to null, "$" to null),
            rows.map { it.path to it.keyLabel },
        )
    }

    @Test
    fun `collapsed array summary reports its item count`() {
        val root = parse("""[1,2,3]""")

        val rows = flattenJsonTree(root, isExpanded = { false })

        assertEquals(1, rows.size)
        assertEquals(JsonTreeRowContent.ContainerCollapsed(true, 3), rows.single().content)
    }

    @Test
    fun `empty object and array still emit start and end rows when expanded`() {
        assertEquals(2, flattenJsonTree(parse("{}"), allExpanded()).size)
        assertEquals(2, flattenJsonTree(parse("[]"), allExpanded()).size)
    }

    @Test
    fun `string values are re-quoted through jsonQuoted`() {
        // A JSON string literal containing an escaped embedded quote: "hello \"world\"".
        val jsonText = "\"hello \\\"world\\\"\""

        val rows = flattenJsonTree(parse(jsonText), allExpanded())
        val scalar = rows.single().content as JsonTreeRowContent.Scalar

        assertEquals(JsonScalarType.STRING, scalar.type)
        // jsonQuoted() re-escapes back to the exact same source form.
        assertEquals(jsonText, scalar.text)
    }

    @Test
    fun `root path is a scalar row when the document itself is a scalar`() {
        val rows = flattenJsonTree(parse("null"), allExpanded())
        assertEquals(listOf(JsonTreeRowContent.Scalar("null", JsonScalarType.NULL)), rows.map { it.content })
    }

    // --- highlightedPaths (mock-response-diff feature) --------------------------------------------

    @Test
    fun `rows default to not highlighted when no highlighted paths are supplied`() {
        val rows = flattenJsonTree(parse("""{"a":1,"b":2}"""), allExpanded())
        assertTrue(rows.none { it.highlighted })
    }

    @Test
    fun `only rows whose exact path is in highlightedPaths are marked highlighted`() {
        val root = parse("""{"a":1,"b":2}""")

        val rows = flattenJsonTree(root, allExpanded(), highlightedPaths = setOf("$/1:b"))
        val byPath = rows.associateBy { it.path }

        assertTrue(byPath.getValue("$/1:b").highlighted)
        assertEquals(false, byPath.getValue("$/0:a").highlighted)
    }

    @Test
    fun `a highlighted container marks both its start and end rows`() {
        val root = parse("""{"a":{"x":1}}""")

        val rows = flattenJsonTree(root, allExpanded(), highlightedPaths = setOf("$/0:a"))
        val aRows = rows.filter { it.path == "$/0:a" }

        assertEquals(2, aRows.size) // ContainerStart + ContainerEnd, sharing one path
        assertTrue(aRows.all { it.highlighted })
    }

    // --- JsonTreeRow.lazyKey ---------------------------------------------------------------------

    @Test
    fun `lazy keys are unique across an expanded container's start and end rows`() {
        val rows = flattenJsonTree(parse("""{"a":1}"""), allExpanded())

        val keys = rows.map { it.lazyKey() }

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `collapsed containers and scalars key on their path alone`() {
        val row = flattenJsonTree(parse("[1,2]"), isExpanded = { false }).single()
        assertEquals(row.path, row.lazyKey())
    }
}
