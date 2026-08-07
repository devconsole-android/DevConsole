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
 * Control/Data/More-screen-only glyphs. Kept out of the shared [InspectorGlyph] set -- see
 * [ObserveGlyph] for the same reasoning. More reuses [ObserveGlyph.Plug]/[ObserveGlyph.Download]
 * for its plug/download icons since those already exist, rather than duplicating them here.
 */
internal enum class ControlGlyph { Record, Pause, Eye }

private const val GLYPH_VIEWBOX = 16f
private const val GLYPH_STROKE_WIDTH = 1.4f

/** Renders one [ControlGlyph]; same contract as [InspectorGlyphIcon] (null description = decorative). */
@Composable
internal fun ControlGlyphIcon(
    glyph: ControlGlyph,
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
        drawControlGlyph(glyph, tint)
    }
}

@Suppress("CyclomaticComplexMethod") // One draw routine per glyph shape, all in one when.
private fun DrawScope.drawControlGlyph(
    glyph: ControlGlyph,
    tint: Color,
) {
    val scale = size.width / GLYPH_VIEWBOX
    val stroke =
        Stroke(
            width = size.width * (GLYPH_STROKE_WIDTH / GLYPH_VIEWBOX),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
    when (glyph) {
        ControlGlyph.Record -> drawRecord(scale, stroke, tint)
        ControlGlyph.Pause -> drawPause(scale, stroke, tint)
        ControlGlyph.Eye -> drawEye(scale, stroke, tint)
    }
}

/** M8 2a6 6 0 1 0 0 12A6 6 0 0 0 8 2M8 5.6a2.4 2.4 0 1 0 0 4.8... -- outer ring + filled record dot. */
private fun DrawScope.drawRecord(
    scale: Float,
    stroke: Stroke,
    tint: Color,
) {
    drawCircle(color = tint, radius = 6f * scale, center = Offset(8f * scale, 8f * scale), style = stroke)
    drawCircle(color = tint, radius = 2.4f * scale, center = Offset(8f * scale, 8f * scale))
}

/** M6 4v8M10 4v8 -- two vertical bars. */
private fun DrawScope.drawPause(
    scale: Float,
    stroke: Stroke,
    tint: Color,
) {
    drawLine(tint, Offset(6f * scale, 4f * scale), Offset(6f * scale, 12f * scale), strokeWidth = stroke.width)
    drawLine(tint, Offset(10f * scale, 4f * scale), Offset(10f * scale, 12f * scale), strokeWidth = stroke.width)
}

/** M1.5 8S4 3.8 8 3.8 14.5 8 14.5 8...ZM8 6.2a1.8 1.8 0 1 0 0 3.6... -- lens outline + pupil, arc-approximated. */
private fun DrawScope.drawEye(
    scale: Float,
    stroke: Stroke,
    tint: Color,
) {
    val topLeft = Offset(1.5f * scale, 3.8f * scale)
    val boxSize = Size(13f * scale, 8.4f * scale)
    drawArc(tint, 200f, 140f, useCenter = false, topLeft = topLeft, size = boxSize, style = stroke)
    drawArc(tint, 20f, 140f, useCenter = false, topLeft = topLeft, size = boxSize, style = stroke)
    drawCircle(color = tint, radius = 1.8f * scale, center = Offset(8f * scale, 8f * scale), style = stroke)
}
