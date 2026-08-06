/**
 * @author Shakib
 * @since 03/08/26
 */
package io.devconsole.ui.compose.qr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [QrGf256] is the field every Reed-Solomon computation in [QrReedSolomonTest] sits on top of, so
 * it's verified against hand-derivable identities first: the multiplicative identity, and the one
 * reduction step (`2^8 mod 0x11D`) that is easy to redo by hand and is quoted almost verbatim in
 * every description of the QR Reed-Solomon field.
 */
class QrGf256Test {
    @Test
    fun `multiplying by zero is zero`() {
        assertEquals(0, QrGf256.multiply(0, 200))
        assertEquals(0, QrGf256.multiply(200, 0))
    }

    @Test
    fun `one is the multiplicative identity`() {
        for (value in listOf(1, 2, 3, 17, 128, 255)) {
            assertEquals(value, QrGf256.multiply(value, 1))
            assertEquals(value, QrGf256.multiply(1, value))
        }
    }

    @Test
    fun `doubling below the field's byte range is ordinary multiplication by two`() {
        // 64 * 2 = 128 stays inside one byte, so no reduction by the primitive polynomial fires.
        assertEquals(128, QrGf256.multiply(64, 2))
    }

    @Test
    fun `2 to the 8th reduces to the primitive polynomial's low byte, 0x1D`() {
        // x^8 = x^8 + 0 must reduce modulo the field's primitive polynomial
        // x^8 + x^4 + x^3 + x^2 + 1 (0x11D): x^8 = 0x11D - x^8 (mod 2) = x^4+x^3+x^2+1 = 0x1D = 29.
        // Reproduced by doubling 1 eight times (128 = 2^7, so one more doubling reaches 2^8).
        var value = 1
        repeat(8) { value = QrGf256.multiply(value, 2) }
        assertEquals(0x1D, value)
    }

    @Test
    fun `multiplication is commutative`() {
        assertEquals(QrGf256.multiply(53, 199), QrGf256.multiply(199, 53))
    }
}
