/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole.server.ktor

import io.devconsole.mocks.MockAction
import io.ktor.http.Parameters

private const val ACTION_TYPE_PARAM = "actionType"
private const val STATUS_PARAM = "status"
private const val BODY_PARAM = "body"
private const val DELAY_PARAM = "delayMs"
private const val FAILURE_MESSAGE_PARAM = "failureMessage"
private const val TIMEOUT_PARAM = "timeoutMs"
private const val BYTES_PER_SECOND_PARAM = "bytesPerSecond"
private const val LATENCY_PARAM = "latencyMs"
private const val REMOTE_SCHEME_PARAM = "remoteScheme"
private const val REMOTE_HOST_PARAM = "remoteHost"
private const val REMOTE_PORT_PARAM = "remotePort"
private const val REMOTE_PATH_PARAM = "remotePath"
private const val STEP_TYPE_PARAM = "stepType"
private const val STEP_STATUS_PARAM = "stepStatus"
private const val STEP_BODY_PARAM = "stepBody"
private const val STEP_DELAY_PARAM = "stepDelayMs"
private const val STEP_TIMEOUT_PARAM = "stepTimeoutMs"
private const val STEP_LOOP_PARAM = "loop"

private const val ACTION_STATIC = "static"
private const val ACTION_TEMPLATE = "template"
private const val ACTION_STATUS_OVERRIDE = "status_override"
private const val ACTION_BODY_REPLACEMENT = "body_replacement"
private const val ACTION_CONNECTION_FAILURE = "connection_failure"
private const val ACTION_TIMEOUT = "timeout"
private const val ACTION_MAP_REMOTE = "map_remote"
private const val ACTION_THROTTLE = "throttle"
private const val ACTION_SEQUENCE = "sequence"
private const val ACTION_PASSTHROUGH = "passthrough"

private const val STEP_STATIC = "static"
private const val STEP_CONNECTION_FAILURE = "connection_failure"
private const val STEP_TIMEOUT = "timeout"
private const val STEP_PASSTHROUGH = "passthrough"

private const val MIN_STATUS = 100
private const val MAX_STATUS = 599
private const val DEFAULT_THROTTLE_LATENCY_MS = 0L

/** Builds the full [MockAction] tree a mock-rule form describes. Invalid input throws. */
internal fun parseMockAction(
    parameters: Parameters,
    headers: Map<String, String>,
): MockAction {
    val type =
        parameters[ACTION_TYPE_PARAM]
            ?.trim()
            ?.lowercase()
            .orEmpty()
            .ifEmpty { ACTION_STATIC }
    return when (type) {
        ACTION_STATIC -> parseResponseAction(parameters, headers, template = false)
        ACTION_TEMPLATE -> parseResponseAction(parameters, headers, template = true)
        ACTION_STATUS_OVERRIDE -> MockAction.StatusOverride(parameters.statusCode())
        ACTION_BODY_REPLACEMENT -> MockAction.BodyReplacement(parameters[BODY_PARAM].orEmpty())
        ACTION_CONNECTION_FAILURE -> connectionFailure(parameters[FAILURE_MESSAGE_PARAM])
        ACTION_TIMEOUT ->
            MockAction.Timeout(
                requireNotNull(parameters.longOrNull(TIMEOUT_PARAM)) { "$TIMEOUT_PARAM is required" },
            )
        ACTION_MAP_REMOTE -> parseMapRemote(parameters)
        ACTION_THROTTLE ->
            MockAction.Throttle(
                bytesPerSecond =
                    requireNotNull(parameters.longOrNull(BYTES_PER_SECOND_PARAM)) {
                        "$BYTES_PER_SECOND_PARAM is required"
                    },
                latencyMs = parameters.longOrNull(LATENCY_PARAM) ?: DEFAULT_THROTTLE_LATENCY_MS,
            )
        ACTION_SEQUENCE -> parseSequence(parameters)
        ACTION_PASSTHROUGH -> MockAction.Passthrough
        else -> throw IllegalArgumentException("Unknown mock action type: $type")
    }
}

/** The status a rule's action carries, when it has exactly one (a sequence reports null). */
internal val MockAction.statusCodeOrNull: Int?
    get() =
        when (this) {
            is MockAction.StaticResponse -> statusCode
            is MockAction.TemplateResponse -> statusCode
            is MockAction.StatusOverride -> statusCode
            is MockAction.Delay -> next.statusCodeOrNull
            else -> null
        }

