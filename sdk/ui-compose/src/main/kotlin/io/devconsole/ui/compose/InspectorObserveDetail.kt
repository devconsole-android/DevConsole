/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "TooManyFunctions")

package io.devconsole.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * The full-screen capture detail's section content: key-value, code, and progress-bar body shapes,
 * plus the pre-resolved empty shape a caller can pass directly (e.g. "no body sent"). See
 * [resolveDetailSection] for the query filtering/highlighting/auto-expand behavior applied on top of
 * whichever shape a section starts as.
 */
internal sealed interface InspectorDetailSectionBody {
    data class KeyValues(
        val entries: List<InspectorKeyValue>,
    ) : InspectorDetailSectionBody

    data class Code(
        val lines: List<InspectorCodeLine>,
    ) : InspectorDetailSectionBody

    /**
     * A captured body/payload that may be JSON or XML: [rawText]/[rawLines]
     * are always available (redaction-aware, exactly as captured) as both the search-highlight
     * source and the "Raw" side of the viewer's toggle; [formatted] is non-null when [rawText]
     * parsed as JSON or pretty-printed as XML, and [tooLarge] flags a body over
     * [MAX_FORMATTABLE_BODY_BYTES] that skipped formatting outright. See [InspectorFormattableBody].
     *
     * [jsonHighlightPaths] carries the mock-response-diff feature's highlight set (see
     * [computeJsonMockDiff]) straight through to [flattenJsonTree] when [formatted] is JSON; empty
     * for every other body, including this same body once a search query degrades it to [Code].
     */
    data class Formattable(
        val rawText: String,
        val rawLines: List<InspectorCodeLine>,
        val formatted: FormattedBody?,
        val tooLarge: Boolean = false,
        val jsonHighlightPaths: Set<String> = emptySet(),
    ) : InspectorDetailSectionBody

    data class Bars(
        val stats: List<InspectorProgressStat>,
    ) : InspectorDetailSectionBody

    data class Empty(
        val text: String,
    ) : InspectorDetailSectionBody
}

/** One section of a capture detail screen, mirroring one `aSec(key, label, body, copyAs)` call. */
internal data class InspectorDetailSectionSpec(
    val key: String,
    val label: String,
    val body: InspectorDetailSectionBody,
    val copyDescription: String? = null,
    val onCopy: (() -> Unit)? = null,
)

/** Bundles [InspectorDetailHeader]'s payload so [InspectorObserveDetailScreen] stays under the param-count limit. */
internal data class InspectorObserveDetailHeaderSpec(
    val kindLabel: String,
    val leadText: String,
    val leadColor: Color,
    val leadContainerColor: Color,
    val title: String,
    val subtitle: String,
    val status: String,
    val statusColor: Color,
    val actions: List<InspectorTopAction> = emptyList(),
)

private data class ResolvedDetailSection(
    val expanded: Boolean,
    val hits: Int,
    val meta: String,
    val metaColor: Color,
    val body: InspectorDetailSectionBody?,
    val emptyMessage: String?,
    val searchMatches: List<InspectorDetailSearchMatch> = emptyList(),
)

private fun matchesQuery(
    key: String,
    value: String,
    lowerQuery: String,
): Boolean = "$key $value".lowercase(Locale.US).contains(lowerQuery)

