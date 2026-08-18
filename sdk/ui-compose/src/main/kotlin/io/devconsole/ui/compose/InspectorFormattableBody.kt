/**
 * @author Shakib
 * @since 05/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.devconsole.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders an [InspectorDetailSectionBody.Formattable] section inline (capped height, same as
 * [InspectorCodeBlock]): a Raw/Formatted toggle when formatting is available, a size-guard notice
 * when it isn't (body too large), and either the raw redaction-aware lines, a pretty-printed XML
 * code block, or -- for JSON -- the collapsible tree ([flattenJsonTree]). [onExpandFullScreen], when
 * given, shows the same top-right expand button [InspectorCodeBlock] does.
 */
@Composable
internal fun InspectorFormattableBody(
    body: InspectorDetailSectionBody.Formattable,
    modifier: Modifier = Modifier,
    showRaw: Boolean? = null,
    onShowRawChange: ((Boolean) -> Unit)? = null,
    sectionKey: String = "",
    searchMatches: List<InspectorDetailSearchMatch> = emptyList(),
    currentMatchOrdinal: Int? = null,
    onExpandFullScreen: (() -> Unit)? = null,
) {
    val colors = DevConsoleTheme.colors
    var localShowRaw by rememberSaveable(body.rawText) { mutableStateOf(body.formatted == null) }
    val renderedShowRaw = showRaw ?: localShowRaw
    val updateShowRaw: (Boolean) -> Unit = { value ->
        if (showRaw == null) localShowRaw = value else onShowRawChange?.invoke(value)
    }
    val precomputed =
        rememberFormattableContent(
            body = body,
            showRaw = renderedShowRaw,
            sectionKey = sectionKey,
            searchMatches = searchMatches,
            currentMatchOrdinal = currentMatchOrdinal,
        )
    val listState = rememberLazyListState()
    val sectionMatches = searchMatches.filter { it.sectionKey == sectionKey || sectionKey.isEmpty() }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(precomputed.targetIndex, currentMatchOrdinal) {
        precomputed.targetIndex?.let { index ->
            listState.animateScrollToItem(index.coerceAtLeast(0))
        }
        if (sectionMatches.any { it.ordinal == currentMatchOrdinal }) {
            bringIntoViewRequester.bringIntoView()
        }
    }
    Column(modifier = modifier.fillMaxWidth().bringIntoViewRequester(bringIntoViewRequester)) {
        if (body.formatted != null) {
            RawFormattedToggle(
                showRaw = renderedShowRaw,
                onToggle = updateShowRaw,
                // Matches the 16dp horizontal inset InspectorKeyValueList/InspectorProgressBars/
                // InspectorDetailEmptyText each apply themselves for a CollapsibleSection's content --
                // FilterChipRow carries no inset of its own (its other callers sit inside a parent
                // that already pads), so without this the chip row alone sat flush against the
                // section/card edge while every sibling body stayed inset.
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
            )
        }
        if (body.tooLarge) {
            val byteCount =
                body.rawText
                    .toByteArray(Charsets.UTF_8)
                    .size
                    .toLong()
            val size = formatByteSize(byteCount)
            WarnNote(
                "Body too large to format ($size) — showing raw text.",
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.codeBg)
                        .heightIn(max = InlineCodeBlockMaxHeight)
                        // Same known tradeoff as InspectorCodeBlock's own horizontalScroll: a
                        // LazyColumn only measures composed rows, so the scrollable width can
                        // under-report and jump as wider not-yet-composed rows virtualize in --
                        // only visible for tall bodies with progressively wider lines, not a bug.
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                content = precomputed.content,
            )
            if (onExpandFullScreen != null) {
                InspectorCodeBlockExpandButton(onExpandFullScreen)
            }
        }
    }
}

