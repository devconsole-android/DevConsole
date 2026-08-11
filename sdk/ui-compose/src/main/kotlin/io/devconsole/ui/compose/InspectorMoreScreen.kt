/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "TooManyFunctions", "UnusedPrivateMember")

package io.devconsole.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.devconsole.api.ScreenshotResult
import io.devconsole.ui.compose.qr.ConnectUrlQrCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The empty-selection ("export everything") HAR/Postman subtitle. This in-app export path never
 * goes through HTTP -- [InspectorDataSource.exportHar]/[InspectorDataSource.exportPostman] call
 * straight into the on-device `AndroidInspectorExporter`, which resolves an empty selection as
 * `ExportSelection.All` against `NetworkTransactionStore`, bounded to
 * `NetworkTransactionQuery.MAX_PAGE_LIMIT` (500) rows -- the same cap the browser's
 * `/api/v1/network/har`/`postman` routes enforce, just without that path's
 * `X-DevConsole-Export-Truncated`/`X-DevConsole-Export-Count` response headers to report an actual
 * truncated count back to this module. Rather than let a silently-capped export look complete, this
 * row states the cap up front. An explicit id-based selection (this row's non-empty branch, and the
 * Observe traffic tab's whole selection-mode flow) never hits it: [InspectorViewModel] never loads
 * more than 200 transactions into [InspectorState.transactions] in the first place, well under the
 * 500-row bound.
 */
private const val EXPORT_ALL_SUBTITLE = "Redacted · all captured traffic (capped at the 500 most recent)"

private const val LAN_BINDING = "LAN"
private const val SERVER_STATE_RUNNING = "Running"
private const val ACTIVE_SESSION_STATUS = "ACTIVE"
// Byte-size and MS_PER_* constants live in InspectorObserveFormat.kt (formatByteSize/MS_PER_SECOND
// etc.) -- this file used to duplicate both; see that file's own doc.

/** How often [MoreRoute] re-reads the snapshot while visible -- see the poll's own doc. */
private const val MORE_SCREEN_REFRESH_INTERVAL_MS = 5_000L

/** Stateful entry point wired into the MORE destination. Theme is owned by [DevConsoleWorkspace]. */
@Composable
internal fun MoreRoute(
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InspectorViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var heroCollapsed by rememberSaveable { mutableStateOf(true) }
    var qrUrl by remember { mutableStateOf<String?>(null) }
    val showMessage: (String) -> Unit = { message -> scope.launch { snackbarHostState.showSnackbar(message) } }

    LaunchedEffect(Unit) { viewModel.dispatch(InspectorAction.Refresh) }
    // A one-shot Refresh above leaves the URL card and uptime frozen at whatever they were the
    // moment this screen opened. Session codes expire on their own (shorter) TTL and the bind
    // address can change independently of this screen -- poll while visible so both stay live
    // instead of silently going stale for as long as the operator stays here.
    LaunchedEffect(Unit) {
        while (true) {
            delay(MORE_SCREEN_REFRESH_INTERVAL_MS)
            viewModel.dispatch(InspectorAction.Refresh)
        }
    }
    LaunchedEffect(state.lastCommandResult) {
        state.lastCommandResult?.let { result ->
            showMessage(result.toFlashMessage())
            viewModel.dispatch(InspectorAction.DismissCommandResult)
        }
    }
    LaunchedEffect(state.lastScreenshotResult) {
        state.lastScreenshotResult?.let { result ->
            showMessage(result.toFlashMessage())
            viewModel.dispatch(InspectorAction.DismissScreenshotResult)
        }
    }
    // Also offered here, not only on Control. Without POST_NOTIFICATIONS the keep-alive service
    // still runs but Android hides its notification, so the server looks like it is not running in
    // the background at all -- and this is the screen someone is on when they start it and go
    // looking for that notification. The prompt is a no-op on any screen once granted or dismissed.
    KeepAliveNotificationPromptEffect(
        promptNeeded = state.keepAlivePromptNeeded,
        snackbarHostState = snackbarHostState,
        onPermissionResult = { viewModel.dispatch(InspectorAction.NotificationPermissionGranted) },
    )

    Box(modifier = modifier.fillMaxSize()) {
        MoreScreen(
            state = state,
            heroCollapsed = heroCollapsed,
            onToggleHero = { heroCollapsed = !heroCollapsed },
            onToggleTheme = onToggleTheme,
            actions = rememberMoreActions(viewModel, clipboard, showMessage) { url -> qrUrl = url },
        )
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
    qrUrl?.let { url -> QrDialog(url, onDismiss = { qrUrl = null }) }
}

/**
 * Grouped callbacks [MoreScreen] dispatches. [onSetServerRunning] takes the *desired* next state
 * (the hero's CTA already knows the current one) rather than a bare toggle, since the CTA's own
 * label/icon already flip on [InspectorHealthUi.state] -- this just carries the CTA's intent.
 */
@Suppress("LongParameterList") // One real interaction per field; see doc above.
internal data class MoreActions(
    val onSetServerRunning: (Boolean) -> Unit,
    val onCopyUrl: (String) -> Unit,
    val onCopyCode: (String) -> Unit,
    val onShowQr: (String) -> Unit,
    val onExportHar: () -> Unit,
    val onExportPostman: () -> Unit,
    val onExportSessionZip: () -> Unit,
    val onCaptureScreenshot: () -> Unit,
    val onRevoke: (String) -> Unit,
    val onSelectSession: (String) -> Unit,
)

@Composable
private fun rememberMoreActions(
    viewModel: InspectorViewModel,
    clipboard: ClipboardManager,
    showMessage: (String) -> Unit,
    onShowQr: (String) -> Unit,
): MoreActions =
    MoreActions(
        onSetServerRunning = { running -> viewModel.dispatch(InspectorAction.SetServerRunning(running)) },
        onCopyUrl = { url ->
            clipboard.setText(AnnotatedString(url))
            showMessage("$url copied")
        },
        onCopyCode = { code ->
            clipboard.setText(AnnotatedString(code))
            showMessage("Session code $code copied")
        },
        onShowQr = onShowQr,
        onExportHar = { viewModel.dispatch(InspectorAction.ExportHar) },
        onExportPostman = { viewModel.dispatch(InspectorAction.ExportPostman) },
        onExportSessionZip = { viewModel.dispatch(InspectorAction.ExportSessionZip) },
        onCaptureScreenshot = { viewModel.dispatch(InspectorAction.CaptureScreenshot) },
        onRevoke = { id -> viewModel.dispatch(InspectorAction.RevokePrincipal(id)) },
        onSelectSession = { id -> viewModel.dispatch(InspectorAction.SelectSession(id)) },
    )

/**
 * The QR is a fixed-size square and the dialog is wider than it, so it needs to be told to centre:
 * [AlertDialog]'s `text` slot aligns its content to the start, which left the code visibly hugging
 * the left edge with all the slack piled up on the right.
 *
 * The vertical half is the empty `confirmButton`. A content-only [AlertDialog] still lays out its
 * button row and keeps the padding that separates it from the text, so the QR sat above centre by
 * that much. Weighting the padding to the top pays it back, which is why this is not a symmetric
 * [Modifier.padding] value.
 */
@Composable
private fun QrDialog(
    url: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        text = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ConnectUrlQrCode(
                    url = url,
                    modifier = Modifier.padding(top = QR_DIALOG_TOP_PADDING, bottom = QR_DIALOG_BOTTOM_PADDING),
                )
            }
        },
    )
}

