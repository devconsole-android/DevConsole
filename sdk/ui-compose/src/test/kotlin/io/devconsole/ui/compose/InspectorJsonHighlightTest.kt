package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class InspectorJsonHighlightTest {
    private fun types(text: String) = jsonHighlightSpans(text).map { text.substring(it.start, it.end) to it.type }

    @Test
    fun `keys strings numbers booleans and nulls are told apart`() {
        assertEquals(
            listOf(
                "{" to JsonTokenType.BRACE,
                "\"a\"" to JsonTokenType.KEY,
                ":" to JsonTokenType.PUNCT,
                "\"a\"" to JsonTokenType.STRING,
                "," to JsonTokenType.PUNCT,
                "\"n\"" to JsonTokenType.KEY,
                ":" to JsonTokenType.PUNCT,
                "-1.5e3" to JsonTokenType.NUMBER,
                "," to JsonTokenType.PUNCT,
                "\"b\"" to JsonTokenType.KEY,
                ":" to JsonTokenType.PUNCT,
                "true" to JsonTokenType.BOOLEAN,
                "," to JsonTokenType.PUNCT,
                "\"z\"" to JsonTokenType.KEY,
                ":" to JsonTokenType.PUNCT,
                "null" to JsonTokenType.NULL,
                "}" to JsonTokenType.BRACE,
            ),
            types("""{"a": "a", "n": -1.5e3, "b": true, "z": null}"""),
        )
    }

    @Test
    fun `braces carry their nesting depth`() {
        val depths =
            jsonHighlightSpans("""{"a":[{"b":1}]}""")
                .filter { it.type == JsonTokenType.BRACE }
                .map { it.depth }
        assertEquals(listOf(0, 1, 2, 2, 1, 0), depths)
    }

    @Test
    fun `half-typed json still lexes instead of throwing`() {
        // Unterminated string swallows the rest; nothing after it is mis-coloured as structure.
        assertEquals(listOf("{" to JsonTokenType.BRACE, "\"ab: 1" to JsonTokenType.STRING), types("""{"ab: 1"""))
        assertEquals(emptyList<Pair<String, JsonTokenType>>(), types(""))
    }

    @Test
    fun `structural characters inside strings are not tokenized`() {
        val escaped = "\"{\\\"a\\\": 1}\""
        assertEquals(listOf(escaped to JsonTokenType.STRING), types(escaped))
    }

    @Test
    fun `body search finds keys and values alike, ignoring case`() {
        val json = """{"userId":1,"note":"the ID lives here"}"""

        val hits = bodySearchMatches(json, "id").map { json.substring(it) }

        // Two in the key "userId"/"Id" casing and one inside the value -- values are searchable too.
        assertEquals(listOf("Id", "ID"), hits)
    }

    @Test
    fun `overlapping hits are counted once and blank queries match nothing`() {
        assertEquals(1, bodySearchMatches("aaa", "aa").size)
        assertEquals(emptyList<IntRange>(), bodySearchMatches("""{"a":1}""", ""))
        assertEquals(emptyList<IntRange>(), bodySearchMatches("""{"a":1}""", "   "))
    }
}
