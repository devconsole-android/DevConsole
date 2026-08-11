/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "TooManyFunctions")
// TooManyFunctions: this file groups every small per-tab hero/row/empty-state composable and helper
// (Sockets/Push/Logs) that Observe's tab content dispatches to -- splitting it further would scatter
// closely related, single-purpose UI pieces across files for no readability gain.
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.devconsole.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.devconsole.api.CaptureCategory
import java.util.Locale

/** Sockets tab: open-connection hero + a flattened, newest-first "Recent frames" list. */
@Composable
internal fun SocketsTabContent(
    state: InspectorState,
    ui: ObserveUiState,
    actions: ObserveActions,
) {
    val colors = DevConsoleTheme.colors
    if (state.sockets.isEmpty()) {
        ObserveTabEmptyState(state.socketsEmptyStateMessage())
        return
    }
    val recentFrames = rememberRecentFrames(state.sockets)
    // Frames have no id of their own -- key on (socket, timestamp), deduped for the rare case of two
    // frames landing in the same millisecond on the same socket (see [uniqueKeys]'s own doc for why
    // this stays stable across new frames arriving, unlike keying on list position).
    val frameKeys =
        remember(recentFrames) {
            uniqueKeys(recentFrames) { (socket, frame) -> "${socket.id}@${frame.timestampEpochMs}" }
        }
    val searchedIndices =
        remember(recentFrames, ui.socketsSearch) {
            val query = ui.socketsSearch.trim().lowercase(Locale.US)
            recentFrames.indices.filter { index ->
                if (query.isEmpty()) {
                    true
                } else {
                    val (socket, frame) = recentFrames[index]
                    socket.url.lowercase(Locale.US).contains(query) ||
                        frame.preview?.lowercase(Locale.US)?.contains(query) == true
                }
            }
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 12dp start/end/top padding, extra bottom clearance for every Observe tab's scroll container.
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = EvidenceFabScrollClearance),
    ) {
        stickyHeader {
            InspectorSearchBar(
                query = ui.socketsSearch,
                onQueryChange = actions.onSocketsSearchChange,
                placeholder = "Search host, path or frames",
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
        item { SocketsHero(state.sockets, ui, actions, colors) }
        item { Spacer(Modifier.height(16.dp)) }
        item { GroupLabel("Recent frames") }
        itemsIndexed(searchedIndices, key = { _, index -> frameKeys[index] }) { _, index ->
            val (socket, frame) = recentFrames[index]
            SocketFrameRow(socket, frame, colors) { actions.onOpenFrameDetail(socket, frame) }
        }
    }
}

@Composable
private fun SocketsHero(
    sockets: List<InspectorSocketUi>,
    ui: ObserveUiState,
    actions: ObserveActions,
    colors: DevConsoleColors,
) {
    val openCount = sockets.count { it.state == "OPEN" }
    if (ui.socketsHeroCollapsed) {
        HeroBar(
            value = openCount.toString(),
            label = "of ${sockets.size} connections open",
            onExpand = actions.onToggleSocketsHero,
            containerColor = colors.surface2,
            valueColor = colors.signal,
            labelColor = colors.text3,
        )
        return
    }
    val loudest = sockets.maxByOrNull { it.receivedCount }
    val errorCount = sockets.count { it.error != null }
    val sub =
        buildString {
            if (loudest != null && loudest.receivedCount > 0) {
                val path = loudest.url.removePrefix("wss://").removePrefix("ws://")
                append("$path is the loudest at ${loudest.receivedCount} frames received.")
            } else {
                append("No frames received yet.")
            }
            if (errorCount > 0) {
                append(" $errorCount socket${if (errorCount == 1) "" else "s"} in error.")
            }
        }
    HeroCard(
        label = "Open connections",
        value = openCount.toString(),
        valueSuffix = "of ${sockets.size}",
        subtitle = sub,
        containerColor = colors.surface2,
        labelColor = colors.text3,
        valueColor = colors.signal,
        onCollapse = actions.onToggleSocketsHero,
    )
}

@Composable
private fun SocketFrameRow(
    socket: InspectorSocketUi,
    frame: InspectorSocketFrameUi,
    colors: DevConsoleColors,
    onClick: () -> Unit,
) {
    val received = frame.direction == "RECEIVED"
    val leadColor = if (received) colors.put else colors.signal
    val leadBg = if (received) colors.putSoft else colors.signalSoft
    val protocolBadge = socket.protocolBadge()
    val path = socket.url.replace(Regex("^wss?://[^/]+"), "").ifEmpty { socket.url }
    // An MQTT frame's topic is what an operator scanning this list actually wants to see -- the
    // socket's own URL is just the broker address, identical across every message on the connection.
    val title = frame.topic ?: path
    Column(modifier = Modifier.fillMaxWidth()) {
        TonalListRow(
            leadText = if (received) "↓" else "↑",
            leadColor = leadColor,
            leadContainerColor = leadBg,
            title = title,
            subtitle = "$protocolBadge · ${frame.frameType} · ${frame.preview.orEmpty().take(34)}",
            trailValue = socketFrameSizeLabel(frame),
            trailValueColor = colors.ink,
            trailSubtitle = formatCaptureClockTime(frame.timestampEpochMs),
            containerColor = Color.Transparent,
            onClick = onClick,
        )
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(start = 76.dp),
            color = colors.line
        )
    }
}

