/**
 * @author Shakib
 * @since 18/08/26
 */
package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the section-meta and match-label strings that the detail screen shows, so the extraction
 * that split them out of InspectorObserveDetailScreen cannot drift.
 */
class InspectorObserveDetailMetaTest {
    private fun search(
        query: String = "id",
        matchCount: Int = 0,
        searchable: Boolean = true,
    ) = NetworkSectionSearch(
        query = query,
        matches =
            List(matchCount) { index ->
                InspectorDetailSearchMatch(
                    ordinal = index,
                    sectionKey = "body",
                    itemId = "item$index",
                    field = InspectorSearchField.VALUE,
                    start = 0,
                    endExclusive = 1,
                )
            },
        searchable = searchable,
    )

    @Test
    fun `a searchable section with an active query reports its match count`() {
        assertEquals("1 match", networkSectionMeta(search(matchCount = 1), expanded = true, total = 9))
        assertEquals("3 matches", networkSectionMeta(search(matchCount = 3), expanded = true, total = 9))
        assertEquals("no match", networkSectionMeta(search(matchCount = 0), expanded = true, total = 9))
    }

    @Test
    fun `a section outside the search scope falls back to its line count`() {
        val outOfScope = search(matchCount = 0, searchable = false)
        assertEquals("4", networkSectionMeta(outOfScope, expanded = true, total = 4))
        assertEquals("4 lines", networkSectionMeta(outOfScope, expanded = false, total = 4))
        assertEquals("1 line", networkSectionMeta(outOfScope, expanded = false, total = 1))
    }

    /** An empty body reports nothing at all, expanded or not -- never "0" or "0 lines". */
    @Test
    fun `an empty section reports no meta in either state`() {
        val blank = search(query = "", searchable = false)
        assertEquals("", networkSectionMeta(blank, expanded = true, total = 0))
        assertEquals("", networkSectionMeta(blank, expanded = false, total = 0))
    }

    @Test
    fun `the match label counts hits on plain kinds and steps through them on searchable ones`() {
        assertEquals(
            "",
            detailSearchMatchLabel("", searching = true, matchCount = 5, activeMatchOrdinal = 0, totalHits = 5),
        )
        assertEquals(
            "3/17",
            detailSearchMatchLabel("id", searching = true, matchCount = 17, activeMatchOrdinal = 2, totalHits = 17),
        )
        assertEquals(
            "0/0",
            detailSearchMatchLabel("id", searching = true, matchCount = 0, activeMatchOrdinal = 0, totalHits = 0),
        )
        assertEquals(
            "1 match",
            detailSearchMatchLabel("id", searching = false, matchCount = 0, activeMatchOrdinal = 0, totalHits = 1),
        )
        assertEquals(
            "2 matches",
            detailSearchMatchLabel("id", searching = false, matchCount = 0, activeMatchOrdinal = 0, totalHits = 2),
        )
    }
}
