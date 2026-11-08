/**
 * @author Zakaria
 * @since 26/10/26
 */
package io.devconsole.locationping

internal sealed interface PingJson {
    data class Obj(
        val fields: Map<String, PingJson>,
    ) : PingJson {
        operator fun get(key: String): PingJson? = fields[key]
    }

    data class Arr(
        val items: List<PingJson>,
    ) : PingJson

    data class Str(
        val value: String,
    ) : PingJson

    data class Num(
        val raw: String,
    ) : PingJson

    data class Bool(
        val value: Boolean,
    ) : PingJson

    data object Null : PingJson

    companion object {
        fun parseOrNull(text: String): PingJson? = runCatching { PingJsonReader(text).readDocument() }.getOrNull()
    }
}

internal fun PingJson.toCompactJson(): String = StringBuilder().also { appendCompactJson(it) }.toString()

internal fun PingJson.asDouble(): Double? =
    when (this) {
        is PingJson.Num -> raw.toDoubleOrNull()
        is PingJson.Str -> value.trim().toDoubleOrNull()
        else -> null
    }

internal fun PingJson.asReadableText(): String =
    when (this) {
        is PingJson.Str -> value
        is PingJson.Num -> raw
        is PingJson.Bool -> value.toString()
        PingJson.Null -> ""
        is PingJson.Arr -> items.joinToString(", ") { it.asReadableText() }
        is PingJson.Obj -> toCompactJson()
    }

private fun PingJson.appendCompactJson(out: StringBuilder) {
    when (this) {
        is PingJson.Obj -> {
            out.append('{')
            fields.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) out.append(',')
                appendQuoted(key, out)
                out.append(':')
                value.appendCompactJson(out)
            }
            out.append('}')
        }
        is PingJson.Arr -> {
            out.append('[')
            items.forEachIndexed { index, item ->
                if (index > 0) out.append(',')
                item.appendCompactJson(out)
            }
            out.append(']')
        }
        is PingJson.Str -> appendQuoted(value, out)
        is PingJson.Num -> out.append(raw)
        is PingJson.Bool -> out.append(value)
        PingJson.Null -> out.append("null")
    }
}

private fun appendQuoted(
    text: String,
    out: StringBuilder,
) {
    out.append('"')
    text.forEach { char ->
        when (char) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            else ->
                if (char < ' ') {
                    out.append("\\u").append(char.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_DIGITS, '0'))
                } else {
                    out.append(char)
                }
        }
    }
    out.append('"')
}

private class PingJsonReader(
    private val text: String,
) {
    private var position = 0
    private var depth = 0

    fun readDocument(): PingJson {
        val value = readValue()
        skipWhitespace()
        check(position == text.length) { "Trailing characters at $position" }
        return value
    }

    private fun readValue(): PingJson {
        skipWhitespace()
        check(position < text.length) { "Unexpected end of JSON" }
        return when (val char = text[position]) {
            '{', '[' -> {
                depth += 1
                check(depth <= MAX_DEPTH) { "JSON nests deeper than $MAX_DEPTH levels" }
                val container = if (char == '{') readObject() else readArray()
                depth -= 1
                container
            }
            '"' -> PingJson.Str(readString())
            't', 'f', 'n' -> {
                val (keyword, value) = KEYWORDS.getValue(char)
                check(text.startsWith(keyword, position)) { "Expected $keyword at $position" }
                position += keyword.length
                value
            }
            else -> if (char == '-' || char.isDigit()) readNumber() else error("Unexpected '$char' at $position")
        }
    }

    private fun readObject(): PingJson {
        position += 1
        val fields = LinkedHashMap<String, PingJson>()
        skipWhitespace()
        if (peek() == '}') {
            position += 1
            return PingJson.Obj(fields)
        }
        while (true) {
            skipWhitespace()
            val key = readString()
            skipWhitespace()
            expect(':')
            fields[key] = readValue()
            skipWhitespace()
            if (peek() == ',') {
                position += 1
            } else {
                expect('}')
                return PingJson.Obj(fields)
            }
        }
    }

    private fun readArray(): PingJson {
        position += 1
        val items = ArrayList<PingJson>()
        skipWhitespace()
        if (peek() == ']') {
            position += 1
            return PingJson.Arr(items)
        }
        while (true) {
            items += readValue()
            skipWhitespace()
            if (peek() == ',') {
                position += 1
            } else {
                expect(']')
                return PingJson.Arr(items)
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            check(position < text.length) { "Unterminated string" }
            when (val char = text[position++]) {
                '"' -> return out.toString()
                '\\' -> out.append(readEscape())
                else -> out.append(char)
            }
        }
    }

    private fun readEscape(): Char {
        check(position < text.length) { "Unterminated escape" }
        return when (val escaped = text[position++]) {
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'b' -> '\b'
            'f' -> '\u000C'
            'u' -> {
                check(position + UNICODE_ESCAPE_DIGITS <= text.length) { "Short unicode escape" }
                val code = text.substring(position, position + UNICODE_ESCAPE_DIGITS).toInt(HEX_RADIX)
                position += UNICODE_ESCAPE_DIGITS
                code.toChar()
            }
            else -> escaped
        }
    }

    private fun readNumber(): PingJson {
        val start = position
        while (position < text.length && text[position] in NUMBER_CHARS) position += 1
        val raw = text.substring(start, position)
        checkNotNull(raw.toDoubleOrNull()) { "Malformed number '$raw'" }
        return PingJson.Num(raw)
    }

    private fun expect(char: Char) {
        check(peek() == char) { "Expected '$char' at $position" }
        position += 1
    }

    private fun peek(): Char? = text.getOrNull(position)

    private fun skipWhitespace() {
        while (position < text.length && text[position].isWhitespace()) position += 1
    }
}

private const val MAX_DEPTH = 64
private val KEYWORDS =
    mapOf(
        't' to ("true" to PingJson.Bool(true)),
        'f' to ("false" to PingJson.Bool(false)),
        'n' to ("null" to PingJson.Null),
    )
private const val HEX_RADIX = 16
private const val UNICODE_ESCAPE_DIGITS = 4
private val NUMBER_CHARS = "0123456789+-.eE".toSet()