/** "MQTT" for an MQTT-protocol connection, "WS" otherwise -- shown as a prefix in [SocketFrameRow]'s subtitle. */
private fun InspectorSocketUi.protocolBadge(): String = if (protocol == "mqtt") "MQTT" else "WS"

/**
 * Sockets-tab empty-state copy: names the one protocol actually enabled when only SOCKET or only
 * MQTT is on, per [io.devconsole.api.CaptureCategory] -- default (both, or neither individually
 * distinguishable) keeps the original WebSocket-first wording since that is still the common case.
 */
private fun InspectorState.socketsEmptyStateMessage(): String =
    if (captures(CaptureCategory.MQTT) && !captures(CaptureCategory.SOCKET)) {
        "No MQTT connections captured yet."
    } else {
        "No WebSocket connections captured yet."
    }

/** Push tab: delivered-count hero + a lifecycle-ordered event list. */
@Composable
internal fun PushTabContent(
    state: InspectorState,
    ui: ObserveUiState,
    actions: ObserveActions,
) {
    val colors = DevConsoleTheme.colors
    if (state.pushEvents.isEmpty()) {
        ObserveTabEmptyState("No push events captured yet.")
        return
    }
    // Push events have no id of their own either -- key on (messageId or "no-id", receivedAt), same
    // dedupe/stability rationale as the sockets tab's frame keys.
    val pushKeys =
        remember(state.pushEvents) {
            uniqueKeys(state.pushEvents) { push -> "${push.messageId ?: "no-id"}@${push.receivedAtEpochMs}" }
        }
    val searchedIndices =
        remember(state.pushEvents, ui.pushSearch) {
            val query = ui.pushSearch.trim().lowercase(Locale.US)
            state.pushEvents.indices.filter { index ->
                if (query.isEmpty()) {
                    true
                } else {
                    val push = state.pushEvents[index]
                    push.provider.lowercase(Locale.US).contains(query) ||
                        push.messageId?.lowercase(Locale.US)?.contains(query) == true ||
                        push.dataPreview.values.any { it.lowercase(Locale.US).contains(query) }
                }
            }
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 12dp start/end/top padding, extra bottom clearance for every Observe tab's scroll container.
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = EvidenceFabScrollClearance),
    ) {
        stickyHeader {
            InspectorSearchBar(
                query = ui.pushSearch,
                onQueryChange = actions.onPushSearchChange,
                placeholder = "Search provider, message id or data",
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
        item { PushHero(state.pushEvents, ui, actions, colors) }
        item { Spacer(Modifier.height(16.dp)) }
        item { GroupLabel("Lifecycle") }
        itemsIndexed(searchedIndices, key = { _, index -> pushKeys[index] }) { _, index ->
            val push = state.pushEvents[index]
            PushRow(push, colors) { actions.onOpenPushDetail(push) }
        }
    }
}

@Composable
private fun PushHero(
    pushEvents: List<InspectorPushUi>,
    ui: ObserveUiState,
    actions: ObserveActions,
    colors: DevConsoleColors,
) {
    if (ui.pushHeroCollapsed) {
        HeroBar(
            value = pushEvents.size.toString(),
            label = "push events delivered",
            onExpand = actions.onTogglePushHero,
            containerColor = colors.surface2,
            valueColor = colors.ink,
            labelColor = colors.text3,
        )
        return
    }
    val simulatedCount = pushEvents.count { it.simulated }
    val sub =
        if (simulatedCount > 0) {
            val pronoun = if (simulatedCount == 1) "it appears" else "they appear"
            "$simulatedCount simulated from the dashboard — labelled everywhere $pronoun."
        } else {
            "All delivered from a real push provider this session."
        }
    HeroCard(
        label = "Delivered this session",
        value = pushEvents.size.toString(),
        valueSuffix = "events",
        subtitle = sub,
        containerColor = colors.surface2,
        labelColor = colors.text3,
        valueColor = colors.ink,
        onCollapse = actions.onTogglePushHero,
    )
}

@Composable
private fun PushRow(
    push: InspectorPushUi,
    colors: DevConsoleColors,
    onClick: () -> Unit,
) {
    val leadColor = if (push.simulated) colors.warn else colors.signal
    val leadBg = if (push.simulated) colors.warnSoft else colors.signalSoft
    val title = push.dataPreview["title"] ?: push.messageId ?: "Push notification"
    Column(modifier = Modifier.fillMaxWidth()) {
        TonalListRow(
            leadText = push.provider.uppercase(Locale.US),
            leadColor = leadColor,
            leadContainerColor = leadBg,
            title = title,
            subtitle = "${push.messageId ?: "no id"} · ${if (push.simulated) "simulated" else "captured"}",
            trailValue = pushLifecycleShortLabel(push.lifecycle),
            trailValueColor = colors.muted,
            trailSubtitle = formatCaptureClockTimeShort(push.receivedAtEpochMs),
            containerColor = Color.Transparent,
            onClick = onClick,
        )
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(start = 76.dp),
            color = colors.line
        )
    }
}

