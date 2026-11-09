/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole.server.ktor

import io.devconsole.mocks.MockAction

/** Serializes the whole [MockAction] tree for `MockRule.json`'s `actionDetail` field. */
internal fun MockAction.actionDetailJson(): String =
    when (this) {
        is MockAction.StaticResponse -> responseJson("static", statusCode, body, headers)
        is MockAction.TemplateResponse -> templateJson()
        is MockAction.Delay -> delayJson()
        is MockAction.ConnectionFailure ->
            "{\"type\":\"connection_failure\",\"message\":\"${message.escapeJson()}\"}"
        is MockAction.Timeout -> "{\"type\":\"timeout\",\"durationMs\":$durationMs}"
        is MockAction.StatusOverride -> "{\"type\":\"status_override\",\"statusCode\":$statusCode}"
        is MockAction.BodyReplacement -> {
            val (body, truncated) = body.truncateForMockRuleJson()
            "{\"type\":\"body_replacement\",\"body\":\"${body.escapeJson()}\",\"bodyTruncated\":$truncated}"
        }
        is MockAction.Sequence -> sequenceJson()
        is MockAction.MapRemote -> mapRemoteJson()
        is MockAction.Throttle -> throttleJson()
        is MockAction.Passthrough -> "{\"type\":\"passthrough\"}"
    }

private fun responseJson(
    type: String,
    statusCode: Int,
    bodyText: String,
    headers: Map<String, String>,
): String {
    val (body, truncated) = bodyText.truncateForMockRuleJson()
    return "{\"type\":\"$type\",\"statusCode\":$statusCode,\"body\":\"${body.escapeJson()}\"," +
        "\"bodyTruncated\":$truncated,\"headers\":${headersJson(headers)}}"
}

private fun MockAction.TemplateResponse.templateJson(): String {
    val (template, truncated) = template.truncateForMockRuleJson()
    return "{\"type\":\"template\",\"statusCode\":$statusCode,\"template\":\"${template.escapeJson()}\"," +
        "\"templateTruncated\":$truncated,\"headers\":${headersJson(headers)}}"
}

private fun MockAction.Delay.delayJson(): String =
    "{\"type\":\"delay\",\"durationMs\":$durationMs,\"next\":${next.actionDetailJson()}}"

private fun MockAction.Sequence.sequenceJson(): String =
    "{\"type\":\"sequence\",\"loop\":$loop,\"steps\":[" +
        steps.joinToString(",") { it.actionDetailJson() } + "]}"

private fun MockAction.MapRemote.mapRemoteJson(): String =
    "{\"type\":\"map_remote\",\"scheme\":${scheme.orNullJson()},\"host\":${host.orNullJson()}," +
        "\"port\":${port?.toString() ?: "null"},\"path\":${path.orNullJson()}}"

private fun MockAction.Throttle.throttleJson(): String =
    "{\"type\":\"throttle\",\"bytesPerSecond\":$bytesPerSecond,\"latencyMs\":$latencyMs," +
        "\"next\":${next.actionDetailJson()}}"

private fun headersJson(headers: Map<String, String>): String =
    headers.entries.joinToString(",", "{", "}") { (name, value) ->
        "\"${name.escapeJson()}\":\"${value.escapeJson()}\""
    }

internal fun String?.orNullJson(): String = this?.let { "\"${it.escapeJson()}\"" } ?: "null"

/** The static response a rule list entry previews; a `Delay(StaticResponse)` reports the wrapped one. */
internal val MockAction.previewStatic: MockAction.StaticResponse?
    get() = ((this as? MockAction.Delay)?.next ?: this) as? MockAction.StaticResponse

/** Rendered as JSON so `MockRule.json` never branches for an action that carries no status. */
internal val MockAction.previewStatusCodeJson: String
    get() = statusCodeOrNull?.toString() ?: "null"

/** Rendered as JSON: `null` unless the action delays its response. */
internal val MockAction.previewDelayMsJson: String
    get() = (this as? MockAction.Delay)?.durationMs?.toString() ?: "null"
