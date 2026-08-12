package io.devconsole.ui.compose

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class InspectorAdaptiveLayoutTest {
    @Test fun `compact width uses navigation bar`() {
        assertEquals(InspectorNavigationLayout.Bar, inspectorNavigationLayout(599.dp))
    }

    @Test fun `expanded width uses navigation rail`() {
        assertEquals(InspectorNavigationLayout.Rail, inspectorNavigationLayout(600.dp))
    }
}
