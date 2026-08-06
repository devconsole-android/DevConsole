/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Detail screen header (header portion only -- the find field is [InspectorDetailSearchField]):
 * 48dp round back button + kind label + trailing 48dp round actions, then a lead badge, mono
 * title (wraps, does not truncate), sub, and a big mono status.
 */
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
    val lineColor = DevConsoleTheme.colors.line
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .drawBehind {
                    // Centred on the exact bottom edge clips half the stroke outside the layout's
                    // bounds; inset by half the stroke width so the whole 1dp line stays on-canvas.
                    val strokeWidth = 1.dp.toPx()
                    val y = size.height - strokeWidth / 2
                    drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth)
                }.padding(top = 8.dp, start = 8.dp, end = 8.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            InspectorRoundIconButton(
                contentDescription = backContentDescription,
                onClick = onBack,
                icon = {
                    InspectorGlyphIcon(
                        InspectorGlyph.ChevronDown,
                        contentDescription = null,
                        tint = DevConsoleTheme.colors.ink,
                        size = 20.dp,
                        rotationDegrees = 90f,
                    )
                },
            )
            Text(
                kindLabel,
                modifier = Modifier.weight(1f),
                color = DevConsoleTheme.colors.muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            actions.forEach { action ->
                InspectorRoundIconButton(
                    contentDescription = action.contentDescription,
                    onClick = action.onClick,
                    containerColor = action.containerColor,
                    icon = action.icon,
                )
            }
        }
        DetailHeaderIdentityRow(leadText, leadColor, leadContainerColor, title, subtitle, status, statusColor)
    }
}

@Suppress("LongParameterList") // Same header payload as InspectorDetailHeader.
@Composable
private fun DetailHeaderIdentityRow(
    leadText: String,
    leadColor: Color,
    leadContainerColor: Color,
    title: String,
    subtitle: String,
    status: String,
    statusColor: Color,
) {
    Row(
        modifier = Modifier.padding(top = 2.dp, start = 12.dp, end = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(leadContainerColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                leadText,
                color = leadColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = DevConsoleTheme.colors.ink, fontFamily = FontFamily.Monospace, fontSize = 14.5.sp)
            Text(subtitle, color = DevConsoleTheme.colors.muted, fontSize = 12.5.sp)
        }
        Text(
            status,
            color = statusColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
