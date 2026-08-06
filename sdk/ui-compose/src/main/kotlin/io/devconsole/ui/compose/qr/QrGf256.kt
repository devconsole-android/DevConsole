/**
 * @author Shakib
 * @since 03/08/26
 */
package io.devconsole.ui.compose.qr

/**
 * GF(256) multiplication for the primitive polynomial `x^8 + x^4 + x^3 + x^2 + 1` (0x11D), the
 * field QR codes' Reed-Solomon error correction is defined over (ISO/IEC 18004 Annex A). This is
 * the standard "carry-less multiply, then reduce" routine reproduced by essentially every public
 * QR implementation (e.g. Project Nayuki's `reedSolomonMultiply`): for each bit of [y] from the top
 * down, double the accumulator -- XORing in the primitive polynomial whenever doubling would carry
 * a 9th bit, which is exactly what `z shr 7` (the pre-shift top bit) predicts -- and conditionally
 * XOR in [x].
 */
internal object QrGf256 {
    private const val PRIMITIVE_POLYNOMIAL = 0x11D
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xFF

    fun multiply(
        x: Int,
        y: Int,
    ): Int {
        require(x in 0..BYTE_MASK && y in 0..BYTE_MASK) { "GF(256) operands must be single bytes: x=$x y=$y" }
        var z = 0
        for (bitIndex in BYTE_BITS - 1 downTo 0) {
            z = (z shl 1) xor ((z shr (BYTE_BITS - 1)) * PRIMITIVE_POLYNOMIAL)
            if ((y shr bitIndex) and 1 == 1) z = z xor x
        }
        return z and BYTE_MASK
    }
}
