/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "MatchingDeclarationName")
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.devconsole.ui.compose

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The small, hand-authored glyph set the shared components need (search, chevron, check, alert,
 * flag, copy). There is no icon library dependency in this module; the glyphs are bespoke (not
 * Material Symbols), with coordinates below on a 16x16 viewBox `<path>` data.
 */
internal enum class InspectorGlyph { Search, ChevronDown, Check, Alert, Flag, Copy, Expand }

private const val GLYPH_VIEWBOX = 16f
private const val GLYPH_STROKE_WIDTH = 1.4f

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
    Canvas(
        modifier =
            modifier
                .size(size)
                .rotate(rotationDegrees)
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier.semantics { invisibleToUser() }
                    },
                ),
    ) {
        drawInspectorGlyph(glyph, tint)
    }
}

@Suppress("LongMethod") // One draw routine per glyph shape, all in one when -- see ObserveGlyph's own suppression.
private fun DrawScope.drawInspectorGlyph(
    glyph: InspectorGlyph,
    tint: Color,
) {
    val scale = size.width / GLYPH_VIEWBOX
    val stroke =
        Stroke(
            width = size.width * (GLYPH_STROKE_WIDTH / GLYPH_VIEWBOX),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
    val path = Path()
    when (glyph) {
        InspectorGlyph.Search -> {
            drawCircle(color = tint, radius = 4.2f * scale, center = Offset(7f * scale, 7f * scale), style = stroke)
            path.moveTo(10.2f * scale, 10.2f * scale)
            path.lineTo(14f * scale, 14f * scale)
        }
        InspectorGlyph.ChevronDown -> {
            path.moveTo(4f * scale, 6.5f * scale)
            path.lineTo(8f * scale, 10.5f * scale)
            path.lineTo(12f * scale, 6.5f * scale)
        }
        InspectorGlyph.Check -> {
            path.moveTo(3f * scale, 8.5f * scale)
            path.lineTo(6.2f * scale, 11.5f * scale)
            path.lineTo(13f * scale, 4.5f * scale)
        }
        InspectorGlyph.Alert -> {
            path.moveTo(8f * scale, 2.5f * scale)
            path.lineTo(14.5f * scale, 13.5f * scale)
            path.lineTo(1.5f * scale, 13.5f * scale)
            path.close()
            path.moveTo(8f * scale, 6.6f * scale)
            path.lineTo(8f * scale, 9.6f * scale)
            drawCircle(color = tint, radius = stroke.width / 2f, center = Offset(8f * scale, 11.6f * scale))
        }
        InspectorGlyph.Flag -> {
            path.moveTo(4f * scale, 14f * scale)
            path.lineTo(4f * scale, 2.5f * scale)
            path.lineTo(12f * scale, 2.5f * scale)
            path.lineTo(10f * scale, 5.5f * scale)
            path.lineTo(12f * scale, 8.5f * scale)
            path.lineTo(4f * scale, 8.5f * scale)
        }
        InspectorGlyph.Copy -> {
            path.addRoundRect(
                RoundRect(6.8f * scale, 5.5f * scale, 13.5f * scale, 12.2f * scale, 1.3f * scale, 1.3f * scale),
            )
            path.addRoundRect(
                RoundRect(3.3f * scale, 3f * scale, 10.3f * scale, 10f * scale, 1.3f * scale, 1.3f * scale),
            )
        }
        InspectorGlyph.Expand -> {
            // Four open corner brackets -- a fullscreen/expand affordance, disconnected on purpose
            // (no closed outline) so it reads distinct from Copy's two overlapping rectangles.
            path.moveTo(2f * scale, 5.5f * scale)
            path.lineTo(2f * scale, 2f * scale)
            path.lineTo(5.5f * scale, 2f * scale)
            path.moveTo(10.5f * scale, 2f * scale)
            path.lineTo(14f * scale, 2f * scale)
            path.lineTo(14f * scale, 5.5f * scale)
            path.moveTo(14f * scale, 10.5f * scale)
            path.lineTo(14f * scale, 14f * scale)
            path.lineTo(10.5f * scale, 14f * scale)
            path.moveTo(5.5f * scale, 14f * scale)
            path.lineTo(2f * scale, 14f * scale)
            path.lineTo(2f * scale, 10.5f * scale)
        }
    }
    drawPath(path, color = tint, style = stroke)
}

/** A 48dp (or [size]) round icon-only button. Every call site must supply a real [contentDescription]. */
@Suppress("LongParameterList") // Size/color/icon vary per call site (top area, header, section copy...).
@Composable
internal fun InspectorRoundIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    containerColor: Color = Color.Transparent,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .size(size)
                .clip(CircleShape)
                .background(containerColor)
                .clickable(onClick = onClick, role = Role.Button)
                .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { icon() }
}

/**
 * Pill-shaped 48dp-min-height CTA used by [HeroCard]'s optional CTA and the detail footer bar.
 * [outlined] swaps the filled [containerColor] for a hairline border on transparent -- the "demoted,
 * can never succeed" affordance (e.g. the push detail's Replay button) that
 * still reads as a button but no longer competes visually with a real primary action. [enabled]
 * disables the click target too; a caller that passes `enabled = false` is expected to also dim
 * [contentColor]/the icon's own tint itself, since this composable never overrides a caller-chosen
 * color.
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
