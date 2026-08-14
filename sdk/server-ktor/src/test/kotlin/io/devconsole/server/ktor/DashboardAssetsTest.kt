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
    fun `remote config keys open a value viewer offering both pretty JSON and raw`() {
        val dashboard = DashboardAssets.index()
        val script = DashboardAssets.js()

        listOf(
            "remoteConfigValueModal",
            "remoteConfigValueSeg",
            "remoteConfigValueBody",
            "remoteConfigValueNotice",
            "remoteConfigValueCopy",
            "remoteConfigValueClose",
        ).forEach { id ->
            assertTrue("expected dashboard to contain id=\"$id\"", dashboard.contains("id=\"$id\""))
        }
        // Pretty JSON is the segment that carries `active` in the static markup, so the viewer
        // opens on it for every value that actually parses.
        assertTrue(
            dashboard.contains("<button type=\"button\" class=\"active\" data-value=\"json\">Pretty JSON</button>"),
        )
        assertTrue(dashboard.contains("data-value=\"raw\">Raw</button>"))
        // The rows have to be clickable for any of the above to be reachable.
        assertTrue(script.contains("openRemoteConfigValue"))
        assertTrue(script.contains("data-card-row"))
    }

    @Test
    fun `remote config rail count goes through setNavCount and every source is filterable`() {
        val dashboard = DashboardAssets.index()
        val script = DashboardAssets.js()

        // Setting .textContent directly leaves the badge at `display: none` -- only setNavCount
        // adds the `.show` class, and it is also what applies the 999+ clamp.
        assertTrue(script.contains("setNavCount('navCountRemoteConfig'"))
        assertFalse(script.contains("\$('navCountRemoteConfig').textContent"))
        // Every source in the model is reachable: the matcher is an exact `===`, so a source with
        // no button cannot be isolated at all -- `override` above all, which is the whole question
        // the source column exists to answer.
        listOf("all", "remote", "default", "static", "override", "unknown").forEach { source ->
            assertTrue("no source filter for \"$source\"", dashboard.contains("data-value=\"$source\""))
        }
        // The rail button and the view id move together: the id gates the auto-expand that keeps
        // the active button from being stranded inside a collapsed Advanced group.
        assertTrue(script.contains("'viewRemoteConfig'"))
        assertTrue(script.contains("'remoteConfig', 'preferences'"))
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

    @Test
    fun `dashboard uses the graphite cobalt design tokens`() {
        val css = DashboardAssets.css().lowercase()

        listOf(
            "--ground: #111317",
            "--panel: #171a20",
            "--surface-2: #1d2129",
            "--ink: #eceef2",
            "--signal: #72a7ff",
        ).forEach { token -> assertTrue("missing $token", css.contains(token)) }

        listOf("#b7ed65", "#427526", "terminal-green", "instrument panel").forEach { legacy ->
            assertFalse("legacy design value remains: $legacy", css.contains(legacy))
        }
    }

    @Test
    fun `dashboard defaults to system theme and persists only explicit choice`() {
        val script = DashboardAssets.js()

        assertTrue(script.contains("matchMedia('(prefers-color-scheme: dark)')"))
        assertTrue(script.contains("devconsole-theme"))
        assertTrue(script.contains("media.addEventListener('change'"))
    }

    @Test
    fun `dashboard shell keeps stable functional ids and semantic landmarks`() {
        val dashboard = DashboardAssets.index()

        assertTrue(dashboard.contains("<header class=\"topbar\""))
        assertTrue(dashboard.contains("<nav class=\"rail\""))
        assertTrue(dashboard.contains("<main id=\"mainContent\""))
        assertTrue(dashboard.contains("aria-label=\"Inspector views\""))
    }
}