/**
 * Filters/highlights [spec]'s body against [query], auto-expands a section with hits while a query
 * is active, and otherwise defers to [openState]'s manually-toggled open/closed flag for [spec]'s key.
 *
 * The per-shape branches are kept in one function rather than split further, to keep the filtering
 * logic easy to compare across shapes.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun resolveDetailSection(
    spec: InspectorDetailSectionSpec,
    query: String,
    openState: Map<String, Boolean>,
    colors: DevConsoleColors,
): ResolvedDetailSection {
    val trimmedQuery = query.trim()
    val lowerQuery = trimmedQuery.lowercase(Locale.US)
    val hasQuery = lowerQuery.isNotEmpty()

    var hits = 0
    var total = 0
    var displayBody: InspectorDetailSectionBody? = null
    var queryEmptyMessage: String? = null

    when (val body = spec.body) {
        is InspectorDetailSectionBody.KeyValues -> {
            total = body.entries.size
            val filtered =
                if (hasQuery) body.entries.filter { matchesQuery(it.key, it.value, lowerQuery) } else body.entries
            hits = if (hasQuery) filtered.size else 0
            if (hasQuery && filtered.isEmpty()) {
                queryEmptyMessage = "No field matches “$trimmedQuery”"
            } else if (filtered.isNotEmpty()) {
                displayBody = InspectorDetailSectionBody.KeyValues(filtered)
            }
        }
        is InspectorDetailSectionBody.Code -> {
            total = body.lines.size
            val highlighted =
                body.lines.map { line ->
                    val matches = hasQuery && matchesQuery(line.key, line.value, lowerQuery)
                    if (matches) hits++
                    line.copy(highlighted = matches)
                }
            if (highlighted.isNotEmpty()) displayBody = InspectorDetailSectionBody.Code(highlighted)
        }
        is InspectorDetailSectionBody.Formattable -> {
            // Search degrades to the same flat, highlighted raw-line view Code sections use --
            // matching/highlighting individual nodes inside the collapsible JSON tree is out of
            // scope here.
            // With no active query the section stays Formattable, so the card renders the
            // Raw/Formatted toggle and (for JSON) the collapsible tree as normal.
            total = body.rawLines.size
            if (hasQuery) {
                val highlighted =
                    body.rawLines.map { line ->
                        val matches = matchesQuery(line.key, line.value, lowerQuery)
                        if (matches) hits++
                        line.copy(highlighted = matches)
                    }
                if (highlighted.isNotEmpty()) displayBody = InspectorDetailSectionBody.Code(highlighted)
            } else {
                displayBody = body
            }
        }
        is InspectorDetailSectionBody.Bars -> {
            total = body.stats.size
            if (body.stats.isNotEmpty()) displayBody = body
        }
        is InspectorDetailSectionBody.Empty -> Unit
    }

    val expanded = if (hasQuery && hits > 0) true else openState[spec.key] == true
    val meta =
        when {
            hasQuery -> if (hits > 0) "$hits match${if (hits == 1) "" else "es"}" else "no match"
            expanded -> if (total > 0) total.toString() else ""
            else -> if (total > 0) "$total line${if (total == 1) "" else "s"}" else ""
        }
    val metaColor = if (hasQuery) (if (hits > 0) colors.signal else colors.text3) else colors.text3

    if (!expanded) {
        return ResolvedDetailSection(
            expanded = false,
            hits = hits,
            meta = meta,
            metaColor = metaColor,
            body = null,
            emptyMessage = null,
        )
    }
    val emptyMessage =
        queryEmptyMessage
            ?: if (displayBody == null) {
                (spec.body as? InspectorDetailSectionBody.Empty)?.text ?: "Nothing captured."
            } else {
                null
            }
    return ResolvedDetailSection(
        expanded = true,
        hits = hits,
        meta = meta,
        metaColor = metaColor,
        body = if (emptyMessage != null) null else displayBody,
        emptyMessage = emptyMessage,
    )
}

/** Network detail resolver: preserves every original body shape and decorates it with match metadata. */
private fun resolveNetworkDetailSection(
    spec: InspectorDetailSectionSpec,
    query: String,
    openState: Map<String, Boolean>,
    colors: DevConsoleColors,
    matches: List<InspectorDetailSearchMatch>,
    searchable: Boolean,
): ResolvedDetailSection {
    val hasQuery = query.trim().isNotEmpty()
    val total = detailBodyItemCount(spec.body)
    val expanded = if (hasQuery && matches.isNotEmpty()) true else openState[spec.key] == true
    val meta =
        when {
            hasQuery && searchable ->
                if (matches.isNotEmpty()) {
                    "${matches.size} match${if (matches.size == 1) "" else "es"}"
                } else {
                    "no match"
                }
            expanded -> if (total > 0) total.toString() else ""
            else -> if (total > 0) "$total line${if (total == 1) "" else "s"}" else ""
        }
    val metaColor =
        if (hasQuery && searchable) {
            if (matches.isNotEmpty()) colors.signal else colors.text3
        } else {
            colors.text3
        }
    if (!expanded) {
        return ResolvedDetailSection(
            expanded = false,
            hits = matches.size,
            meta = meta,
            metaColor = metaColor,
            body = null,
            emptyMessage = null,
            searchMatches = matches,
        )
    }
    val emptyMessage = (spec.body as? InspectorDetailSectionBody.Empty)?.text
    return ResolvedDetailSection(
        expanded = true,
        hits = matches.size,
        meta = meta,
        metaColor = metaColor,
        body = if (emptyMessage == null) spec.body else null,
        emptyMessage = emptyMessage,
        searchMatches = matches,
    )
}

