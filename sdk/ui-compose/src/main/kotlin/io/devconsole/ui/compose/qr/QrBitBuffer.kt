/**
 * @author Shakib
 * @since 03/08/26
 */
package io.devconsole.ui.compose.qr

/** Append-only MSB-first bit sequence, used to assemble a QR data segment before it is cut into codewords. */
internal class QrBitBuffer {
    private val bits = ArrayList<Boolean>()

    val bitCount: Int get() = bits.size

    /** Appends the low [bitCount] bits of [value], most-significant bit first. */
    fun append(
        value: Int,
        bitCount: Int,
    ) {
        for (i in bitCount - 1 downTo 0) {
            bits.add(((value shr i) and 1) == 1)
        }
    }

    /** Packs the buffer into 8-bit codewords; the caller is responsible for byte-aligning first. */
    fun toCodewords(): IntArray {
        require(bits.size % BYTE_BITS == 0) { "Bit buffer must be byte-aligned before packing: ${bits.size} bits" }
        return IntArray(bits.size / BYTE_BITS) { codewordIndex ->
            var codeword = 0
            for (bitOffset in 0 until BYTE_BITS) {
                val bit = if (bits[codewordIndex * BYTE_BITS + bitOffset]) 1 else 0
                codeword = (codeword shl 1) or bit
            }
            codeword
        }
    }

    private companion object {
        const val BYTE_BITS = 8
    }
}