/** Full-screen counterpart of [InspectorFormattableBody] -- see [InspectorCodeFullScreenOverlay]'s general overload. */
@Composable
internal fun InspectorFormattableFullScreenOverlay(
    title: String,
    body: InspectorDetailSectionBody.Formattable,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    showRaw: Boolean? = null,
    onShowRawChange: ((Boolean) -> Unit)? = null,
    sectionKey: String = "",
    searchMatches: List<InspectorDetailSearchMatch> = emptyList(),
    currentMatchOrdinal: Int? = null,
    onCopy: (() -> Unit)? = null,
    copyContentDescription: String = "Copy $title",
) {
    var localShowRaw by rememberSaveable(body.rawText) { mutableStateOf(body.formatted == null) }
    val renderedShowRaw = showRaw ?: localShowRaw
    val updateShowRaw: (Boolean) -> Unit = { value ->
        if (showRaw == null) localShowRaw = value else onShowRawChange?.invoke(value)
    }
    val precomputed =
        rememberFormattableContent(
            body = body,
            showRaw = renderedShowRaw,
            fontSize = 14.sp,
            lineHeight = 23.8.sp,
            sectionKey = sectionKey,
            searchMatches = searchMatches,
            currentMatchOrdinal = currentMatchOrdinal,
        )
    InspectorCodeFullScreenOverlay(
        title = title,
        onDismiss = onDismiss,
        modifier = modifier,
        headerTrailing =
            if (body.formatted != null) {
                { RawFormattedToggle(showRaw = renderedShowRaw, onToggle = updateShowRaw, compact = true) }
            } else {
                null
            },
        targetIndex = precomputed.targetIndex,
        onCopy = onCopy,
        copyContentDescription = copyContentDescription,
        meta = inspectorLineCountMeta(body.rawLines.size),
        content = precomputed.content,
    )
}

/**
 * Precomputes whichever [LazyListScope] content [InspectorFormattableBody]/
 * [InspectorFormattableFullScreenOverlay] will show, as a plain closure over already-`remember`ed
 * state. This has to happen out here, in a real `@Composable` function body, rather than inside the
 * `content: LazyListScope.() -> Unit` lambda itself -- that lambda type isn't `@Composable`, so
 * `remember`/[rememberJsonTreeRows] can't be called from inside it directly.
 */
private data class FormattableRenderContent(
    val content: LazyListScope.() -> Unit,
    val targetIndex: Int?,
)

@Composable
private fun rememberFormattableContent(
    body: InspectorDetailSectionBody.Formattable,
    showRaw: Boolean,
    fontSize: TextUnit = 13.sp,
    lineHeight: TextUnit = 22.1.sp,
    sectionKey: String = "",
    searchMatches: List<InspectorDetailSearchMatch> = emptyList(),
    currentMatchOrdinal: Int? = null,
): FormattableRenderContent {
    val colors = DevConsoleTheme.colors
    val sectionMatches = searchMatches.filter { it.sectionKey == sectionKey || sectionKey.isEmpty() }
    val highlightIndex =
        remember(sectionMatches, currentMatchOrdinal) {
            indexInspectorSearchHighlights(sectionMatches, currentMatchOrdinal)
        }
    val formatted = body.formatted
    val useRaw = showRaw || formatted == null

    val xmlLines: List<InspectorCodeLine>? =
        if (!useRaw && formatted is FormattedBody.Xml) {
            remember(formatted.text) { formatted.text.lines().map { it.toRedactionAwareCodeLine(colors) } }
        } else {
            null
        }
    val jsonTree: Pair<List<JsonTreeRow>, (String) -> Unit>? =
        if (!useRaw && formatted is FormattedBody.Json) {
            rememberJsonTreeRows(
                root = formatted.root,
                highlightedPaths = body.jsonHighlightPaths,
                searchMatches = sectionMatches,
                currentMatchOrdinal = currentMatchOrdinal,
                forcedExpandedPaths = sectionMatches.flatMap { it.ancestorPaths }.toSet(),
            )
        } else {
            null
        }

    val activeMatch = sectionMatches.firstOrNull { it.ordinal == currentMatchOrdinal }
    val targetIndex =
        if (jsonTree != null) {
            val rows = jsonTree.first
            activeMatch?.path?.let { path -> rows.indexOfFirst { row -> row.path == path }.takeIf { it >= 0 } }
        } else {
            activeMatch
                ?.itemId
                ?.substringAfter("line:", "")
                ?.toIntOrNull()
        }
    return FormattableRenderContent(
        content = {
            when {
                xmlLines != null ->
                    itemsIndexed(xmlLines, key = { index, _ -> index }) { index, line ->
                        val itemId = "line:$index"
                        CodeLineRow(
                            line = line,
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                            valueHighlights =
                                highlightIndex.highlightsFor(
                                    itemId,
                                    InspectorSearchField.VALUE,
                                ),
                        )
                    }
                jsonTree != null -> {
                    val (rows, onToggle) = jsonTree
                    items(rows, key = { it.lazyKey() }) { row ->
                        JsonTreeRowView(row, onToggle, fontSize, lineHeight)
                    }
                }
                else ->
                    itemsIndexed(body.rawLines, key = { index, _ -> index }) { index, line ->
                        val itemId = "line:$index"
                        CodeLineRow(
                            line = line,
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                            valueHighlights =
                                highlightIndex.highlightsFor(
                                    itemId,
                                    InspectorSearchField.VALUE,
                                ),
                        )
                    }
            }
        },
        targetIndex = targetIndex,
    )
}