/** Offsets the button-row padding an empty `confirmButton` still contributes below the QR. */
private val QR_DIALOG_TOP_PADDING = 24.dp
private val QR_DIALOG_BOTTOM_PADDING = 12.dp

/**
 * Stateless, previewable More surface: hero, [InspectorUrlCard], and Export rows. Session/browser/
 * retention detail -- real capability the old screen had that the compact row list has no room
 * for -- lives behind the RUN/WEB/KEEP rows as inline `rememberSaveable` expand-in-place sections
 * rather than being dropped.
 */
@Suppress("LongParameterList") // One callback group + hero-collapse state; see doc above.
@Composable
internal fun MoreScreen(
    state: InspectorState,
    heroCollapsed: Boolean,
    onToggleHero: () -> Unit,
    onToggleTheme: () -> Unit,
    actions: MoreActions,
    modifier: Modifier = Modifier,
) {
    val colors = DevConsoleTheme.colors
    // A ticking clock, not a snapshot re-read on screen entry -- otherwise the uptime value freezes
    // at whatever it was the moment this screen last (re)opened instead of counting up live.
    var nowEpochMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowEpochMs = System.currentTimeMillis()
        }
    }
    val hero = rememberMoreHero(state, nowEpochMs)
    Column(modifier = modifier.fillMaxSize().background(colors.ground)) {
        InspectorTopArea(
            subLine = "Server, session and export",
            title = "More",
            actions = listOf(themeToggleTopAction(onToggleTheme)),
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 16.dp),
        ) {
            if (state.serverControlSupported) {
                item { ServerControlCard(hero.running, actions.onSetServerRunning, colors) }
                item { Spacer(Modifier.height(16.dp)) }
            }
            item {
                MoreHero(
                    info = hero,
                    collapsed = heroCollapsed,
                    onToggleCollapse = onToggleHero,
                    colors = colors,
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
            item { MoreUrlCard(state, hero.running, actions, colors) }
            if (!hero.running) {
                item { Spacer(Modifier.height(16.dp)) }
                item {
                    WarnNote(
                        "Captures are paused while the server is stopped — the SDK keeps its retained " +
                            "buffer but publishes nothing.",
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
            item { LanWarningBanner(state.browser) }
            item { GroupLabel("Export") }
            item { ExportHarRow(state, actions, colors) }
            item { ExportPostmanRow(state, actions, colors) }
            item { ExportZipRow(state, colors, actions) }
            item { CaptureScreenshotRow(colors, actions) }
            item { RetainedRunsRow(state, actions, colors) }
            item { BrowserSessionsRow(state, actions, colors) }
            item { RetentionRow(state, colors) }
            item { Spacer(Modifier.height(8.dp)) }
            item { GroupLabel("SDK health") }
            sdkHealthRows(state.health, colors)
        }
    }
}

/**
 * Real, computed More hero: [InspectorHealthUi.state] for running/stopped, active session uptime,
 * real browser count.
 */
private data class MoreHeroInfo(
    val running: Boolean,
    val value: String,
    val of: String,
    val sub: String,
    val bar: String,
)

/** Not `remember`-memoized: [nowEpochMs] ticks every second (see [MoreScreen]) so uptime counts up live. */
@Composable
private fun rememberMoreHero(
    state: InspectorState,
    nowEpochMs: Long,
): MoreHeroInfo {
    val running = state.health?.state == SERVER_STATE_RUNNING
    return if (running) moreHeroRunning(state, nowEpochMs) else moreHeroStopped()
}

private fun moreHeroRunning(
    state: InspectorState,
    nowEpochMs: Long,
): MoreHeroInfo {
    val active = state.sessions.firstOrNull { it.status == ACTIVE_SESSION_STATUS }
    val uptime = active?.let { formatUptime(nowEpochMs - it.startedAtEpochMs) } ?: "—"
    val attached = state.browser?.principals?.size ?: 0
    val sub =
        "Accepting browser connections on this Wi-Fi network. " +
            "$attached browser${if (attached == 1) "" else "s"} attached."
    return MoreHeroInfo(true, uptime, "uptime", sub, "uptime · server running")
}

private fun moreHeroStopped(): MoreHeroInfo =
    MoreHeroInfo(
        running = false,
        value = "—",
        of = "not listening",
        sub = "No port is open and nothing is being captured. Start the server to attach a browser.",
        bar = "server stopped",
    )

@Composable
private fun MoreHero(
    info: MoreHeroInfo,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    colors: DevConsoleColors,
) {
    val accentColor = if (info.running) colors.signal else colors.text3
    val valueColor = if (info.running) colors.signal else colors.muted
    if (collapsed) {
        HeroBar(
            value = info.value,
            label = info.bar,
            onExpand = onToggleCollapse,
            valueColor = valueColor,
            labelColor = accentColor,
        )
        return
    }
    HeroCard(
        label = if (info.running) "Server running" else "Server stopped",
        value = info.value,
        valueSuffix = info.of,
        subtitle = info.sub,
        labelColor = accentColor,
        valueColor = valueColor,
        valueFontFamily = FontFamily.Monospace,
        onCollapse = onToggleCollapse,
        // Start/Stop lives in the dedicated ServerControlCard above this hero, not in a hero CTA.
    )
}

/**
 * The dedicated Start/Stop pair at the top of More -- the same affordance the sample apps put on
 * their home screens, on the SDK's own surface. Rendered only when this build actually wires
 * [InspectorDataSource.setServerRunning]; a control that can never do anything is worse than none
 * (same honesty rule as the push Replay toast).
 */
@Composable
private fun ServerControlCard(
    running: Boolean,
    onSetServerRunning: (Boolean) -> Unit,
    colors: DevConsoleColors,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(if (running) colors.signalSoft else colors.surface2)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (running) "Server is running" else "Server is stopped",
            color = colors.ink,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InspectorPillButton(
                label = "Start server",
                onClick = { onSetServerRunning(true) },
                modifier = Modifier.weight(1f),
                enabled = !running,
                containerColor = if (running) colors.panel else colors.signal,
                contentColor = if (running) colors.muted else colors.signalInk,
            )
            InspectorPillButton(
                label = "Stop",
                onClick = { onSetServerRunning(false) },
                modifier = Modifier.weight(1f),
                enabled = running,
                outlined = true,
                contentColor = if (running) colors.ink else colors.muted,
            )
        }
    }
}

/** The URL card: running vs. stopped variants. */
@Composable
private fun MoreUrlCard(
    state: InspectorState,
    running: Boolean,
    actions: MoreActions,
    colors: DevConsoleColors,
) {
    val browser = state.browser
    when {
        // The device's live address no longer matches what the server bound to -- the URL
        // is dead either way, so say so honestly instead of showing it as if still connectable.
        running && browser?.bindAddressChanged == true ->
            StaleAddressUrlCard(state.serverControlSupported, actions, colors)
        running && browser?.sessionCodeUrl != null && browser.sessionCode != null ->
            RunningUrlCard(
                browser.sessionCodeUrl,
                browser.sessionCode,
                browser.sessionCodeRemainingTtlMs,
                actions,
                colors,
            )
        else -> StoppedUrlCard(state.serverControlSupported, actions, colors)
    }
}

@Composable
private fun StaleAddressUrlCard(
    serverControlSupported: Boolean,
    actions: MoreActions,
    colors: DevConsoleColors,
) {
    InspectorUrlCard(
        dotColor = colors.warn,
        dotPulsing = true,
        label = "Server address changed",
        url = "—",
        subtitle =
            "The device's network address changed while the server was running. Stop the " +
                "server, then start it again to reconnect at the new address.",
        actions =
            if (serverControlSupported) {
                listOf(
                    InspectorUrlAction(
                        "Stop server",
                        { actions.onSetServerRunning(false) },
                        colors.surface3,
                        colors.warn,
                    ),
                )
            } else {
                emptyList()
            },
    )
}

@Composable
private fun RunningUrlCard(
    url: String,
    code: String,
    remainingTtlMs: Long?,
    actions: MoreActions,
    colors: DevConsoleColors,
) {
    val rotation = remainingTtlMs?.let { " · rotates in ${formatDuration(it)}" }.orEmpty()
    InspectorUrlCard(
        dotColor = colors.signal,
        dotPulsing = true,
        label = "Open in a browser",
        url = url,
        subtitle = "Session code $code$rotation. Plaintext on your LAN — debug builds only.",
        actions = runningUrlCardActions(url, code, actions, colors),
    )
}

private fun runningUrlCardActions(
    url: String,
    code: String,
    actions: MoreActions,
    colors: DevConsoleColors,
): List<InspectorUrlAction> =
    listOf(
        InspectorUrlAction(
            "Copy URL",
            { actions.onCopyUrl(url) },
            colors.signal,
            colors.signalInk,
            // 1.5f, not 2f: at 2f the two secondary pills were squeezed until "Code" lost its "e".
            flex = 1.5f,
            icon = { UrlActionCopyIcon(colors.signalInk) },
        ),
        InspectorUrlAction(
            "Code",
            { actions.onCopyCode(code) },
            colors.surface3,
            colors.ink,
            icon = { UrlActionCopyIcon(colors.ink) },
        ),
        InspectorUrlAction(
            "QR",
            { actions.onShowQr(url) },
            colors.surface3,
            colors.ink,
            icon = { UrlActionEyeIcon(colors.ink) },
        ),
    )

@Composable
private fun StoppedUrlCard(
    serverControlSupported: Boolean,
    actions: MoreActions,
    colors: DevConsoleColors,
) {
    InspectorUrlCard(
        dotColor = colors.borderStrong,
        label = "No address",
        url = "—",
        subtitle = "The URL appears here once the server is running.",
        actions =
            if (serverControlSupported) {
                listOf(
                    InspectorUrlAction(
                        "Start server to get a URL",
                        { actions.onSetServerRunning(true) },
                        colors.surface3,
                        colors.muted,
                    ),
                )
            } else {
                emptyList()
            },
    )
}

@Composable
private fun UrlActionCopyIcon(tint: Color) {
    InspectorGlyphIcon(InspectorGlyph.Copy, contentDescription = null, tint = tint, size = 15.dp)
}

@Composable
private fun UrlActionEyeIcon(tint: Color) {
    ControlGlyphIcon(ControlGlyph.Eye, contentDescription = null, tint = tint, size = 15.dp)
}

/**
 * Prominent, non-dismissible warning shown only when the server is bound to LAN rather than
 * loopback -- see docs/THREAT_MODEL.md: the dashboard is plaintext HTTP, and on LAN anyone sharing
 * the network can observe the bearer token and every captured request. Restyled onto the token
 * palette (error-soft, same shape as [WarnNote]) rather than dropped -- this is a real safety
 * signal the app still needs.
 */
@Composable
private fun LanWarningBanner(browser: InspectorBrowserUi?) {
    if (browser?.binding != LAN_BINDING) return
    val colors = DevConsoleTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .clip(MaterialTheme.shapes.large)
                .background(colors.errorSoft)
                .padding(16.dp),
    ) {
        Text(
            "LAN MODE — UNENCRYPTED",
            color = colors.error,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.5.sp,
        )
        Text(
            LOCAL_NETWORK_HTTP_WARNING,
            color = colors.muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        browser.endpoint?.let { endpoint ->
            Text(
                "Bound at $endpoint",
                color = colors.muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** HAR/ZIP: real, direct export actions -- trail uses real counts (transactions/sessions), never a fabricated size. */
@Composable
private fun ExportHarRow(
    state: InspectorState,
    actions: MoreActions,
    colors: DevConsoleColors,
) {
    val selection = state.selectedTransactionIds
    val subtitle =
        if (selection.isEmpty()) {
            EXPORT_ALL_SUBTITLE
        } else {
            "Redacted · ${selection.size} selected transaction(s)"
        }
    TonalListRow(
        leadText = "HAR",
        leadColor = colors.put,
        leadContainerColor = colors.putSoft,
        title = "Export HAR",
        subtitle = subtitle,
        trailValue = state.transactions.size.toString(),
        trailValueColor = colors.ink,
        trailSubtitle = "txns",
        onClick = actions.onExportHar,
    )
}

/** Same selection semantics as HAR, as a Postman collection (pre-refresh capability, kept). */
@Composable
private fun ExportPostmanRow(
    state: InspectorState,
    actions: MoreActions,
    colors: DevConsoleColors,
) {
    val selection = state.selectedTransactionIds
    val subtitle =
        if (selection.isEmpty()) {
            EXPORT_ALL_SUBTITLE
        } else {
            "Redacted · ${selection.size} selected transaction(s)"
        }
    TonalListRow(
        leadText = "PM",
        leadColor = colors.put,
        leadContainerColor = colors.putSoft,
        title = "Export Postman collection",
        subtitle = subtitle,
        trailValue = state.transactions.size.toString(),
        trailValueColor = colors.ink,
        trailSubtitle = "txns",
        onClick = actions.onExportPostman,
    )
}

@Composable
private fun ExportZipRow(
    state: InspectorState,
    colors: DevConsoleColors,
    actions: MoreActions,
) {
    TonalListRow(
        leadText = "ZIP",
        leadColor = colors.put,
        leadContainerColor = colors.putSoft,
        title = "Export session",
        subtitle = "Timeline, captures, health snapshot",
        trailValue = state.sessions.size.toString(),
        trailValueColor = colors.ink,
        trailSubtitle = if (state.sessions.size == 1) "session" else "sessions",
        onClick = actions.onExportSessionZip,
    )
}

/**
 * Screenshot capture button, next to the export actions. Every [ScreenshotResult] variant
 * -- including [ScreenshotResult.Disabled] naming the exact config property to flip, and
 * [ScreenshotResult.SecureWindow] naming `FLAG_SECURE` -- surfaces through the shared flash-message
 * `LaunchedEffect` in [MoreRoute], so this row itself stays a plain, always-tappable action: capture
 * is the most sensitive artifact the SDK can emit and is off by default, so the row deliberately
 * doesn't pretend to know the host's policy up front and grey itself out -- it explains the real
 * reason after the fact instead, the same honesty rule the design spec states for every other
 * gated control in this product.
 */
@Composable
private fun CaptureScreenshotRow(
    colors: DevConsoleColors,
    actions: MoreActions,
) {
    TonalListRow(
        leadText = "SHOT",
        leadColor = colors.put,
        leadContainerColor = colors.putSoft,
        title = "Capture screenshot",
        subtitle = "The foreground screen, unredacted — off by default",
        trailValue = "Capture",
        trailValueColor = colors.put,
        onClick = actions.onCaptureScreenshot,
    )
}

/** RUN: real retained-run list, expand-in-place -- the old screen's [InspectorSessionUi] browsing, restyled. */
@Composable
private fun RetainedRunsRow(
    state: InspectorState,
    actions: MoreActions,
    colors: DevConsoleColors,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val oldest = state.sessions.minByOrNull { it.startedAtEpochMs }
    val subtitle =
        if (oldest == null) {
            "No retained runs yet."
        } else {
            val plural = if (state.sessions.size == 1) "" else "s"
            "${state.sessions.size} run$plural · oldest ${formatCaptureClockTime(oldest.startedAtEpochMs)}"
        }
    TonalListRow(
        leadText = "RUN",
        leadColor = colors.muted,
        leadContainerColor = colors.surface3,
        title = "Retained runs",
        subtitle = subtitle,
        trailValue = "",
        trailValueColor = colors.muted,
        trailContent = { TonalRowExpandChevron(expanded) },
        onClick = { expanded = !expanded },
    )
    if (expanded) {
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            state.sessions.forEach { session ->
                TonalListRow(
                    leadText = session.status.take(3),
                    leadColor = if (session.id == state.selectedSessionId) colors.signal else colors.muted,
                    leadContainerColor =
                        if (session.id == state.selectedSessionId) colors.signalSoft else colors.surface3,
                    title = session.label,
                    subtitle = "${session.recordCount} records · ${formatByteSize(session.estimatedBytes)}",
                    trailValue = formatCaptureClockTime(session.startedAtEpochMs),
                    trailValueColor = colors.muted,
                    containerColor = colors.surface3,
                    onClick = { actions.onSelectSession(session.id) },
                )
            }
        }
    }
}

/** WEB: real authenticated-browser list with revoke, expand-in-place -- the old screen's Session view, restyled. */
@Composable
private fun BrowserSessionsRow(
    state: InspectorState,
    actions: MoreActions,
    colors: DevConsoleColors,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val principals = state.browser?.principals.orEmpty()
    val subtitle =
        if (principals.isEmpty()) {
            "No browsers connected."
        } else {
            "${principals.size} connected · ${principals.take(2).joinToString { it.label }}"
        }
    TonalListRow(
        leadText = "WEB",
        leadColor = colors.muted,
        leadContainerColor = colors.surface3,
        title = "Browser sessions",
        subtitle = subtitle,
        trailValue = "",
        trailValueColor = colors.muted,
        trailContent = { TonalRowExpandChevron(expanded) },
        onClick = { expanded = !expanded },
    )
    if (expanded) {
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            principals.forEach { principal ->
                TonalListRow(
                    leadText = "REV",
                    leadColor = colors.error,
                    leadContainerColor = colors.errorSoft,
                    title = principal.label,
                    subtitle = "${principal.sourceIp} · expires ${formatCaptureClockTime(principal.expiresAtEpochMs)}",
                    trailValue = "revoke",
                    trailValueColor = colors.error,
                    containerColor = colors.surface3,
                    onClick = { actions.onRevoke(principal.id) },
                )
            }
        }
    }
}

/** KEEP: configured retention caps next to actual usage, expand-in-place -- the old Retention section, restyled. */
@Composable
private fun RetentionRow(
    state: InspectorState,
    colors: DevConsoleColors,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val retention = state.retention
    val subtitle =
        if (retention == null) {
            "Retention policy unavailable."
        } else {
            val bytes = formatByteSize(retention.maxBytes)
            val age = formatDuration(retention.maxAgeMs)
            "${retention.maxSessions} sessions · $bytes · $age"
        }
    TonalListRow(
        leadText = "KEEP",
        leadColor = colors.muted,
        leadContainerColor = colors.surface3,
        title = "Retention",
        subtitle = subtitle,
        trailValue = "",
        trailValueColor = colors.muted,
        trailContent = if (retention != null) ({ TonalRowExpandChevron(expanded) }) else null,
        onClick = if (retention != null) ({ expanded = !expanded }) else null,
    )
    if (expanded && retention != null) {
        val used = state.sessions.sumOf { it.estimatedBytes }
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            TonalListRow(
                leadText = "USE",
                leadColor = colors.muted,
                leadContainerColor = colors.surface3,
                title = "Storage used",
                subtitle = "${state.sessions.size} of ${retention.maxSessions} sessions",
                trailValue = formatByteSize(used),
                trailValueColor = colors.ink,
                containerColor = colors.surface3,
            )
        }
    }
}

/** Read-only mirror of the dashboard's SDK Health view, restyled as [TonalListRow]s. */
private fun LazyListScope.sdkHealthRows(
    health: InspectorHealthUi?,
    colors: DevConsoleColors,
) {
    if (health == null) {
        item { SectionEmptyText("SDK health is unavailable.") }
        return
    }
    item { HealthTonalRow("State", health.state, colors) }
    item { HealthTonalRow("Initializations", health.initializationCount.toString(), colors) }
    item { HealthTonalRow("Published events", health.publishedEventCount.toString(), colors) }
    item { HealthTonalRow("Dropped events", health.droppedEventCount.toString(), colors) }
}

@Composable
private fun HealthTonalRow(
    label: String,
    value: String,
    colors: DevConsoleColors,
) {
    TonalListRow(
        leadText = label.take(3).uppercase(Locale.US),
        leadColor = colors.muted,
        leadContainerColor = colors.surface3,
        title = label,
        subtitle = "SDK health counter",
        trailValue = value,
        trailValueColor = colors.ink,
    )
}

private fun formatDuration(ms: Long): String =
    when {
        ms >= MS_PER_DAY -> "${ms / MS_PER_DAY}d"
        ms >= MS_PER_HOUR -> "${ms / MS_PER_HOUR}h"
        ms >= MS_PER_MINUTE -> "${ms / MS_PER_MINUTE}m"
        else -> "${ms / MS_PER_SECOND}s"
    }

/** `HH:MM:SS` uptime clock, computed from the active session's real start time. */
private fun formatUptime(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / MS_PER_SECOND
    val hours = totalSeconds / (MS_PER_HOUR / MS_PER_SECOND)
    val minutes = (totalSeconds % (MS_PER_HOUR / MS_PER_SECOND)) / (MS_PER_MINUTE / MS_PER_SECOND)
    val seconds = totalSeconds % (MS_PER_MINUTE / MS_PER_SECOND)
    return "%02d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
}

/** Hand-built fixture for [MoreScreenPreview]. */
private fun moreScreenPreviewState() =
    InspectorState(
        available = true,
        serverControlSupported = true,
        sessions =
            listOf(
                InspectorSessionUi(
                    id = "session-1",
                    startedAtEpochMs = System.currentTimeMillis() - MS_PER_HOUR,
                    label = "Run 1",
                    status = "ACTIVE",
                    recordCount = 412,
                    estimatedBytes = 2_400_000,
                ),
            ),
        health =
            InspectorHealthUi(
                state = SERVER_STATE_RUNNING,
                initializationCount = 1,
                publishedEventCount = 412,
                droppedEventCount = 0,
            ),
        browser =
            InspectorBrowserUi(
                binding = LAN_BINDING,
                endpoint = "192.168.1.42:8787",
                principals = listOf(InspectorBrowserPrincipalUi("p-1", "Chrome on Mac", "192.168.1.10", 0)),
                sessionCodeUrl = "http://192.168.1.42:8787/#s=ABCD1234",
                sessionCode = "ABCD1234",
            ),
        retention = InspectorRetentionUi(maxSessions = 5, maxAgeMs = MS_PER_DAY * 7, maxBytes = 50_000_000),
    )

private fun moreScreenPreviewActions() =
    MoreActions(
        onSetServerRunning = {},
        onCopyUrl = {},
        onCopyCode = {},
        onShowQr = {},
        onExportHar = {},
        onExportPostman = {},
        onExportSessionZip = {},
        onCaptureScreenshot = {},
        onRevoke = {},
        onSelectSession = {},
    )

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun MoreScreenPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        MoreScreen(
            state = moreScreenPreviewState(),
            heroCollapsed = false,
            onToggleHero = {},
            onToggleTheme = {},
            actions = moreScreenPreviewActions(),
        )
    }
}
