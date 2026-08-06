/**
 * @author Shakib
 * @since 03/08/26
 */
@file:Suppress("FunctionNaming") // Composable name follows PascalCase, matching every other Composable in this module.

package io.devconsole.ui.compose.qr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DEFAULT_QR_SIZE = 176.dp
private const val QUIET_ZONE_MODULES = 4

/**
 * Renders [url] as a scannable QR code on a Compose [Canvas] -- no bitmap file, no third-party
 * dependency; see [QrCodeEncoder] for the encoding itself. Recomposes whenever [url] changes
 * (`remember(url)` keys the re-encode to it), and draws nothing if [url] is too long to encode
 * ([QrCodeEncoder.encode] returning `null`) so a caller can unconditionally place this next to the
 * connect-URL text without its own null check.
 */
@Composable
internal fun ConnectUrlQrCode(
    url: String,
    modifier: Modifier = Modifier,
) {
    val matrix = remember(url) { QrCodeEncoder.encode(url) } ?: return
    Canvas(modifier = modifier.size(DEFAULT_QR_SIZE)) {
        val totalModules = matrix.size + QUIET_ZONE_MODULES * 2
        val moduleSize = minOf(size.width, size.height) / totalModules
        val quietZoneOffset = moduleSize * QUIET_ZONE_MODULES
        val boardSize = moduleSize * totalModules
        val originX = (size.width - boardSize) / 2f + quietZoneOffset
        val originY = (size.height - boardSize) / 2f + quietZoneOffset

        drawRect(
            color = Color.White,
            topLeft = Offset(originX - quietZoneOffset, originY - quietZoneOffset),
            size = Size(boardSize, boardSize),
        )
        for (row in 0 until matrix.size) {
            for (col in 0 until matrix.size) {
                if (matrix[row, col]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(originX + col * moduleSize, originY + row * moduleSize),
                        size = Size(moduleSize, moduleSize),
                    )
                }
            }
        }
    }
}
