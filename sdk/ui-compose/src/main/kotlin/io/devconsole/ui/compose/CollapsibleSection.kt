/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")

package io.devconsole.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
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

/**
 * A collapsible detail section: a rule, then a 56dp header row with an animated chevron, a 14sp
 * label, an optional mono meta string, and an optional 44dp copy button; [content] renders only
 * while [expanded]. Nest [InspectorKeyValueList], [InspectorCodeBlock], [InspectorProgressBars] or
 * [InspectorDetailEmptyText] inside it.
 *
 * Flat and rule-separated rather than a filled card, matching [HeroCard], [TerminalCard] and the
 * dashboard's `.card-shell`. The chevron sits on the shared 16dp gutter so the header lines up with
 * the [InspectorKeyValueList] rows underneath it and with every [TonalListRow] and [GroupLabel]
 * elsewhere in the app.
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
            animationSpec = chevronSpec(),
            label = "sectionChevron",
        )
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = DevConsoleTheme.colors.line)
        CollapsibleSectionHeader(label, onToggle, rotation, meta, metaColor, onCopy, copyContentDescription)
        // The chevron was already animating while the content it controls snapped in and out, which
        // reads as two unrelated events rather than one. Expanding the height ties them together:
        // the section grows out of the header the chevron just turned on.
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(feedbackSpec(InspectorMotion.EXPAND_MS)) + fadeIn(feedbackSpec()),
            // Exit faster than entrance -- collapsing is a dismissal, not an arrival.
            exit = shrinkVertically(feedbackSpec()) + fadeOut(feedbackSpec()),
        ) {
            Column(content = content)
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
    // start 8 + the inner row's own 8 puts the chevron on the shared 16dp gutter; end 4 optically
    // lands the copy glyph on the same gutter, since it is centred in a 44dp round button.
    Row(modifier = Modifier.padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
