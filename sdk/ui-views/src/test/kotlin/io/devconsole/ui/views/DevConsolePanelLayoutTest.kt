package io.devconsole.ui.views

import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class DevConsolePanelLayoutTest {
    @Test
    fun `large text stacks lifecycle actions instead of clipping labels`() {
        assertEquals(LinearLayout.HORIZONTAL, actionOrientation(fontScale = 1f))
        assertEquals(LinearLayout.VERTICAL, actionOrientation(fontScale = 1.5f))
        assertEquals(LinearLayout.VERTICAL, actionOrientation(fontScale = 2f))
    }
}
