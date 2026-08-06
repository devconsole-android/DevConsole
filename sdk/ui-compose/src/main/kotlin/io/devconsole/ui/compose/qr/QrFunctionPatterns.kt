/**
 * @author Shakib
 * @since 03/08/26
 *
 * Every literal below is a fixed ISO/IEC 18004 finder/timing/alignment geometry constant, not app data.
 */
@file:Suppress("MagicNumber")

package io.devconsole.ui.compose.qr

import kotlin.math.abs

/**
 * Draws every fixed ("function") module a QR symbol carries regardless of payload: timing
 * patterns, the three finder patterns (each with its light separator baked into the same 9x9
 * draw), alignment patterns, and a placeholder for the format/version info areas (the real values
 * are drawn once by [QrFormatVersionInfo] after mask selection). Matches ISO/IEC 18004 section 6.3.
 */
internal object QrFunctionPatterns {
    private const val TIMING_AXIS = 6
    private const val FINDER_CENTER_OFFSET = 3
    private const val FINDER_HALF_SPAN = 4

    /** Chebyshev ring distances that are light within the 9x9 finder+separator draw: white ring (2), separator (4). */
    private val FINDER_LIGHT_RINGS = setOf(2, 4)
    private const val ALIGNMENT_HALF_SPAN = 2
    private const val ALIGNMENT_LIGHT_RING = 1

    fun draw(matrix: QrMutableMatrix) {
        drawTimingPatterns(matrix)
        drawFinderPattern(matrix, FINDER_CENTER_OFFSET, FINDER_CENTER_OFFSET)
        drawFinderPattern(matrix, matrix.size - 1 - FINDER_CENTER_OFFSET, FINDER_CENTER_OFFSET)
        drawFinderPattern(matrix, FINDER_CENTER_OFFSET, matrix.size - 1 - FINDER_CENTER_OFFSET)
        drawAlignmentPatterns(matrix)
        // Dummy mask/level: only the module *positions* matter here, so later placement of data
        // codewords knows to skip them. QrCodeEncoder overwrites the values once a mask is chosen.
        QrFormatVersionInfo.drawFormatBits(matrix, QrErrorCorrectionLevel.M, mask = 0)
        QrFormatVersionInfo.drawVersionInfo(matrix, matrix.version)
    }

    private fun drawTimingPatterns(matrix: QrMutableMatrix) {
        for (i in 0 until matrix.size) {
            val dark = i % 2 == 0
            matrix.setFunctionModule(x = TIMING_AXIS, y = i, dark = dark)
            matrix.setFunctionModule(x = i, y = TIMING_AXIS, dark = dark)
        }
    }

    /**
     * Draws a 9x9 finder pattern (7x7 core plus its light separator ring) centered at ([x], [y]);
     * out-of-bounds cells are skipped.
     */
    private fun drawFinderPattern(
        matrix: QrMutableMatrix,
        x: Int,
        y: Int,
    ) {
        for (dy in -FINDER_HALF_SPAN..FINDER_HALF_SPAN) {
            for (dx in -FINDER_HALF_SPAN..FINDER_HALF_SPAN) {
                val ring = maxOf(abs(dx), abs(dy))
                matrix.setFunctionModule(x + dx, y + dy, dark = ring !in FINDER_LIGHT_RINGS)
            }
        }
    }

    private fun drawAlignmentPatterns(matrix: QrMutableMatrix) {
        val positions = QrVersionTables.ALIGNMENT_POSITIONS.getValue(matrix.version)
        val last = positions.size - 1
        for (row in positions.indices) {
            for (col in positions.indices) {
                val overlapsFinderCorner =
                    (row == 0 && col == 0) || (row == 0 && col == last) || (row == last && col == 0)
                if (!overlapsFinderCorner) drawAlignmentPattern(matrix, positions[row], positions[col])
            }
        }
    }

    /** Draws a 5x5 alignment pattern centered at ([x], [y]): dark ring, one light ring, dark center. */
    private fun drawAlignmentPattern(
        matrix: QrMutableMatrix,
        x: Int,
        y: Int,
    ) {
        for (dy in -ALIGNMENT_HALF_SPAN..ALIGNMENT_HALF_SPAN) {
            for (dx in -ALIGNMENT_HALF_SPAN..ALIGNMENT_HALF_SPAN) {
                val ring = maxOf(abs(dx), abs(dy))
                matrix.setFunctionModule(x + dx, y + dy, dark = ring != ALIGNMENT_LIGHT_RING)
            }
        }
    }
}
