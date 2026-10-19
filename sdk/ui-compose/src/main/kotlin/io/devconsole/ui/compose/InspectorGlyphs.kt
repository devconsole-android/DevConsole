/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "MatchingDeclarationName")

package io.devconsole.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The small glyph set the shared components need (search, chevron, check, alert, flag, copy). Each
 * maps to one of the module's `devconsole_ic_*` vector drawables -- the same 24dp, 1.5-stroke set the
 * web dashboard's sprite ships -- so the two surfaces draw identical icons.
 */
internal enum class InspectorGlyph { Search, ChevronDown, Check, Alert, Flag, Copy, Expand }

/** DESIGN.md's disabled treatment: the whole control at half opacity, container and content alike. */
private const val DISABLED_PILL_ALPHA = 0.5f

/**
 * Renders one [InspectorGlyph]. Pass [contentDescription] only when this icon is the sole
 * accessible label for its surroundings; leave it `null` (the default expectation for glyphs
 * nested in an already-labelled button/row) and it is hidden from the accessibility tree instead
 * of surfacing as an unlabeled node. [rotationDegrees] lets one base shape stand in for several
 * glyphs, e.g. the down-chevron rotated 90 degrees clockwise becomes the detail screen's back
 * arrow, and rotated 180 becomes the hero card's collapse chevron.
 */
@Suppress("LongParameterList") // Every glyph call site tunes description/tint/size/rotation independently.
@Composable
internal fun InspectorGlyphIcon(
    glyph: InspectorGlyph,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = DevConsoleTheme.colors.ink,
    size: Dp = 18.dp,
    rotationDegrees: Float = 0f,
) {
    DevConsoleIcon(glyph.drawableRes(), contentDescription, modifier.rotate(rotationDegrees), tint, size)
}

private fun InspectorGlyph.drawableRes(): Int =
    when (this) {
        InspectorGlyph.Search -> R.drawable.devconsole_ic_search
        InspectorGlyph.ChevronDown -> R.drawable.devconsole_ic_chevron_down
        InspectorGlyph.Check -> R.drawable.devconsole_ic_check
        InspectorGlyph.Alert -> R.drawable.devconsole_ic_crashes
        InspectorGlyph.Flag -> R.drawable.devconsole_ic_evidence
        InspectorGlyph.Copy -> R.drawable.devconsole_ic_copy
        InspectorGlyph.Expand -> R.drawable.devconsole_ic_expand
    }

/** A 48dp (or [size]) round icon-only button. Every call site must supply a real [contentDescription]. */
@Suppress("LongParameterList") // Size/color/icon vary per call site (top area, header, section copy...).
@Composable
internal fun InspectorRoundIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    enabled: Boolean = true,
    containerColor: Color = Color.Transparent,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .size(size)
                .alpha(if (enabled) 1f else DISABLED_PILL_ALPHA)
                .clip(CircleShape)
                .background(containerColor)
                .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
                .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { icon() }
}

/**
 * Pill-shaped 48dp-min-height CTA used by [HeroCard]'s optional CTA and the detail footer bar.
 * [outlined] swaps the filled [containerColor] for a hairline border on transparent -- the "demoted,
 * can never succeed" affordance (e.g. the push detail's Replay button) that
 * still reads as a button but no longer competes visually with a real primary action. [enabled]
 * disables the click target and dims the whole pill to the design system's 0.5 disabled opacity, so
 * a gated action never looks like it will still work.
 */
@Suppress("LongParameterList") // Colors/icon vary per call site (hero CTA vs. footer actions).
@Composable
internal fun InspectorPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = DevConsoleTheme.colors.signal,
    contentColor: Color = DevConsoleTheme.colors.signalInk,
    icon: (@Composable () -> Unit)? = null,
    /** Override for call sites whose label runs smaller than the default (e.g. the URL card's 13.5sp). */
    labelFontSize: TextUnit = 14.5.sp,
    enabled: Boolean = true,
    outlined: Boolean = false,
) {
    val resolvedContainer = if (outlined) Color.Transparent else containerColor
    val outlineModifier =
        if (outlined) Modifier.border(1.dp, DevConsoleTheme.colors.line, RoundedCornerShape(50)) else Modifier
    Row(
        modifier =
            modifier
                .heightIn(min = 48.dp)
                .alpha(if (enabled) 1f else DISABLED_PILL_ALPHA)
                .clip(RoundedCornerShape(50))
                .then(outlineModifier)
                .background(resolvedContainer)
                .clickable(onClick = onClick, enabled = enabled, role = Role.Button)
                .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.invoke()
            // Ellipsis, not the default clip: weighted pills used to drop a label's last glyph.
            Text(
                label,
                color = contentColor,
                fontSize = labelFontSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
