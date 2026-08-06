/**
 * @author Shakib
 * @since 02/08/26
 */
package io.devconsole.ui.compose

private const val RADIX_HEX = 16
private const val UNICODE_ESCAPE_WIDTH = 4

/**
 * The request body to actually reproduce (replay/cURL/fetch), or `null` when [requestPreview] is
 * not real body content -- i.e. a `[binary, N bytes]` placeholder for [InspectorBodyKind.BINARY],
 * or nothing captured at all. Only [InspectorBodyKind.TEXT] is ever safe to send or copy verbatim.
 *
 * Block body is deliberate: the equivalent one-line expression body is 135 chars, over detekt's
 * 120-char MaxLineLength, but ktlint's function-expression-body rule wants a block this short
 * converted back to an expression -- an irreconcilable conflict between the two linters'
 * thresholds for this exact declaration. Suppressing the ktlint rule here (rather than growing
 * the detekt baseline) keeps the conflict visible at the one call site it affects; see 50f413a
 * for the same conflict/fix.
 */
@Suppress("ktlint:standard:function-expression-body")
private fun InspectorTransactionUi.sendableRequestBody(): String? {
    return requestPreview.takeIf { requestBodyKind == InspectorBodyKind.TEXT }
}

/**
 * Reproduces a captured transaction as a composer request so Replay reuses the existing
 * [InspectorDataSource.execute] path (capability gate + exact-host allow-list) unchanged. A
 * binary request body is never fabricated from its `[binary, N bytes]` placeholder text.
 */
internal fun InspectorTransactionUi.toComposerRequest(): InspectorComposerRequest =
    InspectorComposerRequest(
        method = method,
        url = displayUrl(),
        headers = requestHeaders,
        body = sendableRequestBody(),
    )

/**
 * Redacted cURL reproduction of the captured request, built only from the already-redacted
 * [InspectorTransactionUi] fields -- never from a live network object. A binary body is omitted
 * (with a comment noting why) rather than sent as its placeholder text; a truncated text body is
 * still included but flagged so the caller knows it is a partial capture.
 */
internal fun InspectorTransactionUi.toCurlCommand(): String =
    buildString {
        when (requestBodyKind) {
            InspectorBodyKind.BINARY -> append("# body omitted: binary request body was not captured as text\n")
            InspectorBodyKind.TEXT ->
                if (requestBodyTruncated) append("# warning: request body was truncated at the capture limit\n")
            InspectorBodyKind.ABSENT -> Unit
        }
        append("curl -X ").append(method.shellQuoted())
        requestHeaders.entries.sortedBy { it.key.lowercase() }.forEach { (name, value) ->
            append(" -H ").append("$name: $value".shellQuoted())
        }
        sendableRequestBody()?.let { body -> append(" --data-raw ").append(body.shellQuoted()) }
        append(' ').append(displayUrl().shellQuoted())
    }

/**
 * Redacted `fetch()` reproduction of the captured request. A binary body is omitted (with a
 * comment) instead of being sent as its placeholder text; a truncated text body is flagged.
 */
internal fun InspectorTransactionUi.toFetchSnippet(): String =
    buildString {
        when (requestBodyKind) {
            InspectorBodyKind.BINARY -> append("// body omitted: binary request body was not captured as text\n")
            InspectorBodyKind.TEXT ->
                if (requestBodyTruncated) append("// warning: request body was truncated at the capture limit\n")
            InspectorBodyKind.ABSENT -> Unit
        }
        append("fetch(").append(displayUrl().jsonQuoted()).append(", {\n")
        append("  method: ").append(method.jsonQuoted()).append(",\n")
        if (requestHeaders.isNotEmpty()) {
            append("  headers: {\n")
            requestHeaders.entries.forEachIndexed { index, (name, value) ->
                append("    ").append(name.jsonQuoted()).append(": ").append(value.jsonQuoted())
                append(if (index < requestHeaders.size - 1) ",\n" else "\n")
            }
            append("  },\n")
        }
        sendableRequestBody()?.let { body -> append("  body: ").append(body.jsonQuoted()).append(",\n") }
        append("});")
    }

/** Redacted JSON reproduction of the captured transaction, for pasting into any HTTP tool. */
internal fun InspectorTransactionUi.toJsonSnippet(): String =
    buildString {
        append("{\n")
        append("  \"method\": ").append(method.jsonQuoted()).append(",\n")
        append("  \"url\": ").append(displayUrl().jsonQuoted()).append(",\n")
        append("  \"status\": ").append(statusCode?.toString() ?: "null").append(",\n")
        append("  \"durationMs\": ").append(durationMs?.toString() ?: "null").append(",\n")
        append("  \"requestHeaders\": ").append(requestHeaders.jsonObject()).append(",\n")
        append("  \"requestBody\": ").append(requestPreview.jsonQuotedOrNull()).append(",\n")
        append("  \"responseHeaders\": ").append(responseHeaders.jsonObject()).append(",\n")
        append("  \"responseBody\": ").append(responsePreview.jsonQuotedOrNull()).append(",\n")
        append("  \"error\": ").append(error.jsonQuotedOrNull()).append('\n')
        append('}')
    }

/** [InspectorTransactionUi.url] is empty for adapters that predate that field. */
private fun InspectorTransactionUi.displayUrl(): String = url.ifBlank { "https://$host$path" }

private fun Map<String, String>.jsonObject(): String =
    if (isEmpty()) {
        "{}"
    } else {
        entries.joinToString(prefix = "{\n", postfix = "\n  }", separator = ",\n") { (key, value) ->
            "    ${key.jsonQuoted()}: ${value.jsonQuoted()}"
        }
    }

internal fun String?.jsonQuotedOrNull(): String = this?.jsonQuoted() ?: "null"

/** Shared with the frame/push/log "share as JSON" snippets in InspectorObserve{Frame,Push,Log}Detail.kt. */
internal fun String.jsonQuoted(): String =
    buildString {
        append('"')
        this@jsonQuoted.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (character.code < ' '.code) {
                        append("\\u").append(character.code.toString(RADIX_HEX).padStart(UNICODE_ESCAPE_WIDTH, '0'))
                    } else {
                        append(character)
                    }
            }
        }
        append('"')
    }

private fun String.shellQuoted(): String = "'${replace("'", "'\\''")}'"
