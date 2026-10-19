/**
 * @author Shakib
 * @since 19/10/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "MatchingDeclarationName") // Named for its main composable.

package io.devconsole.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Outer corners for independently composed rows in a single grouped panel. */
internal enum class GroupPosition {
    Single,
    First,
    Middle,
    Last,
    ;

    companion object {
        fun of(
            index: Int,
            count: Int,
        ): GroupPosition =
            when {
                count <= 1 -> Single
                index == 0 -> First
                index == count - 1 -> Last
                else -> Middle
            }
    }
}

/**
 * Places one row of a flush group: rows sit straight on the ground (no raised panel) and are
 * separated by a hairline inset 16dp from both edges. Rows bring their own 16dp content padding.
 */
internal fun Modifier.groupedPanelItem(
    position: GroupPosition,
    line: Color,
): Modifier =
    drawBehind {
        if (position == GroupPosition.Middle || position == GroupPosition.Last) {
            val strokeWidth = 1.dp.toPx()
            val inset = 16.dp.toPx()
            drawLine(line, Offset(inset, strokeWidth / 2), Offset(size.width - inset, strokeWidth / 2), strokeWidth)
        }
    }

/**
 * The one raised surface left in the list language: a 12dp-radius panel strip on the 16dp gutter,
 * kept for the single problem notice so it reads apart from the flush rows below it.
 */
@Composable
internal fun Modifier.noticeStrip(): Modifier =
    padding(horizontal = 16.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(DevConsoleTheme.colors.panel)

/** Theme-reading convenience for callers composing grouped list rows. */
@Composable
internal fun Modifier.groupedPanelItem(position: GroupPosition): Modifier =
    groupedPanelItem(position, DevConsoleTheme.colors.line)

/** One flush group of non-lazy rows; separate them with [GroupedPanelDivider]. */
@Composable
internal fun GroupedPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}

/** Hairline between rows inside a [GroupedPanel], inset 16dp from both edges. */
@Composable
internal fun GroupedPanelDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 1.dp,
        color = DevConsoleTheme.colors.line,
    )
}