/**
 * Per-node expand/collapse state for one [JsonValue] tree, and the always-current flattened visible
 * list it produces -- [flattenJsonTree] never recurses into a collapsed node's children, so toggling
 * a huge array closed drops its subtree from this list (and from composition) entirely, not just
 * hides it. Overrides only record what a user has *changed*; every node defaults to expanded (see
 * [flattenJsonTree]'s own doc), so the initial render matches the old flat pretty-printed text.
 */
@Composable
private fun rememberJsonTreeRows(
    root: JsonValue,
    highlightedPaths: Set<String>,
    searchMatches: List<InspectorDetailSearchMatch> = emptyList(),
    currentMatchOrdinal: Int? = null,
    forcedExpandedPaths: Set<String> = emptySet(),
): Pair<List<JsonTreeRow>, (String) -> Unit> {
    val expandedOverrides = remember(root) { mutableStateMapOf<String, Boolean>() }
    val rows by remember(root, highlightedPaths, forcedExpandedPaths) {
        derivedStateOf {
            flattenJsonTree(
                root,
                isExpanded = { path -> path in forcedExpandedPaths || expandedOverrides[path] ?: true },
                highlightedPaths = highlightedPaths,
            )
        }
    }
    val highlightIndex =
        remember(searchMatches, currentMatchOrdinal) {
            indexInspectorSearchHighlights(searchMatches, currentMatchOrdinal)
        }
    val searchRows =
        remember(rows, highlightIndex) {
            rows.map { row ->
                row.copy(
                    searchKeyHighlights = highlightIndex.highlightsFor(row.path, InspectorSearchField.KEY),
                    searchValueHighlights = highlightIndex.highlightsFor(row.path, InspectorSearchField.VALUE),
                )
            }
        }
    val onToggle: (String) -> Unit = { path -> expandedOverrides[path] = !(expandedOverrides[path] ?: true) }
    return searchRows to onToggle
}

