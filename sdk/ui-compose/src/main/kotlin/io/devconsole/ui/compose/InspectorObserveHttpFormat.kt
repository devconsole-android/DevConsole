/**
 * @author Shakib
 * @since 04/08/26
 */
package io.devconsole.ui.compose

import androidx.compose.ui.graphics.Color

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
        else -> colors.ink
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

/** Standard reason phrases, keyed by the code's text; unknown codes keep their captured numeric identity. */
private val HTTP_REASON_PHRASES =
    mapOf(
        "200" to "OK",
        "201" to "Created",
        "202" to "Accepted",
        "204" to "No Content",
        "206" to "Partial Content",
        "301" to "Moved Permanently",
        "302" to "Found",
        "304" to "Not Modified",
        "307" to "Temporary Redirect",
        "308" to "Permanent Redirect",
        "400" to "Bad Request",
        "401" to "Unauthorized",
        "403" to "Forbidden",
        "404" to "Not Found",
        "405" to "Method Not Allowed",
        "408" to "Request Timeout",
        "409" to "Conflict",
        "410" to "Gone",
        "422" to "Unprocessable Content",
        "429" to "Too Many Requests",
        "500" to "Internal Server Error",
        "501" to "Not Implemented",
        "502" to "Bad Gateway",
        "503" to "Service Unavailable",
        "504" to "Gateway Timeout",
    )

internal fun httpStatusDescription(code: Int?): String =
    if (code == null) "No response received" else HTTP_REASON_PHRASES[code.toString()].orEmpty()
