/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole.ui.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorLogDetailTest {
    @Test
    fun recordedThrowableIsDetectedAsStackTrace() {
        val trace =
            """
            java.lang.IllegalStateException: Card issuer returned 402
            	at io.devconsole.sample.compose.MainActivity.writeSampleLogs(MainActivity.kt:120)
            	at androidx.compose.foundation.ClickableNode.onClick(Clickable.kt:42)
            """.trimIndent()

        assertTrue(trace.looksLikeStackTrace())
    }

    @Test
    fun plainContextIsNotAStackTrace() {
        assertFalse(null.looksLikeStackTrace())
        assertFalse("""{"orderId": 42}""".looksLikeStackTrace())
        assertFalse("at the start of the line only".looksLikeStackTrace())
        assertFalse("first line\nsecond line mentions at once".looksLikeStackTrace())
    }
}
