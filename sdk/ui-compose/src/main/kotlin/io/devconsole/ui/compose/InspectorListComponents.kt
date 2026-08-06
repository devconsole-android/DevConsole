/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.devconsole.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** 12.5sp/600-weight uppercase section header. */
@Composable
internal fun GroupLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    // The 8dp horizontal inset lines this up with the list rows below it, which the bare bottom
    // padding was missing.
    Text(
        text.uppercase(),
        modifier = modifier.padding(start = 8.dp, top = 2.dp, end = 8.dp, bottom = 8.dp),
        color = DevConsoleTheme.colors.text3,
        style = DevConsoleType.groupLabel,
    )
}

/**
 * mergeDescendants: without it TalkBack reads the lead badge, title, sub, trail value and trail sub
 * as separate stops instead of one row. No-op when both [onClick] and [onLongClick] are null (a purely informational
 * row). [onLongClick] backs the Observe traffic tab's "long-press to enter selection mode" gesture;
 * `combinedClickable` is used instead of plain `clickable` only once a caller actually supplies one,
 * so every other row's semantics tree is unchanged.
 *
 * [mergeDescendants] must be `false` for rows hosting a second actionable control (e.g. a trailing
 * [Switch][androidx.compose.material3.Switch] or a leading checkbox) -- merging would fold that
 * control's toggle action into the row's own click node and TalkBack would only expose one of the
 * two actions.
 */
private fun Modifier.tonalRowClickable(
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    mergeDescendants: Boolean,
): Modifier =
    if (onLongClick != null) {
        this
            .combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick, role = Role.Button)
            .semantics(mergeDescendants = mergeDescendants) {}
    } else if (onClick != null) {
        this.clickable(onClick = onClick, role = Role.Button).semantics(mergeDescendants = mergeDescendants) {}
    } else {
        this
    }

/**
 * A tonal list row: round mono lead badge, mono title + muted sub, trailing mono value + sub.
 * `min 74dp`, whole row clickable, `8dp` bottom margin baked in on every row (including the last).
 */
@Suppress("LongParameterList") // One row primitive covers every Observe/Control/Data list item shape.
@Composable
internal fun TonalListRow(
    leadText: String,
    leadColor: Color,
    leadContainerColor: Color,
    title: String,
    subtitle: String,
    trailValue: String,
    modifier: Modifier = Modifier,
    trailValueColor: Color = DevConsoleTheme.colors.ink,
    trailSubtitle: String? = null,
    containerColor: Color = DevConsoleTheme.colors.surface2,
    /** Real composable trailing content (e.g. [TonalRowExpandChevron]) rendered after [trailValue]'s column. */
    trailContent: (@Composable () -> Unit)? = null,
    /** Optional composable rendered before the lead badge -- e.g. the traffic tab's selection checkbox. */
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    /**
     * Set `false` when [trailContent] or [leading] hosts its own actionable control (a [Switch] or
     * toggleable checkbox) so TalkBack keeps that control's toggle action separate from the row's
     * click action instead of folding both into one merged node.
     */
    mergeDescendants: Boolean = true,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .heightIn(min = 74.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(containerColor)
                .tonalRowClickable(onClick, onLongClick, mergeDescendants)
                .padding(start = 12.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        leading?.invoke()
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
                letterSpacing = 0.02.em,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                color = DevConsoleTheme.colors.ink,
                style = DevConsoleType.rowTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                color = DevConsoleTheme.colors.muted,
                style = DevConsoleType.rowSub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                trailValue,
                color = trailValueColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            if (trailSubtitle != null) {
                Text(trailSubtitle, color = DevConsoleTheme.colors.text3, fontSize = 11.5.sp, maxLines = 1)
            }
        }
        trailContent?.invoke()
    }
}

/**
 * A real rotating chevron glyph for a [TonalListRow]'s expand/collapse affordance, replacing the
 * CJK presentation-form `︿` (U+FE3F) hack -- font-coverage dependent and announced as a symbol by
 * TalkBack. Same rotation convention [CollapsibleSection] uses: open -> 0deg (points down),
 * collapsed -> -90deg (points right), animated with the same 140ms tween.
 */
@Composable
internal fun TonalRowExpandChevron(
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val rotation by
        animateFloatAsState(
            targetValue = if (expanded) 0f else -90f,
            animationSpec = tween(140),
            label = "tonalRowExpandChevron",
        )
    InspectorGlyphIcon(
        InspectorGlyph.ChevronDown,
        contentDescription = null,
        modifier = modifier,
        tint = DevConsoleTheme.colors.muted,
        size = 16.dp,
        rotationDegrees = rotation,
    )
}

/** Warning-tinted note row: alert icon + 13sp muted text on a warn-soft card. */
@Composable
internal fun WarnNote(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(DevConsoleTheme.colors.warnSoft)
                .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InspectorGlyphIcon(
            InspectorGlyph.Alert,
            contentDescription = null,
            tint = DevConsoleTheme.colors.warn,
            size = 18.dp,
            modifier = Modifier.padding(top = 1.dp),
        )
        Text(text, color = DevConsoleTheme.colors.muted, fontSize = 13.sp)
    }
}

/** One entry in a [FilterChipRow]. */
internal data class InspectorFilterChip(
    val id: String,
    val label: String,
    val selected: Boolean = false,
    val count: String? = null,
)

/**
 * Horizontally scrolling filter chips: 44dp visual height, 12dp corners, selected = signal fill +
 * leading check. Each chip reserves a real 48dp touch target via [minimumInteractiveComponentSize]
 * even though it draws at 44dp.
 */
@Composable
internal fun FilterChipRow(
    chips: List<InspectorFilterChip>,
    onChipClick: (InspectorFilterChip) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { chip -> InspectorChip(chip, onClick = { onChipClick(chip) }) }
    }
}

@Composable
private fun InspectorChip(
    chip: InspectorFilterChip,
    onClick: () -> Unit,
) {
    val colors = DevConsoleTheme.colors
    val borderColor = if (chip.selected) colors.signal else colors.line
    val containerColor = if (chip.selected) colors.signal else Color.Transparent
    val contentColor = if (chip.selected) colors.signalInk else colors.ink
    Row(
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .height(44.dp)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(containerColor)
                // toggleable (not a bare clickable+Role.Checkbox) so TalkBack announces the real checked state.
                .toggleable(value = chip.selected, onValueChange = { onClick() }, role = Role.Checkbox)
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (chip.selected) {
            InspectorGlyphIcon(InspectorGlyph.Check, contentDescription = null, tint = contentColor, size = 15.dp)
        }
        Text(
            chip.label,
            color = contentColor,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (chip.count != null) {
            Text(chip.count, color = if (chip.selected) contentColor else colors.text3, fontSize = 12.sp)
        }
    }
}
