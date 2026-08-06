/**
 * @author Shakib
 * @since 04/08/26
 */
package io.devconsole.ui.compose

import androidx.compose.ui.graphics.Color

private const val HTTP_REDIRECT_START = 300
private const val HTTP_CLIENT_ERROR_START = 400
private const val HTTP_SERVER_ERROR_START = 500

/** A captured HTTP transaction with no status code at all means the request never got a response. */
internal fun InspectorTransactionUi.isFailing(): Boolean = statusCode == null || statusCode >= HTTP_CLIENT_ERROR_START

/** Matches the design spec's status-color scheme. */
internal fun statusTint(
    statusCode: Int?,
    colors: DevConsoleColors,
): Color =
    when {
        statusCode == null -> colors.error
        statusCode >= HTTP_SERVER_ERROR_START -> colors.error
        statusCode >= HTTP_CLIENT_ERROR_START -> colors.warn
        statusCode >= HTTP_REDIRECT_START -> colors.put
        else -> colors.signal
    }

/** Matches the design spec's method-color scheme: (lead text color, lead badge background). */
internal fun methodTint(
    method: String,
    colors: DevConsoleColors,
): Pair<Color, Color> =
    when (method) {
        "GET" -> colors.signal to colors.signalSoft
        "POST", "PUT" -> colors.put to colors.putSoft
        "PATCH" -> colors.warn to colors.warnSoft
        "DELETE" -> colors.error to colors.errorSoft
        else -> colors.muted to colors.surface2
    }

/** Abbreviates only DELETE; every other method passes through unchanged. */
internal fun methodLeadText(method: String): String = if (method == "DELETE") "DEL" else method
