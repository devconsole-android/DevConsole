/**
 * @author Shakib
 * @since 03/08/26
 */
package io.devconsole.ui.compose.qr

/**
 * Pure-Kotlin QR Code encoder (byte mode, EC level M preferred, falling back to L only when M
 * would need a version beyond [QrVersionTables.MAX_VERSION]) -- no third-party dependency, built
 * for rendering the DevConsole connect URL as an on-device scannable code (see `QrCodeView`).
 *
 * Pipeline, mirroring ISO/IEC 18004 end to end:
 *  1. [QrVersionTables.selectVersion] picks the smallest version/level whose byte-mode capacity fits.
 *  2. [QrDataEncoder] builds the data codewords (mode + count + payload + terminator + padding).
 *  3. [QrBlockInterleaver] splits into Reed-Solomon blocks, computes EC codewords, and interleaves.
 *  4. [QrFunctionPatterns] draws the fixed patterns; [drawCodewords] places the interleaved stream
 *     into every remaining module via the standard boustrophedon (zigzag) column scan.
 *  5. Each of the 8 masks in [QrMasking] is tried and scored; the lowest-penalty mask is kept and
 *     [QrFormatVersionInfo] draws its real format bits over the placeholder from step 4.
 *
 * Every step above is covered by `QrKnownAnswerTest` against a worked byte-mode example, and
 * `QrReedSolomonTest` against the published "HELLO WORLD" Version-1-M Reed-Solomon vector.
 */
internal object QrCodeEncoder {
    private const val BITS_PER_BYTE = 8

    /**
     * Encodes [text] as a byte-mode QR symbol, or `null` if it exceeds [QrVersionTables.MAX_SUPPORTED_BYTES]
     * bytes once encoded as ISO-8859-1 (the byte-for-byte charset QR byte mode assumes absent an ECI
     * segment, which this encoder never emits -- correct for the ASCII pairing/session-code URLs this
     * is built to render).
     */
    fun encode(text: String): QrMatrix? {
        val data = text.toByteArray(Charsets.ISO_8859_1)
        val info = QrVersionTables.selectVersion(data.size) ?: return null

        val dataCodewords = QrDataEncoder.encode(data, info)
        val allCodewords = QrBlockInterleaver.interleave(dataCodewords, info)

        val matrix = QrMutableMatrix(info.version)
        QrFunctionPatterns.draw(matrix)
        drawCodewords(matrix, allCodewords)

        val bestMask = chooseBestMask(matrix, info.level)
        QrMasking.apply(matrix, bestMask)
        QrFormatVersionInfo.drawFormatBits(matrix, info.level, bestMask)
        return matrix.toImmutable()
    }

    /**
     * Tries all 8 masks (applying, scoring, then undoing each -- XOR is its own inverse) and
     * returns the lowest-penalty index.
     */
    private fun chooseBestMask(
        matrix: QrMutableMatrix,
        level: QrErrorCorrectionLevel,
    ): Int {
        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0..MAX_MASK_INDEX) {
            QrMasking.apply(matrix, mask)
            QrFormatVersionInfo.drawFormatBits(matrix, level, mask)
            val penalty = QrMasking.penaltyScore(matrix)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
            }
            QrMasking.apply(matrix, mask)
        }
        return bestMask
    }

    /**
     * Places [codewords] into every non-function module via the standard right-to-left, two-columns-
     * wide, up/down-snaking scan (ISO/IEC 18004 section 6.7.3), skipping the vertical timing column.
     */
    private fun drawCodewords(
        matrix: QrMutableMatrix,
        codewords: IntArray,
    ) {
        var bitIndex = 0
        val totalBits = codewords.size * BITS_PER_BYTE
        // rightCursor is the pure column-pair driver (size-1, size-3, ..., 2); the timing-column
        // adjustment below must NOT feed back into it, or every later pair shifts by one column --
        // it only affects `right`, the coordinate actually used for this one iteration.
        var rightCursor = matrix.size - 1
        while (rightCursor > 0) {
            val right = if (rightCursor <= TIMING_COLUMN) rightCursor - 1 else rightCursor
            bitIndex = drawColumnPair(matrix, codewords, right, bitIndex, totalBits)
            rightCursor -= 2
        }
        check(bitIndex == totalBits) { "QR codeword placement consumed $bitIndex of $totalBits bits" }
    }

    /**
     * Fills the two columns (`right`, `right`-1) top-to-bottom or bottom-to-top, whichever this
     * strip's snake direction is.
     */
    private fun drawColumnPair(
        matrix: QrMutableMatrix,
        codewords: IntArray,
        right: Int,
        startBitIndex: Int,
        totalBits: Int,
    ): Int {
        var bitIndex = startBitIndex
        val upward = (right + 1) and UPWARD_CHECK_MASK == 0
        for (vertical in 0 until matrix.size) {
            val y = if (upward) matrix.size - 1 - vertical else vertical
            for (columnOffset in 0..1) {
                val x = right - columnOffset
                if (bitIndex >= totalBits || matrix.isFunctionModule(x, y)) continue
                matrix.setDataModule(x, y, bitAt(codewords, bitIndex))
                bitIndex++
            }
        }
        return bitIndex
    }

    private fun bitAt(
        codewords: IntArray,
        bitIndex: Int,
    ): Boolean {
        val byte = codewords[bitIndex shr BYTE_INDEX_SHIFT]
        return (byte shr (BITS_PER_BYTE - 1 - (bitIndex and BIT_INDEX_MASK))) and 1 == 1
    }

    private const val MAX_MASK_INDEX = 7
    private const val TIMING_COLUMN = 6
    private const val UPWARD_CHECK_MASK = 2
    private const val BYTE_INDEX_SHIFT = 3
    private const val BIT_INDEX_MASK = 7
}
