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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

private const val TRAFFIC_HTTP_CLIENT_ERROR_START = 400
private const val TRAFFIC_HTTP_CLIENT_ERROR_END = 499
private const val TRAFFIC_HTTP_SERVER_ERROR_START = 500
private const val TRAFFIC_HOST_LIMIT = 8

/** Status-class chips in the traffic header, in HTTP bucket order. */
private val TRAFFIC_STATUS_CLASS_LABELS =
    listOf(
        TrafficStatusClass.ALL to "All",
        TrafficStatusClass.SUCCESS to "2xx",
        TrafficStatusClass.REDIRECT to "3xx",
        TrafficStatusClass.CLIENT_ERROR to "4xx",
        TrafficStatusClass.SERVER_ERROR to "5xx",
        TrafficStatusClass.FAILED to "Failed",
    )

/**
 * Hosts worth offering in the traffic header's dropdown: the [TRAFFIC_HOST_LIMIT] most captured
 * hosts first, ties alphabetical so the list does not reshuffle between identical counts.
 */
internal fun topTrafficHosts(transactions: List<InspectorTransactionUi>): List<String> =
    transactions
        .groupingBy { it.host }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(TRAFFIC_HOST_LIMIT)
        .map { it.key }

/** Search+chip-filtered view over [InspectorState.transactions], plus the counts the hero/chips need. */
private data class TrafficStats(
    val failCount: Int,
    val total: Int,
    val clientCount: Int,
    val serverCount: Int,
    val timeoutCount: Int,
    val rowsNewestFirst: List<InspectorTransactionUi>,
)

