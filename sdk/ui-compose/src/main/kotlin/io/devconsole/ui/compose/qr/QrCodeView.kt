/**
 * @author Shakib
 * @since 03/08/26
 */
@file:Suppress("FunctionNaming") // Composable name follows PascalCase, matching every other Composable in this module.

package io.devconsole.ui.compose.qr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val matrix by produceState<QrMatrix?>(initialValue = null, url) {
        value = withContext(Dispatchers.Default) { QrCodeEncoder.encode(url) }
    }
    Canvas(
        modifier =
            modifier
                .size(DEFAULT_QR_SIZE)
                .drawWithCache {
                    val m = matrix
                    if (m == null) {
                        onDrawBehind { }
                    } else {
                        val totalModules = m.size + QUIET_ZONE_MODULES * 2
                        val moduleSize = minOf(size.width, size.height) / totalModules
                        val quietZoneOffset = moduleSize * QUIET_ZONE_MODULES
                        val boardSize = moduleSize * totalModules
                        val originX = (size.width - boardSize) / 2f + quietZoneOffset
                        val originY = (size.height - boardSize) / 2f + quietZoneOffset

                        val path =
                            Path().apply {
                                for (row in 0 until m.size) {
                                    for (col in 0 until m.size) {
                                        if (m[row, col]) {
                                            addRect(
                                                Rect(
                                                    offset =
                                                        Offset(
                                                            originX + col * moduleSize,
                                                            originY + row * moduleSize,
                                                        ),
                                                    size = Size(moduleSize, moduleSize),
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        onDrawBehind {
                            drawRect(
                                color = Color.White,
                                topLeft = Offset(originX - quietZoneOffset, originY - quietZoneOffset),
                                size = Size(boardSize, boardSize),
                            )
                            drawPath(path, Color.Black)
                        }
                    }
                },
    ) {
    }
}
