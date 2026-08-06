/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "TooManyFunctions")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.devconsole.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private const val TRAFFIC_HTTP_CLIENT_ERROR_START = 400
private const val TRAFFIC_HTTP_CLIENT_ERROR_END = 499
private const val TRAFFIC_HTTP_SERVER_ERROR_START = 500

/** Search+chip-filtered view over [InspectorState.transactions], plus the counts the hero/chips need. */
private data class TrafficStats(
    val failCount: Int,
    val total: Int,
    val clientCount: Int,
    val serverCount: Int,
    val timeoutCount: Int,
    val rowsNewestFirst: List<InspectorTransactionUi>,
)

private fun List<InspectorTransactionUi>.matchesTrafficSearch(query: String): List<InspectorTransactionUi> {
    if (query.isEmpty()) return this
    return filter { tx ->
        tx.path.lowercase(Locale.US).contains(query) ||
            tx.host.lowercase(Locale.US).contains(query) ||
            tx.requestPreview?.lowercase(Locale.US)?.contains(query) == true ||
            tx.responsePreview?.lowercase(Locale.US)?.contains(query) == true
    }
}

/**
 * Chip-driven filtering plus the hero/chip *counts* have to agree with whatever the search narrowed
 * down to -- so both filters run client-side over the already-loaded [InspectorState.transactions]
 * page here, rather than round-tripping through the traffic-query actions this screen never dispatches.
 */
@Composable
private fun rememberTrafficStats(
    transactions: List<InspectorTransactionUi>,
    search: String,
    chip: String,
): TrafficStats =
    remember(transactions, search, chip) {
        val searched = transactions.matchesTrafficSearch(search.trim().lowercase(Locale.US))
        val chipFiltered =
            when (chip) {
                "failing" -> searched.filter { it.isFailing() }
                "GET", "POST" -> searched.filter { it.method == chip }
                else -> searched
            }
        val clientRange = TRAFFIC_HTTP_CLIENT_ERROR_START..TRAFFIC_HTTP_CLIENT_ERROR_END
        TrafficStats(
            failCount = searched.count { it.isFailing() },
            total = searched.size,
            clientCount = searched.count { it.statusCode in clientRange },
            serverCount = searched.count { (it.statusCode ?: 0) >= TRAFFIC_HTTP_SERVER_ERROR_START },
            timeoutCount = searched.count { it.statusCode == null },
            // Explicit timestamp sort rather than a positional .asReversed() -- the latter only
            // matches "newest first" when the upstream list happens to arrive oldest-first, which
            // isn't guaranteed.
            rowsNewestFirst = chipFiltered.sortedByDescending { it.startedAtEpochMs },
        )
    }

@Composable
internal fun TrafficTabContent(
    state: InspectorState,
    ui: ObserveUiState,
    actions: ObserveActions,
) {
    if (state.transactions.isEmpty()) {
        TrafficEmptyState()
        return
    }
    val colors = DevConsoleTheme.colors
    val stats = rememberTrafficStats(state.transactions, ui.trafficSearch, ui.trafficChip)
    // Selection mode has no state of its own: a non-empty selectedTransactionIds *is* selection
    // mode, so long-pressing a row (which selects it) enters the mode, and clearing the selection
    // (explicit close, back press, or toggling the last selected row off) exits it -- one source of
    // truth instead of a redundant boolean that could drift from it.
    val selectionModeActive = state.selectedTransactionIds.isNotEmpty()
    BackHandler(enabled = selectionModeActive, onBack = actions.onClearTransactionSelection)

    Box(Modifier.fillMaxSize()) {
        TrafficList(stats, ui, actions, colors, TrafficSelection(state.selectedTransactionIds, selectionModeActive))
        EvidenceFab(
            label =
                if (ui.flaggedTransactionIds.isEmpty()) "Flag" else "Evidence · ${ui.flaggedTransactionIds.size}",
            onClick = actions.onOpenEvidenceTray,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
        )
    }
}

/** The traffic tab's selection state, bundled so [TrafficList] doesn't grow a parameter per field. */
private data class TrafficSelection(
    val ids: Set<String>,
    val modeActive: Boolean,
)

