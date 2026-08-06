/**
 * @author Shakib
 * @since 03/08/26
 */
package io.devconsole.ui.compose.qr

/**
 * Builds the data-codeword sequence for a byte-mode QR segment (ISO/IEC 18004 section 7.4.5):
 * mode indicator, character-count indicator, the raw bytes, a terminator, bit-padding to a byte
 * boundary, then the standard alternating pad codewords until [QrVersionInfo.totalDataCodewords]
 * is reached.
 */
internal object QrDataEncoder {
    private const val MODE_INDICATOR_BYTE = 0b0100
    private const val MODE_INDICATOR_BITS = 4

    /** Character-count indicator width for byte mode, versions 1-9 (the only range this encoder supports). */
    private const val COUNT_INDICATOR_BITS = 8
    private const val TERMINATOR_MAX_BITS = 4
    private const val BITS_PER_BYTE = 8
    private const val PAD_CODEWORD_A = 0b1110_1100
    private const val PAD_CODEWORD_B = 0b0001_0001
    private const val BYTE_MASK = 0xFF

    fun encode(
        data: ByteArray,
        info: QrVersionInfo,
    ): IntArray {
        val bits = QrBitBuffer()
        bits.append(MODE_INDICATOR_BYTE, MODE_INDICATOR_BITS)
        bits.append(data.size, COUNT_INDICATOR_BITS)
        for (b in data) bits.append(b.toInt() and BYTE_MASK, BITS_PER_BYTE)

        val totalBits = info.totalDataCodewords * BITS_PER_BYTE
        val terminatorBits = minOf(TERMINATOR_MAX_BITS, totalBits - bits.bitCount)
        if (terminatorBits > 0) bits.append(0, terminatorBits)
        val paddingToByteBoundary = (BITS_PER_BYTE - bits.bitCount % BITS_PER_BYTE) % BITS_PER_BYTE
        if (paddingToByteBoundary > 0) bits.append(0, paddingToByteBoundary)

        var useFirstPadCodeword = true
        while (bits.bitCount < totalBits) {
            bits.append(if (useFirstPadCodeword) PAD_CODEWORD_A else PAD_CODEWORD_B, BITS_PER_BYTE)
            useFirstPadCodeword = !useFirstPadCodeword
        }
        return bits.toCodewords()
    }
}