/** A two-chip Raw/Formatted switcher, built from the same [FilterChipRow] every other filter row uses. */
@Composable
private fun RawFormattedToggle(
    showRaw: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    // A segmented control, not chips: Formatted/Raw are two views of one body, not two filters over
    // a set. See DESIGN.md's Components section.
    InspectorSegmentedControl(
        options = listOf("Formatted", "Raw"),
        selectedIndex = if (showRaw) 1 else 0,
        onSelect = { index -> onToggle(index == 1) },
        modifier = if (compact) modifier else modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Width of the fold chevron (12dp glyph + 2dp trailing padding) every tree row reserves. */
private val JsonTreeChevronSlot = 14.dp

@Composable
private fun JsonTreeRowView(
    row: JsonTreeRow,
    onToggle: (String) -> Unit,
    fontSize: TextUnit,
    lineHeight: TextUnit,
) {
    val colors = DevConsoleTheme.colors
    val togglable =
        row.content is JsonTreeRowContent.ContainerStart || row.content is JsonTreeRowContent.ContainerCollapsed
    // Same signalSoft row-background search-hit highlight InspectorCodeBlock's CodeLineRow uses --
    // here it flags mock-response-diff fields (see computeJsonMockDiff) instead of a search match.
    val rowBg = if (row.highlighted) colors.signalSoft else Color.Transparent
    Row(
        modifier =
            (
                if (togglable) {
                    // defaultMinSize (not minimumInteractiveComponentSize) pads the touch target to
                    // 48dp while keeping the content start-aligned -- minimumInteractiveComponentSize
                    // centers its content, so a row as narrow as a bare '{' drifted right of the
                    // fixed-width chevron gutter every other row aligns against.
                    Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable(onClick = { onToggle(row.path) }, role = Role.Button)
                } else {
                    Modifier
                }
            ).background(rowBg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        JsonTreeMono("  ".repeat(row.depth), colors.text3, fontSize, lineHeight)
        // Every row spends the same JsonTreeChevronSlot ahead of its content -- rows without a
        // chevron (scalars, closing braces) fill it with a spacer so an opening brace and its
        // closing brace land in the same column instead of the open one sitting a chevron's
        // width further right.
        if (togglable) {
            val expanded = row.content is JsonTreeRowContent.ContainerStart
            InspectorGlyphIcon(
                InspectorGlyph.ChevronDown,
                contentDescription = null,
                tint = colors.muted,
                size = 12.dp,
                rotationDegrees = if (expanded) 0f else -90f,
                modifier = Modifier.padding(end = 2.dp),
            )
        } else {
            Spacer(Modifier.width(JsonTreeChevronSlot))
        }
        row.keyLabel?.let { key ->
            JsonTreeMono(
                text = "${key.jsonQuoted()}: ",
                color = colors.jsonKey,
                fontSize = fontSize,
                lineHeight = lineHeight,
                highlights = row.searchKeyHighlights,
            )
        }
        JsonTreeValueSpan(
            content = row.content,
            depth = row.depth,
            colors = colors,
            fontSize = fontSize,
            lineHeight = lineHeight,
            highlights = row.searchValueHighlights,
        )
        if (row.trailingComma) JsonTreeMono(",", colors.text3, fontSize, lineHeight)
    }
}

@Composable
private fun JsonTreeValueSpan(
    content: JsonTreeRowContent,
    depth: Int,
    colors: DevConsoleColors,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    highlights: List<InspectorSearchHighlight> = emptyList(),
) {
    when (content) {
        is JsonTreeRowContent.Scalar ->
            JsonTreeMono(content.text, content.type.color(colors), fontSize, lineHeight, highlights)
        is JsonTreeRowContent.ContainerStart ->
            JsonTreeMono(if (content.isArray) "[" else "{", colors.jsonBraceAt(depth), fontSize, lineHeight)
        is JsonTreeRowContent.ContainerEnd ->
            JsonTreeMono(if (content.isArray) "]" else "}", colors.jsonBraceAt(depth), fontSize, lineHeight)
        is JsonTreeRowContent.ContainerCollapsed -> {
            val open = if (content.isArray) "[" else "{"
            val close = if (content.isArray) "]" else "}"
            val noun = if (content.isArray) "item" else "key"
            val plural = if (content.childCount == 1) noun else "${noun}s"
            JsonTreeMono("$open…$close ${content.childCount} $plural", colors.jsonBraceAt(depth), fontSize, lineHeight)
        }
    }
}

private fun JsonScalarType.color(colors: DevConsoleColors): Color =
    when (this) {
        JsonScalarType.STRING -> colors.jsonString
        JsonScalarType.NUMBER -> colors.jsonNumber
        JsonScalarType.BOOLEAN -> colors.jsonBoolean
        JsonScalarType.NULL -> colors.jsonNull
    }

/**
 * [depth]'s rung on [DevConsoleColors.jsonBraces], cycling every 5 levels -- mirrors the web
 * dashboard's `depth % BRACE_LEVELS`.
 */
private fun DevConsoleColors.jsonBraceAt(depth: Int): Color = jsonBraces[depth % jsonBraces.size]

@Composable
private fun JsonTreeMono(
    text: String,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    highlights: List<InspectorSearchHighlight> = emptyList(),
) {
    Text(
        inspectorHighlightedText(text, highlights, DevConsoleTheme.colors),
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize,
        lineHeight = lineHeight,
    )
}
