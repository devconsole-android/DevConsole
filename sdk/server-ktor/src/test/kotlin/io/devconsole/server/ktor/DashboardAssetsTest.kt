package io.devconsole.server.ktor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardAssetsTest {
    @Test
    fun `index html no longer inlines a style or script body`() {
        val dashboard = DashboardAssets.index()

        assertFalse(dashboard.contains("<style>"))
        assertFalse(dashboard.contains("</style>"))
        assertFalse(dashboard.contains("<script>"))
        assertTrue(dashboard.contains("<link rel=\"stylesheet\" href=\"/assets/dashboard.css\">"))
        assertTrue(dashboard.contains("<script src=\"/assets/dashboard.js\"></script>"))
    }

    @Test
    fun `socket message formatting does not require the structured clone browser API`() {
        val script = DashboardAssets.js()

        assertTrue(script.contains("formatSocketMessages"))
        assertFalse(script.contains("structuredClone"))
    }

    @Test
    fun `navigation rail groups views under the six design-mock workspace labels`() {
        val dashboard = DashboardAssets.index()

        // "Export" was renamed to "Report" (Report = Evidence tray + Session & Security); a
        // "Design review" group is intentionally not part of the web dashboard.
        listOf("Workbench", "Traffic", "Signals", "Control", "Data", "Report").forEach { label ->
            assertTrue("expected dashboard to contain group label \"$label\"", dashboard.contains(label))
        }
    }

    @Test
    fun `navigation rail preserves every existing view button and section id`() {
        val dashboard = DashboardAssets.index()

        val buttonIds =
            listOf(
                "viewOverview",
                "viewTimeline",
                "viewNetwork",
                "viewSockets",
                "viewComposer",
                "viewMocks",
                "viewPush",
                "viewState",
                "viewSdkHealth",
                "viewEvidence",
                "viewSession",
            )
        buttonIds.forEach { id ->
            assertTrue("expected dashboard to contain button id=\"$id\"", dashboard.contains("id=\"$id\""))
        }

        val sectionIds =
            listOf(
                "overviewView",
                "timelineView",
                "networkView",
                "socketView",
                "stateView",
                "pushView",
                "composerView",
                "mocksView",
                "sdkHealthView",
                "evidenceView",
                "sessionView",
            )
        sectionIds.forEach { id ->
            assertTrue("expected dashboard to contain section id=\"$id\"", dashboard.contains("id=\"$id\""))
        }
    }

    @Test
    fun `network view offers a Postman export next to the existing HAR export`() {
        val dashboard = DashboardAssets.index()
        val script = DashboardAssets.js()

        assertTrue(dashboard.contains("id=\"networkPostmanDownload\""))
        assertTrue(script.contains("/api/v1/network/postman"))
        assertTrue(script.contains("downloadPostman"))
    }

    @Test
    fun `css asset styles the rail and does not leak into the html document`() {
        val css = DashboardAssets.css()
        val dashboard = DashboardAssets.index()

        assertTrue(css.contains(".rail"))
        assertFalse(dashboard.contains("color-scheme: dark"))
    }
}
