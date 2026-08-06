/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "MatchingDeclarationName")

package io.devconsole.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One tracked stat in an [InspectorProgressBars] list, e.g. retention/health bars. */
internal data class InspectorProgressStat(
    val label: String,
    val valueText: String,
    val fraction: Float,
    val color: Color,
)

/** Progress bar list for the [InspectorDetailSectionBody.Bars] variant: 7dp r99 track. */
@Composable
internal fun InspectorProgressBars(
    stats: List<InspectorProgressStat>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        stats.forEach { stat ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stat.label,
                        modifier = Modifier.weight(1f).alignByBaseline(),
                        color = DevConsoleTheme.colors.muted,
                        fontSize = 13.sp,
                    )
                    Text(
                        stat.valueText,
                        modifier = Modifier.alignByBaseline(),
                        color = DevConsoleTheme.colors.ink,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                }
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(50))
                            .background(DevConsoleTheme.colors.surface3),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth(stat.fraction.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(50))
                                .background(stat.color),
                    )
                }
            }
        }
    }
}

/** Muted empty-state line for the [InspectorDetailSectionBody.Empty] variant. */
@Composable
internal fun InspectorDetailEmptyText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        color = DevConsoleTheme.colors.text3,
        fontSize = 13.sp,
    )
}
