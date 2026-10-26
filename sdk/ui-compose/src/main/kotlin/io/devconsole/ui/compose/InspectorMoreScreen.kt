/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "TooManyFunctions", "UnusedPrivateMember")

package io.devconsole.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.devconsole.api.ScreenshotResult
import io.devconsole.ui.compose.qr.ConnectUrlQrCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
private const val SERVER_STATE_RUNNING = "Running"
private const val SERVER_STATE_PERMISSION_REQUIRED = "PermissionRequired"
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
    val pendingServerStartPermission by viewModel.serverStartPermission.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var qrUrl by remember { mutableStateOf<String?>(null) }
    val showMessage: (String) -> Unit = { message -> scope.launch { snackbarHostState.showSnackbar(message) } }

    // Session codes expire and the bind address can change independently of this screen. Refresh
    // on each show and poll only while started so a hidden keep-alive destination stays idle.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.dispatch(InspectorAction.Refresh)
            while (true) {
                delay(MORE_SCREEN_REFRESH_INTERVAL_MS)
                viewModel.dispatch(InspectorAction.Refresh)
            }
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
        promptNeeded = state.keepAlivePromptNeeded && lifecycleState.isAtLeast(Lifecycle.State.STARTED),
        snackbarHostState = snackbarHostState,
        onPermissionResult = { viewModel.dispatch(InspectorAction.NotificationPermissionGranted) },
    )
    val legacyPermissionRequired = state.health?.state == SERVER_STATE_PERMISSION_REQUIRED
    val serverStartPermission =
        pendingServerStartPermission ?: LOCAL_NETWORK_PERMISSION.takeIf { legacyPermissionRequired }
    ServerPermissionPromptEffect(
        permission = serverStartPermission.takeIf { lifecycleState.isAtLeast(Lifecycle.State.STARTED) },
        snackbarHostState = snackbarHostState,
        onPermissionResult = { granted ->
            if (pendingServerStartPermission != null) {
                viewModel.dispatch(InspectorAction.ServerStartPermissionResult(granted))
            } else if (granted) {
                // Compatibility path for a host/API start that reached PermissionRequired before
                // this SDK-owned preflight was installed.
                viewModel.dispatch(InspectorAction.SetServerRunning(true))
            } else {
                viewModel.dispatch(InspectorAction.Refresh)
            }
        },
    )

    Box(modifier = modifier.fillMaxSize()) {
        MoreScreen(
            state = state,
            heroCollapsed = true,
            onToggleHero = {},
            onToggleTheme = onToggleTheme,
            actions = rememberMoreActions(viewModel, clipboard, showMessage) { url -> qrUrl = url },
        )
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
    // Dialogs own a separate window, so unplacing the destination alone cannot hide them.
    qrUrl?.takeIf { lifecycleState.isAtLeast(Lifecycle.State.STARTED) }?.let { url ->
        QrDialog(url, onDismiss = { qrUrl = null })
    }
}

