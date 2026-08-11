/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.devconsole.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
    Text(
        text,
        modifier = modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
        color = DevConsoleTheme.colors.text3,
        style = MaterialTheme.typography.labelMedium,
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
 * `min 48dp` (the Material touch-target floor), whole row clickable, content inset 16dp to match
 * [GroupLabel], [HeroCard] and [CollapsibleSection]. Rows carry no margin of their own -- they sit
 * flush against each other, and the list separates groups with [GroupLabel] instead.
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
    containerColor: Color = Color.Transparent,
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
    /**
     * `true` for a row a live capture just added, which washes it in signal for
     * [InspectorMotion.ROW_FLASH_MS] -- see [arrivalFlash]. Feed it from [rememberArrivals] at the
     * list level; a row can never work out on its own whether it is new or merely re-composed.
     */
    isArrival: Boolean = false,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(containerColor)
                .arrivalFlash(isArrival)
                .tonalRowClickable(onClick, onLongClick, mergeDescendants)
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
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
 * collapsed -> -90deg (points right), on the shared [chevronSpec] spring.
 */
@Composable
internal fun TonalRowExpandChevron(
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val rotation by
        animateFloatAsState(
            targetValue = if (expanded) 0f else -90f,
            animationSpec = chevronSpec(),
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

/** Warning-tinted note row: alert icon + 13sp muted text on a warn-soft block. */
@Composable
internal fun WarnNote(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
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
 * Horizontally scrolling filter chips on the shared 16dp gutter. Each chip is a stock M3
 * [FilterChip] -- outlined on transparent when unselected, signal-tinted with a signal border when
 * selected -- so it inherits Material's own height, shape ([DevConsoleShapes]' `small`) and 48dp
 * touch target rather than restating them here.
 */
@Composable
internal fun FilterChipRow(
    chips: List<InspectorFilterChip>,
    onChipClick: (InspectorFilterChip) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
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
    FilterChip(
        selected = chip.selected,
        onClick = onClick,
        label = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(chip.label)
                if (chip.count != null) {
                    Text(chip.count)
                }
            }
        },
        colors =
            FilterChipDefaults.filterChipColors(
                containerColor = Color.Transparent,
                labelColor = DevConsoleTheme.colors.ink,
                // signalSoft, not an ad-hoc alpha: the token is 0.13 in dark and 0.10 in light, so a
                // literal .copy(alpha = ...) here would drift from every other signal-tinted surface
                // in exactly one theme.
                selectedContainerColor = DevConsoleTheme.colors.signalSoft,
                selectedLabelColor = DevConsoleTheme.colors.signal,
                selectedLeadingIconColor = DevConsoleTheme.colors.signal,
            ),
        border =
            FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = chip.selected,
                borderColor = DevConsoleTheme.colors.line,
                selectedBorderColor = DevConsoleTheme.colors.signal,
            ),
    )
}