private fun detailBodyItemCount(body: InspectorDetailSectionBody): Int =
    when (body) {
        is InspectorDetailSectionBody.KeyValues -> body.entries.size
        is InspectorDetailSectionBody.Code -> body.lines.size
        is InspectorDetailSectionBody.Formattable -> body.rawLines.size
        is InspectorDetailSectionBody.Bars -> body.stats.size
        is InspectorDetailSectionBody.Empty -> 0
    }

@Composable
private fun InspectorDetailSectionCard(
    spec: InspectorDetailSectionSpec,
    resolved: ResolvedDetailSection,
    currentMatchOrdinal: Int?,
    formattableShowRaw: Boolean?,
    onFormattableShowRawChange: ((Boolean) -> Unit)?,
    onToggle: () -> Unit,
    onExpandCodeFullScreen: (String) -> Unit,
) {
    // 12dp bottom margin per card, including the last one --
    // InspectorDetailScaffold's own 100dp bottom content padding covers the footer clearance on top.
    CollapsibleSection(
        label = spec.label,
        expanded = resolved.expanded,
        onToggle = onToggle,
        modifier = Modifier.padding(bottom = 12.dp),
        meta = resolved.meta.ifEmpty { null },
        metaColor = resolved.metaColor,
        onCopy = spec.onCopy,
        copyContentDescription = spec.copyDescription ?: "Copy ${spec.label}",
    ) {
        val emptyMessage = resolved.emptyMessage
        when {
            emptyMessage != null -> InspectorDetailEmptyText(emptyMessage)
            else ->
                when (val body = resolved.body) {
                    is InspectorDetailSectionBody.KeyValues ->
                        InspectorKeyValueList(
                            entries = body.entries,
                            sectionKey = spec.key,
                            searchMatches = resolved.searchMatches,
                            currentMatchOrdinal = currentMatchOrdinal,
                        )
                    is InspectorDetailSectionBody.Code ->
                        InspectorCodeBlock(
                            lines = body.lines,
                            sectionKey = spec.key,
                            searchMatches = resolved.searchMatches,
                            currentMatchOrdinal = currentMatchOrdinal,
                            onExpandFullScreen = { onExpandCodeFullScreen(spec.key) },
                        )
                    is InspectorDetailSectionBody.Formattable ->
                        InspectorFormattableBody(
                            body = body,
                            showRaw = formattableShowRaw,
                            onShowRawChange = onFormattableShowRawChange,
                            sectionKey = spec.key,
                            searchMatches = resolved.searchMatches,
                            currentMatchOrdinal = currentMatchOrdinal,
                            onExpandFullScreen = { onExpandCodeFullScreen(spec.key) },
                        )
                    is InspectorDetailSectionBody.Bars -> InspectorProgressBars(body.stats)
                    is InspectorDetailSectionBody.Empty, null -> InspectorDetailEmptyText("Nothing captured.")
                }
        }
    }
}

