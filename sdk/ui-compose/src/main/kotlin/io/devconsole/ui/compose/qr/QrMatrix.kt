/**
 * @author Shakib
 * @since 03/08/26
 */
package io.devconsole.ui.compose.qr

/** Immutable square grid of QR modules; `true` means a dark (printed) module. Row/column are both 0-based. */
internal class QrMatrix(
    val size: Int,
    private val darkModules: BooleanArray,
) {
    init {
        require(darkModules.size == size * size) { "Expected $size*$size modules, got ${darkModules.size}" }
    }

    operator fun get(
        row: Int,
        col: Int,
    ): Boolean = darkModules[row * size + col]
}

/**
 * Mutable working grid used while a [QrMatrix] is under construction. Coordinates follow the
 * `(x = column, y = row)` convention the ISO/IEC 18004 module-placement algorithm (and every public
 * reference implementation of it) is conventionally described in, so the placement code in
 * [QrFunctionPatterns], [QrFormatVersionInfo], and [QrCodeEncoder] can be checked term-by-term
 * against that reference without a row/column transposition to account for.
 */
internal class QrMutableMatrix(
    val version: Int,
) {
    val size: Int = QR_BASE_SIZE + QR_SIZE_PER_VERSION * version
    private val modules = BooleanArray(size * size)
    private val functionModules = BooleanArray(size * size)

    private fun index(
        x: Int,
        y: Int,
    ) = y * size + x

    fun get(
        x: Int,
        y: Int,
    ): Boolean = modules[index(x, y)]

    fun isFunctionModule(
        x: Int,
        y: Int,
    ): Boolean = functionModules[index(x, y)]

    /**
     * Marks a module as part of a fixed pattern (finder/timing/alignment/dark/format/version);
     * out-of-bounds is a no-op.
     */
    fun setFunctionModule(
        x: Int,
        y: Int,
        dark: Boolean,
    ) {
        if (x !in 0 until size || y !in 0 until size) return
        modules[index(x, y)] = dark
        functionModules[index(x, y)] = true
    }

    /** Sets a data-carrying module; must never be called on a module already claimed by [setFunctionModule]. */
    fun setDataModule(
        x: Int,
        y: Int,
        dark: Boolean,
    ) {
        modules[index(x, y)] = dark
    }

    fun toggle(
        x: Int,
        y: Int,
    ) {
        val i = index(x, y)
        modules[i] = !modules[i]
    }

    fun toImmutable(): QrMatrix = QrMatrix(size, modules.copyOf())

    private companion object {
        const val QR_BASE_SIZE = 17
        const val QR_SIZE_PER_VERSION = 4
    }
}
