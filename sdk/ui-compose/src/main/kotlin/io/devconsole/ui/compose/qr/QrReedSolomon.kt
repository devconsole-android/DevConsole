/**
 * @author Shakib
 * @since 03/08/26
 */
package io.devconsole.ui.compose.qr

/**
 * Reed-Solomon error-correction codeword generation over [QrGf256], mirroring ISO/IEC 18004
 * section 7.5. [computeDivisor] builds the generator polynomial `(x-a^0)(x-a^1)...(x-a^(degree-1))`
 * for a QR generator root of a=2 (the standard `0x02`); [computeRemainder] then divides a data
 * block's message polynomial by that generator to produce the EC codewords appended after it.
 *
 * Verified against the published "HELLO WORLD" Version-1-M worked example (see
 * `QrReedSolomonTest`): 16 data codewords `32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17,
 * 236, 17, 236, 17` produce the 10 EC codewords `196, 35, 39, 119, 235, 215, 231, 226, 93, 23`.
 */
internal object QrReedSolomon {
    private const val GENERATOR_ROOT = 0x02

    /**
     * Coefficients of the degree-[degree] generator polynomial, highest degree first (leading
     * coefficient is always 1).
     */
    fun computeDivisor(degree: Int): IntArray {
        require(degree in 1..MAX_DEGREE) { "Reed-Solomon degree out of range: $degree" }
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        repeat(degree) {
            for (j in 0 until degree) {
                result[j] = QrGf256.multiply(result[j], root)
                if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
            }
            root = QrGf256.multiply(root, GENERATOR_ROOT)
        }
        return result
    }

    /** Polynomial long division of [data] by [divisor] via XOR-subtraction; the remainder is the EC codewords. */
    fun computeRemainder(
        data: IntArray,
        divisor: IntArray,
    ): IntArray {
        val result = IntArray(divisor.size)
        for (b in data) {
            val factor = b xor result[0]
            for (i in 0 until result.size - 1) result[i] = result[i + 1]
            result[result.size - 1] = 0
            for (i in divisor.indices) result[i] = result[i] xor QrGf256.multiply(divisor[i], factor)
        }
        return result
    }

    private const val MAX_DEGREE = 255
}