/**
 * The full-screen capture detail overlay for all four capture kinds: header + live find field,
 * [sections] as [CollapsibleSection]s with search-driven expand/highlight behavior, and a footer
 * action bar. [resetKey] identifies which capture is open (e.g. a transaction id) so the find query
 * and each section's manual open/closed state reset when a different capture is opened. Both the
 * header's back button and the system back gesture close it via [onBack].
 *
 * LongMethod: the full-screen code overlay branch pushed this a couple lines past the default budget.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun InspectorObserveDetailScreen(
    resetKey: Any,
    header: InspectorObserveDetailHeaderSpec,
    sections: List<InspectorDetailSectionSpec>,
    initiallyOpenSectionKeys: Set<String>,
    footerActions: List<InspectorFooterAction>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    searchPlaceholder: String = DEFAULT_DETAIL_SEARCH_PLACEHOLDER,
    searchOptions: InspectorDetailSearchOptions? = null,
) {
    var query by rememberSaveable(resetKey) { mutableStateOf("") }
    var selectedSectionKeys by
        rememberSaveable(resetKey) {
            mutableStateOf(searchOptions?.defaultSectionKeys?.toList().orEmpty())
        }
    // Enums are Serializable, so the mode survives process death without a name round-trip.
    var searchMode by
        rememberSaveable(resetKey) {
            mutableStateOf(searchOptions?.defaultMode ?: InspectorSearchMode.KEYS_AND_VALUES)
        }
    var currentMatchOrdinal by rememberSaveable(resetKey) { mutableIntStateOf(0) }
    var rawFormattableSectionKeys by rememberSaveable(resetKey) { mutableStateOf(emptyList<String>()) }
    var searchOptionsVisible by remember(resetKey) { mutableStateOf(false) }
    val openState =
        remember(resetKey) {
            mutableStateMapOf<String, Boolean>().apply { initiallyOpenSectionKeys.forEach { key -> this[key] = true } }
        }
    val colors = DevConsoleTheme.colors
    val selectedKeys = remember(selectedSectionKeys) { selectedSectionKeys.toSet() }
    val rawSectionKeys = remember(rawFormattableSectionKeys) { rawFormattableSectionKeys.toSet() }
    val searchableSectionKeys =
        remember(searchOptions) { searchOptions?.sections?.mapTo(mutableSetOf()) { it.key }.orEmpty() }
    val searchableSectionBodies = inspectorSearchSectionBodies(sections, searchableSectionKeys)
    val hasNetworkQuery = searchOptions != null && query.isNotBlank()
    val networkSearchCandidates =
        remember(searchableSectionBodies, rawSectionKeys, hasNetworkQuery) {
            if (!hasNetworkQuery) {
                emptyList()
            } else {
                searchableSectionBodies.flatMap { section ->
                    searchInspectorBodyCandidates(
                        sectionKey = section.sectionKey,
                        body = section.body,
                        representation =
                            if (section.sectionKey in rawSectionKeys) {
                                InspectorBodySearchRepresentation.RAW
                            } else {
                                InspectorBodySearchRepresentation.FORMATTED
                            },
                    )
                }
            }
        }
    val networkMatches =
        remember(networkSearchCandidates, query, selectedKeys, searchMode, hasNetworkQuery) {
            if (hasNetworkQuery) {
                searchInspectorCandidates(networkSearchCandidates, query, selectedKeys, searchMode)
            } else {
                emptyList()
            }
        }
    val activeMatchOrdinal =
        if (networkMatches.isEmpty()) 0 else currentMatchOrdinal.coerceIn(0, networkMatches.lastIndex)
    val resolved =
        sections.map { spec ->
            if (searchOptions != null) {
                val sectionMatches = networkMatches.filter { it.sectionKey == spec.key }
                spec to
                    resolveNetworkDetailSection(
                        spec = spec,
                        query = query,
                        openState = openState,
                        colors = colors,
                        matches = sectionMatches,
                        searchable = spec.key in searchableSectionKeys,
                    )
            } else {
                spec to resolveDetailSection(spec, query, openState, colors)
            }
        }
    val totalHits = if (searchOptions != null) networkMatches.size else resolved.sumOf { (_, section) -> section.hits }

    // Which section's code block (if any) is showing full-screen.
    // Bundle-safe on its own (a String key), and the lines it needs are re-derived from `resolved`
    // above on recreation rather than saved themselves -- InspectorCodeLine carries a Color, which
    // isn't a Saveable type.
    var fullScreenSectionKey by rememberSaveable(resetKey) { mutableStateOf<String?>(null) }
    val fullScreenSection =
        fullScreenSectionKey?.let { key ->
            resolved.firstOrNull { (spec, _) -> spec.key == key }
        }

    BackHandler(onBack = onBack)

    if (fullScreenSection != null) {
        val (spec, section) = fullScreenSection
        val body = section.body
        if (body is InspectorDetailSectionBody.Code) {
            InspectorCodeFullScreenOverlay(
                title = spec.label,
                lines = body.lines,
                onDismiss = { fullScreenSectionKey = null },
                modifier = modifier.fillMaxSize(),
                sectionKey = spec.key,
                searchMatches = section.searchMatches,
                currentMatchOrdinal = activeMatchOrdinal,
                onCopy = spec.onCopy,
                copyContentDescription = spec.copyDescription ?: "Copy ${spec.label}",
            )
            return
        }
        if (body is InspectorDetailSectionBody.Formattable) {
            InspectorFormattableFullScreenOverlay(
                title = spec.label,
                body = body,
                onDismiss = { fullScreenSectionKey = null },
                showRaw =
                    if (searchOptions != null) {
                        body.formatted == null || spec.key in rawSectionKeys
                    } else {
                        null
                    },
                onShowRawChange =
                    if (searchOptions != null) {
                        { showRaw ->
                            rawFormattableSectionKeys =
                                if (showRaw) {
                                    (rawFormattableSectionKeys + spec.key).distinct()
                                } else {
                                    rawFormattableSectionKeys.filterNot { it == spec.key }
                                }
                            currentMatchOrdinal = 0
                        }
                    } else {
                        null
                    },
                modifier = modifier.fillMaxSize(),
                sectionKey = spec.key,
                searchMatches = section.searchMatches,
                currentMatchOrdinal = activeMatchOrdinal,
                onCopy = spec.onCopy,
                copyContentDescription = spec.copyDescription ?: "Copy ${spec.label}",
            )
            return
        }
    }

    InspectorDetailScaffold(
        modifier = modifier.fillMaxSize(),
        header = {
            InspectorDetailHeader(
                kindLabel = header.kindLabel,
                leadText = header.leadText,
                leadColor = header.leadColor,
                leadContainerColor = header.leadContainerColor,
                title = header.title,
                subtitle = header.subtitle,
                status = header.status,
                statusColor = header.statusColor,
                onBack = onBack,
                actions = header.actions,
            )
            InspectorDetailSearchField(
                query = query,
                onQueryChange = {
                    query = it
                    currentMatchOrdinal = 0
                },
                matchLabel =
                    if (query.isBlank()) {
                        ""
                    } else if (searchOptions != null) {
                        "${if (networkMatches.isEmpty()) 0 else activeMatchOrdinal + 1}/$totalHits"
                    } else {
                        "$totalHits match${if (totalHits == 1) "" else "es"}"
                    },
                onPrevious =
                    if (searchOptions != null) {
                        { currentMatchOrdinal = previousInspectorMatchIndex(activeMatchOrdinal, networkMatches.size) }
                    } else {
                        null
                    },
                onNext =
                    if (searchOptions != null) {
                        { currentMatchOrdinal = nextInspectorMatchIndex(activeMatchOrdinal, networkMatches.size) }
                    } else {
                        null
                    },
                navigationEnabled = networkMatches.isNotEmpty(),
                onOpenOptions = if (searchOptions != null) ({ searchOptionsVisible = true }) else null,
                scopeLabel = searchOptions?.let { inspectorSearchScopeSummary(it, selectedKeys) },
                modifier = Modifier.padding(top = 12.dp),
                placeholder = searchPlaceholder,
            )
        },
        footer = { InspectorDetailFooterBar(footerActions) },
    ) {
        resolved.forEach { (spec, section) ->
            InspectorDetailSectionCard(
                spec = spec,
                resolved = section,
                currentMatchOrdinal = if (searchOptions != null) activeMatchOrdinal else null,
                formattableShowRaw =
                    if (searchOptions != null) {
                        (spec.body as? InspectorDetailSectionBody.Formattable)?.let { body ->
                            body.formatted == null || spec.key in rawSectionKeys
                        }
                    } else {
                        null
                    },
                onFormattableShowRawChange =
                    if (searchOptions != null) {
                        { showRaw ->
                            rawFormattableSectionKeys =
                                if (showRaw) {
                                    (rawFormattableSectionKeys + spec.key).distinct()
                                } else {
                                    rawFormattableSectionKeys.filterNot { it == spec.key }
                                }
                            currentMatchOrdinal = 0
                        }
                    } else {
                        null
                    },
                onToggle = { openState[spec.key] = !section.expanded },
                onExpandCodeFullScreen = { key -> fullScreenSectionKey = key },
            )
        }
    }
    if (searchOptionsVisible && searchOptions != null) {
        InspectorDetailSearchOptionsSheet(
            options = searchOptions,
            selectedSectionKeys = selectedKeys,
            mode = searchMode,
            onDismiss = { searchOptionsVisible = false },
            onApply = { newKeys, newMode ->
                selectedSectionKeys = newKeys.toList()
                searchMode = newMode
                currentMatchOrdinal = 0
                searchOptionsVisible = false
            },
        )
    }
}

/** Everything [InspectorObserveDetailScreen] needs for one capture, built by the per-kind `*DetailContent` builders. */
internal data class ObserveDetailContent(
    val header: InspectorObserveDetailHeaderSpec,
    val sections: List<InspectorDetailSectionSpec>,
    val initiallyOpenSectionKeys: Set<String>,
    val footerActions: List<InspectorFooterAction>,
    /**
     * Overridden only by kinds whose sections are not a captured request/response. The default names
     * what a network, socket, push, log or crash detail actually holds; a Remote Config key holds
     * one value, and offering to search its "headers" would be describing a screen that isn't there.
     */
    val searchPlaceholder: String = DEFAULT_DETAIL_SEARCH_PLACEHOLDER,
    val searchOptions: InspectorDetailSearchOptions? = null,
)

