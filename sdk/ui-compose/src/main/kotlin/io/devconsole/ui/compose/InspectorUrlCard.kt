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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One 48dp pill action in an [InspectorUrlCard]'s action row. [flex] defaults to 1f.
 * Colors have no default: a data class constructor is not `@Composable`, so it cannot read
 * [DevConsoleTheme.colors] itself -- every call site supplies its own tint.
 */
internal data class InspectorUrlAction(
    val label: String,
    val onClick: () -> Unit,
    val containerColor: Color,
    val contentColor: Color,
    val flex: Float = 1f,
    val icon: (@Composable () -> Unit)? = null,
)

/**
 * A surface-2 card with a status dot + uppercase label, a mono 16sp URL, a muted sub line, and a
 * row of 48dp pill actions.
 */
@Suppress("LongParameterList") // Every field varies per screen.
@Composable
internal fun InspectorUrlCard(
    dotColor: Color,
    label: String,
    url: String,
    subtitle: String,
    actions: List<InspectorUrlAction>,
    modifier: Modifier = Modifier,
    labelColor: Color = DevConsoleTheme.colors.text3,
    urlColor: Color = DevConsoleTheme.colors.ink,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = DevConsoleTheme.colors.surface2,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box8Dot(dotColor)
                Text(
                    label.uppercase(),
                    color = labelColor,
                    style = DevConsoleType.groupLabel,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                url,
                modifier = Modifier.padding(top = 8.dp),
                color = urlColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
            )
            Text(
                subtitle,
                modifier = Modifier.padding(top = 8.dp),
                color = DevConsoleTheme.colors.muted,
                fontSize = 12.5.sp,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                actions.forEach { action ->
                    InspectorPillButton(
                        label = action.label,
                        onClick = action.onClick,
                        modifier = Modifier.weight(action.flex),
                        containerColor = action.containerColor,
                        contentColor = action.contentColor,
                        icon = action.icon,
                        // URL-card action labels run 13.5sp, smaller than the button's own
                        // 14.5sp default.
                        labelFontSize = 13.5.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun Box8Dot(color: Color) {
    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
}
