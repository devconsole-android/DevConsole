/**
 * @author Shakib
 * @since 06/10/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "TooManyFunctions")

package io.devconsole.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Everything the tabbed detail derives from the query: every match across the searchable
 * sections (for the tab and chip counts), and the ones in the section on screen (for the find
 * bar's counter, arrows and active highlight).
 */
private data class TabbedDetailSearch(
    val hasQuery: Boolean,
    val matches: List<InspectorDetailSearchMatch>,
    val visibleMatches: List<InspectorDetailSearchMatch>,
    val position: Int,
    val activeOrdinal: Int?,
)

/**
 * The tabbed capture detail: [InspectorCompactDetailHeader], a tab row, the selected tab's
 * content filling the rest of the screen, and the footer bar. See [ObserveDetailContent.tabs].
 *
 * A [InspectorDetailTabLayout.Chips] tab shows one section at a time under a chip row, with the
 * find field on top of it and the body below taking every remaining pixel -- the point of the
 * layout. A [InspectorDetailTabLayout.Stacked] tab shows its sections as open [CollapsibleSection]s
 * in one scrolling column.
 *
 * Search runs one query over every searchable section at once. The tab row and chips badge their
 * match counts, while the find field's counter and arrows step only through the section on
 * screen, so a hit elsewhere is announced without the arrows dragging the user across tabs. The
 * options sheet keeps only the match mode: the tab and chip already say where to look.
 */
@Suppress("LongMethod", "LongParameterList") // One screen's state wiring; the pieces below are split out.
@Composable
internal fun InspectorObserveTabbedDetailScreen(
    resetKey: Any,
    header: InspectorObserveDetailHeaderSpec,
    sections: List<InspectorDetailSectionSpec>,
    tabs: InspectorDetailTabs,
    footerActions: List<InspectorFooterAction>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    searchOptions: InspectorDetailSearchOptions? = null,
) {
    var selectedTabKey by rememberSaveable(resetKey) { mutableStateOf(tabs.initialTabKey) }
    // One selected section key per chips tab, as a flat list so it saves without a custom Saver.
    var selectedChipKeys by rememberSaveable(resetKey) { mutableStateOf(emptyList<String>()) }
    var query by rememberSaveable(resetKey) { mutableStateOf("") }
    var searchMode by
        rememberSaveable(resetKey) {
            mutableStateOf(searchOptions?.defaultMode ?: InspectorSearchMode.KEYS_AND_VALUES)
        }
    var matchPosition by rememberSaveable(resetKey) { mutableIntStateOf(0) }
    var rawSectionKeyList by rememberSaveable(resetKey) { mutableStateOf(emptyList<String>()) }
    var fullScreenSectionKey by rememberSaveable(resetKey) { mutableStateOf<String?>(null) }
    var searchOptionsVisible by remember(resetKey) { mutableStateOf(false) }
    val overviewOpenState = remember(resetKey) { mutableStateMapOf<String, Boolean>() }

    val sectionsByKey = remember(sections) { sections.associateBy { it.key } }
    val selectedTab = tabs.tabs.firstOrNull { it.key == selectedTabKey } ?: tabs.tabs.first()
    val visibleSectionKey = selectedTab.visibleSectionKey(selectedChipKeys)
    val rawSectionKeys = remember(rawSectionKeyList) { rawSectionKeyList.toSet() }
    val search =
        rememberTabbedDetailSearch(
            sections = sections,
            searchOptions = searchOptions,
            rawSectionKeys = rawSectionKeys,
            query = query,
            mode = searchMode,
            visibleSectionKey = visibleSectionKey,
            matchPosition = matchPosition,
        )
    val onShowRawChange: (String, Boolean) -> Unit = { key, showRaw ->
        rawSectionKeyList = if (showRaw) (rawSectionKeyList + key).distinct() else rawSectionKeyList - key
        matchPosition = 0
    }

    BackHandler(onBack = onBack)

    val fullScreenSpec = fullScreenSectionKey?.let { sectionsByKey[it] }
    if (fullScreenSpec != null) {
        TabbedDetailFullScreen(
            spec = fullScreenSpec,
            search = search,
            showRaw = fullScreenSpec.showRaw(rawSectionKeys),
            onShowRawChange = { onShowRawChange(fullScreenSpec.key, it) },
            onDismiss = { fullScreenSectionKey = null },
            modifier = modifier.fillMaxSize(),
        )
        if (fullScreenSpec.body.hasFullScreenReader()) return
    }

    Surface(modifier = modifier.fillMaxSize(), color = DevConsoleTheme.colors.ground) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            InspectorCompactDetailHeader(header = header, onBack = onBack)
            InspectorTabRow(
                tabs =
                    tabs.tabs.map { tab ->
                        InspectorTab(
                            label = tab.label,
                            selected = tab.key == selectedTab.key,
                            onClick = {
                                selectedTabKey = tab.key
                                matchPosition = 0
                            },
                            badge = tab.matchBadge(search.matches, search.hasQuery),
                        )
                    },
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (val layout = selectedTab.layout) {
                    is InspectorDetailTabLayout.Stacked ->
                        StackedTabContent(
                            specs = layout.sectionKeys.mapNotNull { sectionsByKey[it] },
                            openState = overviewOpenState,
                        )
                    is InspectorDetailTabLayout.Chips -> {
                        val visibleSpec = visibleSectionKey?.let { sectionsByKey[it] }
                        ChipsTabContent(
                            tab = selectedTab,
                            layout = layout,
                            sectionsByKey = sectionsByKey,
                            visibleSpec = visibleSpec,
                            search = search,
                            query = query,
                            onQueryChange = {
                                query = it
                                matchPosition = 0
                            },
                            onPositionChange = { matchPosition = it },
                            onChipSelect = { chip ->
                                selectedChipKeys = (selectedChipKeys - layout.sectionKeys.toSet()) + chip.sectionKey
                                matchPosition = 0
                            },
                            onOpenSearchOptions =
                                if (searchOptions !=
                                    null
                                ) {
                                    ({ searchOptionsVisible = true })
                                } else {
                                    null
                                },
                            showRaw = visibleSpec?.showRaw(rawSectionKeys),
                            onShowRawChange = { showRaw -> visibleSpec?.let { onShowRawChange(it.key, showRaw) } },
                            onExpandFullScreen = { key -> fullScreenSectionKey = key },
                        )
                    }
                }
            }
            InspectorDetailFooterBar(footerActions)
        }
    }
    if (searchOptions != null && searchOptionsVisible) {
        InspectorDetailSearchOptionsSheet(
            options = searchOptions,
            selectedSectionKeys = searchOptions.sections.mapTo(mutableSetOf()) { it.key },
            mode = searchMode,
            onDismiss = { searchOptionsVisible = false },
            onApply = { _, newMode ->
                searchMode = newMode
                matchPosition = 0
                searchOptionsVisible = false
            },
            showSectionPicker = false,
        )
    }
}

