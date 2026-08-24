/**
 * @author Shakib
 * @since 23/08/26
 *
 * Syntax colouring for *editable* JSON. The viewer side ([InspectorFormattableBody]) colours parsed
 * rows via [MinimalJsonParser], which is no use in a text field: half-typed JSON doesn't parse, and
 * a field that loses its colours on every keystroke is worse than one with none. So this is a
 * forgiving lexer over raw text -- unterminated strings, trailing commas and garbage all just stop
 * a span instead of failing -- reusing the same [DevConsoleColors] JSON palette as the viewer.
 */
@file:Suppress("MagicNumber")

package io.devconsole.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

internal enum class JsonTokenType { KEY, STRING, NUMBER, BOOLEAN, NULL, BRACE, PUNCT }

/** [start], [end] are indices into the lexed text; [depth] is the nesting rung, only set for [JsonTokenType.BRACE]. */
internal data class JsonTokenSpan(
    val start: Int,
    val end: Int,
    val type: JsonTokenType,
    val depth: Int = 0,
)

/** Tokenizes [text] far enough to colour it -- never validates, never throws. */
internal fun jsonHighlightSpans(text: String): List<JsonTokenSpan> {
    val spans = mutableListOf<JsonTokenSpan>()
    var depth = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val span =
            when {
                c == '"' -> {
                    val end = jsonStringEnd(text, i)
                    // A string is a key only when a ':' follows it -- the one bit of context this lexer keeps.
                    val type = if (nextNonSpace(text, end) == ':') JsonTokenType.KEY else JsonTokenType.STRING
                    JsonTokenSpan(i, end, type)
                }
                c == '{' || c == '[' -> JsonTokenSpan(i, i + 1, JsonTokenType.BRACE, depth++)
                // Unbalanced closers (mid-edit) clamp at 0 rather than colouring off the end of the palette.
                c == '}' || c == ']' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    JsonTokenSpan(i, i + 1, JsonTokenType.BRACE, depth)
                }
                c == ':' || c == ',' -> JsonTokenSpan(i, i + 1, JsonTokenType.PUNCT)
                else -> jsonScalarSpan(text, i)
            }
        // No span means whitespace or garbage: skip the char, keep colouring what follows.
        i = span?.end ?: (i + 1)
        span?.let(spans::add)
    }
    return spans
}

/** Number/true/false/null starting exactly at [start], or null when nothing literal begins there. */
private fun jsonScalarSpan(
    text: String,
    start: Int,
): JsonTokenSpan? =
    when {
        text[start] == '-' || text[start].isDigit() -> {
            var end = start + 1
            while (end < text.length && (text[end].isDigit() || text[end] in ".eE+-")) end++
            JsonTokenSpan(start, end, JsonTokenType.NUMBER)
        }
        text.startsWith("true", start) -> JsonTokenSpan(start, start + 4, JsonTokenType.BOOLEAN)
        text.startsWith("false", start) -> JsonTokenSpan(start, start + 5, JsonTokenType.BOOLEAN)
        text.startsWith("null", start) -> JsonTokenSpan(start, start + 4, JsonTokenType.NULL)
        else -> null
    }

/** Index just past the closing quote of the string opening at [start]; end of text when unterminated. */
private fun jsonStringEnd(
    text: String,
    start: Int,
): Int {
    var i = start + 1
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i++
            '"' -> return i + 1
        }
        i++
    }
    return text.length
}

private fun nextNonSpace(
    text: String,
    from: Int,
): Char? {
    var i = from
    while (i < text.length && text[i].isWhitespace()) i++
    return text.getOrNull(i)
}

private fun JsonTokenSpan.color(colors: DevConsoleColors): Color =
    when (type) {
        JsonTokenType.KEY -> colors.jsonKey
        JsonTokenType.STRING -> colors.jsonString
        JsonTokenType.NUMBER -> colors.jsonNumber
        JsonTokenType.BOOLEAN -> colors.jsonBoolean
        JsonTokenType.NULL -> colors.jsonNull
        // Same rainbow-brace cycling as the viewer, so a body reads the same coloured in both places.
        JsonTokenType.BRACE -> colors.jsonBraces[depth % colors.jsonBraces.size]
        JsonTokenType.PUNCT -> colors.text3
    }

/**
 * Every occurrence of [query] in [text], ignoring case -- keys, values and anything else, because a
 * body is as often searched for the value you are about to change as for the key holding it, and a
 * plain find keeps working on the non-JSON bodies (HTML, templates) this field also accepts. Blank
 * query matches nothing rather than everything; overlapping hits count once (the caller highlights
 * ranges, so "aa" in "aaa" is one hit, not two).
 */
internal fun bodySearchMatches(
    text: String,
    query: String,
): List<IntRange> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    val matches = mutableListOf<IntRange>()
    var from = text.indexOf(needle, ignoreCase = true)
    while (from >= 0) {
        matches += from until from + needle.length
        from = text.indexOf(needle, from + needle.length, ignoreCase = true)
    }
    return matches
}

/**
 * Applies [jsonHighlightSpans] to a text field's contents, plus a search wash over every
 * [bodySearchMatches] hit for [query]; offsets are untouched, so caret/selection stay put.
 */
internal fun AnnotatedString.jsonHighlighted(
    colors: DevConsoleColors,
    query: String = "",
    activeMatch: IntRange? = null,
): AnnotatedString =
    buildAnnotatedString {
        append(this@jsonHighlighted)
        val raw = this@jsonHighlighted.text
        jsonHighlightSpans(raw).forEach { span ->
            addStyle(SpanStyle(color = span.color(colors)), span.start, span.end)
        }
        // Same two-tone wash the read-only viewer paints behind search hits (inspectorHighlightedText):
        // solid signal on the one the arrows are parked on, soft on the rest.
        bodySearchMatches(raw, query).forEach { match ->
            val background = if (match == activeMatch) colors.signal else colors.signalSoft
            addStyle(SpanStyle(background = background), match.first, match.last + 1)
        }
    }

/**
 * JSON colouring for a `BasicTextField`, off past [MAX_AUTO_FORMAT_BODY_CHARS] (see that constant
 * for why). Plain text is the graceful degradation: the field still edits and saves, it just stops
 * paying to re-colour a body that big.
 *
 * ponytail: re-lexes the whole body on every keystroke -- fine under the cap (~10ms for 32KB);
 * lex only the edited line's neighbourhood if the cap ever needs raising.
 */
@Composable
internal fun rememberJsonSyntaxTransformation(
    colors: DevConsoleColors,
    query: String = "",
    activeMatch: IntRange? = null,
): VisualTransformation =
    remember(colors, query, activeMatch) {
        VisualTransformation { text ->
            if (text.length > MAX_AUTO_FORMAT_BODY_CHARS) {
                TransformedText(text, OffsetMapping.Identity)
            } else {
                TransformedText(text.jsonHighlighted(colors, query, activeMatch), OffsetMapping.Identity)
            }
        }
    }
