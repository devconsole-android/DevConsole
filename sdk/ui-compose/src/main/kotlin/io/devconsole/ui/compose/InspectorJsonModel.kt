/**
 * @author Shakib
 * @since 05/08/26
 */
package io.devconsole.ui.compose

private const val UNICODE_HEX_DIGITS = 4
private const val HEX_RADIX = 16

/**
 * Upper bound on `{`/`[` nesting [MinimalJsonParser] will descend into before failing cleanly with
 * [JsonSyntaxException]. Recursive descent means each nesting level costs a JVM stack frame; without
 * a cap, a ~300KB body of `[[[[...` overflows the stack at roughly 5,000 levels deep, and the only
 * reason the inspector survives that today is that [analyzeBodyFormat]'s `runCatching` happens to
 * catch `Throwable` (so it also catches `StackOverflowError`) -- an accident of a broad catch clause,
 * not a real safeguard: a `StackOverflowError` can leave the thread in a partially-unwound state
 * that varies by JVM/Android runtime. 256 comfortably covers any real API payload's nesting while
 * failing at a small, predictable stack depth well before the actual limit.
 */
internal const val MAX_NESTING_DEPTH = 256

/**
 * A parsed JSON value, order-preserving for objects (`entries` is a `List`, not a `Map`, so a
 * captured payload round-trips key order exactly). Shared by [formatMockRuleBodyJson] (pretty-print
 * only) and the capture-detail body formatter's collapsible tree ([flattenJsonTree]) -- both need to
 * parse JSON on the plain JVM unit-test runtime this module targets (see [MinimalJsonParser]'s own
 * doc for why that rules out `org.json`/`kotlinx.serialization`), so there is exactly one parser.
 */
internal sealed interface JsonValue {
    data class Obj(
        val entries: List<Pair<String, JsonValue>>,
    ) : JsonValue

    data class Arr(
        val items: List<JsonValue>,
    ) : JsonValue

    data class Str(
        val value: String,
    ) : JsonValue

    data class Num(
        val raw: String,
    ) : JsonValue

    data class Bool(
        val value: Boolean,
    ) : JsonValue

    data object Null : JsonValue
}

internal class JsonSyntaxException(
    message: String,
) : Exception(message)

/** Pretty-prints with [indentUnit]-per-level indentation (2-space by default, matching every caller). */
internal fun JsonValue.prettyPrint(
    indentUnit: String = "  ",
    level: Int = 0,
): String {
    val indent = indentUnit.repeat(level)
    val childIndent = indentUnit.repeat(level + 1)
    return when (this) {
        is JsonValue.Obj ->
            if (entries.isEmpty()) {
                "{}"
            } else {
                entries.joinToString(",\n", prefix = "{\n", postfix = "\n$indent}") { (key, value) ->
                    "$childIndent${key.jsonQuoted()}: ${value.prettyPrint(indentUnit, level + 1)}"
                }
            }
        is JsonValue.Arr ->
            if (items.isEmpty()) {
                "[]"
            } else {
                items.joinToString(",\n", prefix = "[\n", postfix = "\n$indent]") { item ->
                    "$childIndent${item.prettyPrint(indentUnit, level + 1)}"
                }
            }
        is JsonValue.Str -> value.jsonQuoted()
        is JsonValue.Num -> raw
        is JsonValue.Bool -> value.toString()
        JsonValue.Null -> "null"
    }
}

/** Number of direct children -- object keys or array items -- for a collapsed container's "N keys"/"N items" label. */
internal fun JsonValue.childCount(): Int =
    when (this) {
        is JsonValue.Obj -> entries.size
        is JsonValue.Arr -> items.size
        else -> 0
    }

/**
 * A minimal, strict JSON parser: no trailing commas, no comments, no unquoted keys -- valid JSON
 * only. Deliberately hand-rolled rather than `org.json` or `kotlinx.serialization`: this module has
 * no dependency on either, and its unit tests run on the plain JVM (no Robolectric, unlike
 * sdk/full), so an `org.json` call would hit Android's "not mocked" stub at test time.
 */