private fun parseResponseAction(
    parameters: Parameters,
    headers: Map<String, String>,
    template: Boolean,
): MockAction {
    val status = parameters.statusCode()
    val body = parameters[BODY_PARAM].orEmpty()
    val response =
        if (template) {
            MockAction.TemplateResponse(status, body, headers)
        } else {
            MockAction.StaticResponse(status, body, headers)
        }
    val delay = parameters.longOrNull(DELAY_PARAM) ?: return response
    return MockAction.Delay(delay, response)
}

private fun connectionFailure(message: String?): MockAction.ConnectionFailure {
    val trimmed = message?.takeIf(String::isNotBlank) ?: return MockAction.ConnectionFailure()
    return MockAction.ConnectionFailure(trimmed)
}

private fun parseMapRemote(parameters: Parameters): MockAction.MapRemote =
    MockAction.MapRemote(
        scheme = parameters[REMOTE_SCHEME_PARAM]?.trim()?.takeIf(String::isNotEmpty),
        host = parameters[REMOTE_HOST_PARAM]?.trim()?.takeIf(String::isNotEmpty),
        port = parameters.intOrNull(REMOTE_PORT_PARAM),
        path = parameters[REMOTE_PATH_PARAM]?.trim()?.takeIf(String::isNotEmpty),
    )

private fun parseSequence(parameters: Parameters): MockAction.Sequence {
    val types = parameters.getAll(STEP_TYPE_PARAM).orEmpty()
    require(types.isNotEmpty()) { "A sequence needs at least one step" }
    val statuses = parameters.getAll(STEP_STATUS_PARAM).orEmpty()
    val bodies = parameters.getAll(STEP_BODY_PARAM).orEmpty()
    val delays = parameters.getAll(STEP_DELAY_PARAM).orEmpty()
    val timeouts = parameters.getAll(STEP_TIMEOUT_PARAM).orEmpty()
    listOf(statuses, bodies, delays, timeouts).forEach { values ->
        require(values.isEmpty() || values.size == types.size) { "Sequence step lists must match in length" }
    }
    val steps =
        types.indices.map { index ->
            parseSequenceStep(
                type = types[index],
                status = statuses.getOrNull(index),
                body = bodies.getOrNull(index),
                delayMs = delays.getOrNull(index),
                timeoutMs = timeouts.getOrNull(index),
            )
        }
    val loop = parameters[STEP_LOOP_PARAM]?.toBooleanStrictOrNull() ?: false
    return MockAction.Sequence(steps, loop)
}

private fun parseSequenceStep(
    type: String,
    status: String?,
    body: String?,
    delayMs: String?,
    timeoutMs: String?,
): MockAction {
    val step =
        when (type.trim().lowercase()) {
            STEP_STATIC -> {
                val code =
                    requireNotNull(status?.trim()?.toIntOrNull()) { "A static sequence step needs a status" }
                require(code in MIN_STATUS..MAX_STATUS) { "status must be between $MIN_STATUS and $MAX_STATUS" }
                MockAction.StaticResponse(code, body.orEmpty())
            }
            STEP_CONNECTION_FAILURE -> connectionFailure(body)
            STEP_TIMEOUT ->
                MockAction.Timeout(
                    requireNotNull(timeoutMs.toStepLongOrNull(STEP_TIMEOUT_PARAM)) {
                        "$STEP_TIMEOUT_PARAM is required for a timeout step"
                    },
                )
            STEP_PASSTHROUGH -> MockAction.Passthrough
            else -> throw IllegalArgumentException("Unknown sequence step type: $type")
        }
    val delay = delayMs.toStepLongOrNull(STEP_DELAY_PARAM) ?: return step
    require(step is MockAction.StaticResponse) { "Only static sequence steps can be delayed" }
    return MockAction.Delay(delay, step)
}

private fun Parameters.statusCode(): Int {
    val status =
        this[STATUS_PARAM]?.trim()?.toIntOrNull()
            ?: throw IllegalArgumentException("$STATUS_PARAM is required")
    require(status in MIN_STATUS..MAX_STATUS) { "status must be between $MIN_STATUS and $MAX_STATUS" }
    return status
}

private fun Parameters.longOrNull(name: String): Long? {
    val raw = this[name]?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return raw.toLongOrNull() ?: throw IllegalArgumentException("$name must be a number")
}

private fun Parameters.intOrNull(name: String): Int? {
    val raw = this[name]?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return raw.toIntOrNull() ?: throw IllegalArgumentException("$name must be a number")
}

private fun String?.toStepLongOrNull(name: String): Long? {
    val raw = this?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return raw.toLongOrNull() ?: throw IllegalArgumentException("$name must be a number")
}
