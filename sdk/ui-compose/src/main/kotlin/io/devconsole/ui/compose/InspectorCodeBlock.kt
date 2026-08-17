/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "MatchingDeclarationName")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.devconsole.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One rendered line in an [InspectorCodeBlock] -- indent, key, value, and value color. */
internal data class InspectorCodeLine(
    val indent: String = "",
    val key: String = "",
    val value: String,
    val valueColor: Color,
    val highlighted: Boolean = false,
)

/**
 * Inline code block's own cap: this viewer sits inside [InspectorDetailScaffold]'s already-scrolling
 * (unbounded-height) `Column`, so a `LazyColumn` here needs a bounded height to virtualize against --
 * without one it would report its full content height, put every row inside its own "viewport", and
 * defeat the laziness entirely. Below this height nothing changes (short bodies just wrap to content,
 * same as the old plain `Column`); a body taller than this scrolls internally instead of composing
 * every one of a multi-thousand-line body's rows up front. [InspectorCodeFullScreenOverlay] has no such
 * cap -- it already owns the whole remaining viewport, so it virtualizes for real.
 */
internal val InlineCodeBlockMaxHeight = 420.dp

/**
 * Code / JSON viewer for a [CollapsibleSection]'s code variant: 16dp radius, code-bg background,
 * mono 13sp lines at 1.7 line-height, horizontally scrolling, with a signal-soft row highlight for
 * search hits. Backed by a [LazyColumn] (not a `Column`+`forEach`) so a pretty-printed body of
 * thousands of lines only composes/measures the rows actually scrolled into view -- capture-detail
 * sections default-expand now, so this runs on every open, not just on demand.
 */
@Composable
internal fun InspectorCodeBlock(
    lines: List<InspectorCodeLine>,
    modifier: Modifier = Modifier,
    sectionKey: String = "",
    searchMatches: List<InspectorDetailSearchMatch> = emptyList(),
    currentMatchOrdinal: Int? = null,
    onExpandFullScreen: (() -> Unit)? = null,
) {
    val colors = DevConsoleTheme.colors
    val sectionMatches = searchMatches.filter { it.sectionKey == sectionKey || sectionKey.isEmpty() }
    val highlightIndex =
        remember(sectionMatches, currentMatchOrdinal) {
            indexInspectorSearchHighlights(sectionMatches, currentMatchOrdinal)
        }
    val listState = rememberLazyListState()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val activeLineIndex = activeInspectorLineIndex(sectionMatches, currentMatchOrdinal)
    LaunchedEffect(activeLineIndex, currentMatchOrdinal) {
        activeLineIndex?.let { index ->
            if (lines.isNotEmpty()) listState.animateScrollToItem(index.coerceIn(0, lines.lastIndex))
        }
        if (activeLineIndex != null) bringIntoViewRequester.bringIntoView()
    }
    Box(modifier = modifier.fillMaxWidth().bringIntoViewRequester(bringIntoViewRequester)) {
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.codeBg)
                    .heightIn(max = InlineCodeBlockMaxHeight)
                    // A lazy layout can't be measured with `IntrinsicSize.Max` (no intrinsic-measurement
                    // support), so unlike the old Column this can no longer force every row to the
                    // widest line's width for a block-spanning search-hit highlight -- each row's
                    // background now spans only its own content instead. Rows still scroll together
                    // (one shared horizontal ScrollState), just without that shared width.
                    // Known tradeoff: horizontalScroll's content width is derived only from *composed*
                    // rows, unlike the old Column's upfront IntrinsicSize.Max measure of every line --
                    // for a body taller than the cap with lines that get progressively wider further
                    // down, the scrollable extent can under-report and jump as wider rows virtualize
                    // in. Only visible for such bodies; not a correctness bug.
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                val itemId = "line:$index"
                CodeLineRow(
                    line = line,
                    keyHighlights = highlightIndex.highlightsFor(itemId, InspectorSearchField.KEY),
                    valueHighlights = highlightIndex.highlightsFor(itemId, InspectorSearchField.VALUE),
                )
            }
        }
        if (onExpandFullScreen != null) {
            InspectorCodeBlockExpandButton(onExpandFullScreen)
        }
    }
}

/** The small top-right "open full screen" button every code-ish block variant shares. */
@Composable
internal fun BoxScope.InspectorCodeBlockExpandButton(onExpandFullScreen: () -> Unit) {
    val colors = DevConsoleTheme.colors
    InspectorRoundIconButton(
        contentDescription = "Full screen",
        onClick = onExpandFullScreen,
        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
        size = 28.dp,
        containerColor = colors.surface3,
        icon = {
            InspectorGlyphIcon(
                InspectorGlyph.Expand,
                contentDescription = null,
                tint = colors.muted,
                size = 14.dp,
            )
        },
    )
}

/** One [InspectorCodeLine] row, shared by [InspectorCodeBlock] and [InspectorCodeFullScreenOverlay]. */
@Composable
internal fun CodeLineRow(
    line: InspectorCodeLine,
    fontSize: TextUnit = 13.sp,
    lineHeight: TextUnit = 22.1.sp,
    keyHighlights: List<InspectorSearchHighlight> = emptyList(),
    valueHighlights: List<InspectorSearchHighlight> = emptyList(),
) {
    val colors = DevConsoleTheme.colors
    val rowBg = if (line.highlighted) colors.signalSoft else Color.Transparent
    Row(modifier = Modifier.background(rowBg)) {
        CodeSpan(line.indent, colors.text3, fontSize, lineHeight)
        CodeSpan(line.key, colors.jsonKey, fontSize, lineHeight, keyHighlights)
        CodeSpan(line.value, line.valueColor, fontSize, lineHeight, valueHighlights)
    }
}

@Composable
private fun CodeSpan(
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
