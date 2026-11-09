/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InspectorResendResultTest {
    @Test
    fun `success with a status maps summary status and body`() {
        val mapped =
            resendResultFrom(
                InspectorCommandResult.Success(
                    summary = "GET https://api.example.test/todos/1 -> 200",
                    statusCode = 200,
                    body = "{\"id\":1}",
                ),
            ) ?: error("expected a resend result")

        assertEquals("GET https://api.example.test/todos/1", mapped.summary)
        assertEquals(200, mapped.statusCode)
        assertEquals("{\"id\":1}", mapped.body)
    }

    @Test
    fun `success without a status is not a resend result`() {
        assertNull(resendResultFrom(InspectorCommandResult.Success(summary = "Mocks enabled")))
    }

    @Test
    fun `invalid failed and disabled results are not resend results`() {
        assertNull(resendResultFrom(InspectorCommandResult.Invalid("Destination host is not allowed")))
        assertNull(resendResultFrom(InspectorCommandResult.Failed("connection reset")))
        assertNull(resendResultFrom(InspectorCommandResult.Disabled("requestExecution")))
    }
}