/** The section a chips tab shows: the one picked for it, else its first chip. Null on a stacked tab. */
private fun InspectorDetailTabSpec.visibleSectionKey(selectedChipKeys: List<String>): String? {
    val chips = (layout as? InspectorDetailTabLayout.Chips)?.chips ?: return null
    return chips.firstOrNull { it.sectionKey in selectedChipKeys }?.sectionKey ?: chips.firstOrNull()?.sectionKey
}

/** Raw/Formatted state for a formattable section: raw when the user chose it or nothing formatted. */
private fun InspectorDetailSectionSpec.showRaw(rawSectionKeys: Set<String>): Boolean? =
    (body as? InspectorDetailSectionBody.Formattable)?.let { it.formatted == null || key in rawSectionKeys }

private fun InspectorDetailSectionBody.hasFullScreenReader(): Boolean =
    this is InspectorDetailSectionBody.Code || this is InspectorDetailSectionBody.Formattable

@Suppress("LongParameterList") // Each input is a separate piece of search state.
@Composable
private fun rememberTabbedDetailSearch(
    sections: List<InspectorDetailSectionSpec>,
    searchOptions: InspectorDetailSearchOptions?,
    rawSectionKeys: Set<String>,
    query: String,
    mode: InspectorSearchMode,
    visibleSectionKey: String?,
    matchPosition: Int,
): TabbedDetailSearch {
    val searchableKeys = remember(searchOptions) { searchOptions?.sections?.mapTo(mutableSetOf()) { it.key }.orEmpty() }
    val hasQuery = searchOptions != null && query.isNotBlank()
    val bodies = inspectorSearchSectionBodies(sections, searchableKeys)
    val candidates =
        remember(bodies, rawSectionKeys, hasQuery) {
            inspectorSearchCandidatesFor(bodies, hasQuery) { key ->
                if (key in
                    rawSectionKeys
                ) {
                    InspectorBodySearchRepresentation.RAW
                } else {
                    InspectorBodySearchRepresentation.FORMATTED
                }
            }
        }
    val matches =
        remember(candidates, query, mode, hasQuery) {
            if (hasQuery) searchInspectorCandidates(candidates, query, searchableKeys, mode) else emptyList()
        }
    val visibleMatches = remember(matches, visibleSectionKey) { matches.filter { it.sectionKey == visibleSectionKey } }
    val position = if (visibleMatches.isEmpty()) 0 else matchPosition.coerceIn(0, visibleMatches.lastIndex)
    return TabbedDetailSearch(
        hasQuery = hasQuery,
        matches = matches,
        visibleMatches = visibleMatches,
        position = position,
        activeOrdinal = activeVisibleMatchOrdinal(visibleMatches, position),
    )
}

