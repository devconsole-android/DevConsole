/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "MatchingDeclarationName")

package io.devconsole.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
    /** Share of the bar's leftover width; null sizes the pill to its own label instead. */
    val weight: Float? = 1f,
    val icon: (@Composable () -> Unit)? = null,
    val containerColor: Color? = null,
    val contentColor: Color? = null,
    val enabled: Boolean = true,
    val outlined: Boolean = false,
    val supportingText: String? = null,
    /**
     * The accessible name of an icon-only action (a blank [label]), which renders as a square
     * icon button instead of a labelled pill.
     */
    val contentDescription: String? = null,
    /** A shorter [label] used when the bar is narrower than [FOOTER_COMPACT_BELOW]. */
    val compactLabel: String? = null,
) {
    val isIconOnly: Boolean get() = label.isBlank()
}

/**
 * Below this width a four-action bar (two icon buttons, cURL, "Mock response") cannot fit every
 * label, so pills with a [InspectorFooterAction.compactLabel] switch to it rather than ellipsize.
 */
private val FOOTER_COMPACT_BELOW = 400.dp

/**
 * An icon-only footer action: a 54dp round button the same height as the pills beside it, so a
 * row mixing both keeps one baseline. It takes no weight -- its width is its height.
 */
@Composable
private fun FooterIconButton(action: InspectorFooterAction) {
    val colors = DevConsoleTheme.colors
    InspectorRoundIconButton(
        contentDescription = action.contentDescription ?: action.label,
        onClick = action.onClick,
        size = 54.dp,
        enabled = action.enabled,
        containerColor = action.containerColor ?: colors.surface3,
        icon = { action.icon?.invoke() },
    )
}

/** Detail screen footer bar: 54dp pill buttons, top divider, panel bg. */
@Composable
internal fun InspectorDetailFooterBar(
    actions: List<InspectorFooterAction>,
    modifier: Modifier = Modifier,
) {
    val colors = DevConsoleTheme.colors
    val lineColor = colors.line
    // Icon buttons already read as separate controls; the tighter gap is what lets four fit.
    val gap = if (actions.any { it.isIconOnly }) 8.dp else 12.dp
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.panel)
                .drawBehind {
                    drawLine(lineColor, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                },
    ) {
        val compact = maxWidth < FOOTER_COMPACT_BELOW
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            actions.forEach { action ->
                if (action.isIconOnly) {
                    FooterIconButton(action)
                } else {
                    FooterPill(action, label = if (compact) action.compactLabel ?: action.label else action.label)
                }
            }
        }
    }
}

@Composable
private fun RowScope.FooterPill(
    action: InspectorFooterAction,
    label: String,
) {
    val colors = DevConsoleTheme.colors
    val weight = action.weight
    Column(modifier = if (weight != null) Modifier.weight(weight) else Modifier) {
        InspectorPillButton(
            label = label,
            onClick = action.onClick,
            modifier = (if (weight != null) Modifier.fillMaxWidth() else Modifier).height(54.dp),
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
