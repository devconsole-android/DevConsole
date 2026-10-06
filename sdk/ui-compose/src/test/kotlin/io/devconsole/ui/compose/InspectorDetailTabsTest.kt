/**
 * @author Shakib
 * @since 06/10/26
 */
package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the counts the tabbed detail layout shows on its tabs, chips and find bar, so a match in a
 * section the user isn't looking at is still announced somewhere visible.
 */
class InspectorDetailTabsTest {
    private fun match(
        ordinal: Int,
        sectionKey: String,
    ) = InspectorDetailSearchMatch(
        ordinal = ordinal,
        sectionKey = sectionKey,
        itemId = "item$ordinal",
        field = InspectorSearchField.VALUE,
        start = 0,
        endExclusive = 1,
    )

    private val responseTab =
        InspectorDetailTabSpec(
            key = "response",
            label = "Response",
            layout =
                InspectorDetailTabLayout.Chips(
                    listOf(InspectorDetailTabChip("res", "Body"), InspectorDetailTabChip("resh", "Headers")),
                ),
        )

    @Test
    fun `a tab's badge counts the matches in every section it holds`() {
        val matches = listOf(match(0, "req"), match(1, "res"), match(2, "resh"), match(3, "res"))

        assertEquals("3", responseTab.matchBadge(matches, hasQuery = true))
    }

    @Test
    fun `a tab with no matches or no query shows no badge`() {
        assertNull(responseTab.matchBadge(listOf(match(0, "req")), hasQuery = true))
        assertNull(responseTab.matchBadge(listOf(match(0, "res")), hasQuery = false))
    }

    @Test
    fun `a stacked tab lists its sections in order`() {
        val overview =
            InspectorDetailTabSpec(
                "overview",
                "Overview",
                InspectorDetailTabLayout.Stacked(listOf("general", "timing")),
            )

        assertEquals(listOf("general", "timing"), overview.sectionKeys)
        assertEquals(listOf("res", "resh"), responseTab.sectionKeys)
    }

    @Test
    fun `a chip shows its match count while a query is active`() {
        val headers = InspectorDetailSectionBody.KeyValues(listOf(InspectorKeyValue("Accept", "*/*")))

        assertEquals("2", detailChipCount(headers, matchCount = 2, hasQuery = true))
        assertNull(detailChipCount(headers, matchCount = 0, hasQuery = true))
    }

    @Test
    fun `without a query a headers chip shows how many headers it holds and a body chip shows nothing`() {
        val headers =
            InspectorDetailSectionBody.KeyValues(
                listOf(InspectorKeyValue("Accept", "*/*"), InspectorKeyValue("Host", "api")),
            )
        val body = InspectorDetailSectionBody.Empty("GET request — no body sent.")

        assertEquals("2", detailChipCount(headers, matchCount = 0, hasQuery = false))
        assertNull(detailChipCount(InspectorDetailSectionBody.KeyValues(emptyList()), matchCount = 0, hasQuery = false))
        assertNull(detailChipCount(body, matchCount = 0, hasQuery = false))
    }

    @Test
    fun `the find bar counts only the matches in the section on screen`() {
        assertEquals("", tabbedSearchMatchLabel(query = " ", position = 0, count = 3))
        assertEquals("0/0", tabbedSearchMatchLabel(query = "id", position = 0, count = 0))
        assertEquals("2/3", tabbedSearchMatchLabel(query = "id", position = 1, count = 3))
    }

    @Test
    fun `the active match is the visible section's match at the current position`() {
        val visible = listOf(match(4, "res"), match(7, "res"))

        assertEquals(4, activeVisibleMatchOrdinal(visible, position = 0))
        assertEquals(7, activeVisibleMatchOrdinal(visible, position = 1))
        // A stale position from a longer match list clamps instead of pointing at nothing.
        assertEquals(7, activeVisibleMatchOrdinal(visible, position = 5))
        assertNull(activeVisibleMatchOrdinal(emptyList(), position = 0))
    }
}
