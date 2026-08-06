/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")

package io.devconsole.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val CHEVRON_ROTATION_DURATION_MS = 140

/**
 * A collapsible detail card: 56dp header row with an animated chevron, a 14sp label, an optional
 * mono meta string, and an optional 44dp copy button; [content] renders only while [expanded].
 * Nest [InspectorKeyValueList], [InspectorCodeBlock], [InspectorProgressBars] or
 * [InspectorDetailEmptyText] inside it.
 */
@Suppress("LongParameterList") // Label, meta, copy button, and content.
@Composable
internal fun CollapsibleSection(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
    metaColor: Color = DevConsoleTheme.colors.muted,
    onCopy: (() -> Unit)? = null,
    copyContentDescription: String = "Copy $label",
    content: @Composable ColumnScope.() -> Unit,
) {
    // Open: chevron points down (0deg); collapsed: chevron points right (-90deg).
    val rotation by
        animateFloatAsState(
            targetValue = if (expanded) 0f else -90f,
            animationSpec = tween(CHEVRON_ROTATION_DURATION_MS),
            label = "sectionChevron",
        )
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = DevConsoleTheme.colors.surface2,
    ) {
        Column {
            CollapsibleSectionHeader(label, onToggle, rotation, meta, metaColor, onCopy, copyContentDescription)
            if (expanded) {
                Column(content = content)
            }
        }
    }
}

@Suppress("LongParameterList") // Mirrors CollapsibleSection's parameters.
@Composable
private fun CollapsibleSectionHeader(
    label: String,
    onToggle: () -> Unit,
    rotation: Float,
    meta: String?,
    metaColor: Color,
    onCopy: (() -> Unit)?,
    copyContentDescription: String,
) {
    Row(modifier = Modifier.padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clickable(onClick = onToggle, role = Role.Button)
                    .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InspectorGlyphIcon(
                InspectorGlyph.ChevronDown,
                contentDescription = null,
                tint = DevConsoleTheme.colors.muted,
                size = 16.dp,
                rotationDegrees = rotation,
            )
            Text(
                label,
                modifier = Modifier.weight(1f),
                color = DevConsoleTheme.colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta != null) {
                Text(meta, color = metaColor, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
        if (onCopy != null) {
            InspectorRoundIconButton(
                contentDescription = copyContentDescription,
                onClick = onCopy,
                size = 44.dp,
                icon = {
                    InspectorGlyphIcon(
                        InspectorGlyph.Copy,
                        contentDescription = null,
                        tint = DevConsoleTheme.colors.muted,
                        size = 17.dp,
                    )
                },
            )
        }
    }
}
