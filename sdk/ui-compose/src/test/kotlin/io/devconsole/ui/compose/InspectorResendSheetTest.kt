/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InspectorResendSheetTest {
    @Test
    fun `draft seeds url headers and body from the capture`() {
        val transaction =
            InspectorTransactionUi(
                id = "tx-1",
                method = "POST",
                host = "api.example.test",
                path = "/orders",
                statusCode = 201,
                durationMs = null,
                url = "https://api.example.test/orders?status=open",
                requestHeaders = mapOf("Content-Type" to "application/json", "X-Id" to "7"),
                requestPreview = "{\"id\":7}",
                requestBodyKind = InspectorBodyKind.TEXT,
            )

        val draft = resendDraftFromTransaction(transaction)

        assertEquals("POST", draft.method)
        assertEquals("https://api.example.test/orders?status=open", draft.url)
        assertEquals("Content-Type: application/json\nX-Id: 7", draft.headerLines)
        assertEquals("{\"id\":7}", draft.body)
    }

    @Test
    fun `draft composes a url when the adapter predates the url field`() {
        val transaction =
            InspectorTransactionUi(
                id = "tx-1",
                method = "GET",
                host = "api.example.test",
                path = "/orders",
                statusCode = 200,
                durationMs = null,
            )

        assertEquals("https://api.example.test/orders", resendDraftFromTransaction(transaction).url)
    }

    @Test
    fun `header lines parse trims whitespace and skips malformed lines`() {
        val parsed = parseResendHeaderLines("  Content-Type: application/json \n\nX-Id:7\nnot-a-header\n: value\n")

        assertEquals(mapOf("Content-Type" to "application/json", "X-Id" to "7"), parsed)
    }

    @Test
    fun `a draft becomes the composer request the view model executes`() {
        val request =
            InspectorResendDraft(
                method = " post ",
                url = " https://api.example.test/orders ",
                headerLines = "Content-Type: application/json",
                body = "{\"id\":1}",
            ).toComposerRequest()

        assertEquals("POST", request.method)
        assertEquals("https://api.example.test/orders", request.url)
        assertEquals(mapOf("Content-Type" to "application/json"), request.headers)
        assertEquals("{\"id\":1}", request.body)
    }

    @Test
    fun `an empty body is sent as no body`() {
        val request = InspectorResendDraft("GET", "https://api.example.test", "", "").toComposerRequest()

        assertNull(request.body)
    }
}
