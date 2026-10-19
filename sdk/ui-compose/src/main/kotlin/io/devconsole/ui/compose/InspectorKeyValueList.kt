/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "MatchingDeclarationName")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.devconsole.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One key/value pair in an [InspectorKeyValueList]. */
internal data class InspectorKeyValue(
    val key: String,
    val value: String,
    val valueColor: Color? = null,
)

/**
 * Fixed-width muted keys and monospace values, separated by inset panel hairlines.
 */
@Composable
internal fun InspectorKeyValueList(
    entries: List<InspectorKeyValue>,
    modifier: Modifier = Modifier,
    sectionKey: String = "",
    searchMatches: List<InspectorDetailSearchMatch> = emptyList(),
    currentMatchOrdinal: Int? = null,
) {
    val colors = DevConsoleTheme.colors
    val sectionMatches = searchMatches.filter { it.sectionKey == sectionKey || sectionKey.isEmpty() }
    val highlightIndex =
        remember(sectionMatches, currentMatchOrdinal) {
            indexInspectorSearchHighlights(sectionMatches, currentMatchOrdinal)
        }
    Column(modifier = modifier.fillMaxWidth()) {
        entries.forEachIndexed { index, entry ->
            val itemId = "row:$index"
            val bringIntoViewRequester = remember { BringIntoViewRequester() }
            val isActive = sectionMatches.any { it.ordinal == currentMatchOrdinal && it.itemId == itemId }
            LaunchedEffect(isActive, currentMatchOrdinal) {
                if (isActive) bringIntoViewRequester.bringIntoView()
            }
            if (index > 0) GroupedPanelDivider()
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(bringIntoViewRequester)
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    inspectorHighlightedText(
                        text = entry.key,
                        highlights = highlightIndex.highlightsFor(itemId, InspectorSearchField.KEY),
                        colors = colors,
                    ),
                    modifier = Modifier.width(96.dp).padding(end = 12.dp),
                    color = colors.muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
                Text(
                    inspectorHighlightedText(
                        text = entry.value,
                        highlights = highlightIndex.highlightsFor(itemId, InspectorSearchField.VALUE),
                        colors = colors,
                    ),
                    color = entry.valueColor ?: colors.ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                )
            }
        }
    }
}