@Suppress("TooManyFunctions") // One small method per grammar production; splitting further hides the grammar.
internal class MinimalJsonParser(
    private val text: String,
) {
    private var pos = 0
    private var depth = 0

    fun parseDocument(): JsonValue {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        if (pos != text.length) fail("Unexpected trailing content at position $pos")
        return value
    }

    private fun fail(message: String): Nothing = throw JsonSyntaxException(message)

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun peekIs(char: Char): Boolean = pos < text.length && text[pos] == char

    private fun parseValue(): JsonValue {
        skipWhitespace()
        if (pos >= text.length) fail("Unexpected end of input")
        return when (text[pos]) {
            '{' -> parseNested(::parseObject)
            '[' -> parseNested(::parseArray)
            '"' -> JsonValue.Str(parseStringLiteral())
            't' -> parseKeyword("true", JsonValue.Bool(true))
            'f' -> parseKeyword("false", JsonValue.Bool(false))
            'n' -> parseKeyword("null", JsonValue.Null)
            else -> parseNumber()
        }
    }

    /** Tracks `{`/`[` recursion depth around [parse] and fails cleanly past [MAX_NESTING_DEPTH] -- see its own doc. */
    private fun <T : JsonValue> parseNested(parse: () -> T): T {
        depth++
        if (depth > MAX_NESTING_DEPTH) {
            fail("Exceeded maximum nesting depth of $MAX_NESTING_DEPTH at position $pos")
        }
        try {
            return parse()
        } finally {
            depth--
        }
    }

    private fun parseKeyword(
        keyword: String,
        value: JsonValue,
    ): JsonValue {
        if (pos + keyword.length > text.length || text.substring(pos, pos + keyword.length) != keyword) {
            fail("Invalid token at position $pos")
        }
        pos += keyword.length
        return value
    }

    private fun parseObject(): JsonValue.Obj {
        pos++ // consume '{'
        val entries = mutableListOf<Pair<String, JsonValue>>()
        skipWhitespace()
        if (peekIs('}')) {
            pos++
            return JsonValue.Obj(entries)
        }
        while (true) {
            skipWhitespace()
            if (!peekIs('"')) fail("Expected a string key at position $pos")
            val key = parseStringLiteral()
            skipWhitespace()
            if (!peekIs(':')) fail("Expected ':' at position $pos")
            pos++
            entries += key to parseValue()
            skipWhitespace()
            if (consumeCommaOrClose('}')) break
        }
        return JsonValue.Obj(entries)
    }

    private fun parseArray(): JsonValue.Arr {
        pos++ // consume '['
        val items = mutableListOf<JsonValue>()
        skipWhitespace()
        if (peekIs(']')) {
            pos++
            return JsonValue.Arr(items)
        }
        while (true) {
            items += parseValue()
            skipWhitespace()
            if (consumeCommaOrClose(']')) break
        }
        return JsonValue.Arr(items)
    }

    /** Consumes a trailing `,` (continue) or [closer] (stop); returns true once [closer] is consumed. */
    private fun consumeCommaOrClose(closer: Char): Boolean =
        when {
            peekIs(',') -> {
                pos++
                false
            }
            peekIs(closer) -> {
                pos++
                true
            }
            else -> fail("Expected ',' or '$closer' at position $pos")
        }

    private fun parseStringLiteral(): String {
        pos++ // consume opening quote
        val builder = StringBuilder()
        while (true) {
            if (pos >= text.length) fail("Unterminated string")
            when (val c = text[pos]) {
                '"' -> {
                    pos++
                    return builder.toString()
                }
                '\\' -> {
                    pos++
                    builder.append(parseEscape())
                }
                else -> {
                    builder.append(c)
                    pos++
                }
            }
        }
    }

    private fun parseEscape(): Char {
        if (pos >= text.length) fail("Unterminated escape")
        val c = text[pos]
        pos++
        return when (c) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseUnicodeEscape()
            else -> fail("Invalid escape \\$c")
        }
    }

    private fun parseUnicodeEscape(): Char {
        if (pos + UNICODE_HEX_DIGITS > text.length) fail("Invalid unicode escape")
        val hex = text.substring(pos, pos + UNICODE_HEX_DIGITS)
        pos += UNICODE_HEX_DIGITS
        return hex.toIntOrNull(HEX_RADIX)?.toChar() ?: fail("Invalid unicode escape \\u$hex")
    }

    private fun parseNumber(): JsonValue.Num {
        val start = pos
        if (peekIs('-')) pos++
        if (pos >= text.length || !text[pos].isDigit()) fail("Invalid number at position $start")
        consumeDigits()
        if (peekIs('.')) {
            pos++
            if (pos >= text.length || !text[pos].isDigit()) fail("Invalid number at position $start")
            consumeDigits()
        }
        if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
            pos++
            if (peekIs('+') || peekIs('-')) pos++
            if (pos >= text.length || !text[pos].isDigit()) fail("Invalid number exponent at position $start")
            consumeDigits()
        }
        return JsonValue.Num(text.substring(start, pos))
    }

    private fun consumeDigits() {
        while (pos < text.length && text[pos].isDigit()) pos++
    }
}
