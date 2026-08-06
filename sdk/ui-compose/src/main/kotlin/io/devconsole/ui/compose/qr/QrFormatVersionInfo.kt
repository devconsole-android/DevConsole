/**
 * @author Shakib
 * @since 03/08/26
 *
 * Every literal below is a fixed ISO/IEC 18004 module coordinate or BCH bit-width, not app data.
 */
@file:Suppress("MagicNumber")

package io.devconsole.ui.compose.qr

/**
 * Format information (5 data bits: 2-bit EC level + 3-bit mask index, BCH(15,5)-encoded and then
 * XOR-masked) and version information (6 data bits, BCH(18,6)-encoded), per ISO/IEC 18004 Annex C
 * and D. Both use the same "multiply by the generator, reduce by XOR" BCH encoding, then place two
 * redundant copies into fixed matrix positions so a reader can recover the value even if one copy
 * is damaged.
 *
 * The two BCH generator constants and the format XOR mask below are verified in
 * `QrFormatVersionInfoTest` against every published (level, mask) format string and the
 * version-7 version string from the Thonky QR tutorial's format/version tables.
 */
internal object QrFormatVersionInfo {
    private const val FORMAT_GENERATOR = 0x537
    private const val FORMAT_XOR_MASK = 0x5412
    private const val FORMAT_BCH_BITS = 10

    private const val VERSION_GENERATOR = 0x1F25
    private const val VERSION_BCH_BITS = 12
    const val MIN_VERSION_WITH_INFO = 7

    fun bitAt(
        value: Int,
        index: Int,
    ): Boolean = ((value shr index) and 1) == 1

    /** 15-bit masked format value for ([level], [mask]); bit 0 is the LSB placed first by [drawFormatBits]. */
    fun formatBits(
        level: QrErrorCorrectionLevel,
        mask: Int,
    ): Int {
        val data = (level.indicatorBits shl 3) or mask
        var remainder = data
        repeat(FORMAT_BCH_BITS) {
            remainder = (remainder shl 1) xor ((remainder shr (FORMAT_BCH_BITS - 1)) * FORMAT_GENERATOR)
        }
        return ((data shl FORMAT_BCH_BITS) or remainder) xor FORMAT_XOR_MASK
    }

    /** 18-bit version value for [version] (only meaningful for [MIN_VERSION_WITH_INFO] and up). */
    fun versionBits(version: Int): Int {
        var remainder = version
        repeat(VERSION_BCH_BITS) {
            remainder = (remainder shl 1) xor ((remainder shr (VERSION_BCH_BITS - 1)) * VERSION_GENERATOR)
        }
        return (version shl VERSION_BCH_BITS) or remainder
    }

    /**
     * Draws both redundant copies of the format string plus the always-dark module beside them
     * (ISO/IEC 18004 Fig. 25).
     */
    fun drawFormatBits(
        matrix: QrMutableMatrix,
        level: QrErrorCorrectionLevel,
        mask: Int,
    ) {
        val bits = formatBits(level, mask)
        val size = matrix.size
        for (i in 0 until 6) matrix.setFunctionModule(x = 8, y = i, dark = bitAt(bits, i))
        matrix.setFunctionModule(x = 8, y = 7, dark = bitAt(bits, 6))
        matrix.setFunctionModule(x = 8, y = 8, dark = bitAt(bits, 7))
        matrix.setFunctionModule(x = 7, y = 8, dark = bitAt(bits, 8))
        for (i in 9 until 15) matrix.setFunctionModule(x = 14 - i, y = 8, dark = bitAt(bits, i))
        for (i in 0 until 8) matrix.setFunctionModule(x = size - 1 - i, y = 8, dark = bitAt(bits, i))
        for (i in 8 until 15) matrix.setFunctionModule(x = 8, y = size - 15 + i, dark = bitAt(bits, i))
        matrix.setFunctionModule(x = 8, y = size - 8, dark = true) // always-dark module
    }

    /** Draws both 6x3 version-info blocks (ISO/IEC 18004 Fig. 26); a no-op below [MIN_VERSION_WITH_INFO]. */
    fun drawVersionInfo(
        matrix: QrMutableMatrix,
        version: Int,
    ) {
        if (version < MIN_VERSION_WITH_INFO) return
        val bits = versionBits(version)
        val size = matrix.size
        for (i in 0 until 18) {
            val dark = bitAt(bits, i)
            val a = size - 11 + i % 3
            val b = i / 3
            matrix.setFunctionModule(x = a, y = b, dark = dark)
            matrix.setFunctionModule(x = b, y = a, dark = dark)
        }
    }
}
