/**
 * @author Shakib
 * @since 05/08/26
 */
@file:Suppress("TooManyFunctions") // One small validator/parser per editable mock-rule field, grouped with the form.

package io.devconsole.ui.compose

import java.util.Locale

/** Mirrors FullInspectorDataSource.MOCK_RULE_ID_PATTERN (sdk/full) -- this module has no dependency on it. */
internal val MOCK_RULE_ID_REGEX = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")
private const val MOCK_RULE_MIN_STATUS = 100
private const val MOCK_RULE_MAX_STATUS = 599
internal const val MOCK_RULE_MAX_DELAY_MS = 30_000L
private const val MOCK_RULE_ID_SUGGESTION_MAX_LENGTH = 64

/** The real `MockScope` enum values (sdk:mocks), as literals -- ui-compose has no dependency on that module. */
internal val MOCK_RULE_SCOPES = listOf("SESSION", "PERSISTENT_INTERNAL", "TEST_FIXTURE")

/** Inline-error text for the id field, or null when valid; mirrors FullInspectorDataSource.upsertMockRule. */
internal fun mockRuleIdError(id: String): String? =
    when {
        id.isBlank() -> "Give the rule an id, e.g. rule-checkout"
        id.matches(MOCK_RULE_ID_REGEX) -> null
        else -> "Letters, digits, . _ - only -- can't start with . _ or -"
    }

/** Inline-error text for the status field, or null when valid; mirrors FullInspectorDataSource.upsertMockRule. */
internal fun mockRuleStatusError(statusCode: Int?): String? {
    val range = "$MOCK_RULE_MIN_STATUS-$MOCK_RULE_MAX_STATUS"
    return when {
        statusCode == null -> "Must be a number"
        statusCode !in MOCK_RULE_MIN_STATUS..MOCK_RULE_MAX_STATUS -> "Must be $range"
        else -> null
    }
}

/** Delay is optional -- a blank field is valid (no delay); mirrors FullInspectorDataSource.upsertMockRule. */
internal fun mockRuleDelayError(
    delayText: String,
    delayMs: Long?,
): String? =
    when {
        delayText.isBlank() -> null
        delayMs == null -> "Must be a number"
        delayMs !in 0..MOCK_RULE_MAX_DELAY_MS -> "Must be 0-$MOCK_RULE_MAX_DELAY_MS ms"
        else -> null
    }

/** [skippedLines] counts lines with no `:` -- shown as a non-blocking hint, never blocks Save. */
internal data class ParsedMockRuleHeaders(
    val headers: Map<String, String>,
    val skippedLines: Int,
)

/** Parses the multiline `Name: value` convention the composer and server round-trip already use. */
internal fun parseMockRuleHeaderLines(text: String): ParsedMockRuleHeaders {
    var skipped = 0
    val headers = LinkedHashMap<String, String>()
    text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
        val colonIndex = line.indexOf(':')
        if (colonIndex <= 0) {
            skipped++
        } else {
            headers[line.substring(0, colonIndex).trim()] = line.substring(colonIndex + 1).trim()
        }
    }
    return ParsedMockRuleHeaders(headers, skipped)
}

internal fun Map<String, String>.toMockRuleHeaderLines(): String = entries.joinToString("\n") { (k, v) -> "$k: $v" }

/** Every editable action type offered by the sheet's Action dropdown, in display order. */
internal val MOCK_RULE_ACTION_TYPES =
    listOf(
        InspectorMockActionType.STATIC,
        InspectorMockActionType.STATUS_OVERRIDE,
        InspectorMockActionType.BODY_REPLACEMENT,
        InspectorMockActionType.CONNECTION_FAILURE,
        InspectorMockActionType.TIMEOUT,
        InspectorMockActionType.THROTTLE,
        InspectorMockActionType.MAP_REMOTE,
    )

@Suppress("MagicNumber")
internal val MOCK_RULE_STATUS_PRESETS = listOf(200, 201, 400, 401, 404, 500)

internal fun mockRuleMatchesEverything(
    method: String,
    path: String,
): Boolean = method == "ALL" && (path.isBlank() || path.trim() == ".*")