/** The overview: every section open in one scrolling column, each still collapsible. */
@Composable
private fun StackedTabContent(
    specs: List<InspectorDetailSectionSpec>,
    openState: MutableMap<String, Boolean>,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(top = 4.dp, bottom = 24.dp),
    ) {
        specs.forEach { spec ->
            val expanded = openState[spec.key] ?: true
            CollapsibleSection(
                label = spec.label,
                expanded = expanded,
                onToggle = { openState[spec.key] = !expanded },
                modifier = Modifier.padding(bottom = 12.dp),
                onCopy = spec.onCopy,
                copyContentDescription = spec.copyDescription ?: "Copy ${spec.label}",
            ) {
                InlineSectionBody(spec.body)
            }
        }
    }
}

/** A section body at its inline size, for the overview's stacked sections. */
@Composable
private fun InlineSectionBody(body: InspectorDetailSectionBody) {
    when (body) {
        is InspectorDetailSectionBody.KeyValues -> InspectorKeyValueList(entries = body.entries)
        is InspectorDetailSectionBody.Bars -> InspectorProgressBars(body.stats)
        is InspectorDetailSectionBody.Code -> InspectorCodeBlock(lines = body.lines)
        is InspectorDetailSectionBody.Formattable -> InspectorFormattableBody(body = body)
        is InspectorDetailSectionBody.Empty -> InspectorDetailEmptyText(body.text)
    }
}

/**
 * A request or response tab: chips to pick Body or Headers (plus the mode and copy actions for
 * the section on screen), the find field, then that section filling the remaining height.
 * [onOpenSearchOptions] is null on a kind with no search options, which also hides the find
 * field: with nothing declared searchable there is nothing for it to find.
 */
@Suppress("LongParameterList") // Every callback here is a distinct user action.
@Composable
private fun ChipsTabContent(
    tab: InspectorDetailTabSpec,
    layout: InspectorDetailTabLayout.Chips,
    sectionsByKey: Map<String, InspectorDetailSectionSpec>,
    visibleSpec: InspectorDetailSectionSpec?,
    search: TabbedDetailSearch,
    query: String,
    onQueryChange: (String) -> Unit,
    onPositionChange: (Int) -> Unit,
    onChipSelect: (InspectorDetailTabChip) -> Unit,
    onOpenSearchOptions: (() -> Unit)?,
    showRaw: Boolean?,
    onShowRawChange: (Boolean) -> Unit,
    onExpandFullScreen: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TabChipRow(
            layout = layout,
            sectionsByKey = sectionsByKey,
            visibleSectionKey = visibleSpec?.key,
            search = search,
            onChipSelect = onChipSelect,
            onOpenSearchOptions = onOpenSearchOptions.takeIf { search.hasQuery },
            onCopy = visibleSpec?.onCopy,
            copyDescription = visibleSpec?.let { it.copyDescription ?: "Copy ${it.label}" },
        )
        if (onOpenSearchOptions != null) {
            val count = search.visibleMatches.size
            InspectorDetailSearchField(
                query = query,
                onQueryChange = onQueryChange,
                matchLabel = tabbedSearchMatchLabel(query, search.position, count),
                onPrevious = { onPositionChange(previousInspectorMatchIndex(search.position, count)) },
                onNext = { onPositionChange(nextInspectorMatchIndex(search.position, count)) },
                navigationEnabled = count > 0,
                placeholder = "Find in ${tab.label.lowercase(Locale.US)}",
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            if (visibleSpec != null) {
                FilledSectionBody(
                    spec = visibleSpec,
                    search = search,
                    showRaw = showRaw,
                    onShowRawChange = onShowRawChange,
                    onExpandFullScreen = { onExpandFullScreen(visibleSpec.key) },
                )
            }
        }
    }
}

