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
 * [InspectorBottomNav]'s destination icons: Observe reuses [ObserveGlyph.Activity];
 * Control/Data/More need their own icons, which live here.
 */
internal enum class NavGlyph { Send, Db, Grid }

private const val GLYPH_VIEWBOX = 16f
private const val GLYPH_STROKE_WIDTH = 1.4f

/** Renders one [NavGlyph]; same contract as [InspectorGlyphIcon] (null description = decorative). */
@Composable
internal fun NavGlyphIcon(
    glyph: NavGlyph,
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
        drawNavGlyph(glyph, tint)
    }
}

private fun DrawScope.drawNavGlyph(
    glyph: NavGlyph,
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
        NavGlyph.Send -> drawSend(scale, stroke, tint)
        NavGlyph.Db -> drawDb(scale, stroke, tint)
        NavGlyph.Grid -> drawGrid(scale, stroke, tint)
    }
}

/** M14 2 2 7l4.5 1.5L8 14l6-12ZM6.5 8.5 14 2 -- paper-airplane outline + the fold line. */
private fun DrawScope.drawSend(
    scale: Float,
    stroke: Stroke,
    tint: Color,
) {
    val path = Path()
    path.moveTo(14f * scale, 2f * scale)
    path.lineTo(2f * scale, 7f * scale)
    path.lineTo(6.5f * scale, 8.5f * scale)
    path.lineTo(8f * scale, 14f * scale)
    path.close()
    path.moveTo(6.5f * scale, 8.5f * scale)
    path.lineTo(14f * scale, 2f * scale)
    drawPath(path, color = tint, style = stroke)
}

/** M8 2c3 0 5.5.9 5.5 2s...  -- a cylinder: top ellipse + two side lines + a mid ellipse arc. */
private fun DrawScope.drawDb(
    scale: Float,
    stroke: Stroke,
    tint: Color,
) {
    val topLeft = Offset(2.5f * scale, 2f * scale)
    val ellipseSize = Size(11f * scale, 4f * scale)
    drawOval(color = tint, topLeft = topLeft, size = ellipseSize, style = stroke)
    val leftX = 2.5f * scale
    val rightX = 13.5f * scale
    drawLine(tint, Offset(leftX, 4f * scale), Offset(leftX, 12f * scale), strokeWidth = stroke.width)
    drawLine(tint, Offset(rightX, 4f * scale), Offset(rightX, 12f * scale), strokeWidth = stroke.width)
    val arc = { midY: Float -> Offset(leftX, midY * scale) }
    drawArc(tint, 0f, 180f, useCenter = false, topLeft = arc(6f), size = ellipseSize, style = stroke)
    drawArc(tint, 0f, 180f, useCenter = false, topLeft = arc(10f), size = ellipseSize, style = stroke)
}

/** M2 2h5v5H2zM9 2h5v5H9zM2 9h5v5H2zM9 9h5v5H9z -- a 2x2 grid of small squares. */
private fun DrawScope.drawGrid(
    scale: Float,
    stroke: Stroke,
    tint: Color,
) {
    val squareSize = Size(5f * scale, 5f * scale)
    drawRect(color = tint, topLeft = Offset(2f * scale, 2f * scale), size = squareSize, style = stroke)
    drawRect(color = tint, topLeft = Offset(9f * scale, 2f * scale), size = squareSize, style = stroke)
    drawRect(color = tint, topLeft = Offset(2f * scale, 9f * scale), size = squareSize, style = stroke)
    drawRect(color = tint, topLeft = Offset(9f * scale, 9f * scale), size = squareSize, style = stroke)
}