internal fun mockRulePathPatternForLiteral(path: String): String =
    if (path.any { it in "\\.[]{}()*+?^$|" }) Regex.escape(path) else path

internal fun InspectorMockActionType.plainLabel(): String =
    when (this) {
        InspectorMockActionType.STATIC -> "Return a response"
        InspectorMockActionType.STATUS_OVERRIDE -> "Change status only"
        InspectorMockActionType.BODY_REPLACEMENT -> "Change body only"
        InspectorMockActionType.CONNECTION_FAILURE -> "Fail the connection"
        InspectorMockActionType.TIMEOUT -> "Time out"
        InspectorMockActionType.THROTTLE -> "Slow it down"
        InspectorMockActionType.MAP_REMOTE -> "Use another server"
        InspectorMockActionType.OTHER -> "Other (read-only)"
    }

internal fun InspectorMockActionType.label(): String =
    when (this) {
        InspectorMockActionType.STATIC -> "Static response"
        InspectorMockActionType.STATUS_OVERRIDE -> "Status override"
        InspectorMockActionType.BODY_REPLACEMENT -> "Body replacement"
        InspectorMockActionType.CONNECTION_FAILURE -> "Connection failure"
        InspectorMockActionType.TIMEOUT -> "Timeout"
        InspectorMockActionType.THROTTLE -> "Throttle"
        InspectorMockActionType.MAP_REMOTE -> "Map remote"
        InspectorMockActionType.OTHER -> "Other (read-only)"
    }

/**
 * Whether a rule's action round-trips through the create/edit sheet without data loss. Decided by
 * [InspectorMockRuleUi.actionType]: every shape the sheet can rebuild is editable, and
 * [InspectorMockActionType.OTHER] (sequence/template/passthrough, or a delayed non-static action)
 * is not -- opening the editor on one of those and pressing Save would silently replace it.
 */
internal fun InspectorMockRuleUi.isEditableOnDevice(): Boolean = actionType != InspectorMockActionType.OTHER

/**
 * Label-based fallback kept for callers that only have [InspectorMockRuleUi.actionLabel]. Every
 * editable action type except `OTHER` is accepted; a Delay is editable only when its wrapped action
 * is explicitly the static response action (mirrors FullInspectorDataSource.toUi's mapping).
 */
internal fun isEditableMockActionLabel(actionLabel: String): Boolean =
    MOCK_RULE_ACTION_TYPES.any { actionLabel.startsWith(it.label()) } ||
        (
            actionLabel.startsWith("Delay (") &&
                actionLabel.substringAfter(" → ", missingDelimiterValue = "").startsWith("Static response")
        )

private const val MOCK_RULE_MAX_THROTTLE_BYTES_PER_SECOND = 104_857_600L
internal const val MOCK_RULE_BYTES_PER_KB = 1_024L
internal const val MOCK_RULE_MIN_REMOTE_PORT = 1
internal const val MOCK_RULE_MAX_REMOTE_PORT = 65_535

/** Timeout is a required 0-30000 ms value; mirrors `MockAction.Timeout`'s constructor range. */
internal fun mockRuleTimeoutError(
    timeoutText: String,
    timeoutMs: Long?,
): String? =
    when {
        timeoutText.isBlank() || timeoutMs == null -> "Must be a number"
        timeoutMs !in 0..MOCK_RULE_MAX_DELAY_MS -> "Must be 0-$MOCK_RULE_MAX_DELAY_MS ms"
        else -> null
    }

/** Throttle rate is entered in KB/s and validated against `MockAction.Throttle`'s byte range. */
internal fun mockRuleThrottleRateError(
    kbpsText: String,
    bytesPerSecond: Long?,
): String? {
    val maxKbps = MOCK_RULE_MAX_THROTTLE_BYTES_PER_SECOND / MOCK_RULE_BYTES_PER_KB
    return when {
        kbpsText.isBlank() || bytesPerSecond == null -> "Must be a number"
        bytesPerSecond !in 1..MOCK_RULE_MAX_THROTTLE_BYTES_PER_SECOND -> "Must be 1-$maxKbps KB/s"
        else -> null
    }
}

