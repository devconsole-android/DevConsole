/**
 * @author Shakib
 * @since 06/10/26
 */
package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HTTP transaction detail is the one kind laid out as Overview / Request / Response tabs:
 * locks which section lands on which tab, which tab opens first, and where the header and footer
 * actions went once the top bar was cleared.
 */
class InspectorNetDetailTabsTest {
    private fun transaction(
        statusCode: Int? = 200,
        requestHeaders: Map<String, String> = mapOf("Accept" to "application/json"),
        isMocked: Boolean = false,
        mockRuleId: String? = null,
    ) = InspectorTransactionUi(
        id = "t1",
        method = "GET",
        host = "api.p-stageenv.xyz",
        path = "/v1/me/notifications/unseen_count",
        statusCode = statusCode,
        durationMs = 167,
        requestHeaders = requestHeaders,
        responseHeaders = mapOf("Content-Type" to "application/json"),
        responsePreview = """{"unseen_count":12}""",
        isMocked = isMocked,
        mockRuleId = mockRuleId,
    )

    private fun content(
        transaction: InspectorTransactionUi = transaction(),
        isFlagged: Boolean = false,
    ) = netDetailContent(
        transaction = transaction,
        colors = DevConsoleDarkColors,
        isFlagged = isFlagged,
        onToggleFlag = {},
        copyText = {},
        shareText = { _, _ -> },
        onMockThisResponse = {},
        onUnmockThisResponse = {},
    )

    private fun ObserveDetailContent.tab(key: String): InspectorDetailTabSpec =
        requireNotNull(tabs).tabs.single { it.key == key }

    @Test
    fun `the sections are grouped into overview, request and response tabs`() {
        val tabs = requireNotNull(content().tabs).tabs

        assertEquals(listOf("Overview", "Request", "Response"), tabs.map { it.label })
        assertEquals(listOf("general", "timing"), content().tab("overview").sectionKeys)
        assertEquals(
            InspectorDetailTabLayout.Chips(
                listOf(InspectorDetailTabChip("req", "Body"), InspectorDetailTabChip("reqh", "Headers")),
            ),
            content().tab("request").layout,
        )
        assertEquals(
            InspectorDetailTabLayout.Chips(
                listOf(InspectorDetailTabChip("res", "Body"), InspectorDetailTabChip("resh", "Headers")),
            ),
            content().tab("response").layout,
        )
    }

    @Test
    fun `every tab section is a real section of the screen`() {
        val content = content(transaction(requestHeaders = mapOf("Authorization" to REDACTED_MARKER)))
        val sectionKeys = content.sections.map { it.key }.toSet()

        requireNotNull(content.tabs).tabs.flatMap { it.sectionKeys }.forEach { key ->
            assertTrue("$key is not a section", key in sectionKeys)
        }
    }

    @Test
    fun `redactions join the overview only when something was redacted`() {
        val redacted = content(transaction(requestHeaders = mapOf("Authorization" to REDACTED_MARKER)))

        assertEquals(listOf("general", "timing", "redact"), redacted.tab("overview").sectionKeys)
        assertEquals(listOf("general", "timing"), content().tab("overview").sectionKeys)
    }

    @Test
    fun `a transaction with a response opens on the response tab`() {
        assertEquals("response", requireNotNull(content().tabs).initialTabKey)
    }

    @Test
    fun `a transaction that failed before any response opens on the overview tab`() {
        assertEquals("overview", requireNotNull(content(transaction(statusCode = null)).tabs).initialTabKey)
    }

    @Test
    fun `the header splits host from path and keeps the top bar clear`() {
        val header = content().header

        assertEquals("api.p-stageenv.xyz", header.titleOverline)
        assertEquals("/v1/me/notifications/unseen_count", header.title)
        assertNull(header.note)
        assertTrue(header.actions.isEmpty())
    }

    @Test
    fun `the header leaves the duration to the overview`() {
        val content = content()
        val general = content.sections.single { it.key == "general" }.body as InspectorDetailSectionBody.KeyValues

        assertEquals("", content.header.subtitle)
        assertEquals("167 ms", general.entries.single { it.key == "duration" }.value)
    }

    @Test
    fun `a mocked transaction names its rule in the header note`() {
        val header = content(transaction(isMocked = true, mockRuleId = "r1")).header

        assertEquals("MOCK RULE r1", header.note)
    }

    @Test
    fun `share and flag join cURL and mock in the footer`() {
        val footer = content().footerActions

        assertEquals(listOf("", "", "cURL", "Mock response"), footer.map { it.label })
        assertEquals("Share transaction as JSON", footer[0].contentDescription)
        assertEquals("Flag as evidence", footer[1].contentDescription)
    }

    @Test
    fun `the mock action keeps a short label for narrow screens`() {
        assertEquals("Mock", content().footerActions.last().compactLabel)
        assertEquals(
            "Unmock",
            content(transaction(isMocked = true, mockRuleId = "r1")).footerActions.last().compactLabel,
        )
    }

    @Test
    fun `a flagged transaction says so on its footer flag`() {
        assertEquals("Flagged as evidence", content(isFlagged = true).footerActions[1].contentDescription)
    }

    @Test
    fun `a mocked transaction offers to unmock`() {
        val footer = content(transaction(isMocked = true, mockRuleId = "r1")).footerActions

        assertEquals("Unmock response", footer.last().label)
    }

    private companion object {
        const val REDACTED_MARKER = "<redacted>"
    }
}