@Suppress("LongParameterList") // Chip state plus the two trailing actions.
@Composable
private fun TabChipRow(
    layout: InspectorDetailTabLayout.Chips,
    sectionsByKey: Map<String, InspectorDetailSectionSpec>,
    visibleSectionKey: String?,
    search: TabbedDetailSearch,
    onChipSelect: (InspectorDetailTabChip) -> Unit,
    onOpenSearchOptions: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    copyDescription: String?,
) {
    val colors = DevConsoleTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FilterChipRow(
            chips =
                layout.chips.map { chip ->
                    val body = sectionsByKey[chip.sectionKey]?.body ?: InspectorDetailSectionBody.Empty("")
                    InspectorFilterChip(
                        id = chip.sectionKey,
                        label = chip.label,
                        selected = chip.sectionKey == visibleSectionKey,
                        count =
                            detailChipCount(
                                body = body,
                                matchCount = search.matches.count { it.sectionKey == chip.sectionKey },
                                hasQuery = search.hasQuery,
                            ),
                    )
                },
            onChipClick = { clicked -> layout.chips.firstOrNull { it.sectionKey == clicked.id }?.let(onChipSelect) },
            modifier = Modifier.weight(1f),
            fillWidth = false,
        )
        if (onOpenSearchOptions != null) {
            InspectorRoundIconButton(
                contentDescription = "Search options",
                onClick = onOpenSearchOptions,
                size = 44.dp,
                icon = {
                    ObserveGlyphIcon(
                        ObserveGlyph.Filter,
                        contentDescription = null,
                        tint = colors.muted,
                        size = 17.dp,
                    )
                },
            )
        }
        if (onCopy != null && copyDescription != null) {
            InspectorRoundIconButton(
                contentDescription = copyDescription,
                onClick = onCopy,
                size = 44.dp,
                icon = {
                    InspectorGlyphIcon(
                        InspectorGlyph.Copy,
                        contentDescription = null,
                        tint = colors.muted,
                        size = 17.dp,
                    )
                },
            )
        }
    }
}

/** The section on screen in a chips tab, given the whole remaining height. */
@Composable
private fun FilledSectionBody(
    spec: InspectorDetailSectionSpec,
    search: TabbedDetailSearch,
    showRaw: Boolean?,
    onShowRawChange: (Boolean) -> Unit,
    onExpandFullScreen: () -> Unit,
) {
    val scrollingColumn = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
    when (val body = spec.body) {
        is InspectorDetailSectionBody.Formattable ->
            InspectorFormattableBody(
                body = body,
                modifier = Modifier.fillMaxSize().padding(bottom = 12.dp),
                showRaw = showRaw,
                onShowRawChange = onShowRawChange,
                sectionKey = spec.key,
                searchMatches = search.visibleMatches,
                currentMatchOrdinal = search.activeOrdinal,
                onExpandFullScreen = onExpandFullScreen,
                fillHeight = true,
            )
        is InspectorDetailSectionBody.KeyValues ->
            Column(modifier = scrollingColumn) {
                InspectorKeyValueList(
                    entries = body.entries,
                    sectionKey = spec.key,
                    searchMatches = search.visibleMatches,
                    currentMatchOrdinal = search.activeOrdinal,
                )
            }
        is InspectorDetailSectionBody.Code ->
            Column(modifier = scrollingColumn) {
                InspectorCodeBlock(
                    lines = body.lines,
                    sectionKey = spec.key,
                    searchMatches = search.visibleMatches,
                    currentMatchOrdinal = search.activeOrdinal,
                    onExpandFullScreen = onExpandFullScreen,
                )
            }
        is InspectorDetailSectionBody.Bars -> Column(modifier = scrollingColumn) { InspectorProgressBars(body.stats) }
        is InspectorDetailSectionBody.Empty ->
            Column(
                modifier = scrollingColumn,
            ) { InspectorDetailEmptyText(body.text) }
    }
}

/** The full-screen reader for a code or formattable section; renders nothing for other shapes. */
@Suppress("LongParameterList") // Mirrors the reader's own search and toggle inputs.
@Composable
private fun TabbedDetailFullScreen(
    spec: InspectorDetailSectionSpec,
    search: TabbedDetailSearch,
    showRaw: Boolean?,
    onShowRawChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copyDescription = spec.copyDescription ?: "Copy ${spec.label}"
    when (val body = spec.body) {
        is InspectorDetailSectionBody.Code ->
            InspectorCodeFullScreenOverlay(
                title = spec.label,
                lines = body.lines,
                onDismiss = onDismiss,
                modifier = modifier,
                sectionKey = spec.key,
                searchMatches = search.visibleMatches,
                currentMatchOrdinal = search.activeOrdinal,
                onCopy = spec.onCopy,
                copyContentDescription = copyDescription,
            )
        is InspectorDetailSectionBody.Formattable ->
            InspectorFormattableFullScreenOverlay(
                title = spec.label,
                body = body,
                onDismiss = onDismiss,
                modifier = modifier,
                showRaw = showRaw,
                onShowRawChange = onShowRawChange,
                sectionKey = spec.key,
                searchMatches = search.visibleMatches,
                currentMatchOrdinal = search.activeOrdinal,
                onCopy = spec.onCopy,
                copyContentDescription = copyDescription,
            )
        else -> Unit
    }
}
