package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class DevConsoleWorkspaceTest {
    @Test
    fun `workspace uses the original four-domain Android structure`() {
        assertEquals(
            listOf("Observe", "Control", "Data", "More"),
            InspectorDestination.entries.map { it.label },
        )
    }
}
