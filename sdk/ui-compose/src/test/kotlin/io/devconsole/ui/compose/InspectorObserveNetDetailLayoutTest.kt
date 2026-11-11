/**
 * @author Shakib
 * @since 09/11/26
 */
package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorObserveNetDetailLayoutTest {
    private fun content(
        transaction: InspectorTransactionUi,
        resend: NetResendActions? = null,
    ): ObserveDetailContent =
        netDetailContent(
            transaction = transaction,
            colors = DevConsoleDarkColors,
            isFlagged = false,
            onToggleFlag = {},
            copyText = {},
            shareText = { _, _ -> },
            onMockThisResponse = {},
            onUnmockThisResponse = {},
            resendActions = resend,
        )

    private fun transaction() =
        InspectorTransactionUi(
            id = "layout",
            method = "POST",
            host = "api.example.test",
            path = "/items",
            statusCode = 200,
            durationMs = 42,
        )

    @Test
    fun `bodies lead the page and actions are split by placement`() {
        val resend = NetResendActions(true, null, {}, {})
        val detail = content(transaction(), resend)

        assertTrue(detail.singlePage)
        assertEquals(InspectorExchangeSection.PRIMARY_BODY.key, detail.sections[0].key)
        assertEquals(InspectorExchangeSection.SECONDARY_BODY.key, detail.sections[1].key)
        assertFalse(detail.sections[0].secondary)
        assertFalse(detail.sections[1].secondary)
        assertTrue(detail.sections.drop(2).all { it.secondary })
        assertEquals(listOf("Resend", "Mock this response"), detail.pinnedActions.map { it.label })
        assertEquals(
            listOf("Copy for bug report", "Edit & resend", "Copy as cURL"),
            detail.footerActions.map { it.label },
        )

        val withoutResend = content(transaction())
        assertEquals(listOf("Mock this response"), withoutResend.pinnedActions.map { it.label })
        assertEquals(listOf("Copy for bug report", "Copy as cURL"), withoutResend.footerActions.map { it.label })
    }

    @Test
    fun `decoded form sections remain secondary on the single page`() {
        val transaction =
            transaction().copy(
                requestHeaders = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                requestPreview = "name=Shakib&role=developer",
            )
        val content = content(transaction)
        val decoded = content.sections.filter { it.key.startsWith("decoded-") }

        assertTrue(content.singlePage)
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.secondary })
    }
}
