/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "UNUSED_PARAMETER")

package io.devconsole.ui.compose

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Compact capture identity and a single metadata line. Badge parameters preserve callers. */
@Suppress("LongParameterList") // Every field below is part of the header payload.
@Composable
internal fun InspectorDetailHeader(
    kindLabel: String,
    leadText: String,
    leadColor: Color,
    leadContainerColor: Color,
    title: String,
    subtitle: String,
    status: String,
    statusColor: Color,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backContentDescription: String = "Back to the list",
    actions: List<InspectorTopAction> = emptyList(),
) {
    val colors = DevConsoleTheme.colors
    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                DevConsoleIcon(
                    R.drawable.devconsole_ic_arrow_back,
                    backContentDescription,
                    Modifier,
                    colors.ink,
                    24.dp,
                )
            }
            Text(
                title,
                modifier = Modifier.weight(1f),
                color = colors.ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 20.sp,
            )
            actions.forEach { action ->
                InspectorRoundIconButton(
                    contentDescription = action.contentDescription,
                    onClick = action.onClick,
                    icon = action.icon,
                )
            }
        }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = statusColor)) { append(status) }
                if (status.isNotEmpty() && subtitle.isNotEmpty()) append(' ')
                append(subtitle)
            },
            modifier =
                Modifier
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp)
                    .horizontalScroll(rememberScrollState()),
            color = colors.muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}
