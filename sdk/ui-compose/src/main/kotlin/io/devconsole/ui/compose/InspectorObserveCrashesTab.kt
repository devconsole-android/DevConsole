/**
 * @author Shakib
 * @since 06/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.devconsole.ui.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
    if (state.crashes.isEmpty()) {
        ObserveTabEmptyState("No crashes or ANRs captured yet.")
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 12px side/top padding with FAB clearance at the bottom, like every Observe tab's scroll container.
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = EvidenceFabScrollClearance),
    ) {
        stickyHeader {
            InspectorSearchBar(
                query = ui.crashesSearch,
                onQueryChange = actions.onCrashesSearchChange,
                placeholder = "Search summary, thread or dump",
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
        item { CrashesHero(state.crashes, ui, actions, colors) }
        item { Spacer(Modifier.height(16.dp)) }
        item { GroupLabel("Newest first") }
        itemsIndexed(rowsNewestFirst, key = { _, crash -> crash.id }) { _, crash ->
            CrashRow(crash, crash.id in ui.flaggedCrashIds, colors) { actions.onOpenCrashDetail(crash.id) }
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
            containerColor = colors.errorSoft,
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
        containerColor = colors.errorSoft,
        labelColor = colors.error,
        valueColor = colors.error,
        onCollapse = actions.onToggleCrashesHero,
    )
}

/** Container tint mirrors [TrafficRow]'s flagged/unflagged pattern: evidence status surfaces in the row itself. */
@Composable
private fun CrashRow(
    crash: InspectorCrashUi,
    isFlagged: Boolean,
    colors: DevConsoleColors,
    onClick: () -> Unit,
) {
    val (leadColor, leadBg) = logLevelTint(crash.kind, colors)
    val crumbCount = crash.breadcrumbs.size
    val crumbNoun = if (crumbCount == 1) "breadcrumb" else "breadcrumbs"
    TonalListRow(
        leadText = logLevelShortLabel(crash.kind),
        leadColor = leadColor,
        leadContainerColor = leadBg,
        title = if (crash.summary.length > 40) crash.summary.take(40) + "…" else crash.summary,
        subtitle = "${crash.thread.ifBlank { "unknown thread" }} · $crumbCount $crumbNoun",
        trailValue = formatCaptureClockTime(crash.timestampEpochMs),
        trailValueColor = colors.muted,
        containerColor = if (isFlagged) colors.signalSoft else colors.surface2,
        onClick = onClick,
    )
}