@Composable
private fun TrafficList(
    stats: TrafficStats,
    ui: ObserveUiState,
    actions: ObserveActions,
    colors: DevConsoleColors,
    selection: TrafficSelection,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = EvidenceFabScrollClearance),
    ) {
        stickyHeader {
            if (selection.modeActive) {
                TrafficSelectionBar(
                    selectedCount = selection.ids.size,
                    actions =
                        TrafficSelectionActions(
                            onSelectAllFiltered = {
                                actions.onSelectAllFilteredTransactions(
                                    stats.rowsNewestFirst.mapTo(mutableSetOf()) { it.id },
                                )
                            },
                            onExportHar = actions.onExportHar,
                            onExportPostman = actions.onExportPostman,
                            onClear = actions.onClearTransactionSelection,
                        ),
                    colors = colors,
                )
            } else {
                InspectorSearchBar(
                    query = ui.trafficSearch,
                    onQueryChange = actions.onTrafficSearchChange,
                    placeholder = "Search path, host or payload",
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
        item { TrafficHero(stats, ui, colors, actions) }
        item { Spacer(Modifier.height(16.dp)) }
        item { TrafficChips(stats, ui.trafficChip, actions) }
        item { Spacer(Modifier.height(16.dp)) }
        item {
            val suffix = if (ui.trafficChip == "all") " captures · newest first" else " matching · newest first"
            GroupLabel("${stats.rowsNewestFirst.size}$suffix")
        }
        items(stats.rowsNewestFirst, key = { it.id }) { transaction ->
            TrafficRow(
                transaction = transaction,
                isFlagged = transaction.id in ui.flaggedTransactionIds,
                isSelected = transaction.id in selection.ids,
                selectionModeActive = selection.modeActive,
                colors = colors,
                actions = actions,
            )
        }
    }
}

/** Bundled callbacks for [TrafficSelectionBar] -- keeps that composable's own parameter list short. */
private data class TrafficSelectionActions(
    val onSelectAllFiltered: () -> Unit,
    val onExportHar: () -> Unit,
    val onExportPostman: () -> Unit,
    val onClear: () -> Unit,
)

/**
 * Contextual bar (mirrors the dashboard's `#networkSelectionBar`, read read-only from dashboard.js
 * to align wording): a live count, "Select all matching filter" (widens the selection to every row
 * the tab's current search+chip filter matches -- bounded to what's already loaded, since this
 * module has no server round-trip like the dashboard's version does; see the doc on
 * [ObserveActions.onSelectAllFilteredTransactions]'s call site), HAR/Postman export of the
 * selection, and a close action that clears it (also reachable via system back -- see
 * [TrafficTabContent]'s `BackHandler`).
 */
@Composable
private fun TrafficSelectionBar(
    selectedCount: Int,
    actions: TrafficSelectionActions,
    colors: DevConsoleColors,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface2)
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$selectedCount selected",
                color = colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            InspectorPillButton(
                label = "Clear",
                onClick = actions.onClear,
                containerColor = Color.Transparent,
                contentColor = colors.muted,
                labelFontSize = 12.5.sp,
                outlined = true,
            )
        }
        Spacer(Modifier.height(8.dp))
        TrafficSelectionActionRow(actions, colors)
    }
}

@Composable
private fun TrafficSelectionActionRow(
    actions: TrafficSelectionActions,
    colors: DevConsoleColors,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InspectorPillButton(
            label = "Select all matching filter",
            onClick = actions.onSelectAllFiltered,
            containerColor = Color.Transparent,
            contentColor = colors.ink,
            labelFontSize = 12.5.sp,
            outlined = true,
        )
        InspectorPillButton(
            label = "HAR",
            onClick = actions.onExportHar,
            containerColor = colors.put,
            contentColor = colors.ground,
            labelFontSize = 12.5.sp,
            icon = { ExportPillIcon(colors) },
        )
        InspectorPillButton(
            label = "Postman",
            onClick = actions.onExportPostman,
            containerColor = colors.put,
            contentColor = colors.ground,
            labelFontSize = 12.5.sp,
            icon = { ExportPillIcon(colors) },
        )
    }
}

@Composable
private fun ExportPillIcon(colors: DevConsoleColors) {
    ObserveGlyphIcon(ObserveGlyph.Download, contentDescription = null, tint = colors.ground, size = 13.dp)
}

@Composable
private fun TrafficChips(
    stats: TrafficStats,
    trafficChip: String,
    actions: ObserveActions,
) {
    FilterChipRow(
        chips =
            listOf(
                InspectorFilterChip("all", "All", trafficChip == "all", stats.total.toString()),
                InspectorFilterChip("failing", "Failing", trafficChip == "failing", stats.failCount.toString()),
                InspectorFilterChip("GET", "GET", trafficChip == "GET"),
                InspectorFilterChip("POST", "POST", trafficChip == "POST"),
            ),
        onChipClick = { chip -> actions.onTrafficChipClick(chip.id) },
    )
}