private fun List<InspectorTransactionUi>.matchesTrafficSearch(search: String): List<InspectorTransactionUi> {
    if (search.isEmpty()) return this
    return filter { tx ->
        tx.path.contains(search, ignoreCase = true) ||
            tx.queryString().contains(search, ignoreCase = true) ||
            tx.host.contains(search, ignoreCase = true) ||
            tx.requestPreview?.contains(search, ignoreCase = true) == true ||
            tx.responsePreview?.contains(search, ignoreCase = true) == true
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
        val searched = transactions.matchesTrafficSearch(search.trim())
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
    val trafficFilterActive = state.trafficStatusClass != TrafficStatusClass.ALL || state.trafficHost != null
    val headerControlsOpen = ui.searchOpen || ui.trafficFiltersOpen
    if (state.transactions.isEmpty() && !headerControlsOpen && !trafficFilterActive) {
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

    TrafficList(state, stats, ui, actions, colors, TrafficSelection(state.selectedTransactionIds, selectionModeActive))
}

/** The traffic tab's selection state, bundled so [TrafficList] doesn't grow a parameter per field. */
private data class TrafficSelection(
    val ids: Set<String>,
    val modeActive: Boolean,
)

@Composable
private fun TrafficList(
    state: InspectorState,
    stats: TrafficStats,
    ui: ObserveUiState,
    actions: ObserveActions,
    colors: DevConsoleColors,
    selection: TrafficSelection,
) {
    val arrivalIds = remember(stats.rowsNewestFirst) { stats.rowsNewestFirst.map { it.id } }
    val arrivals = rememberArrivals(arrivalIds)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
    ) {
        if (selection.modeActive || ui.searchOpen) {
            stickyHeader {
                Column(Modifier.padding(horizontal = 16.dp)) {
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
                    }
                    if (ui.searchOpen) {
                        InspectorSearchBar(
                            query = ui.trafficSearch,
                            onQueryChange = actions.onTrafficSearchChange,
                            placeholder = "Search path, host or payload",
                        )
                    }
                }
            }
        }
        // Keep the filter row reachable even when the filters match nothing, so a narrowing
        // selection is never a dead end.
        val filterActive = state.trafficStatusClass != TrafficStatusClass.ALL || state.trafficHost != null
        if (state.transactions.isNotEmpty() || filterActive) {
            item {
                TrafficStatusFilterRow(
                    selectedClass = state.trafficStatusClass,
                    selectedHost = state.trafficHost,
                    transactions = state.transactions,
                    actions = actions,
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
        if (stats.failCount > 0) {
            item { TrafficHero(stats, ui, colors, actions) }
            item { Spacer(Modifier.height(12.dp)) }
        }
        if (ui.trafficFiltersOpen) {
            item { TrafficChips(stats, ui.trafficChip, actions) }
            item { Spacer(Modifier.height(12.dp)) }
        }
        if (stats.rowsNewestFirst.isEmpty()) item { ObserveTabEmptyState("No matching captures.", EmptySpot.Search) }
        itemsIndexed(stats.rowsNewestFirst, key = { _, transaction -> transaction.id }) { index, transaction ->
            TrafficRow(
                transaction = transaction,
                isFlagged = transaction.id in ui.flaggedTransactionIds,
                isSelected = transaction.id in selection.ids,
                selectionModeActive = selection.modeActive,
                colors = colors,
                actions = actions,
                isArrival = transaction.id in arrivals,
                modifier = Modifier.animateItem().groupedPanelItem(GroupPosition.of(index, stats.rowsNewestFirst.size)),
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
                .clip(MaterialTheme.shapes.large)
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
            containerColor = colors.signal,
            contentColor = colors.ground,
            labelFontSize = 12.5.sp,
            icon = { ExportPillIcon(colors) },
        )
        InspectorPillButton(
            label = "Postman",
            onClick = actions.onExportPostman,
            containerColor = colors.signal,
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

/**
 * The traffic header's server-side filters: a status-class chip row plus a host dropdown. Both
 * dispatch to the ViewModel, which re-reads the capture store with an [InspectorTrafficQuery] --
 * unlike the search/method chips below, these narrow the page itself rather than the loaded rows.
 */
@Composable
private fun TrafficStatusFilterRow(
    selectedClass: TrafficStatusClass,
    selectedHost: String?,
    transactions: List<InspectorTransactionUi>,
    actions: ObserveActions,
) {
    Column(Modifier.padding(top = 8.dp)) {
        FilterChipRow(
            chips =
                TRAFFIC_STATUS_CLASS_LABELS.map { (statusClass, label) ->
                    InspectorFilterChip(statusClass.name, label, statusClass == selectedClass)
                },
            onChipClick = { chip -> actions.onTrafficStatusClassClick(TrafficStatusClass.valueOf(chip.id)) },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        )
        TrafficHostMenu(selectedHost, transactions, actions)
    }
}

@Composable
private fun TrafficHostMenu(
    selectedHost: String?,
    transactions: List<InspectorTransactionUi>,
    actions: ObserveActions,
) {
    var expanded by remember { mutableStateOf(false) }
    // Once a host is picked the page only holds that host's rows, so the menu keeps offering the
    // hosts seen while unfiltered -- switching hosts must not require a detour through "All hosts".
    val unfilteredHosts = remember { mutableListOf<String>() }
    val hosts =
        remember(transactions, selectedHost) {
            if (selectedHost == null) {
                topTrafficHosts(transactions).also {
                    unfilteredHosts.clear()
                    unfilteredHosts.addAll(it)
                }
            } else {
                (unfilteredHosts + selectedHost).distinct()
            }
        }
    Box {
        FilterChipRow(
            chips = listOf(InspectorFilterChip("host", "${selectedHost ?: "All hosts"} ▾", selectedHost != null)),
            onChipClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("All hosts") },
                onClick = {
                    expanded = false
                    actions.onTrafficHostClick(null)
                },
            )
            hosts.forEach { host ->
                DropdownMenuItem(
                    text = { Text(host) },
                    onClick = {
                        expanded = false
                        actions.onTrafficHostClick(host)
                    },
                )
            }
        }
    }
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
            label = if (stats.failCount == 1) "request failing" else "requests failing",
            onExpand = actions.onToggleTrafficHero,
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
    modifier: Modifier = Modifier,
    isArrival: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        TonalListRow(
            isArrival = isArrival,
            leadText = transaction.method,
            leadColor = colors.muted,
            leadContainerColor = Color.Transparent,
            // breakingAnywhere: without it the line breaker leaves the first line half empty and
            // moves the whole query down (see the helper's own note).
            title = transaction.pathWithQuery().breakingAnywhere(),
            // Four lines, matching the web list's clamp: a path plus its query string routinely
            // runs past two on a phone, and the part that gets cut is the query -- the very thing
            // that tells two captures of the same endpoint apart.
            titleMaxLines = 4,
            subtitle = if (transaction.isMocked) "mocked" else "",
            trailValue = transaction.statusCode?.toString() ?: "ERR",
            trailValueColor = statusTint(transaction.statusCode, colors),
            trailSubtitle =
                when {
                    transaction.durationMs != null -> "${transaction.durationMs} ms"
                    else -> null
                },
            containerColor =
                if (isSelected) {
                    colors.surface2
                } else if (isFlagged) {
                    colors.surface2
                } else {
                    Color.Transparent
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
        InspectorEmptyState(EmptySpot.Traffic, "No traffic captured yet.")
    }
}
