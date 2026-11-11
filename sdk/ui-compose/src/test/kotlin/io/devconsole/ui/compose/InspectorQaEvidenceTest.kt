/**
 * @author Shakib
 * @since 09/11/26
 */
package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class InspectorQaEvidenceTest {
    @Test
    fun `build summary formats version and device`() {
        assertEquals(
            "1.2.3 (45) · Google Pixel 8 · Android 15",
            buildSummaryLine("1.2.3", 45, "google", "Pixel 8", "15"),
        )
        assertEquals(
            "(45) · GOOGLE Pixel 8 · Android 15",
            buildSummaryLine(null, 45, "google", "GOOGLE Pixel 8", "15"),
        )
        assertEquals(
            "(45) · Google Pixel 8 · Android 15",
            buildSummaryLine("  ", 45, "google", "Google Pixel 8", "15"),
        )
    }

    @Test
    fun `active mocks require global enablement and enabled rules`() {
        val state =
            InspectorState(
                mocksEnabled = true,
                mockRules = listOf(InspectorMockRuleUi("on"), InspectorMockRuleUi("off", enabled = false)),
            )
        assertEquals(listOf("on"), activeMockRuleIds(state))
        assertEquals(emptyList<String>(), activeMockRuleIds(state.copy(mocksEnabled = false)))
        assertEquals(emptyList<String>(), activeMockRuleIds(state.copy(mockRules = emptyList())))
    }

    @Test
    fun `bug report includes readable capture and build`() {
        val tx =
            transaction().copy(
                statusCode = 500,
                durationMs = 312,
                startedAtEpochMs = 1_000,
                isMocked = true,
                requestPreview = "{\"a\":1}",
                responsePreview = "{\"error\":\"x\"}",
            )
        val expected =
            "POST https://api.example.com/v1/orders\n" +
                "Result: 500 Internal Server Error · 312 ms · mocked\n" +
                "Time: ${formatCaptureClockTime(1_000)}\nBuild: build\n\n" +
                "Request body\n{\"a\":1}\n\nResponse body\n{\"error\":\"x\"}"
        assertEquals(expected, tx.toBugReportText("build"))
    }

    @Test
    fun `bug report handles failures unknown status empty bodies and limits`() {
        val failed = transaction().copy(statusCode = null, error = "timeout", requestPreview = " ")
        assertEquals(
            "POST https://api.example.com/v1/orders\nResult: Failed · timeout\n\n" +
                "Request body\n(empty)\n\nResponse body\n(empty)",
            failed.toBugReportText(" "),
        )
        assertEquals(
            "POST https://api.example.com/v1/orders\nResult: Failed\n\n" +
                "Request body\n(empty)\n\nResponse body\n(empty)",
            failed.copy(error = null).toBugReportText(""),
        )
        val body = "a".repeat(20000)
        val limited = transaction().copy(statusCode = 599, requestPreview = body + "b", responsePreview = body + "b")
        assertEquals(
            "POST https://api.example.com/v1/orders\nResult: 599\n\nRequest body\n$body" +
                "\n… cut off here\n\nResponse body\n$body\n… cut off here",
            limited.toBugReportText(""),
        )
        assertEquals(
            "POST https://other.test/?x=1\nResult: 599\n\nRequest body\n$body\n\nResponse body\n(empty)",
            transaction()
                .copy(statusCode = 599, url = "https://other.test/?x=1", requestPreview = body)
                .toBugReportText(""),
        )
    }

    private fun transaction() =
        InspectorTransactionUi(
            id = "tx",
            method = "POST",
            host = "api.example.com",
            path = "/v1/orders",
            statusCode = 200,
        )
}