/** Matches [InspectorDetailSearchField]'s own default; named here so callers can opt out of it. */
internal const val DEFAULT_DETAIL_SEARCH_PLACEHOLDER = "Find in headers, payload, response"

/** Redacted-aware header/kv rows shared by every kind's detail sections that render a raw string map. */
internal fun headerRowsBody(
    headers: Map<String, String>,
    colors: DevConsoleColors,
): InspectorDetailSectionBody.KeyValues =
    InspectorDetailSectionBody.KeyValues(
        headers.entries.sortedBy { it.key.lowercase(Locale.US) }.map { (key, value) ->
            InspectorKeyValue(key, value, if (value.looksRedacted()) colors.warn else null)
        },
    )

/** "$key: $value" per entry, in display order -- the section copy button's clipboard payload for a KeyValues body. */
internal fun List<InspectorKeyValue>.toCopyText(): String = joinToString("\n") { "${it.key}: ${it.value}" }

/** A KeyValues section's `onCopy`: null (no copy button) once [entries] is empty. */
internal fun keyValuesCopyAction(
    entries: List<InspectorKeyValue>,
    copyText: (String) -> Unit,
): (() -> Unit)? = entries.takeIf { it.isNotEmpty() }?.let { list -> { copyText(list.toCopyText()) } }

