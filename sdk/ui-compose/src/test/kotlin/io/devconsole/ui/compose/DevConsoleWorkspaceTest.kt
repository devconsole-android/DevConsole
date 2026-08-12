package io.devconsole.ui.compose

import androidx.compose.ui.unit.dp
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

    @Test
    fun `adaptive boundary does not change destination order`() {
        assertEquals(
            listOf("Observe", "Control", "Data", "More"),
            InspectorDestination.entries.map(InspectorDestination::label),
        )
        assertEquals(InspectorNavigationLayout.Bar, inspectorNavigationLayout(360.dp))
        assertEquals(InspectorNavigationLayout.Rail, inspectorNavigationLayout(840.dp))
    }
}