/** Throttle latency is optional; a blank field means no added latency. */
internal fun mockRuleThrottleLatencyError(
    latencyText: String,
    latencyMs: Long?,
): String? =
    when {
        latencyText.isBlank() -> null
        latencyMs == null -> "Must be a number"
        latencyMs !in 0..MOCK_RULE_MAX_DELAY_MS -> "Must be 0-$MOCK_RULE_MAX_DELAY_MS ms"
        else -> null
    }

/** `MockAction.MapRemote` requires at least one replacement field. */
internal fun mockRuleMapRemoteError(hasAnyField: Boolean): String? =
    if (hasAnyField) null else "Set at least one of scheme, host, port or path"

/** Mirrors `MockAction.MapRemote`'s scheme constraint. */
internal fun mockRuleRemoteSchemeError(scheme: String): String? =
    when {
        scheme.isBlank() -> null
        scheme.lowercase(Locale.US) !in setOf("http", "https") -> "Must be http or https"
        else -> null
    }

/** Mirrors `MockAction.MapRemote`'s host constraint. */
internal fun mockRuleRemoteHostError(host: String): String? =
    if (host.isBlank() || '/' !in host) null else "Must not contain '/'"

/** Mirrors `MockAction.MapRemote`'s path constraint. */
internal fun mockRuleRemotePathError(path: String): String? =
    if (path.isBlank() || path.startsWith('/')) null else "Must start with '/'"

/** Port is optional; a blank field leaves the port unchanged. */
internal fun mockRuleRemotePortError(
    portText: String,
    port: Int?,
): String? =
    when {
        portText.isBlank() -> null
        port == null -> "Must be a number"
        port !in MOCK_RULE_MIN_REMOTE_PORT..MOCK_RULE_MAX_REMOTE_PORT ->
            "Must be $MOCK_RULE_MIN_REMOTE_PORT-$MOCK_RULE_MAX_REMOTE_PORT"
        else -> null
    }

/** [skippedLines] counts lines with no `key=value` shape -- shown as a non-blocking hint. */
internal data class ParsedMockRulePredicates(
    val predicates: Map<String, String>,
    val skippedLines: Int,
)

/** Parses the multiline `key=value` convention used for query and header predicates. */
internal fun parseMockRulePredicateLines(text: String): ParsedMockRulePredicates {
    var skipped = 0
    val predicates = LinkedHashMap<String, String>()
    text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
        val equalsIndex = line.indexOf('=')
        if (equalsIndex <= 0) {
            skipped++
        } else {
            predicates[line.substring(0, equalsIndex).trim()] = line.substring(equalsIndex + 1).trim()
        }
    }
    return ParsedMockRulePredicates(predicates, skipped)
}

internal fun Map<String, String>.toMockRulePredicateLines(): String = entries.joinToString("\n") { (k, v) -> "$k=$v" }

private const val MOCK_RULE_ID_MAX_LENGTH = 128

/**
 * A short, id-regex-safe suggestion built from a captured transaction's method/path; still
 * user-editable. [existingIds] (a rule set's current ids) is consulted so re-capturing the same
 * endpoint twice -- the normal "capture it, mock the second response" workflow, not an edge case --
 * suggests `mock-get-v1-cart-2` instead of the id of the rule Save would silently overwrite (review
 * concern ruling 4). The numeric-suffix result is still capped to the id field's real 128-char limit.
 */
internal fun suggestMockRuleId(
    method: String,
    path: String,
    existingIds: Set<String> = emptySet(),
): String {
    val slug =
        path
            .substringBefore('?')
            .lowercase(Locale.US)
            .map { c -> if (c.isLetterOrDigit()) c else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-{2,}"), "-")
            .ifBlank { "root" }
    val base = "mock-${method.lowercase(Locale.US)}-$slug".take(MOCK_RULE_ID_SUGGESTION_MAX_LENGTH)
    if (base !in existingIds) return base
    var suffix = 2
    while ("$base-$suffix" in existingIds) suffix++
    return "$base-$suffix".take(MOCK_RULE_ID_MAX_LENGTH)
}
