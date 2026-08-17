/**
 * @author Shakib
 * @since 17/08/26
 */
package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorDetailSearchTest {
    @Test
    fun `keys mode matches keys but not values and keeps exact ranges`() {
        val candidates =
            listOf(
                InspectorSearchCandidate("reqh", "row:0", InspectorSearchField.KEY, "Content-Type"),
                InspectorSearchCandidate("reqh", "row:0", InspectorSearchField.VALUE, "application/json"),
            )

        val matches =
            searchInspectorCandidates(
                candidates,
                "content",
                setOf("reqh"),
                InspectorSearchMode.KEYS,
            )

        assertEquals(1, matches.size)
        assertEquals(0, matches.single().start)
        assertEquals(7, matches.single().endExclusive)
    }

    @Test
    fun `multi section matching follows candidate order and values mode`() {
        val candidates =
            listOf(
                InspectorSearchCandidate("req", "line:0", InspectorSearchField.VALUE, "userId: 1"),
                InspectorSearchCandidate("res", "line:0", InspectorSearchField.VALUE, "userId: 1"),
                InspectorSearchCandidate("general", "row:0", InspectorSearchField.VALUE, "userId: 1"),
            )

        val matches =
            searchInspectorCandidates(
                candidates,
                "userid",
                setOf("req", "res"),
                InspectorSearchMode.VALUES,
            )

        assertEquals(listOf("req", "res"), matches.map { it.sectionKey })
        assertEquals(listOf(0, 1), matches.map { it.ordinal })
    }

    @Test
    fun `previous and next navigation wrap around`() {
        assertEquals(0, nextInspectorMatchIndex(current = 2, total = 3))
        assertEquals(2, previousInspectorMatchIndex(current = 0, total = 3))
        assertEquals(0, nextInspectorMatchIndex(current = 0, total = 0))
        assertEquals(0, previousInspectorMatchIndex(current = 0, total = 0))
    }

    @Test
    fun `blank query produces no matches`() {
        val candidates =
            listOf(InspectorSearchCandidate("res", "line:0", InspectorSearchField.VALUE, "userId: 1"))

        assertTrue(
            searchInspectorCandidates(
                candidates,
                "  ",
                setOf("res"),
                InspectorSearchMode.VALUES,
            ).isEmpty(),
        )
    }
}
