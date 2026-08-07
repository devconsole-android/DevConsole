/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "MatchingDeclarationName")
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.devconsole.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Observe-screen-only glyphs. Kept out of the shared [InspectorGlyph] set: shared components take
 * icons as caller-supplied `@Composable` slots so a screen can wire in whatever icon set it needs
 * without touching shared-component files. Hand-drawn at 16x16 path coordinates (not pixel-perfect
 * bezier replication of arcs -- the design spec's pixel-authoritative language covers
 * sizes/spacing/radii/colors/typography/states, not icon vector art).
 */
internal enum class ObserveGlyph { Plug, Activity, Filter, Refresh, Download, Sun, Tag }

private const val GLYPH_VIEWBOX = 16f
private const val GLYPH_STROKE_WIDTH = 1.4f

/** Renders one [ObserveGlyph]; same contract as [InspectorGlyphIcon] (null description = decorative). */
@Composable
internal fun ObserveGlyphIcon(
    glyph: ObserveGlyph,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = DevConsoleTheme.colors.ink,
    size: Dp = 18.dp,
) {
    Canvas(
        modifier =
            modifier
                .size(size)
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier.semantics { invisibleToUser() }
                    },
                ),
    ) {
        drawObserveGlyph(glyph, tint)
    }
}

@Suppress("CyclomaticComplexMethod", "LongMethod") // One draw routine per glyph shape, all in one when.
private fun DrawScope.drawObserveGlyph(
    glyph: ObserveGlyph,
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
        ObserveGlyph.Filter -> {
            // M2 3h12l-4.5 5.5V13L6.5 11V8.5L2 3Z -- straight-edged funnel, no arcs.
            path.moveTo(2f * scale, 3f * scale)
            path.lineTo(14f * scale, 3f * scale)
            path.lineTo(9.5f * scale, 8.5f * scale)
            path.lineTo(9.5f * scale, 13f * scale)
            path.lineTo(6.5f * scale, 11f * scale)
            path.lineTo(6.5f * scale, 8.5f * scale)
            path.close()
        }
        ObserveGlyph.Download -> {
            // M8 2v8m0 0 3-3m-3 3L5 7M2.5 13h11 -- stem + chevron head + base line.
            path.moveTo(8f * scale, 2f * scale)
            path.lineTo(8f * scale, 10f * scale)
            path.moveTo(8f * scale, 10f * scale)
            path.lineTo(11f * scale, 7f * scale)
            path.moveTo(8f * scale, 10f * scale)
            path.lineTo(5f * scale, 7f * scale)
            path.moveTo(2.5f * scale, 13f * scale)
            path.lineTo(13.5f * scale, 13f * scale)
        }
        ObserveGlyph.Activity -> {
            // M1.5 8h3L6 4.5 8.5 12l2-4h3.5 -- pulse zigzag, no arcs.
            path.moveTo(1.5f * scale, 8f * scale)
            path.lineTo(4.5f * scale, 8f * scale)
            path.lineTo(6f * scale, 4.5f * scale)
            path.lineTo(8.5f * scale, 12f * scale)
            path.lineTo(10.5f * scale, 8f * scale)
            path.lineTo(14f * scale, 8f * scale)
        }
        ObserveGlyph.Tag -> {
            // M2 8.5 7.5 3h5.5v5.5L8.5 13 2 8.5Z + a small punch-hole -- a price tag shape.
            path.moveTo(2f * scale, 8.5f * scale)
            path.lineTo(7.5f * scale, 3f * scale)
            path.lineTo(13f * scale, 3f * scale)
            path.lineTo(13f * scale, 8.5f * scale)
            path.lineTo(7.5f * scale, 14f * scale)
            path.close()
        }
        ObserveGlyph.Plug -> drawPlug(path, scale, stroke, tint)
        ObserveGlyph.Refresh -> drawRefresh(scale, stroke, tint)
        ObserveGlyph.Sun -> drawSun(scale, stroke, tint)
    }
    if (glyph == ObserveGlyph.Tag) {
        drawCircle(color = tint, radius = 1f * scale, center = Offset(10.5f * scale, 5.5f * scale), style = stroke)
    }
    if (glyph != ObserveGlyph.Refresh && glyph != ObserveGlyph.Sun) {
        drawPath(path, color = tint, style = stroke)
    }
}

/** Center disc with 8 short rays. */
private fun DrawScope.drawSun(
    scale: Float,
    stroke: Stroke,
    tint: Color,
) {
    drawCircle(color = tint, radius = 3f * scale, center = Offset(8f * scale, 8f * scale), style = stroke)
    val rayInner = 5.4f * scale
    val rayOuter = 7.5f * scale
    val center = Offset(8f * scale, 8f * scale)
    for (i in 0 until 8) {
        val angle = (i * 45f) * (Math.PI / 180.0)
        val dx = kotlin.math.cos(angle).toFloat()
        val dy = kotlin.math.sin(angle).toFloat()
        drawLine(
            color = tint,
            start = Offset(center.x + dx * rayInner, center.y + dy * rayInner),
            end = Offset(center.x + dx * rayOuter, center.y + dy * rayOuter),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

/** M6 2v4M10 2v4M4 6h8v2a4 4 0 0 1-4 4 4 4 0 0 1-4-4V6ZM8 12v2.5, approximated: two prongs + a cup + a stem. */
private fun DrawScope.drawPlug(
    path: Path,
    scale: Float,
    stroke: Stroke,
    tint: Color,
) {
    path.moveTo(6f * scale, 2f * scale)
    path.lineTo(6f * scale, 6f * scale)
    path.moveTo(10f * scale, 2f * scale)
    path.lineTo(10f * scale, 6f * scale)
    path.moveTo(4f * scale, 6f * scale)
    path.lineTo(12f * scale, 6f * scale)
    path.moveTo(8f * scale, 12f * scale)
    path.lineTo(8f * scale, 14.5f * scale)
    drawPath(path, color = tint, style = stroke)
    // The cup's rounded underside as one shallow arc across the same span, instead of two quarter-arcs.
    drawArc(
        color = tint,
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(4f * scale, 6f * scale),
        size = Size(8f * scale, 6f * scale),
        style = stroke,
    )
}

/** M13.5 8a5.5 5.5 0 1 1-1.9-4.2M13.5 2v3h-3 -- circular sweep (drawArc) + a small arrowhead. */
private fun DrawScope.drawRefresh(
    scale: Float,
    stroke: Stroke,
    tint: Color,
) {
    drawArc(
        color = tint,
        startAngle = -20f,
        sweepAngle = 290f,
        useCenter = false,
        topLeft = Offset(2.5f * scale, 2.5f * scale),
        size = Size(11f * scale, 11f * scale),
        style = stroke,
    )
    val head = Path()
    head.moveTo(13.5f * scale, 2f * scale)
    head.lineTo(13.5f * scale, 5f * scale)
    head.lineTo(10.5f * scale, 5f * scale)
    drawPath(head, color = tint, style = stroke)
}
