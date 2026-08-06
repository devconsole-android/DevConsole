/**
 * @author Shakib
 * @since 03/08/26
 */
package io.devconsole.ui.compose.qr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [QrFormatVersionInfo]'s BCH encoders against the published format-info and version-info bit
 * tables (ISO/IEC 18004 Annex C/D, reproduced in full on the Thonky QR Code Tutorial's
 * "Format and Version Information" appendix).
 */
class QrFormatVersionInfoTest {
    // Every (level, mask) format string, EC level L, mask 0..7 -- Thonky QR tutorial Appendix.
    private val publishedFormatStringsL =
        listOf(
            "111011111000100",
            "111001011110011",
            "111110110101010",
            "111100010011101",
            "110011000101111",
            "110001100011000",
            "110110001000001",
            "110100101110110",
        )

    // Same table, EC level M.
    private val publishedFormatStringsM =
        listOf(
            "101010000010010",
            "101000100100101",
            "101111001111100",
            "101101101001011",
            "100010111111001",
            "100000011001110",
            "100111110010111",
            "100101010100000",
        )

    @Test
    fun `format bits match the published table for every EC level M mask`() {
        for (mask in 0..7) {
            val bits = QrFormatVersionInfo.formatBits(QrErrorCorrectionLevel.M, mask)
            assertEquals("mask $mask", publishedFormatStringsM[mask], bits.toString(2).padStart(BITS, '0'))
        }
    }

    @Test
    fun `format bits match the published table for every EC level L mask`() {
        for (mask in 0..7) {
            val bits = QrFormatVersionInfo.formatBits(QrErrorCorrectionLevel.L, mask)
            assertEquals("mask $mask", publishedFormatStringsL[mask], bits.toString(2).padStart(BITS, '0'))
        }
    }

    @Test
    fun `version 7 info bits match the published table`() {
        // Thonky QR tutorial's version-information table.
        val bits = QrFormatVersionInfo.versionBits(7)
        assertEquals("000111110010010100", bits.toString(2).padStart(VERSION_BITS, '0'))
    }

    private companion object {
        const val BITS = 15
        const val VERSION_BITS = 18
    }
}
