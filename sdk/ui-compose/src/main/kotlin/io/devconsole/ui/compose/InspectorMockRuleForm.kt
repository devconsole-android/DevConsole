/**
 * @author Shakib
 * @since 05/08/26
 */
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
    if (id.matches(MOCK_RULE_ID_REGEX)) null else "Letters, digits, . _ - only -- can't start with . _ or -"

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

/**
 * Whether a rule's action round-trips through the create/edit sheet without data loss. Only
 * `StaticResponse` and `Delay(StaticResponse)` do -- `upsertMockRule` always builds a plain
 * `StaticResponse` (`FullInspectorDataSource.kt:299-303`), so opening the editor on anything else
 * (`TemplateResponse`/`ConnectionFailure`/`Timeout`/`StatusOverride`/`BodyReplacement`/`Passthrough`)
 * and pressing Save would silently replace a fault-injection or template rule with an empty 200.
 * Discriminated via [InspectorMockRuleUi.actionLabel] since the UI model carries no richer
 * action-kind field; the prefixes are `MockAction.toLabel()`'s exact literals
 * (`FullInspectorDataSource.kt:701-708`) -- "Static response (…)" / "Delay (…ms)".
 */
internal fun InspectorMockRuleUi.isEditableOnDevice(): Boolean =
    actionLabel.startsWith("Static response") || actionLabel.startsWith("Delay")

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