@Composable
private fun TrafficHero(
    stats: TrafficStats,
    ui: ObserveUiState,
    colors: DevConsoleColors,
    actions: ObserveActions,
) {
    val failing = stats.failCount > 0
    val showingFailuresOnly = ui.trafficChip == "failing"
    if (ui.trafficHeroCollapsed) {
        HeroBar(
            value = stats.failCount.toString(),
            label = "of ${stats.total} requests failing",
            onExpand = actions.onToggleTrafficHero,
            containerColor = if (failing) colors.errorSoft else colors.surface2,
            valueColor = if (failing) colors.error else colors.ink,
            labelColor = if (failing) colors.error else colors.text3,
        )
        return
    }
    HeroCard(
        label = "Failing requests",
        value = stats.failCount.toString(),
        valueSuffix = "of ${stats.total}",
        subtitle =
            if (failing) {
                "${stats.clientCount} client, ${stats.serverCount} server, ${stats.timeoutCount} timeout."
            } else {
                "No failing requests out of ${stats.total} captured."
            },
        containerColor = if (failing) colors.errorSoft else colors.surface2,
        labelColor = if (failing) colors.error else colors.text3,
        valueColor = if (failing) colors.error else colors.ink,
        onCollapse = actions.onToggleTrafficHero,
        ctaLabel = if (showingFailuresOnly) "Showing failures only" else "Show only failures",
        ctaIcon = {
            ObserveGlyphIcon(
                ObserveGlyph.Filter,
                contentDescription = null,
                tint = if (showingFailuresOnly) colors.error else colors.errorInk,
                size = 17.dp,
            )
        },
        ctaContainerColor = if (showingFailuresOnly) colors.panel else colors.error,
        ctaContentColor = if (showingFailuresOnly) colors.error else colors.errorInk,
        onCtaClick = { actions.onTrafficChipClick(if (showingFailuresOnly) "all" else "failing") },
    )
}

@Composable
@Suppress("LongParameterList") // One real input per row concern (capture, its two membership flags, colors, actions).
private fun TrafficRow(
    transaction: InspectorTransactionUi,
    isFlagged: Boolean,
    isSelected: Boolean,
    selectionModeActive: Boolean,
    colors: DevConsoleColors,
    actions: ObserveActions,
) {
    val (leadColor, leadBg) = methodTint(transaction.method, colors)
    val mockedSuffix = if (transaction.isMocked) " · mocked" else ""
    TonalListRow(
        leadText = methodLeadText(transaction.method),
        leadColor = leadColor,
        leadContainerColor = leadBg,
        title = transaction.path.substringBefore('?'),
        subtitle = "${transaction.host} · ${formatCaptureClockTime(transaction.startedAtEpochMs)}$mockedSuffix",
        trailValue = transaction.statusCode?.toString() ?: "ERR",
        trailValueColor = statusTint(transaction.statusCode, colors),
        trailSubtitle =
            when {
                transaction.statusCode == null -> "timeout"
                transaction.durationMs != null -> "${transaction.durationMs} ms"
                else -> null
            },
        containerColor =
            if (isSelected) {
                colors.putSoft
            } else if (isFlagged) {
                colors.signalSoft
            } else {
                colors.surface2
            },
        leading =
            if (selectionModeActive) {
                {
                    TrafficRowSelectionCheckbox(
                        checked = isSelected,
                        onToggle = { actions.onToggleTransactionSelection(transaction.id) },
                    )
                }
            } else {
                null
            },
        // Long-press always toggles (entering selection mode on the first hit, since a non-empty
        // selection *is* selection mode -- see TrafficTabContent). A plain tap opens the detail
        // overlay normally, and instead toggles once selection mode is already active, matching the
        // standard Android "long-press to start selecting, tap to extend" idiom.
        onClick = {
            if (selectionModeActive) {
                actions.onToggleTransactionSelection(transaction.id)
            } else {
                actions.onOpenNetDetail(transaction.id)
            }
        },
        onLongClick = { actions.onToggleTransactionSelection(transaction.id) },
        // The leading checkbox only exists once selection mode is active -- only then does it need
        // to stay out of the row's merged semantics node so TalkBack exposes both the row's
        // tap/long-press action and the checkbox's toggle independently.
        mergeDescendants = !selectionModeActive,
    )
}

/** Circular selection-mode checkbox (mirrors [InspectorChip]'s filled/outline convention for the checked state). */
@Composable
private fun TrafficRowSelectionCheckbox(
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val colors = DevConsoleTheme.colors
    val borderColor = if (checked) colors.signal else colors.line
    val containerColor = if (checked) colors.signal else Color.Transparent
    Box(
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .size(26.dp)
                .clip(CircleShape)
                .border(1.dp, borderColor, CircleShape)
                .background(containerColor)
                .toggleable(value = checked, onValueChange = { onToggle() }, role = Role.Checkbox)
                .semantics {
                    contentDescription = if (checked) "Selected for export" else "Select for export"
                },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            InspectorGlyphIcon(InspectorGlyph.Check, contentDescription = null, tint = colors.signalInk, size = 14.dp)
        }
    }
}

@Composable
private fun TrafficEmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "No traffic captured yet.",
            color = DevConsoleTheme.colors.muted,
        )
    }
}
