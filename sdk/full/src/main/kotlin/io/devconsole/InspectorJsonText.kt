/**
 * @author Shakib
 * @since 25/07/26
 */
package io.devconsole

private const val UNICODE_HEX_DIGITS = 4
private const val UNICODE_ESCAPE_LENGTH = 6
private const val HEX_RADIX = 16

/** Extracts one string value from the small, flat tag/payload JSON the capture engines emit. */
internal fun String.jsonStringField(key: String): String? {
    val match = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(this) ?: return null
    return match.groupValues[1].unescapeJsonString()
}

/**
 * Single left-to-right pass, so an escaped backslash (`\\`) is consumed as one unit and a following
 * `n`/`r`/`t`/`"` is never misread as part of an escape — the bug a chain of `replace` calls has.
 */
private fun String.unescapeJsonString(): String {
    val out = StringBuilder(length)
    var index = 0
    while (index < length) {
        val current = this[index]
        if (current != '\\' || index + 1 >= length) {
            out.append(current)
            index += 1
            continue
        }
        val next = this[index + 1]
        val simple = simpleEscape(next)
        index =
            when {
                simple != null -> {
                    out.append(simple)
                    index + 2
                }
                next == 'u' -> appendUnicodeEscape(out, index)
                else -> {
                    out.append(current)
                    index + 1
                }
            }
    }
    return out.toString()
}

/** Maps a single-character JSON escape to its literal, or null for `\\uXXXX` / unknown escapes. */
private fun simpleEscape(escaped: Char): Char? =
    when (escaped) {
        '"', '\\', '/' -> escaped
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'b' -> '\b'
        'f' -> '\u000C'
        else -> null
    }

/** Appends the decoded `\\uXXXX` at [backslashIndex] (or the raw backslash if malformed); returns the next index. */
private fun String.appendUnicodeEscape(
    out: StringBuilder,
    backslashIndex: Int,
): Int {
    val hexStart = backslashIndex + 2
    val hexEnd = hexStart + UNICODE_HEX_DIGITS
    val code = if (hexEnd <= length) substring(hexStart, hexEnd).toIntOrNull(HEX_RADIX) else null
    return if (code != null) {
        out.append(code.toChar())
        backslashIndex + UNICODE_ESCAPE_LENGTH
    } else {
        out.append('\\')
        backslashIndex + 1
    }
}
