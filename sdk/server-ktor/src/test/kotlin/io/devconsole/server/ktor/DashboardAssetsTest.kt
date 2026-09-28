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

    /**
     * A Network row used to print `t.path` alone, so `/orders?status=open` and `/orders?status=all`
     * were two identical-looking rows -- the query is often the only thing that differs between
     * captures of the same endpoint. It rides along as a dimmed suffix rather than replacing the
     * main text, so the endpoint is still what survives the row's ellipsis.
     */
    @Test
    fun `network rows print the query string after the path`() {
        val script = DashboardAssets.js()

        assertTrue(script.contains("mainSub: t.query ? '?' + t.query : ''"))
        assertTrue(script.contains("class=\"row-main-sub\""))
        assertTrue(DashboardAssets.css().contains(".row-main-sub"))
    }

    @Test
    fun `socket message formatting does not require the structured clone browser API`() {
        val script = DashboardAssets.js()

        assertTrue(script.contains("formatSocketMessages"))
        assertFalse(script.contains("structuredClone"))
    }

    /**
     * Regression for #22 ("Search Functionality Is Not Working"). Network and Socket search are
     * server-side filters, so unlike the Timeline/Push/Crashes boxes -- which re-render an
     * already-loaded page and can listen straight to `input` -- they have to re-issue the query.
     * Both shipped with no listener at all, so typing in "Search captures" did nothing until some
     * unrelated control (Apply, a status chip, Refresh) happened to trigger a reload.
     */
    @Test
    fun `server-backed search boxes are wired to re-issue their query`() {
        val script = DashboardAssets.js()

        assertTrue(script.contains("function wireServerSearch("))
        assertTrue(script.contains("wireServerSearch('networkSearch'"))
        assertTrue(script.contains("wireServerSearch('socketSearch'"))
    }

    /**
     * The Network hint used to claim "Search spans every field", but the server matches against
     * `NetworkTransactionStore.searchableText()`, which deliberately excludes request and response
     * bodies. Copy that promises a capability the filter does not have reads as a broken search.
     */
    @Test
    fun `network search hint does not promise fields the server never matches`() {
        val dashboard = DashboardAssets.index()

        assertFalse(dashboard.contains("Search spans every field"))
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

    @Test
    fun `mock rule dialog ships the find-in-body controls and its highlight layer`() {
        val dashboard = DashboardAssets.index()

        listOf(
            "mockRuleBodyFind",
            "mockRuleBodyFindPrev",
            "mockRuleBodyFindNext",
            "mockRuleBodyFindCount",
            // The mirrored layer the marks are painted on; without it every match is invisible,
            // because a textarea can only ever show one hit (its own selection).
            "mockRuleBodyHighlight",
            "mock-body-editor",
        ).forEach { id -> assertTrue("missing find-in-body hook \"$id\"", dashboard.contains(id)) }
    }

    @Test
    fun `find-in-body is wired to the body editor and steps through matches`() {
        val script = DashboardAssets.js()

        assertTrue(script.contains("function mockBodyFindRanges("))
        assertTrue(script.contains("function stepMockBodyFind("))
        assertTrue(script.contains("$('mockRuleBodyFind').addEventListener('input'"))
        // The mirror only lines up with the textarea while their scroll offsets agree.
        assertTrue(script.contains("$('mockRuleBody').addEventListener('scroll', syncMockBodyHighlightMetrics)"))
    }

    /**
     * Regression: `refreshMockBodyEditor` used to `return` early on a blank body, which skipped the
     * find rescan at its end. Opening a rule with a body, searching it, then opening a *blank* rule
     * left the previous rule's marks painted over an empty editor with a stale match count. The
     * blank case has to fall through to the shared tail instead, so the guard is now an if/else.
     */
    @Test
    fun `an empty response body still rescans find state instead of returning early`() {
        val script = DashboardAssets.js()
        val body =
            script
                .substringAfter(
                    "function refreshMockBodyEditor()",
                ).substringBefore("function formatMockRuleBody()")

        assertFalse(
            "blank-body guard must not return before the find rescan",
            body.contains("previewEl.innerHTML = ''; return; }"),
        )
        assertTrue("refreshMockBodyEditor must end by rescanning find offsets", body.contains("refreshMockBodyFind();"))
    }

    /**
     * A mock body is JSON, so `"`, `[` and `{` are ordinary things to search for. Building a
     * RegExp from the query would throw on the single `[` a user types on the way to `["id"]`.
     */
    @Test
    fun `find-in-body treats the query as literal text rather than a pattern`() {
        val script = DashboardAssets.js()
        val fn =
            script
                .substringAfter(
                    "function mockBodyFindRanges(",
                ).substringBefore("function syncMockBodyHighlightMetrics(")

        assertTrue(fn.contains("indexOf(needle"))
        assertFalse("query must never reach RegExp", fn.contains("RegExp"))
    }

    /**
     * Regression for issue #40 ("Do not have access to copy from console"). In non-secure contexts
     * (e.g. accessing DevConsole over plain HTTP via LAN), `navigator.clipboard` is unavailable.
     * `copyToClipboard` must provide a fallback via `document.execCommand('copy')`.
     */
    @Test
    fun `copyToClipboard provides document execCommand copy fallback for insecure contexts`() {
        val script = DashboardAssets.js()
        val fn =
            script
                .substringAfter("async function copyToClipboard(")
                .substringBefore("async function copyNetworkCurl()")

        assertTrue(fn.contains("execCommand('copy')"))
        assertTrue(fn.contains("isSecureContext"))
    }
}