internal fun copyIconAction(tint: Color): @Composable () -> Unit =
    { InspectorGlyphIcon(InspectorGlyph.Copy, contentDescription = null, tint = tint, size = 19.dp) }

internal fun shareIconAction(tint: Color): @Composable () -> Unit =
    { ObserveGlyphIcon(ObserveGlyph.Download, contentDescription = null, tint = tint, size = 19.dp) }

/**
 * A flat captured body preview rendered as an unadorned code block, one real line per line. Shared
 * by the net (request/response body), frame (payload) and log (context) detail builders. A line
 * that carries the SDK's own redaction marker is tinted [DevConsoleColors.warn] instead of ink,
 * so a masked value inside a request payload, response body or frame payload reads as distinct
 * from captured content.
 */
@Suppress("LongParameterList") // key/label/preview/isBinaryPlaceholder/emptyText/colors/copyText each vary per call.
internal fun textPreviewSection(
    key: String,
    label: String,
    preview: String?,
    isBinaryPlaceholder: Boolean,
    emptyText: String,
    colors: DevConsoleColors,
    copyText: (String) -> Unit,
    copyDescription: String? = null,
    jsonHighlightPaths: Set<String> = emptySet(),
): InspectorDetailSectionSpec {
    val body =
        when {
            preview == null -> InspectorDetailSectionBody.Empty(emptyText)
            isBinaryPlaceholder -> InspectorDetailSectionBody.Empty("Binary body captured — not shown as text.")
            else -> formattableTextBody(preview, colors, jsonHighlightPaths)
        }
    // Only a Formattable body has real content to copy -- a null/binary-placeholder Empty body gets
    // no copy button rather than one that would clipboard a placeholder string.
    val onCopy =
        (body as? InspectorDetailSectionBody.Formattable)?.let { formattable -> { copyText(formattable.rawText) } }
    return InspectorDetailSectionSpec(key, label, body, copyDescription, onCopy)
}