/**
 * Grouped callbacks [MoreScreen] dispatches. [onSetServerRunning] takes the *desired* next state
 * (the server button already knows the current one) rather than a bare toggle, since its own
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
    val onSetLocationPingStream: (String, Boolean) -> Unit = { _, _ -> },
)

@Composable
private fun rememberMoreActions(
    viewModel: InspectorViewModel,
    clipboard: ClipboardManager,
    showMessage: (String) -> Unit,
    onShowQr: (String) -> Unit,
): MoreActions {
    val currentShowMessage by rememberUpdatedState(showMessage)
    val currentOnShowQr by rememberUpdatedState(onShowQr)
    return remember(viewModel, clipboard) {
        MoreActions(
            onSetServerRunning = { running -> viewModel.dispatch(InspectorAction.SetServerRunning(running)) },
            onCopyUrl = { url ->
                clipboard.setText(AnnotatedString(url))
                currentShowMessage("$url copied")
            },
            onCopyCode = { code ->
                clipboard.setText(AnnotatedString(code))
                currentShowMessage("Session code $code copied")
            },
            onShowQr = { url -> currentOnShowQr(url) },
            onExportHar = { viewModel.dispatch(InspectorAction.ExportHar) },
            onExportPostman = { viewModel.dispatch(InspectorAction.ExportPostman) },
            onExportSessionZip = { viewModel.dispatch(InspectorAction.ExportSessionZip) },
            onCaptureScreenshot = { viewModel.dispatch(InspectorAction.CaptureScreenshot) },
            onRevoke = { id -> viewModel.dispatch(InspectorAction.RevokePrincipal(id)) },
            onSelectSession = { id -> viewModel.dispatch(InspectorAction.SelectSession(id)) },
            onSetLocationPingStream = { name, enabled ->
                viewModel.dispatch(InspectorAction.SetLocationPingStreamEnabled(name, enabled))
            },
        )
    }
}

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

/** Grouped More surface with expandable session, retention and SDK health details. */
@Suppress("LongParameterList", "UNUSED_PARAMETER") // Preserve the destination signature.
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
    val running = state.health?.state == SERVER_STATE_RUNNING
    Column(modifier = modifier.fillMaxSize().background(colors.ground)) {
        InspectorTopArea(
            subLine = "",
            title = "More",
            actions = listOf(themeToggleTopAction(onToggleTheme)),
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "more_browser_dashboard", contentType = "browser_dashboard") {
                BrowserDashboardPanel(state, running, actions, colors)
            }
            item(key = "more_export_title", contentType = "panel_title") {
                MorePanelTitle("Export")
            }
            item(key = "more_export_har", contentType = "export_row") {
                ExportHarRow(state, actions)
            }
            item(key = "more_export_div_1", contentType = "divider") {
                GroupedPanelDivider()
            }
            item(key = "more_export_postman", contentType = "export_row") {
                ExportPostmanRow(state, actions)
            }
            item(key = "more_export_div_2", contentType = "divider") {
                GroupedPanelDivider()
            }
            item(key = "more_export_zip", contentType = "export_row") {
                ExportZipRow(state, actions)
            }
            item(key = "more_export_div_3", contentType = "divider") {
                GroupedPanelDivider()
            }
            item(key = "more_export_screenshot", contentType = "export_row") {
                CaptureScreenshotRow(actions)
            }
            item(key = "more_export_div_4", contentType = "divider") {
                GroupedPanelDivider()
            }
            item(key = "more_export_runs", contentType = "export_row") {
                RetainedRunsRow(state, actions, colors)
            }
            if (state.locationPingStreams.isNotEmpty()) {
                item(key = "more_location_pings_title", contentType = "panel_title") {
                    MorePanelTitle("Location pings")
                }
                state.locationPingStreams.forEachIndexed { position, stream ->
                    if (position > 0) {
                        item(key = "more_location_pings_div_${stream.name}", contentType = "divider") {
                            GroupedPanelDivider()
                        }
                    }
                    item(key = "more_location_pings_${stream.name}", contentType = "settings_row") {
                        LocationPingStreamRow(stream, actions)
                    }
                }
            }
            item(key = "more_settings_title", contentType = "panel_title") {
                MorePanelTitle("Settings")
            }
            item(key = "more_settings_health", contentType = "settings_row") {
                SdkHealthRow(state.health)
            }
            item(key = "more_settings_div_1", contentType = "divider") {
                GroupedPanelDivider()
            }
            item(key = "more_settings_retention", contentType = "settings_row") {
                RetentionRow(state, colors)
            }
            item(key = "more_settings_div_2", contentType = "divider") {
                GroupedPanelDivider()
            }
            item(key = "more_settings_sessions", contentType = "settings_row") {
                BrowserSessionsRow(state, actions, colors)
            }
        }
    }
}

