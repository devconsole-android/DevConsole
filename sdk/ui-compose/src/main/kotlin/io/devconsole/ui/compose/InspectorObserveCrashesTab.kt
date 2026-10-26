/**
 * @author Shakib
 * @since 06/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.devconsole.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Crashes tab: crash/ANR count hero + a newest-first event list, mirroring the Logs tab's
 * shape (see [LogsTabContent] in `InspectorObserveSignals.kt`). Split into its own file, like the
 * Traffic tab, once the combined Sockets/Push/Logs/Crashes file crossed this module's per-file
 * function-count budget.
 */
@Composable
internal fun CrashesTabContent(
    state: InspectorState,
    ui: ObserveUiState,
    actions: ObserveActions,
) {
    val colors = DevConsoleTheme.colors
    if (state.crashes.isEmpty() && !ui.searchOpen) {
        ObserveTabEmptyState("No crashes or ANRs captured yet.", EmptySpot.CrashesClear)
        return
    }
    val searched =
        remember(state.crashes, ui.crashesSearch) {
            val query = ui.crashesSearch.trim().lowercase(Locale.US)
            if (query.isEmpty()) {
                state.crashes
            } else {
                state.crashes.filter { crash ->
                    crash.summary.lowercase(Locale.US).contains(query) ||
                        crash.thread.lowercase(Locale.US).contains(query) ||
                        crash.stackTrace.lowercase(Locale.US).contains(query)
                }
            }
        }
    val rowsNewestFirst = remember(searched) { searched.sortedByDescending { it.timestampEpochMs } }

    val arrivals = rememberArrivals(rowsNewestFirst.map { it.id })
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
    ) {
        if (ui.searchOpen) {
            stickyHeader {
                InspectorSearchBar(
                    query = ui.crashesSearch,
                    onQueryChange = actions.onCrashesSearchChange,
                    placeholder = "Search summary, thread or dump",
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (state.crashes.isNotEmpty()) {
            item { CrashesHero(state.crashes, ui, actions, colors) }
            item { Spacer(Modifier.height(12.dp)) }
        }
        if (rowsNewestFirst.isEmpty()) item { ObserveTabEmptyState("No matching crashes.", EmptySpot.Search) }
        itemsIndexed(rowsNewestFirst, key = { _, crash -> crash.id }) { index, crash ->
            CrashRow(
                crash = crash,
                isFlagged = crash.id in ui.flaggedCrashIds,
                colors = colors,
                modifier = Modifier.animateItem().groupedPanelItem(GroupPosition.of(index, rowsNewestFirst.size)),
                isArrival = crash.id in arrivals,
                onClick = { actions.onOpenCrashDetail(crash.id) },
            )
        }
    }
}

@Composable
private fun CrashesHero(
    crashes: List<InspectorCrashUi>,
    ui: ObserveUiState,
    actions: ObserveActions,
    colors: DevConsoleColors,
) {
    if (ui.crashesHeroCollapsed) {
        HeroBar(
            value = crashes.size.toString(),
            label = "crashes and ANRs this session",
            onExpand = actions.onToggleCrashesHero,
            valueColor = colors.error,
            labelColor = colors.error,
        )
        return
    }
    val anrCount = crashes.count { it.kind.uppercase(Locale.US) == "ANR" }
    val crashCount = crashes.size - anrCount
    HeroCard(
        label = "Crashes and ANRs",
        value = crashes.size.toString(),
        subtitle =
            "$crashCount crash${if (crashCount == 1) "" else "es"}, " +
                "$anrCount ANR${if (anrCount == 1) "" else "s"} this session.",
        labelColor = colors.error,
        valueColor = colors.error,
        onCollapse = actions.onToggleCrashesHero,
    )
}

/** Container tint mirrors [TrafficRow]'s flagged/unflagged pattern: evidence status surfaces in the row itself. */
@Suppress("LongParameterList") // Row content, evidence state, and the live-arrival flash.
@Composable
private fun CrashRow(
    crash: InspectorCrashUi,
    isFlagged: Boolean,
    colors: DevConsoleColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isArrival: Boolean = false,
) {
    val crumbCount = crash.breadcrumbs.size
    val crumbNoun = if (crumbCount == 1) "breadcrumb" else "breadcrumbs"
    Column(modifier = modifier.fillMaxWidth()) {
        TonalListRow(
            isArrival = isArrival,
            leadText = if (crash.kind.equals("ANR", ignoreCase = true)) "ANR" else "CRASH",
            leadColor = colors.muted,
            leadContainerColor = Color.Transparent,
            title = if (crash.summary.length > 40) crash.summary.take(40) + "…" else crash.summary,
            subtitle = "${crash.thread.ifBlank { "unknown thread" }} · $crumbCount $crumbNoun",
            trailValue = formatCaptureClockTime(crash.timestampEpochMs),
            trailValueColor = colors.muted,
            containerColor = if (isFlagged) colors.surface2 else Color.Transparent,
            onClick = onClick,
        )
    }
}
