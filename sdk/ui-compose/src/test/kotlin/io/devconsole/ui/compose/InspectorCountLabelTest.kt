/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class InspectorCountLabelTest {
    @Test
    fun countLabelUsesSingularOnlyForOne() {
        assertEquals("0 files", countLabel(0, "file"))
        assertEquals("1 file", countLabel(1, "file"))
        assertEquals("2 files", countLabel(2, "file"))
        assertEquals("1 entry", countLabel(1, "entry", "entries"))
        assertEquals("4 entries", countLabel(4, "entry", "entries"))
    }
}
