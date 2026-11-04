/**
 * @author Shakib
 * @since 06/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.devconsole.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            val query = ui.crashesSearch.trim()
            if (query.isEmpty()) {
                state.crashes
            } else {
                state.crashes.filter { crash ->
                    crash.summary.contains(query, ignoreCase = true) ||
                        crash.thread.contains(query, ignoreCase = true) ||
                        crash.stackTrace.contains(query, ignoreCase = true)
                }
            }
        }
    val rowsNewestFirst = remember(searched) { searched.sortedByDescending { it.timestampEpochMs } }
    val defaultGroupSimilar = remember(state.crashes) { hasDuplicateCrashFingerprints(state.crashes) }
    var userGroupSimilarOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val groupSimilar = userGroupSimilarOverride ?: defaultGroupSimilar
    val groupedRows = remember(rowsNewestFirst) { groupCrashes(rowsNewestFirst) }
    val arrivalIds = remember(rowsNewestFirst) { rowsNewestFirst.map { it.id } }
    val arrivals = rememberArrivals(arrivalIds)
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
            item { Spacer(Modifier.height(8.dp)) }
            item {
                CrashesGroupSimilarBar(
                    groupSimilar = groupSimilar,
                    groupCount = groupedRows.size,
                    onToggle = { userGroupSimilarOverride = !groupSimilar },
                )
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
        if (rowsNewestFirst.isEmpty()) item { ObserveTabEmptyState("No matching crashes.", EmptySpot.Search) }
        if (groupSimilar) {
            itemsIndexed(groupedRows, key = { _, group -> "group:${group.fingerprint}" }) { index, group ->
                CrashGroupRow(
                    group = group,
                    isFlagged = group.crashes.any { it.id in ui.flaggedCrashIds },
                    colors = colors,
                    modifier = Modifier.animateItem().groupedPanelItem(GroupPosition.of(index, groupedRows.size)),
                    isArrival = group.newest.id in arrivals,
                    onClick = { actions.onOpenCrashDetail(group.newest.id) },
                )
            }
        } else {
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
}

@Composable
private fun CrashesGroupSimilarBar(
    groupSimilar: Boolean,
    groupCount: Int,
    onToggle: () -> Unit,
) {
    FilterChipRow(
        chips =
            listOf(
                InspectorFilterChip(
                    id = "group-similar",
                    label = "Group similar",
                    selected = groupSimilar,
                    count = if (groupSimilar && groupCount > 0) groupCount.toString() else null,
                ),
            ),
        onChipClick = { onToggle() },
    )
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
            label = if (crashes.size == 1) "crash or ANR this session" else "crashes and ANRs this session",
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

@Suppress("LongParameterList")
@Composable
private fun CrashGroupRow(
    group: InspectorCrashGroup,
    isFlagged: Boolean,
    colors: DevConsoleColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isArrival: Boolean = false,
) {
    val crash = group.newest
    val crumbCount = crash.breadcrumbs.size
    val crumbNoun = if (crumbCount == 1) "breadcrumb" else "breadcrumbs"
    val occurrenceNoun = if (group.count == 1) "occurrence" else "occurrences"
    Column(modifier = modifier.fillMaxWidth()) {
        TonalListRow(
            isArrival = isArrival,
            leadText = if (crash.kind.equals("ANR", ignoreCase = true)) "ANR" else "CRASH",
            leadColor = colors.muted,
            leadContainerColor = Color.Transparent,
            title = if (crash.summary.length > 40) crash.summary.take(40) + "…" else crash.summary,
            subtitle = "${crash.thread.ifBlank { "unknown thread" }} · $crumbCount $crumbNoun",
            trailValue = formatCaptureClockTime(group.lastSeenEpochMs),
            trailValueColor = colors.muted,
            // A ×1 badge on every ungrouped row is noise; the count only says something once it repeats.
            trailContent = {
                if (group.count > 1) {
                    Text(
                        text = "×${group.count}",
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.surface3)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .clearAndSetSemantics { contentDescription = "${group.count} $occurrenceNoun" },
                        color = colors.ink,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            },
            containerColor = if (isFlagged) colors.surface2 else Color.Transparent,
            onClick = onClick,
        )
    }
}
