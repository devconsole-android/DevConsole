/**
 * @author Shakib
 * @since 03/08/26
 */
package io.devconsole.ui.compose.qr

/**
 * Splits [dataCodewords] into the Reed-Solomon blocks [QrVersionInfo.blocks] describes, computes
 * each block's EC codewords via [QrReedSolomon], then interleaves data codewords column-by-column
 * followed by EC codewords column-by-column (ISO/IEC 18004 section 7.6) -- the order a QR reader
 * expects the final codeword stream in.
 *
 * Every version/level this encoder supports (1-7, L or M) uses same-size blocks within a symbol --
 * unlike the mixed short/long block groups Q and H sometimes need -- so this stays a plain
 * fixed-stride split; no short-block-length bookkeeping is required.
 */
internal object QrBlockInterleaver {
    fun interleave(
        dataCodewords: IntArray,
        info: QrVersionInfo,
    ): IntArray {
        val divisor = QrReedSolomon.computeDivisor(info.ecCodewordsPerBlock)
        val dataBlocks = mutableListOf<IntArray>()
        val ecBlocks = mutableListOf<IntArray>()
        var offset = 0
        for (spec in info.blocks) {
            repeat(spec.count) {
                val block = dataCodewords.copyOfRange(offset, offset + spec.dataCodewords)
                offset += spec.dataCodewords
                dataBlocks += block
                ecBlocks += QrReedSolomon.computeRemainder(block, divisor)
            }
        }

        val result = ArrayList<Int>(info.totalDataCodewords + info.ecCodewordsPerBlock * dataBlocks.size)
        val maxDataBlockLength = dataBlocks.maxOf { it.size }
        for (i in 0 until maxDataBlockLength) {
            for (block in dataBlocks) if (i < block.size) result += block[i]
        }
        for (i in 0 until info.ecCodewordsPerBlock) {
            for (ec in ecBlocks) result += ec[i]
        }
        return result.toIntArray()
    }
}