@Composable
private fun MorePanelTitle(title: String) {
    Text(
        title,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        color = DevConsoleTheme.colors.ink,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/** Plain grouped row with a readable label and a muted mono value. */
@Composable
private fun MorePanelRow(
    title: String,
    meta: String = "",
    description: String = "",
    onClick: (() -> Unit)? = null,
    metaContent: (@Composable () -> Unit)? = null,
) {
    val colors = DevConsoleTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .semantics(mergeDescendants = true) {
                    if (description.isNotBlank()) contentDescription = "$title. $description"
                }.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, modifier = Modifier.weight(1f), color = colors.ink, fontSize = 16.sp)
        if (metaContent != null) {
            metaContent()
        } else if (meta.isNotBlank()) {
            Text(meta, color = colors.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        if (onClick != null) {
            DevConsoleIcon(R.drawable.devconsole_ic_chevron_right, null, Modifier, colors.muted, 18.dp)
        }
    }
}

@Composable
private fun BrowserDashboardPanel(
    state: InspectorState,
    running: Boolean,
    actions: MoreActions,
    colors: DevConsoleColors,
) {
    val browser = state.browser
    val stale = running && browser?.bindAddressChanged == true
    val url = browser?.sessionCodeUrl?.takeIf { running && !stale && browser.sessionCode != null }
    GroupedPanel {
        MorePanelTitle("Browser dashboard")
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ServerStatusLine(running, browser?.endpoint, colors)
            if (stale) {
                WarnNote(
                    "The device's network address changed while the server was running. Stop the " +
                        "server, then start it again to reconnect at the new address.",
                )
            }
            if (url != null) {
                val uriHandler = LocalUriHandler.current
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ConnectUrlQrCode(
                        url,
                        modifier =
                            Modifier
                                .size(96.dp)
                                .clickable(onClickLabel = "QR", role = Role.Button) { actions.onShowQr(url) }
                                .semantics { contentDescription = "QR" },
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InspectorUrlCard(
                            dotColor = colors.signal,
                            label = "",
                            url = url,
                            subtitle = "",
                            actions =
                                listOf(
                                    InspectorUrlAction(
                                        "Copy URL",
                                        { actions.onCopyUrl(url) },
                                        colors.panel,
                                        colors.muted,
                                        icon = { UrlActionCopyIcon(colors.muted) },
                                    ),
                                    InspectorUrlAction(
                                        "Open URL",
                                        { uriHandler.openUri(url) },
                                        colors.panel,
                                        colors.muted,
                                        icon = {
                                            DevConsoleIcon(
                                                R.drawable.devconsole_ic_open_external,
                                                null,
                                                Modifier,
                                                colors.muted,
                                                18.dp,
                                            )
                                        },
                                    ),
                                ),
                        )
                        if (state.serverControlSupported) ServerControlButton(true, actions)
                    }
                }
                browser?.let { SessionCodeLine(it, actions, colors) }
            } else {
                if (!stale) {
                    Text(
                        if (running) {
                            "Waiting for a browser URL."
                        } else {
                            "The URL appears here once the server is running."
                        },
                        color = colors.muted,
                        fontSize = 13.sp,
                    )
                }
                if (state.serverControlSupported) ServerControlButton(running, actions)
            }
            LanWarningBanner(browser)
        }
    }
}

@Composable
private fun ServerStatusLine(
    running: Boolean,
    endpoint: String?,
    colors: DevConsoleColors,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (running) colors.signal else colors.muted))
        Text(
            buildAnnotatedString {
                append(if (running) "Running" else "Stopped")
                endpoint?.takeIf { running }?.let {
                    append(" on ")
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(it) }
                }
            },
            color = colors.muted,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun SessionCodeLine(
    browser: InspectorBrowserUi,
    actions: MoreActions,
    colors: DevConsoleColors,
) {
    val code = browser.sessionCode?.takeIf { it.isNotBlank() } ?: return
    val rotation = browser.sessionCodeRemainingTtlMs?.let { " · rotates in ${formatDuration(it)}" }.orEmpty()
    Text(
        "Session code $code$rotation",
        modifier = Modifier.clickable(onClickLabel = "Code") { actions.onCopyCode(code) },
        color = colors.muted,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
    )
}

