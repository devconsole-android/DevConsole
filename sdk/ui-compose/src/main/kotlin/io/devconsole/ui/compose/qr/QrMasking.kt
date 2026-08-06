/**
 * @author Shakib
 * @since 03/08/26
 *
 * Every literal below is a fixed ISO/IEC 18004 mask-formula/penalty-weight constant, not app data.
 */
@file:Suppress("MagicNumber")

package io.devconsole.ui.compose.qr

import kotlin.math.abs

/**
 * The 8 standard QR data-masking patterns (ISO/IEC 18004 Table 10) plus the penalty scoring (section
 * 8.8.2) [QrCodeEncoder] uses to pick whichever of the 8 leaves the fewest reader-confusing runs,
 * blocks, and finder-like false patterns. [apply] is its own inverse (XOR), so calling it twice with
 * the same mask index restores the original data -- [QrCodeEncoder] relies on that to test-and-undo
 * all 8 candidates before committing to the best one.
 */
internal object QrMasking {
    private const val PENALTY_SAME_COLOR_RUN = 3
    private const val PENALTY_SAME_COLOR_BLOCK = 3
    private const val PENALTY_FINDER_LIKE_PATTERN = 40
    private const val PENALTY_DARK_BALANCE = 10
    private const val RUN_PENALTY_THRESHOLD = 5
    private const val FINDER_HISTORY_SIZE = 7

    /** `dark/total` is compared against the 50% target in 5%-wide bands; these scale that comparison to integers. */
    private const val DARK_RATIO_SCALE = 20
    private const val TOTAL_RATIO_SCALE = 10

    fun predicate(
        mask: Int,
        x: Int,
        y: Int,
    ): Boolean =
        when (mask) {
            0 -> (x + y) % 2 == 0
            1 -> y % 2 == 0
            2 -> x % 3 == 0
            3 -> (x + y) % 3 == 0
            4 -> (x / 3 + y / 2) % 2 == 0
            5 -> (x * y) % 2 + (x * y) % 3 == 0
            6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
            7 -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
            else -> error("Invalid QR mask pattern index: $mask")
        }

    fun apply(
        matrix: QrMutableMatrix,
        mask: Int,
    ) {
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (!matrix.isFunctionModule(x, y) && predicate(mask, x, y)) matrix.toggle(x, y)
            }
        }
    }

    fun penaltyScore(matrix: QrMutableMatrix): Int {
        val size = matrix.size
        var result = 0
        for (y in 0 until size) result += scanLine(size) { i -> matrix.get(i, y) }
        for (x in 0 until size) result += scanLine(size) { i -> matrix.get(x, i) }
        result += sameColorBlockPenalty(matrix)
        result += darkBalancePenalty(matrix)
        return result
    }

    /** Rules 1 (5+ same-color run) and 3 (finder-like 1:1:3:1:1 pattern) applied along one row or column. */
    private fun scanLine(
        size: Int,
        valueAt: (Int) -> Boolean,
    ): Int {
        var result = 0
        var runColor = false
        var runLength = 0
        val history = MutableList(FINDER_HISTORY_SIZE) { 0 }
        for (i in 0 until size) {
            val value = valueAt(i)
            if (value == runColor) {
                runLength++
                if (runLength == RUN_PENALTY_THRESHOLD) {
                    result += PENALTY_SAME_COLOR_RUN
                } else if (runLength > RUN_PENALTY_THRESHOLD) {
                    result += 1
                }
            } else {
                addFinderHistory(history, size, runLength)
                if (!runColor) result += countFinderLikePatterns(history) * PENALTY_FINDER_LIKE_PATTERN
                runColor = value
                runLength = 1
            }
        }
        result += terminateFinderScan(runColor, runLength, history, size) * PENALTY_FINDER_LIKE_PATTERN
        return result
    }

    private fun addFinderHistory(
        history: MutableList<Int>,
        size: Int,
        runLength: Int,
    ) {
        val adjusted = if (history[0] == 0) runLength + size else runLength
        history.add(0, adjusted)
        if (history.size > FINDER_HISTORY_SIZE) history.removeAt(history.size - 1)
    }

    /**
     * A run-length history matches a finder-like 1:1:3:1:1 pattern (with a 4-module light quiet
     * zone on either side).
     */
    private fun countFinderLikePatterns(history: List<Int>): Int {
        val unit = history[1]
        val core =
            unit > 0 && history[2] == unit && history[4] == unit && history[5] == unit && history[3] == unit * 3
        var count = 0
        if (core && history[0] >= unit * 4 && history[6] >= unit) count++
        if (core && history[6] >= unit * 4 && history[0] >= unit) count++
        return count
    }

    private fun terminateFinderScan(
        runColor: Boolean,
        runLength: Int,
        history: MutableList<Int>,
        size: Int,
    ): Int {
        var length = runLength
        if (runColor) {
            addFinderHistory(history, size, length)
            length = 0
        }
        addFinderHistory(history, size, length + size)
        return countFinderLikePatterns(history)
    }

    /** Rule 2: each 2x2 block of same-colored modules. */
    private fun sameColorBlockPenalty(matrix: QrMutableMatrix): Int {
        var result = 0
        for (y in 0 until matrix.size - 1) {
            for (x in 0 until matrix.size - 1) {
                val value = matrix.get(x, y)
                val blockIsUniform =
                    value == matrix.get(x + 1, y) && value == matrix.get(x, y + 1) && value == matrix.get(x + 1, y + 1)
                if (blockIsUniform) result += PENALTY_SAME_COLOR_BLOCK
            }
        }
        return result
    }

    /** Rule 4: how far the dark-module proportion strays from 50%, in 5% steps. */
    private fun darkBalancePenalty(matrix: QrMutableMatrix): Int {
        var dark = 0
        for (y in 0 until matrix.size) for (x in 0 until matrix.size) if (matrix.get(x, y)) dark++
        val total = matrix.size * matrix.size
        val steps = (abs(dark * DARK_RATIO_SCALE - total * TOTAL_RATIO_SCALE) + total - 1) / total - 1
        return steps * PENALTY_DARK_BALANCE
    }
}
