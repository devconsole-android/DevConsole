/**
 * @author Shakib
 * @since 17/08/26
 */
package io.devconsole.ui.compose

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @Test
    fun `key value bodies produce stable row candidates`() {
        val body =
            InspectorDetailSectionBody.KeyValues(
                listOf(InspectorKeyValue("Content-Type", "application/json")),
            )

        val candidates = searchInspectorBodyCandidates("reqh", body)

        assertEquals(listOf(InspectorSearchField.KEY, InspectorSearchField.VALUE), candidates.map { it.field })
        assertEquals(listOf("row:0", "row:0"), candidates.map { it.itemId })
    }

    @Test
    fun `nested json candidates retain property path and ancestor paths`() {
        val root = MinimalJsonParser("""{"user":{"id":1}}""").parseDocument()
        val body =
            InspectorDetailSectionBody.Formattable(
                rawText = """{"user":{"id":1}}""",
                rawLines = listOf(InspectorCodeLine(value = "{\"user\":{\"id\":1}}", valueColor = Color.White)),
                formatted = FormattedBody.Json(root),
            )

        val candidates = searchInspectorBodyCandidates("res", body)
        val idValue = candidates.firstOrNull { it.path == "$/0:user/0:id" && it.field == InspectorSearchField.VALUE }

        assertNotNull(idValue)
        assertEquals(listOf("$", "$/0:user"), idValue!!.ancestorPaths)
        assertEquals("1", idValue.text)
    }

    @Test
    fun `json key and value modes select different fields`() {
        val root = MinimalJsonParser("""{"userId":1}""").parseDocument()
        val body =
            InspectorDetailSectionBody.Formattable(
                rawText = """{"userId":1}""",
                rawLines = listOf(InspectorCodeLine(value = "{\"userId\":1}", valueColor = Color.White)),
                formatted = FormattedBody.Json(root),
            )
        val candidates = searchInspectorBodyCandidates("res", body)

        assertEquals(
            1,
            searchInspectorCandidates(candidates, "userid", setOf("res"), InspectorSearchMode.KEYS).size,
        )
        assertEquals(
            1,
            searchInspectorCandidates(candidates, "1", setOf("res"), InspectorSearchMode.VALUES).size,
        )
    }

    @Test
    fun `scope summary reflects multi section selection`() {
        assertEquals(
            "All request + response",
            inspectorSearchScopeSummary(NetworkDetailSearchOptions, NetworkDetailSearchOptions.defaultSectionKeys),
        )
        assertEquals(
            "Response body",
            inspectorSearchScopeSummary(NetworkDetailSearchOptions, setOf("res")),
        )
        assertEquals("2 sections", inspectorSearchScopeSummary(NetworkDetailSearchOptions, setOf("reqh", "res")))
    }
}
