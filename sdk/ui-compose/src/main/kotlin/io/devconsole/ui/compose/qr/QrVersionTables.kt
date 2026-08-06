/**
 * @author Shakib
 * @since 03/08/26
 *
 * Every literal below is a published QR capacity/block-table value (ISO/IEC 18004 Table 9 / E.1), not app data.
 */
@file:Suppress("MagicNumber")

package io.devconsole.ui.compose.qr

/** QR error-correction level; only the two levels this encoder chooses between (ISO/IEC 18004 Table 12). */
internal enum class QrErrorCorrectionLevel(
    val indicatorBits: Int,
) {
    L(0b01),
    M(0b00),
}

/** One Reed-Solomon block group: [count] identical blocks, each holding [dataCodewords] data codewords. */
internal data class QrBlockSpec(
    val count: Int,
    val dataCodewords: Int,
)

/** Per-version, per-level capacity and block-structure constants (ISO/IEC 18004 Table 9), versions 1-7 only. */
internal data class QrVersionInfo(
    val version: Int,
    val level: QrErrorCorrectionLevel,
    val totalDataCodewords: Int,
    val ecCodewordsPerBlock: Int,
    val blocks: List<QrBlockSpec>,
)

/**
 * Byte-mode capacity/version selection for versions 1-7. Byte mode is the only mode this encoder
 * emits, so every table entry and the [selectVersion] policy is scoped to it.
 *
 * The block table below is transcribed from ISO/IEC 18004 Table 9 (equivalently, the widely
 * reproduced Thonky QR tutorial "Error Correction Table"); alignment-pattern positions are Table
 * E.1. Both were cross-checked against Project Nayuki's public-domain QR generator during
 * development -- see the KDoc on [QrReedSolomon] and `QrKnownAnswerTest` for the independently
 * verified worked example this whole pipeline is built and tested against.
 *
 * [selectVersion] prefers EC level M (better error tolerance) at the smallest version 1-7 that
 * fits; only when no version 1-7 fits at M -- i.e. M would require a version this encoder doesn't
 * support -- does it fall back to L, which still fits every version 1-7 up to
 * [MAX_SUPPORTED_BYTES]. This keeps the common case (short pairing/session-code URLs) at the more
 * robust level while still covering the ~120-character ceiling this encoder targets.
 */
internal object QrVersionTables {
    const val MIN_VERSION = 1
    const val MAX_VERSION = 7

    /** Byte-mode capacity of the largest supported version at the most permissive level (V7-L). */
    val MAX_SUPPORTED_BYTES: Int by lazy {
        byteCapacity(requireNotNull(TABLE[MAX_VERSION to QrErrorCorrectionLevel.L]))
    }

    // Byte mode indicator (4 bits) + versions 1-9 character-count indicator (8 bits).
    private const val MODE_AND_COUNT_HEADER_BITS = 4 + 8

    private val TABLE: Map<Pair<Int, QrErrorCorrectionLevel>, QrVersionInfo> =
        buildList {
            add(1, QrErrorCorrectionLevel.L, 19, 7, QrBlockSpec(1, 19))
            add(1, QrErrorCorrectionLevel.M, 16, 10, QrBlockSpec(1, 16))
            add(2, QrErrorCorrectionLevel.L, 34, 10, QrBlockSpec(1, 34))
            add(2, QrErrorCorrectionLevel.M, 28, 16, QrBlockSpec(1, 28))
            add(3, QrErrorCorrectionLevel.L, 55, 15, QrBlockSpec(1, 55))
            add(3, QrErrorCorrectionLevel.M, 44, 26, QrBlockSpec(1, 44))
            add(4, QrErrorCorrectionLevel.L, 80, 20, QrBlockSpec(1, 80))
            add(4, QrErrorCorrectionLevel.M, 64, 18, QrBlockSpec(2, 32))
            add(5, QrErrorCorrectionLevel.L, 108, 26, QrBlockSpec(1, 108))
            add(5, QrErrorCorrectionLevel.M, 86, 24, QrBlockSpec(2, 43))
            add(6, QrErrorCorrectionLevel.L, 136, 18, QrBlockSpec(2, 68))
            add(6, QrErrorCorrectionLevel.M, 108, 16, QrBlockSpec(4, 27))
            add(7, QrErrorCorrectionLevel.L, 156, 20, QrBlockSpec(2, 78))
            add(7, QrErrorCorrectionLevel.M, 124, 18, QrBlockSpec(4, 31))
        }.associateBy { it.version to it.level }

    /** Alignment-pattern center coordinates (row and column share the same list; ISO/IEC 18004 Table E.1). */
    val ALIGNMENT_POSITIONS: Map<Int, List<Int>> =
        mapOf(
            1 to emptyList(),
            2 to listOf(6, 18),
            3 to listOf(6, 22),
            4 to listOf(6, 26),
            5 to listOf(6, 30),
            6 to listOf(6, 34),
            7 to listOf(6, 22, 38),
        )

    private fun MutableList<QrVersionInfo>.add(
        version: Int,
        level: QrErrorCorrectionLevel,
        totalDataCodewords: Int,
        ecCodewordsPerBlock: Int,
        block: QrBlockSpec,
    ) = add(QrVersionInfo(version, level, totalDataCodewords, ecCodewordsPerBlock, listOf(block)))

    /** Usable byte-mode payload size: total data bits minus the mode+count header, floored to whole bytes. */
    fun byteCapacity(info: QrVersionInfo): Int = (info.totalDataCodewords * 8 - MODE_AND_COUNT_HEADER_BITS) / 8

    /** Smallest version 1-7 (preferring level M) whose byte-mode capacity fits [byteLength]; null if none does. */
    fun selectVersion(byteLength: Int): QrVersionInfo? {
        for (level in listOf(QrErrorCorrectionLevel.M, QrErrorCorrectionLevel.L)) {
            for (version in MIN_VERSION..MAX_VERSION) {
                val info = TABLE[version to level] ?: continue
                if (byteCapacity(info) >= byteLength) return info
            }
        }
        return null
    }
}
