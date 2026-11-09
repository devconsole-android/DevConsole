/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole.ui.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorSocketComposerTest {
    @Test
    fun `an empty frame never enables send`() {
        assertFalse(socketComposerSendEnabled("websocket", text = "", topic = ""))
        assertFalse(socketComposerSendEnabled("mqtt", text = "", topic = "orders"))
    }

    @Test
    fun `a websocket frame needs only text`() {
        assertTrue(socketComposerSendEnabled("websocket", text = "ping", topic = ""))
    }

    @Test
    fun `an mqtt publish needs both text and topic`() {
        assertFalse(socketComposerSendEnabled("mqtt", text = "on", topic = ""))
        assertFalse(socketComposerSendEnabled("mqtt", text = "on", topic = "   "))
        assertTrue(socketComposerSendEnabled("mqtt", text = "on", topic = "orders"))
    }
}
