/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "MatchingDeclarationName")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.devconsole.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
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
 * Mono key/value list used inside a [CollapsibleSection]: 12sp json-key-colored key, 13.5sp value,
 * 1dp hairline divider above each entry.
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
    val lineColor = colors.line
    val sectionMatches = searchMatches.filter { it.sectionKey == sectionKey || sectionKey.isEmpty() }
    // No top inset on this padding, so the first divider sits flush under the section header
    // instead of 8dp below it.
    Column(modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        entries.forEachIndexed { index, entry ->
            val itemId = "row:$index"
            val bringIntoViewRequester = remember { BringIntoViewRequester() }
            val isActive = sectionMatches.any { it.ordinal == currentMatchOrdinal && it.itemId == itemId }
            LaunchedEffect(isActive, currentMatchOrdinal) {
                if (isActive) bringIntoViewRequester.bringIntoView()
            }
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(bringIntoViewRequester)
                        .drawBehind {
                            drawLine(lineColor, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                        }.padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    inspectorHighlightedText(
                        text = entry.key,
                        highlights = sectionMatches.highlightsFor(itemId, InspectorSearchField.KEY, currentMatchOrdinal),
                        colors = colors,
                    ),
                    color = colors.jsonKey,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                Text(
                    inspectorHighlightedText(
                        text = entry.value,
                        highlights = sectionMatches.highlightsFor(itemId, InspectorSearchField.VALUE, currentMatchOrdinal),
                        colors = colors,
                    ),
                    color = entry.valueColor ?: colors.ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.5.sp,
                )
            }
        }
    }
}
