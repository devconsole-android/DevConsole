/**
 * @author Shakib
 * @since 03/08/26
 */
package io.devconsole.ui.compose.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [QrVersionTables] capacity math and version/level selection. The byte-capacity numbers asserted
 * here (14 for V1-M, 122 for V7-M, 154 for V7-L, ...) are the widely published QR byte-mode
 * character-capacity table values, re-derivable from the block table via
 * `(totalDataCodewords*8 - 12) / 8` (12 = 4-bit mode indicator + 8-bit versions-1-9 count
 * indicator): e.g. V1-M has 16 data codewords, `(16*8-12)/8 = (128-12)/8 = 116/8 = 14.5 -> 14`.
 */
class QrVersionTablesTest {
    @Test
    fun `V1-M byte capacity is 14, the published value`() {
        val info = requireNotNull(QrVersionTables.selectVersion(14))
        assertEquals(1, info.version)
        assertEquals(QrErrorCorrectionLevel.M, info.level)
        assertEquals(14, QrVersionTables.byteCapacity(info))
    }

    @Test
    fun `15 bytes no longer fits V1-M so version bumps to V2-M rather than dropping to V1-L`() {
        // Policy: prefer M at any supported version over L at a smaller one (see QrVersionTables
        // KDoc) -- L is only a fallback once no version 1-7 fits at M.
        val info = requireNotNull(QrVersionTables.selectVersion(15))
        assertEquals(2, info.version)
        assertEquals(QrErrorCorrectionLevel.M, info.level)
    }

    @Test
    fun `V7-M byte capacity is 122, comfortably covering the ~120-character target`() {
        val info = requireNotNull(QrVersionTables.selectVersion(122))
        assertEquals(7, info.version)
        assertEquals(QrErrorCorrectionLevel.M, info.level)
    }

    @Test
    fun `123 bytes exceeds every version's M capacity so selection falls back to the smallest fitting L version`() {
        val info = requireNotNull(QrVersionTables.selectVersion(123))
        assertEquals(6, info.version)
        assertEquals(QrErrorCorrectionLevel.L, info.level)
    }

    @Test
    fun `V7-L is the largest supported payload, 154 bytes`() {
        assertEquals(154, QrVersionTables.MAX_SUPPORTED_BYTES)
        val info = requireNotNull(QrVersionTables.selectVersion(154))
        assertEquals(7, info.version)
        assertEquals(QrErrorCorrectionLevel.L, info.level)
    }

    @Test
    fun `155 bytes exceeds every supported version and level`() {
        assertNull(QrVersionTables.selectVersion(155))
    }

    @Test
    fun `alignment pattern positions match the published table for versions 1 through 7`() {
        assertEquals(emptyList<Int>(), QrVersionTables.ALIGNMENT_POSITIONS.getValue(1))
        assertEquals(listOf(6, 18), QrVersionTables.ALIGNMENT_POSITIONS.getValue(2))
        assertEquals(listOf(6, 22), QrVersionTables.ALIGNMENT_POSITIONS.getValue(3))
        assertEquals(listOf(6, 26), QrVersionTables.ALIGNMENT_POSITIONS.getValue(4))
        assertEquals(listOf(6, 30), QrVersionTables.ALIGNMENT_POSITIONS.getValue(5))
        assertEquals(listOf(6, 34), QrVersionTables.ALIGNMENT_POSITIONS.getValue(6))
        assertEquals(listOf(6, 22, 38), QrVersionTables.ALIGNMENT_POSITIONS.getValue(7))
    }
}
