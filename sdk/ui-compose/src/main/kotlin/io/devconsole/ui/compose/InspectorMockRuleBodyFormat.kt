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
        val offset: Int = 0,
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
        JsonFormatResult.Error(error.message ?: "Invalid JSON", error.offset)
    }

/**
 * Ceiling for the *automatic* niceties -- opening pretty-printed and syntax colouring. Both are
 * per-keystroke work in a `BasicTextField`, which lays out its whole string in one un-virtualized
 * text node: a 512KB capture preview (the default `responseBodyPreviewBytes`) pretty-prints to well
 * over a megabyte and tens of thousands of spans, which janks the field for a body nobody hand-edits
 * anyway. Above this the body opens exactly as captured, uncoloured. The FORMAT button is
 * deliberately *not* capped -- that one the user asked for.
 */
internal const val MAX_AUTO_FORMAT_BODY_CHARS = 32 * 1024

/**
 * [formatMockRuleBodyJson] for display: pretty JSON when it parses, the input untouched when it
 * doesn't -- or when it is past [MAX_AUTO_FORMAT_BODY_CHARS].
 */
internal fun prettyOrRaw(input: String): String =
    when {
        input.length > MAX_AUTO_FORMAT_BODY_CHARS -> input
        else ->
            when (val result = formatMockRuleBodyJson(input)) {
                is JsonFormatResult.Formatted -> result.text
                is JsonFormatResult.Error -> input
            }
    }
