/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "MatchingDeclarationName")

package io.devconsole.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
) {
    val colors = DevConsoleTheme.colors
    val lineColor = colors.line
    // No top inset on this padding, so the first divider sits flush under the section header
    // instead of 8dp below it.
    Column(modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        entries.forEach { entry ->
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            drawLine(lineColor, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                        }.padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(entry.key, color = colors.jsonKey, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text(
                    entry.value,
                    color = entry.valueColor ?: colors.ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.5.sp,
                )
            }
        }
    }
}
