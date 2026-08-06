/**
 * @author Shakib
 * @since 03/08/26
 */
package io.devconsole.ui.compose.qr

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * [QrReedSolomon] against two independently checkable references: a hand-derivable degree-2
 * generator polynomial, and the published "HELLO WORLD" Version-1-M worked example reproduced
 * across essentially every QR tutorial (originally from the Thonky QR Code Tutorial's error
 * correction coding chapter).
 */
class QrReedSolomonTest {
    @Test
    fun `degree-2 generator polynomial matches the hand-derivable (x minus a to the 0)(x minus a to the 1) product`() {
        // Generator = (x - a^0)(x - a^1); over GF(2) subtraction is XOR, so this is (x + 1)(x + 2)
        // = x^2 + (1 XOR 2)x + (1 . 2) = x^2 + 3x + 2, where "." is GF(256) multiplication and
        // 1 . 2 = 2 trivially. computeDivisor returns the coefficients below the implicit leading
        // x^2 term, highest remaining degree first: [x^1 coefficient, x^0 coefficient] = [3, 2].
        assertArrayEquals(intArrayOf(3, 2), QrReedSolomon.computeDivisor(2))
    }

    @Test
    fun `degree-10 generator polynomial matches the published QR Version-1-M example`() {
        // Published generator (Thonky QR tutorial, error-correction-coding chapter):
        //   x^10 + a251 x^9 + a67 x^8 + a46 x^7 + a61 x^6 + a118 x^5 + a70 x^4 + a64 x^3
        //        + a94 x^2 + a32 x + a45
        // computeDivisor's array holds the coefficients below the implicit leading x^10 term
        // (always 1), highest degree first, as GF(256) byte values rather than alpha exponents;
        // converting each expected exponent a^k back to a byte via repeated doubling from 1
        // reproduces exactly the array asserted below.
        val divisor = QrReedSolomon.computeDivisor(10)
        val expectedExponents = intArrayOf(251, 67, 46, 61, 118, 70, 64, 94, 32, 45)
        val expected = expectedExponents.map { exponent -> alphaPower(exponent) }.toIntArray()
        assertArrayEquals(expected, divisor)
    }

    @Test
    fun `HELLO WORLD Version-1-M data codewords produce the published EC codewords`() {
        // "HELLO WORLD" encoded alphanumeric, Version 1, EC level M (Thonky QR Code Tutorial):
        //  - mode indicator (0010) + 9-bit count (11 chars) + five 11-bit digit pairs + one 6-bit
        //    trailing char, terminated and padded with the standard 11101100/00010001 sequence up
        //    to the level-M capacity of 16 data codewords. The resulting bytes (verified
        //    independently by re-deriving this exact bitstream during development, see PR
        //    description) are the 16 values below.
        val dataCodewords =
            intArrayOf(32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17)
        val v1mEcCodewordCount = 10
        val divisor = QrReedSolomon.computeDivisor(v1mEcCodewordCount)

        val ec = QrReedSolomon.computeRemainder(dataCodewords, divisor)

        // Published EC codewords for this exact message (Thonky QR Code Tutorial).
        assertArrayEquals(intArrayOf(196, 35, 39, 119, 235, 215, 231, 226, 93, 23), ec)
    }

    /**
     * Recomputes a^exponent as a GF(256) byte by repeated doubling from 1, independent of
     * [QrReedSolomon]'s own tables.
     */
    private fun alphaPower(exponent: Int): Int {
        var value = 1
        repeat(exponent) { value = QrGf256.multiply(value, 2) }
        return value
    }
}
