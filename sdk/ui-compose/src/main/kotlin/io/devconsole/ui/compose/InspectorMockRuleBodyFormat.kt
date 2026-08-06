/**
 * @author Shakib
 * @since 05/08/26
 *
 * Only one top-level declaration remains after the shared JsonValue/MinimalJsonParser model moved to
 * InspectorJsonModel.kt (see that file), so the filename-must-match-declaration rule would otherwise
 * want this renamed to JsonFormatResult.kt -- kept as-is since formatMockRuleBodyJson is the file's
 * real subject; other files in this module (InspectorCodeBlock.kt etc.) suppress the same way for
 * detekt. ktlint has its own, separately-named version of this rule (`filename`), which needs its
 * own suppression to agree: this file groups the mock-rule body's JSON format helpers, and that
 * name reflects its purpose better than the single result type it happens to declare.
 */
@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package io.devconsole.ui.compose

/** Result of the create/edit sheet's non-blocking body Format action. */
internal sealed interface JsonFormatResult {
    data class Formatted(
        val text: String,
    ) : JsonFormatResult

    data class Error(
        val message: String,
    ) : JsonFormatResult
}

/**
 * Parses [input] as JSON and re-serializes it with 2-space indentation, via the shared
 * [MinimalJsonParser]/[JsonValue] model (see that file's own doc for why this module hand-rolls a
 * parser instead of using `org.json`/`kotlinx.serialization`). A non-JSON body (plain text, HTML, a
 * template placeholder) is a normal mock response, not a bug -- the caller renders
 * [JsonFormatResult.Error] as a non-blocking inline hint, never as a reason to refuse Save.
 */
internal fun formatMockRuleBodyJson(input: String): JsonFormatResult =
    try {
        val value = MinimalJsonParser(input).parseDocument()
        JsonFormatResult.Formatted(value.prettyPrint())
    } catch (error: JsonSyntaxException) {
        JsonFormatResult.Error(error.message ?: "Invalid JSON")
    }
