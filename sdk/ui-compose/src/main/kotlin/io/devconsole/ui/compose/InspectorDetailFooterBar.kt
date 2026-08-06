/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "MatchingDeclarationName")

package io.devconsole.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One action button in an [InspectorDetailFooterBar], e.g. a 2:1 flex footer pair.
 * [enabled]/[outlined] demote an action that can never succeed (e.g. push Replay -- no replay API
 * exists on device yet) to an inert, visually secondary button instead of a working-looking primary
 * one; [supportingText], when set, renders a small caption under just that action explaining why.
 */
internal data class InspectorFooterAction(
    val label: String,
    val onClick: () -> Unit,
    val weight: Float = 1f,
    val icon: (@Composable () -> Unit)? = null,
    val containerColor: Color? = null,
    val contentColor: Color? = null,
    val enabled: Boolean = true,
    val outlined: Boolean = false,
    val supportingText: String? = null,
)

/** Detail screen footer bar: 54dp pill buttons, top divider, panel bg. */
@Composable
internal fun InspectorDetailFooterBar(
    actions: List<InspectorFooterAction>,
    modifier: Modifier = Modifier,
) {
    val colors = DevConsoleTheme.colors
    val lineColor = colors.line
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.panel)
                .drawBehind {
                    drawLine(lineColor, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                }.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        actions.forEach { action ->
            Column(modifier = Modifier.weight(action.weight)) {
                InspectorPillButton(
                    label = action.label,
                    onClick = action.onClick,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    containerColor = action.containerColor ?: colors.signal,
                    contentColor = action.contentColor ?: colors.signalInk,
                    icon = action.icon,
                    enabled = action.enabled,
                    outlined = action.outlined,
                )
                action.supportingText?.let { text ->
                    Text(
                        text,
                        color = colors.muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                    )
                }
            }
        }
    }
}
