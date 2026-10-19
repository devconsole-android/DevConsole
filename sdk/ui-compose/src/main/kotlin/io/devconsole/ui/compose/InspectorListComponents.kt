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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Sentence-case heading above a grouped panel. */
@Composable
internal fun GroupLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 8.dp),
        color = DevConsoleTheme.colors.muted,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * mergeDescendants: without it TalkBack reads the method, title, sub, trail value and trail sub
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
 * A grouped list row with a fixed muted method column, monospace title and inline status/duration.
 * Colour is reserved for status; [leadColor] and [leadContainerColor] remain for source compatibility.
 * Rows carry no margin or fill by default; callers supply their grouped panel surface.
 */
@Suppress("LongParameterList", "UnusedParameter") // Keep the shared row's existing colour parameters.
@Composable
internal fun TonalListRow(
    leadText: String,
    leadColor: Color,
    leadContainerColor: Color,
    title: String,
    subtitle: String,
    trailValue: String,
    modifier: Modifier = Modifier,
    /**
     * Lines [title] may wrap to before it ellipsizes. `1` -- one line, the row's minimum 56dp shape --
     * for every list whose titles are scannable at a glance; raise it only where the title is the
     * row's payload rather than its label (a log message, a crash reason) and truncating it loses
     * the thing the reader came for. The row grows to fit, since its height is a `heightIn` floor.
     */
    titleMaxLines: Int = 1,
    trailValueColor: Color = DevConsoleTheme.colors.ink,
    trailSubtitle: String? = null,
    containerColor: Color = Color.Transparent,
    /** Real composable trailing content (e.g. [TonalRowExpandChevron]) rendered after the status/duration. */
    trailContent: (@Composable () -> Unit)? = null,
    /** Optional composable rendered before the method -- e.g. the traffic tab's selection checkbox. */
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
                .heightIn(min = 52.dp)
                .background(containerColor)
                .arrivalFlash(isArrival)
                .tonalRowClickable(onClick, onLongClick, mergeDescendants)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        if (leadText.isNotBlank()) {
            Text(
                leadText,
                modifier = Modifier.width(64.dp),
                color = DevConsoleTheme.colors.muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                // A path drawn with breakingAnywhere()'s joiners is still read and matched as plain text.
                modifier = Modifier.clearAndSetSemantics { text = AnnotatedString(title.withoutBreakControls()) },
                color = DevConsoleTheme.colors.ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = DevConsoleTheme.colors.muted,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                trailValue,
                color = trailValueColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (trailSubtitle != null) {
                Text(" · ", color = DevConsoleTheme.colors.muted, fontSize = 12.5.sp, maxLines = 1)
                Text(trailSubtitle, color = DevConsoleTheme.colors.muted, fontSize = 12.5.sp, maxLines = 1)
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

/** Quiet grouped notice with a warning dot and ink text. */
@Composable
internal fun WarnNote(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .groupedPanelItem(GroupPosition.Single)
                .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(DevConsoleTheme.colors.warn),
        )
        Text(text, color = DevConsoleTheme.colors.ink, fontSize = 13.sp)
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
 * Horizontally scrolling quiet filter chips on the shared 16dp gutter. Material retains the
 * minimum touch target around each 32dp chip; selection uses a neutral fill without an icon.
 */
@Composable
internal fun FilterChipRow(
    chips: List<InspectorFilterChip>,
    onChipClick: (InspectorFilterChip) -> Unit,
    modifier: Modifier = Modifier,
    /** Overridable so a caller that already pads its column can align chips to its own margin. */
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    /** False for a caller that places the chips beside other controls and must not have the row eat the width. */
    fillWidth: Boolean = true,
) {
    Row(
        modifier =
            modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .horizontalScroll(rememberScrollState())
                .padding(contentPadding),
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
        modifier = Modifier.minimumInteractiveComponentSize().height(32.dp),
        shape = RoundedCornerShape(8.dp),
        colors =
            FilterChipDefaults.filterChipColors(
                containerColor = Color.Transparent,
                labelColor = DevConsoleTheme.colors.muted,
                selectedContainerColor = DevConsoleTheme.colors.surface3,
                selectedLabelColor = DevConsoleTheme.colors.ink,
            ),
        border =
            if (chip.selected) {
                null
            } else {
                FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = false,
                    borderColor = DevConsoleTheme.colors.line,
                    borderWidth = 1.dp,
                )
            },
    )
}

/**
 * A joined 32dp view switch with a neutral selected segment on a surface-2 container.
 * Use it for mutually exclusive representations of the same content.
 */
@Composable
internal fun InspectorSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DevConsoleTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier =
            modifier
                .height(32.dp)
                .clip(shape)
                .background(colors.surface2)
                .semantics { role = Role.RadioButton },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .widthIn(min = 42.dp)
                        .clip(shape)
                        .background(if (selected) colors.surface3 else colors.surface2)
                        .selectable(selected = selected, role = Role.RadioButton) { onSelect(index) }
                        .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    color = if (selected) colors.ink else colors.muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}
