/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonMockDiffResultTest {
    // --- scalar changes -----------------------------------------------------------------------

    @Test
    fun `a changed scalar field is highlighted and counted once`() {
        val diff = computeJsonMockDiff("""{"a":1}""", """{"a":2}""")

        check(diff != null)
        assertEquals(setOf("$/0:a"), diff.highlightedPaths)
        assertEquals(0, diff.removedCount)
        assertEquals(1, diff.totalCount)
    }

    @Test
    fun `a scalar that changed type from string to number is highlighted as changed`() {
        val diff = computeJsonMockDiff("""{"a":"1"}""", """{"a":1}""")

        check(diff != null)
        assertEquals(setOf("$/0:a"), diff.highlightedPaths)
        assertEquals(1, diff.totalCount)
    }

    // --- added fields ---------------------------------------------------------------------------

    @Test
    fun `a newly added field is highlighted and counted, path-indexed by its position in the mocked body`() {
        val diff = computeJsonMockDiff("""{"a":1}""", """{"a":1,"b":2}""")

        check(diff != null)
        assertEquals(setOf("$/1:b"), diff.highlightedPaths)
        assertEquals(0, diff.removedCount)
        assertEquals(1, diff.totalCount)
    }

    @Test
    fun `an added field that is itself an object is highlighted as one field, not walked field by field`() {
        val diff = computeJsonMockDiff("""{"a":1}""", """{"a":1,"b":{"x":1,"y":2}}""")

        check(diff != null)
        assertEquals(setOf("$/1:b"), diff.highlightedPaths)
        assertEquals(1, diff.totalCount)
    }

    // --- removed fields: counted only, never highlighted -------------------------------------

    @Test
    fun `a removed field is counted but never highlighted, since it has no row in the mocked body`() {
        val diff = computeJsonMockDiff("""{"a":1,"b":2}""", """{"a":1}""")

        check(diff != null)
        assertTrue(diff.highlightedPaths.isEmpty())
        assertEquals(1, diff.removedCount)
        assertEquals(1, diff.totalCount)
    }

    // --- nested objects -------------------------------------------------------------------------

    @Test
    fun `a changed field nested inside an unchanged object is highlighted at its own nested path`() {
        val diff = computeJsonMockDiff("""{"a":{"x":1}}""", """{"a":{"x":2}}""")

        check(diff != null)
        assertEquals(setOf("$/0:a/0:x"), diff.highlightedPaths)
        assertEquals(1, diff.totalCount)
    }

    @Test
    fun `a field removed from a nested object is counted but the parent object itself is untouched`() {
        val diff = computeJsonMockDiff("""{"a":{"x":1,"y":2}}""", """{"a":{"x":1}}""")

        check(diff != null)
        assertTrue(diff.highlightedPaths.isEmpty())
        assertEquals(1, diff.removedCount)
    }

    // --- arrays ---------------------------------------------------------------------------------

    @Test
    fun `array items are diffed by index -- a changed item and an appended item are both flagged`() {
        val diff = computeJsonMockDiff("""{"a":[1,2]}""", """{"a":[1,3,4]}""")

        check(diff != null)
        assertEquals(setOf("$/0:a[1]", "$/0:a[2]"), diff.highlightedPaths)
        assertEquals(0, diff.removedCount)
        assertEquals(2, diff.totalCount)
    }

    @Test
    fun `array items dropped off the end are counted as removed, not highlighted`() {
        val diff = computeJsonMockDiff("""{"a":[1,2,3]}""", """{"a":[1]}""")

        check(diff != null)
        assertTrue(diff.highlightedPaths.isEmpty())
        assertEquals(2, diff.removedCount)
    }

    // --- malformed input: silently absent -------------------------------------------------------

    @Test
    fun `a malformed original body yields no diff at all`() {
        assertNull(computeJsonMockDiff("""{"a": }""", """{"a":1}"""))
    }

    @Test
    fun `a malformed mocked body yields no diff at all`() {
        assertNull(computeJsonMockDiff("""{"a":1}""", """{"a": }"""))
    }

    // --- identical bodies -----------------------------------------------------------------------

    @Test
    fun `identical bodies produce an empty, zero-count diff`() {
        val diff = computeJsonMockDiff("""{"a":1,"b":[1,2],"c":{"x":true}}""", """{"a":1,"b":[1,2],"c":{"x":true}}""")

        check(diff != null)
        assertTrue(diff.highlightedPaths.isEmpty())
        assertEquals(0, diff.removedCount)
        assertEquals(0, diff.totalCount)
    }

    // --- numeric equality (must match the web dashboard's JSON.parse-based comparison) ----------

    @Test
    fun `a number that only differs in source formatting is not counted as changed`() {
        val diff = computeJsonMockDiff("""{"a":1.0,"b":1e3,"c":-0}""", """{"a":1,"b":1000,"c":0}""")

        check(diff != null)
        assertTrue(diff.highlightedPaths.isEmpty())
        assertEquals(0, diff.totalCount)
    }

    @Test
    fun `a genuinely different number is still counted as changed`() {
        val diff = computeJsonMockDiff("""{"a":1}""", """{"a":2}""")

        check(diff != null)
        assertEquals(setOf("$/0:a"), diff.highlightedPaths)
    }

    // --- cross-file integration: highlightedPaths must land on real flattenJsonTree rows ---------

    private fun parse(json: String): JsonValue = MinimalJsonParser(json).parseDocument()

    private fun allExpanded(): (String) -> Boolean = { true }

    /**
     * Pins the invariant [computeJsonMockDiff]'s own doc calls out: its path scheme is a hand-mirror
     * of [appendJsonTreeRows]'s, kept in a separate file. Feeding a real diff's [JsonMockDiffResult]
     * straight into [flattenJsonTree] for the same mocked body -- rather than asserting either side's
     * path strings as literals -- means a future change to either path scheme fails this test instead
     * of silently shipping a feature that highlights nothing.
     */
    @Test
    fun `highlightedPaths from a real diff land on exactly the intended flattenJsonTree rows`() {
        val original = """{"a":1,"b":{"x":1,"y":2},"c":[1,2,3]}"""
        val mocked = """{"a":2,"b":{"x":1,"y":2},"c":[1,9],"d":"new"}"""

        val diff = computeJsonMockDiff(original, mocked)
        check(diff != null)

        val rows = flattenJsonTree(parse(mocked), allExpanded(), highlightedPaths = diff.highlightedPaths)
        val rowPaths = rows.map { it.path }.toSet()

        // Format drift (e.g. appendJsonTreeRows changing its path scheme) would make this fail
        // first, before ever getting to the highlight assertions below.
        assertTrue(diff.highlightedPaths.all { it in rowPaths })

        val actuallyHighlighted = rows.filter { it.highlighted }.map { it.path }.toSet()
        assertEquals(diff.highlightedPaths, actuallyHighlighted)
    }
}
