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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.devconsole.api.CaptureCategory
import java.util.Locale

/** Sockets tab: an error notice and frames in one grouped panel. */
@Composable
internal fun SocketsTabContent(
    state: InspectorState,
    ui: ObserveUiState,
    actions: ObserveActions,
) {
    val colors = DevConsoleTheme.colors
    if (state.sockets.isEmpty() && !ui.searchOpen) {
        ObserveTabEmptyState(state.socketsEmptyStateMessage(), EmptySpot.Sockets)
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
    val arrivals = rememberArrivals(searchedIndices.map { frameKeys[it] })

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
    ) {
        if (ui.searchOpen) {
            stickyHeader {
                InspectorSearchBar(
                    query = ui.socketsSearch,
                    onQueryChange = actions.onSocketsSearchChange,
                    placeholder = "Search host, path or frames",
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (state.sockets.any { it.error != null }) {
            item { SocketsHero(state.sockets, ui, actions, colors) }
            item { Spacer(Modifier.height(12.dp)) }
        }
        if (searchedIndices.isEmpty()) item { ObserveTabEmptyState("No matching frames.", EmptySpot.Search) }
        itemsIndexed(searchedIndices, key = { _, index -> frameKeys[index] }) { rowIndex, index ->
            val (socket, frame) = recentFrames[index]
            SocketFrameRow(
                socket = socket,
                frame = frame,
                colors = colors,
                modifier = Modifier.animateItem().groupedPanelItem(GroupPosition.of(rowIndex, searchedIndices.size)),
                isArrival = frameKeys[index] in arrivals,
                onClick = { actions.onOpenFrameDetail(socket, frame) },
            )
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
    val errorCount = sockets.count { it.error != null }
    if (ui.socketsHeroCollapsed) {
        HeroBar(
            value = errorCount.toString(),
            label = "connections in error",
            onExpand = actions.onToggleSocketsHero,
            valueColor = colors.error,
            labelColor = colors.error,
        )
        return
    }
    val loudest = sockets.maxByOrNull { it.receivedCount }
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
        label = "Connections in error",
        value = errorCount.toString(),
        valueSuffix = "of ${sockets.size}",
        subtitle = sub,
        labelColor = colors.text3,
        valueColor = colors.error,
        onCollapse = actions.onToggleSocketsHero,
    )
}

@Suppress("LongParameterList") // Row content, its socket, and the live-arrival flash.
@Composable
private fun SocketFrameRow(
    socket: InspectorSocketUi,
    frame: InspectorSocketFrameUi,
    colors: DevConsoleColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isArrival: Boolean = false,
) {
    val received = frame.direction == "RECEIVED"
    val protocolBadge = socket.protocolBadge()
    val path = socket.url.replace(Regex("^wss?://[^/]+"), "").ifEmpty { socket.url }
    // An MQTT frame's topic is what an operator scanning this list actually wants to see -- the
    // socket's own URL is just the broker address, identical across every message on the connection.
    val title = frame.topic ?: path
    Column(modifier = modifier.fillMaxWidth()) {
        TonalListRow(
            isArrival = isArrival,
            leadText = if (received) "RECV" else "SEND",
            leadColor = colors.muted,
            leadContainerColor = Color.Transparent,
            title = title,
            subtitle = "$protocolBadge · ${frame.frameType} · ${frame.preview.orEmpty().take(34)}",
            trailValue = socketFrameSizeLabel(frame),
            trailValueColor = colors.ink,
            trailSubtitle = formatCaptureClockTime(frame.timestampEpochMs),
            containerColor = Color.Transparent,
            onClick = onClick,
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

/** Push tab: a failure notice and delivery events in one grouped panel. */
@Composable
internal fun PushTabContent(
    state: InspectorState,
    ui: ObserveUiState,
    actions: ObserveActions,
) {
    val colors = DevConsoleTheme.colors
    if (state.pushEvents.isEmpty() && !ui.searchOpen) {
        ObserveTabEmptyState("No push events captured yet.", EmptySpot.Push)
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
    val arrivals = rememberArrivals(searchedIndices.map { pushKeys[it] })

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
    ) {
        if (ui.searchOpen) {
            stickyHeader {
                InspectorSearchBar(
                    query = ui.pushSearch,
                    onQueryChange = actions.onPushSearchChange,
                    placeholder = "Search provider, message id or data",
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (state.pushEvents.any { it.hasPushFailure() }) {
            item { PushHero(state.pushEvents, ui, actions, colors) }
            item { Spacer(Modifier.height(12.dp)) }
        }
        if (searchedIndices.isEmpty()) item { ObserveTabEmptyState("No matching push events.", EmptySpot.Search) }
        itemsIndexed(searchedIndices, key = { _, index -> pushKeys[index] }) { rowIndex, index ->
            val push = state.pushEvents[index]
            PushRow(
                push = push,
                colors = colors,
                modifier = Modifier.animateItem().groupedPanelItem(GroupPosition.of(rowIndex, searchedIndices.size)),
                isArrival = pushKeys[index] in arrivals,
                onClick = { actions.onOpenPushDetail(push) },
            )
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
    val failedCount = pushEvents.count { it.hasPushFailure() }
    if (ui.pushHeroCollapsed) {
        HeroBar(
            value = failedCount.toString(),
            label = "push events failed",
            onExpand = actions.onTogglePushHero,
            valueColor = colors.error,
            labelColor = colors.error,
        )
        return
    }
    HeroCard(
        label = "Push failures",
        value = failedCount.toString(),
        valueSuffix = "events",
        subtitle = "push events failed",
        labelColor = colors.error,
        valueColor = colors.error,
        onCollapse = actions.onTogglePushHero,
    )
}

private fun InspectorPushUi.hasPushFailure(): Boolean =
    lifecycle.contains("ERROR", ignoreCase = true) || lifecycle.contains("FAIL", ignoreCase = true)

@Composable
private fun PushRow(
    push: InspectorPushUi,
    colors: DevConsoleColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isArrival: Boolean = false,
) {
    val title = push.dataPreview["title"] ?: push.messageId ?: "Push notification"
    Column(modifier = modifier.fillMaxWidth()) {
        TonalListRow(
            isArrival = isArrival,
            leadText = "MSG",
            leadColor = colors.muted,
            leadContainerColor = Color.Transparent,
            title = title,
            subtitle =
                "${push.provider} · ${pushLifecycleShortLabel(push.lifecycle)}" +
                    if (push.simulated) " · simulated" else "",
            trailValue = formatCaptureClockTimeShort(push.receivedAtEpochMs),
            trailValueColor = if (push.hasPushFailure()) colors.error else colors.muted,
            containerColor = Color.Transparent,
            onClick = onClick,
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
    if (state.logs.isEmpty() && !ui.searchOpen) {
        ObserveTabEmptyState("No logs captured yet.", EmptySpot.Logs)
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
    val arrivals = rememberArrivals(rowsNewestFirst.map { it.id })

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
    ) {
        if (ui.searchOpen) {
            stickyHeader {
                InspectorSearchBar(
                    query = ui.logsSearch,
                    onQueryChange = actions.onLogsSearchChange,
                    placeholder = "Search message, source or detail",
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (state.logs.any { isWarnOrErrorLevel(it.kind) }) {
            item { LogsHero(state.logs, ui, actions, colors) }
            item { Spacer(Modifier.height(12.dp)) }
        }
        if (rowsNewestFirst.isEmpty()) item { ObserveTabEmptyState("No matching logs.", EmptySpot.Search) }
        itemsIndexed(rowsNewestFirst, key = { _, log -> log.id }) { index, log ->
            LogRow(
                log = log,
                colors = colors,
                modifier = Modifier.animateItem().groupedPanelItem(GroupPosition.of(index, rowsNewestFirst.size)),
                isArrival = log.id in arrivals,
                onClick = { actions.onOpenLogDetail(log.id) },
            )
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
    modifier: Modifier = Modifier,
    isArrival: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        TonalListRow(
            isArrival = isArrival,
            leadText = log.kind.take(1).uppercase(Locale.US),
            leadColor = colors.muted,
            leadContainerColor = Color.Transparent,
            title = if (log.summary.length > 40) log.summary.take(40) + "…" else log.summary,
            subtitle = "${log.source} · ${log.kind}",
            trailValue = formatCaptureClockTime(log.timestampEpochMs),
            trailValueColor = if (isWarnOrErrorLevel(log.kind)) logLevelTint(log.kind, colors).first else colors.muted,
            containerColor = Color.Transparent,
            onClick = onClick,
        )
    }
}

@Composable
internal fun ObserveTabEmptyState(
    message: String,
    spot: EmptySpot? = null,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (spot != null) InspectorEmptyState(spot, message) else Text(message, color = DevConsoleTheme.colors.muted)
    }
}