/** Shared by [textPreviewSection] and the log detail's context body -- see [textPreviewSection]'s own doc. */
internal fun String.toRedactionAwareCodeLine(colors: DevConsoleColors): InspectorCodeLine =
    InspectorCodeLine(value = this, valueColor = if (looksRedacted()) colors.warn else colors.ink)

/**
 * Builds an [InspectorDetailSectionBody.Formattable] from a raw captured [text]: [analyzeBodyFormat]
 * sniffs/parses/pretty-prints it (JSON tree or XML text) when possible, while [rawLines] always
 * holds the exact original text, redaction-aware, as both the search-highlight source and the
 * viewer's "Raw" toggle state.
 * [jsonHighlightPaths] passes straight through to the resulting body's own field of the same name.
 */
internal fun formattableTextBody(
    text: String,
    colors: DevConsoleColors,
    jsonHighlightPaths: Set<String> = emptySet(),
): InspectorDetailSectionBody.Formattable {
    val outcome = analyzeBodyFormat(text)
    return InspectorDetailSectionBody.Formattable(
        rawText = text,
        rawLines = text.lines().map { line -> line.toRedactionAwareCodeLine(colors) },
        formatted = (outcome as? BodyFormatOutcome.Formatted)?.body,
        tooLarge = outcome is BodyFormatOutcome.TooLarge,
        jsonHighlightPaths = jsonHighlightPaths,
    )
}

/** Shared `{ "k": "v", ... }` renderer for the frame/push/log "share as JSON" snippets. */
internal fun Map<String, String>.jsonObjectSnippet(): String =
    if (isEmpty()) {
        "{}"
    } else {
        entries.joinToString(prefix = "{\n", postfix = "\n  }", separator = ",\n") { (key, value) ->
            "    ${key.jsonQuoted()}: ${value.jsonQuoted()}"
        }
    }