/** Logs tab: warnings+errors hero + a newest-first event list. */
@Composable
internal fun LogsTabContent(
    state: InspectorState,
    ui: ObserveUiState,
    actions: ObserveActions,
) {
    val colors = DevConsoleTheme.colors
    if (state.logs.isEmpty()) {
        ObserveTabEmptyState("No logs, crashes, or ANRs captured yet.")
        return
    }
    val searched =
        remember(state.logs, ui.logsSearch) {
            val query = ui.logsSearch.trim().lowercase(Locale.US)
            if (query.isEmpty()) {
                state.logs
            } else {
                state.logs.filter { log ->
                    log.summary.lowercase(Locale.US).contains(query) ||
                        log.source.lowercase(Locale.US).contains(query) ||
                        log.detail?.lowercase(Locale.US)?.contains(query) == true
                }
            }
        }
    val rowsNewestFirst = searched.asReversed()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 12dp start/end/top padding, extra bottom clearance for every Observe tab's scroll container.
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = EvidenceFabScrollClearance),
    ) {
        stickyHeader {
            InspectorSearchBar(
                query = ui.logsSearch,
                onQueryChange = actions.onLogsSearchChange,
                placeholder = "Search message, source or detail",
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
        item { LogsHero(state.logs, ui, actions, colors) }
        item { Spacer(Modifier.height(16.dp)) }
        item { GroupLabel("Newest first") }
        itemsIndexed(rowsNewestFirst, key = { _, log -> log.id }) { _, log ->
            LogRow(log, colors) { actions.onOpenLogDetail(log.id) }
        }
    }
}

@Composable
private fun LogsHero(
    logs: List<InspectorLogUi>,
    ui: ObserveUiState,
    actions: ObserveActions,
    colors: DevConsoleColors,
) {
    val flagged = logs.filter { isWarnOrErrorLevel(it.kind) }
    if (ui.logsHeroCollapsed) {
        HeroBar(
            value = flagged.size.toString(),
            label = "of ${logs.size} events need attention",
            onExpand = actions.onToggleLogsHero,
            containerColor = colors.warnSoft,
            valueColor = colors.warn,
            labelColor = colors.warn,
        )
        return
    }
    val sub =
        if (flagged.isEmpty()) {
            "No warnings or errors captured yet."
        } else {
            val first = flagged.minBy { it.timestampEpochMs }
            val last = flagged.maxBy { it.timestampEpochMs }
            if (first === last) {
                "One event at ${formatCaptureClockTime(first.timestampEpochMs)}."
            } else {
                val from = formatCaptureClockTime(first.timestampEpochMs)
                val to = formatCaptureClockTime(last.timestampEpochMs)
                "Clustered between $from–$to."
            }
        }
    HeroCard(
        label = "Warnings and errors",
        value = flagged.size.toString(),
        valueSuffix = "of ${logs.size}",
        subtitle = sub,
        containerColor = colors.warnSoft,
        labelColor = colors.warn,
        valueColor = colors.warn,
        onCollapse = actions.onToggleLogsHero,
    )
}

@Composable
private fun LogRow(
    log: InspectorLogUi,
    colors: DevConsoleColors,
    onClick: () -> Unit,
) {
    val (leadColor, leadBg) = logLevelTint(log.kind, colors)
    Column(modifier = Modifier.fillMaxWidth()) {
        TonalListRow(
            leadText = logLevelShortLabel(log.kind),
            leadColor = leadColor,
            leadContainerColor = leadBg,
            title = if (log.summary.length > 40) log.summary.take(40) + "…" else log.summary,
            subtitle = "${log.source} · ${log.kind}",
            trailValue = formatCaptureClockTime(log.timestampEpochMs),
            trailValueColor = colors.muted,
            containerColor = Color.Transparent,
            onClick = onClick,
        )
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(start = 76.dp),
            color = colors.line
        )
    }
}

@Composable
internal fun ObserveTabEmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = DevConsoleTheme.colors.muted)
    }
}