@Composable
private fun ServerControlButton(
    running: Boolean,
    actions: MoreActions,
) {
    val colors = DevConsoleTheme.colors
    if (running) {
        OutlinedButton(
            onClick = { actions.onSetServerRunning(false) },
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, colors.signal),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.signal),
        ) {
            Text("Stop server", fontSize = 14.sp)
        }
    } else {
        Button(
            onClick = { actions.onSetServerRunning(true) },
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.signal, contentColor = colors.signalInk),
        ) {
            Text("Start server", fontSize = 14.sp)
        }
    }
}

@Composable
private fun SdkHealthRow(health: InspectorHealthUi?) {
    val colors = DevConsoleTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    MorePanelRow(
        "SDK health",
        onClick = { expanded = !expanded },
        metaContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    health?.state ?: "Unavailable",
                    color = if (health?.state == SERVER_STATE_RUNNING) colors.signal else colors.muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                health?.let {
                    Text(
                        " · ${it.droppedEventCount} dropped",
                        color = colors.muted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
        },
    )
    if (expanded) {
        if (health == null) {
            MorePanelRow("SDK health is unavailable.")
        } else {
            GroupedPanelDivider()
            HealthTonalRow("State", health.state)
            GroupedPanelDivider()
            HealthTonalRow("Initializations", health.initializationCount.toString())
            GroupedPanelDivider()
            HealthTonalRow("Published events", health.publishedEventCount.toString())
            GroupedPanelDivider()
            HealthTonalRow("Dropped events", health.droppedEventCount.toString())
        }
    }
}

@Composable
private fun UrlActionCopyIcon(tint: Color) {
    InspectorGlyphIcon(InspectorGlyph.Copy, contentDescription = null, tint = tint, size = 15.dp)
}

/** Preserve the LAN-only caveat without introducing another panel. */
@Composable
private fun LanWarningBanner(browser: InspectorBrowserUi?) {
    if (browser?.binding != LAN_BINDING) return
    Text(
        "Plain HTTP on your Wi-Fi. Use loopback on shared networks.",
        color = DevConsoleTheme.colors.muted,
        fontSize = 13.sp,
    )
}

/** HAR/ZIP: real, direct export actions -- trail uses real counts (transactions/sessions), never a fabricated size. */
@Composable
private fun ExportHarRow(
    state: InspectorState,
    actions: MoreActions,
) {
    val selection = state.selectedTransactionIds
    val subtitle =
        if (selection.isEmpty()) {
            EXPORT_ALL_SUBTITLE
        } else {
            "Redacted · ${selection.size} selected transaction(s)"
        }
    MorePanelRow(
        title = "HAR",
        description = subtitle,
        meta = if (selection.isEmpty()) "${state.transactions.size} txns" else "${selection.size} selected",
        onClick = actions.onExportHar,
    )
}

/** Same selection semantics as HAR, as a Postman collection (pre-refresh capability, kept). */
@Composable
private fun ExportPostmanRow(
    state: InspectorState,
    actions: MoreActions,
) {
    val selection = state.selectedTransactionIds
    val subtitle =
        if (selection.isEmpty()) {
            EXPORT_ALL_SUBTITLE
        } else {
            "Redacted · ${selection.size} selected transaction(s)"
        }
    MorePanelRow(
        title = "Postman collection",
        description = subtitle,
        meta = if (selection.isEmpty()) "${state.transactions.size} txns" else "${selection.size} selected",
        onClick = actions.onExportPostman,
    )
}

@Composable
private fun ExportZipRow(
    state: InspectorState,
    actions: MoreActions,
) {
    MorePanelRow(
        title = "Session ZIP",
        description = "Timeline, captures, health snapshot",
        meta = "${state.sessions.size} ${if (state.sessions.size == 1) "session" else "sessions"}",
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
private fun CaptureScreenshotRow(actions: MoreActions) {
    MorePanelRow(
        title = "Capture screenshot",
        description = "The foreground screen, unredacted — off by default",
        onClick = actions.onCaptureScreenshot,
    )
}

@Composable
private fun LocationPingStreamRow(
    stream: InspectorLocationPingStreamUi,
    actions: MoreActions,
) {
    MorePanelRow(
        title = stream.name,
        description = "${stream.path} · ${if (stream.enabled) "recording" else "off"}",
        metaContent = {
            Switch(
                checked = stream.enabled,
                onCheckedChange = { checked -> actions.onSetLocationPingStream(stream.name, checked) },
                modifier =
                    Modifier.semantics {
                        contentDescription = "${if (stream.enabled) "Stop" else "Start"} recording ${stream.name} pings"
                    },
            )
        },
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
    MorePanelRow(
        title = "Retained runs",
        description = subtitle,
        meta = state.sessions.size.toString(),
        onClick = { expanded = !expanded },
    )
    if (expanded) {
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            state.sessions.forEach { session ->
                GroupedPanelDivider()
                TonalListRow(
                    leadText = "",
                    leadColor = colors.muted,
                    leadContainerColor = colors.panel,
                    title = session.label,
                    subtitle = "${session.status} · ${session.recordCount} records · ${formatByteSize(
                        session.estimatedBytes,
                    )}",
                    trailValue = formatCaptureClockTime(session.startedAtEpochMs),
                    trailValueColor = colors.muted,
                    trailContent =
                        if (session.id == state.selectedSessionId) {
                            (
                                {
                                    InspectorGlyphIcon(
                                        InspectorGlyph.Check,
                                        "Selected",
                                        tint = colors.ink,
                                        size = 18.dp,
                                    )
                                }
                            )
                        } else {
                            null
                        },
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
    MorePanelRow(
        title = "Browser sessions",
        description = subtitle,
        meta = "${principals.size} connected",
        onClick = { expanded = !expanded },
    )
    if (expanded) {
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            principals.forEach { principal ->
                GroupedPanelDivider()
                TonalListRow(
                    leadText = "",
                    leadColor = colors.muted,
                    leadContainerColor = colors.panel,
                    title = principal.label,
                    subtitle = "${principal.sourceIp} · expires ${formatCaptureClockTime(principal.expiresAtEpochMs)}",
                    trailValue = "revoke",
                    trailValueColor = colors.error,
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
    MorePanelRow(
        title = "Retention",
        description = subtitle,
        meta = retention?.let { formatDuration(it.maxAgeMs) }.orEmpty(),
        onClick = if (retention != null) ({ expanded = !expanded }) else null,
    )
    if (expanded && retention != null) {
        val used = state.sessions.sumOf { it.estimatedBytes }
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            GroupedPanelDivider()
            MorePanelRow("Session limit", retention.maxSessions.toString())
            GroupedPanelDivider()
            MorePanelRow("Storage limit", formatByteSize(retention.maxBytes))
            GroupedPanelDivider()
            TonalListRow(
                leadText = "",
                leadColor = colors.muted,
                leadContainerColor = colors.panel,
                title = "Storage used",
                subtitle = "${state.sessions.size} of ${retention.maxSessions} sessions",
                trailValue = formatByteSize(used),
                trailValueColor = colors.ink,
            )
        }
    }
}

@Composable
private fun HealthTonalRow(
    label: String,
    value: String,
) {
    MorePanelRow(title = label, meta = value)
}

private fun formatDuration(ms: Long): String =
    when {
        ms >= MS_PER_HOUR -> "${ms / MS_PER_HOUR} h"
        ms >= MS_PER_MINUTE -> "${ms / MS_PER_MINUTE}m"
        else -> "${ms / MS_PER_SECOND}s"
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
                endpoint = "192.168.0.104:8080",
                principals = listOf(InspectorBrowserPrincipalUi("p-1", "Chrome on Mac", "192.168.1.10", 0)),
                sessionCodeUrl = "http://192.168.0.104:8080/#s=ABCD1234",
                sessionCode = "ABCD1234",
            ),
        retention = InspectorRetentionUi(maxSessions = 5, maxAgeMs = MS_PER_DAY, maxBytes = 50_000_000),
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
