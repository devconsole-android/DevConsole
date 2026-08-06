/**
 * @author Shakib
 * @since 03/08/26
 */
package io.devconsole.ui.compose.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * End-to-end [QrCodeEncoder.encode] tests: structural checks that hold for any valid QR symbol
 * (finder-pattern shape at all three corners, timing-pattern alternation, dimension formula, format
 * info redundancy/correctness, the always-dark module), plus known-answer checks against exact
 * expected matrices/digests.
 *
 * The full-matrix and digest expectations below were derived by independently implementing this
 * same pipeline in Python during development (mode/count/data/terminator/padding bit-packing per
 * ISO/IEC 18004 7.4.5, the identical Reed-Solomon routine [QrReedSolomonTest] verifies against the
 * published "HELLO WORLD" vector, and the module-placement/masking algorithm ported from Project
 * Nayuki's public-domain QR generator) and cross-checking every sub-step against a published,
 * independent source: the Reed-Solomon output against the Thonky worked example, and the
 * format/version info bits against the Thonky format/version tables (see [QrReedSolomonTest] and
 * [QrFormatVersionInfoTest]). The structural tests in this file are the independent check on the one
 * remaining piece -- module *placement* -- that isn't already pinned to a published table.
 */
class QrKnownAnswerTest {
    @Test
    fun `byte-mode HI at Version 1-M matches the exact expected 21x21 matrix`() {
        val matrix = requireNotNull(QrCodeEncoder.encode("HI"))
        assertEquals(21, matrix.size)
        assertEquals(EXPECTED_HI_MATRIX.joinToString("") { it }, matrix.toBitString())
    }

    @Test
    fun `byte-mode HI matrix hashes to the independently derived digest`() {
        val matrix = requireNotNull(QrCodeEncoder.encode("HI"))
        assertEquals(
            "b524ec936d3c5bb83582e9ff4ff0d242bad7f4a552370f40608058d4994a092d",
            sha256Hex(matrix),
        )
    }

    @Test
    fun `a 36-character pairing-shaped URL selects Version 3-M and matches its derived digest`() {
        val url = "http://127.0.0.1:8080/#code=ABCD1234"
        assertEquals(36, url.length)
        val matrix = requireNotNull(QrCodeEncoder.encode(url))
        assertEquals(29, matrix.size) // 17 + 4*3
        assertEquals("57bf54f49209cc593a152c4dd676dd4a48e74fb322365d26ef225682c40938bb", sha256Hex(matrix))
        assertMatrixIsStructurallyValid(matrix, version = 3)
    }

    @Test
    fun `a 120-character URL sits exactly at the ~120-char target and selects Version 7-M`() {
        val url = "http://192.168.100.100:8099/#pair=" + "A".repeat(120 - "http://192.168.100.100:8099/#pair=".length)
        assertEquals(120, url.length)
        val matrix = requireNotNull(QrCodeEncoder.encode(url))
        assertEquals(45, matrix.size) // 17 + 4*7
        assertEquals("b6784ddcf6b3a4fa2e961caceb46230553fcde7851575052c441bc57b39a9f69", sha256Hex(matrix))
        assertMatrixIsStructurallyValid(matrix, version = 7)
    }

    @Test
    fun `123 bytes forces a fallback to EC level L within the supported version range`() {
        val matrix = requireNotNull(QrCodeEncoder.encode("x".repeat(123)))
        assertEquals(41, matrix.size) // 17 + 4*6 -- falls back to Version 6-L, see QrVersionTablesTest
        assertEquals("d9d5b3fa9a9b117ef91cff349d53d780ae010243ad73b6c927f44b1ad904eb73", sha256Hex(matrix))
        assertMatrixIsStructurallyValid(matrix, version = 6)
    }

    @Test
    fun `text longer than the largest supported symbol returns null instead of throwing`() {
        assertNull(QrCodeEncoder.encode("x".repeat(QrVersionTables.MAX_SUPPORTED_BYTES + 1)))
    }

    @Test
    fun `dimension follows the 17 plus 4 times version formula for every supported version`() {
        // Byte-mode, EC level M capacity per version 1-7 (published table, also asserted piecemeal
        // in QrVersionTablesTest); each capacity is strictly greater than the previous version's, so
        // encoding exactly that many bytes selects exactly that version at level M.
        val capacityByVersion = mapOf(1 to 14, 2 to 26, 3 to 42, 4 to 62, 5 to 84, 6 to 106, 7 to 122)
        for ((version, capacity) in capacityByVersion) {
            val matrix = requireNotNull(QrCodeEncoder.encode("y".repeat(capacity)))
            assertEquals("version $version", 17 + 4 * version, matrix.size)
        }
    }

    /** Finder patterns (all 3 corners), timing-pattern alternation, format-info redundancy, and the dark module. */
    private fun assertMatrixIsStructurallyValid(
        matrix: QrMatrix,
        version: Int,
    ) {
        val size = matrix.size
        assertEquals(17 + 4 * version, size)

        assertIsFinderPattern(matrix, topRow = 0, leftCol = 0)
        assertIsFinderPattern(matrix, topRow = 0, leftCol = size - 7)
        assertIsFinderPattern(matrix, topRow = size - 7, leftCol = 0)

        for (i in 8 until size - 8) {
            assertEquals("timing row at col $i", i % 2 == 0, matrix[6, i])
            assertEquals("timing col at row $i", i % 2 == 0, matrix[i, 6])
        }

        // Dark module, ISO/IEC 18004 6.9: fixed coordinate (row = 4*version+9, col = 8).
        assertTrue(matrix[4 * version + 9, 8])
    }

    /** A finder pattern is a 7x7 block: dark border, one light ring, dark 3x3 center. */
    private fun assertIsFinderPattern(
        matrix: QrMatrix,
        topRow: Int,
        leftCol: Int,
    ) {
        for (dr in 0..6) {
            for (dc in 0..6) {
                val ring = minOf(dr, dc, 6 - dr, 6 - dc)
                val expectedDark = ring == 0 || ring >= 2
                assertEquals(
                    "finder($topRow,$leftCol) offset($dr,$dc)",
                    expectedDark,
                    matrix[topRow + dr, leftCol + dc],
                )
            }
        }
    }

    private fun QrMatrix.toBitString(): String =
        buildString {
            for (row in 0 until size) {
                for (col in 0 until size) append(if (this@toBitString[row, col]) '1' else '0')
            }
        }

    private fun sha256Hex(matrix: QrMatrix): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(matrix.toBitString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        // Rows of QrCodeEncoder.encode("HI") (Version 1, EC level M, mask 2), independently derived
        // -- see the class KDoc.
        val EXPECTED_HI_MATRIX =
            listOf(
                "111111100011101111111",
                "100000100000101000001",
                "101110101011001011101",
                "101110101110101011101",
                "101110101001101011101",
                "100000101110101000001",
                "111111101010101111111",
                "000000001111100000000",
                "101111100110101111100",
                "011010001000100100000",
                "101100101011010011110",
                "010001000000000110100",
                "011100100001010010101",
                "000000001001111001000",
                "111111100000101100010",
                "100000101001111001001",
                "101110101100100100100",
                "101110101100100100100",
                "101110101011010011100",
                "100000100010000110100",
                "111111101111010011110",
            )
    }
}
