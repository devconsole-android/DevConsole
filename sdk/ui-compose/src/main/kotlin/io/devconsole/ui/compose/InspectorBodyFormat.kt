/**
 * @author Shakib
 * @since 05/08/26
 */
package io.devconsole.ui.compose

/**
 * Perf guard for the capture-detail body formatter: a body over this size skips JSON/XML
 * formatting entirely rather than parsing (and, for JSON, flattening into a tree) megabytes of
 * text just to render it -- the raw view is always available as a fallback either way. Measured in
 * real UTF-8 bytes, not `String.length`, since a redacted/multi-byte-heavy body's char count and
 * byte count can diverge.
 */
internal const val MAX_FORMATTABLE_BODY_BYTES = 300_000

/** What [sniffBodyFormat] saw at the first non-whitespace character -- a guess only, not a parse result. */
internal enum class SniffedBodyFormat { JSON, XML, PLAIN }

/** Sniffs [text]'s likely format from its first non-whitespace character only -- `{`/`[` for JSON, `<` for XML. */
internal fun sniffBodyFormat(text: String): SniffedBodyFormat {
    val firstNonWhitespace = text.firstOrNull { !it.isWhitespace() } ?: return SniffedBodyFormat.PLAIN
    return when (firstNonWhitespace) {
        '{', '[' -> SniffedBodyFormat.JSON
        '<' -> SniffedBodyFormat.XML
        else -> SniffedBodyFormat.PLAIN
    }
}

/**
 * A successfully formatted body, ready to render -- JSON keeps its parsed tree (for the collapsible
 * view), XML is flat pretty text.
 */
internal sealed interface FormattedBody {
    data class Json(
        val root: JsonValue,
    ) : FormattedBody

    data class Xml(
        val text: String,
    ) : FormattedBody
}

/** Result of attempting to format a captured body for display; see [analyzeBodyFormat]. */
internal sealed interface BodyFormatOutcome {
    data class Formatted(
        val body: FormattedBody,
    ) : BodyFormatOutcome

    /** Sniffed as JSON/XML but over [MAX_FORMATTABLE_BODY_BYTES] -- caller shows a skip notice + raw text. */
    data object TooLarge : BodyFormatOutcome

    /** Plain text, or sniffed as JSON/XML but failed to actually parse (malformed) -- raw view only, no notice. */
    data object NotFormattable : BodyFormatOutcome
}

/**
 * Attempts to format [rawText] as pretty-printed, collapsible JSON or pretty-printed XML for the
 * capture-detail body viewer. Never throws: a sniff that turns out wrong (content starts with `{`
 * but isn't valid JSON, "XML" that isn't well-formed) degrades to [BodyFormatOutcome.NotFormattable]
 * rather than surfacing a parse error -- the raw view is always correct, formatting is a bonus.
 */
@Suppress("ReturnCount") // Plain-text/too-large/parse-failure are each an honest early exit, not a branch to merge.
internal fun analyzeBodyFormat(rawText: String): BodyFormatOutcome {
    val sniffed = sniffBodyFormat(rawText)
    if (sniffed == SniffedBodyFormat.PLAIN) return BodyFormatOutcome.NotFormattable
    if (rawText.toByteArray(Charsets.UTF_8).size > MAX_FORMATTABLE_BODY_BYTES) return BodyFormatOutcome.TooLarge
    return when (sniffed) {
        SniffedBodyFormat.JSON ->
            runCatching { MinimalJsonParser(rawText).parseDocument() }
                .getOrNull()
                ?.let { BodyFormatOutcome.Formatted(FormattedBody.Json(it)) }
                ?: BodyFormatOutcome.NotFormattable
        SniffedBodyFormat.XML ->
            formatXml(rawText)
                ?.let { BodyFormatOutcome.Formatted(FormattedBody.Xml(it)) }
                ?: BodyFormatOutcome.NotFormattable
        SniffedBodyFormat.PLAIN -> BodyFormatOutcome.NotFormattable
    }
}

// Comments/CDATA are matched whole first (DOTALL-ish via [\s\S]) so an embedded '>' inside either
// doesn't truncate the token early; everything else falls through to the generic tag pattern.
private val XmlTokenRegex = Regex("<!--[\\s\\S]*?-->|<!\\[CDATA\\[[\\s\\S]*?]]>|<[^>]+>")
private const val XML_INDENT_UNIT = "  "

/**
 * Pretty-prints [rawText] as XML by re-indenting per nesting depth -- a lightweight tag-stack walk,
 * not a validating parser. Returns null (raw fallback) for anything that doesn't look well-formed:
 * a closing tag that doesn't match the innermost open tag, or any tag left unclosed at the end --
 * both cheap enough to check while walking and better than emitting confidently-wrong indentation
 * for HTML-ish markup (unclosed `<br>`, `<img>`, ...) that was never actually XML.
 */
@Suppress("CyclomaticComplexMethod", "ReturnCount") // One tag-kind branch per case + an early-out per malformed case.
internal fun formatXml(rawText: String): String? {
    val trimmed = rawText.trim()
    if (!trimmed.startsWith("<")) return null
    val builder = StringBuilder()
    val openTags = ArrayDeque<String>()
    var depth = 0
    var cursor = 0

    fun appendLine(
        text: String,
        level: Int,
    ) {
        if (builder.isNotEmpty()) builder.append('\n')
        builder.append(XML_INDENT_UNIT.repeat(level.coerceAtLeast(0))).append(text)
    }

    for (match in XmlTokenRegex.findAll(trimmed)) {
        val between = trimmed.substring(cursor, match.range.first).trim()
        if (between.isNotEmpty()) appendLine(between, depth)
        val tag = match.value
        cursor = match.range.last + 1
        when {
            tag.startsWith("<!") || tag.startsWith("<?") -> appendLine(tag, depth) // comment/CDATA/doctype/decl
            tag.startsWith("</") -> {
                val name =
                    tag
                        .removePrefix("</")
                        .removeSuffix(">")
                        .trim()
                        .tagName()
                if (openTags.isEmpty() || openTags.last() != name) return null
                openTags.removeLast()
                depth--
                appendLine(tag, depth)
            }
            tag.endsWith("/>") -> appendLine(tag, depth)
            else -> {
                appendLine(tag, depth)
                val name =
                    tag
                        .removePrefix("<")
                        .removeSuffix(">")
                        .trim()
                        .tagName()
                openTags.addLast(name)
                depth++
            }
        }
    }
    val trailing = trimmed.substring(cursor).trim()
    if (trailing.isNotEmpty()) appendLine(trailing, depth)
    if (openTags.isNotEmpty()) return null
    return builder.toString()
}

/** The element name portion of a tag's inner text, e.g. `foo:bar attr="1"` -> `foo:bar`. */
private fun String.tagName(): String = substringBefore(' ').substringBefore('\t').substringBefore('\n')

/** A JSON scalar's rendered kind, for the tree view's syntax coloring ([DevConsoleColors.jsonString] etc.). */
internal enum class JsonScalarType { STRING, NUMBER, BOOLEAN, NULL }

/** One flattened, visible row of a [JsonValue] tree -- see [flattenJsonTree]'s own doc. */
internal sealed interface JsonTreeRowContent {
    data class Scalar(
        val text: String,
        val type: JsonScalarType,
    ) : JsonTreeRowContent

    data class ContainerCollapsed(
        val isArray: Boolean,
        val childCount: Int,
    ) : JsonTreeRowContent

    data class ContainerStart(
        val isArray: Boolean,
    ) : JsonTreeRowContent

    data class ContainerEnd(
        val isArray: Boolean,
    ) : JsonTreeRowContent
}

/**
 * One visible row in a flattened JSON tree. [path] is a stable, structural identity (not re-derived
 * from content) safe to use as both a `LazyColumn` item key and an expand-state map key across
 * recompositions of the *same* tree; [keyLabel] is the object key this value was found under (null
 * for array items and the root). [trailingComma] tells the renderer whether to draw a `,` after this
 * row's own content -- true for every node except the last child of its parent. [highlighted] is true
 * when [path] is a member of the `highlightedPaths` set [flattenJsonTree] was called with -- the mock-
 * response-diff feature's row-background highlight (see [computeJsonMockDiff]), analogous to
 * [InspectorCodeLine.highlighted] for the flat code view.
 */
internal data class JsonTreeRow(
    val path: String,
    val depth: Int,
    val keyLabel: String?,
    val content: JsonTreeRowContent,
    val trailingComma: Boolean,
    val highlighted: Boolean = false,
    val searchKeyHighlights: List<InspectorSearchHighlight> = emptyList(),
    val searchValueHighlights: List<InspectorSearchHighlight> = emptyList(),
)

/**
 * Flattens [root] into the [JsonTreeRow]s currently visible given [isExpanded] -- a node whose
 * ancestor is collapsed contributes nothing, and a collapsed container contributes exactly one
 * summary row instead of recursing into its children at all. Rendering this flat list in a
 * `LazyColumn` (rather than nested recursive composables, one per level) is what lets a huge body
 * stay cheap: only the rows actually scrolled into view ever compose, and collapsing a huge array
 * drops its subtree from this list entirely instead of merely hiding already-composed content.
 *
 * [isExpanded] defaults every node to expanded unless told otherwise (`path !in collapsedPaths`, not
 * an explicit "is this path in the expanded set" membership test) -- this keeps the *initial* render
 * visually identical to the old flat pretty-printed text, with collapsing purely an opt-in per node.
 *
 * [highlightedPaths] marks each produced row's [JsonTreeRow.highlighted] by exact [JsonTreeRow.path]
 * membership; empty by default for every caller except the mocked net-detail response body.
 */
internal fun flattenJsonTree(
    root: JsonValue,
    isExpanded: (path: String) -> Boolean,
    highlightedPaths: Set<String> = emptySet(),
): List<JsonTreeRow> {
    val rows = mutableListOf<JsonTreeRow>()
    appendJsonTreeRows(
        root,
        path = "$",
        depth = 0,
        keyLabel = null,
        isLast = true,
        isExpanded = isExpanded,
        highlightedPaths = highlightedPaths,
        rows = rows,
    )
    return rows
}

// LongParameterList: one flattening pass; every parameter is load-bearing for a single row's identity.
// LongMethod: threading highlightedPaths through every branch (mock-response-diff feature) pushed
// this a few lines past the default budget; splitting further would fragment one flattening pass.
@Suppress("LongParameterList", "LongMethod")
private fun appendJsonTreeRows(
    value: JsonValue,
    path: String,
    depth: Int,
    keyLabel: String?,
    isLast: Boolean,
    isExpanded: (String) -> Boolean,
    highlightedPaths: Set<String>,
    rows: MutableList<JsonTreeRow>,
) {
    when (value) {
        is JsonValue.Obj ->
            appendContainerRows(
                isArray = false,
                childCount = value.entries.size,
                path = path,
                depth = depth,
                keyLabel = keyLabel,
                isLast = isLast,
                isExpanded = isExpanded,
                highlightedPaths = highlightedPaths,
                rows = rows,
            ) {
                val lastIndex = value.entries.lastIndex
                value.entries.forEachIndexed { index, (key, child) ->
                    appendJsonTreeRows(
                        child,
                        "$path/$index:$key",
                        depth + 1,
                        key,
                        index == lastIndex,
                        isExpanded,
                        highlightedPaths,
                        rows,
                    )
                }
            }
        is JsonValue.Arr ->
            appendContainerRows(
                isArray = true,
                childCount = value.items.size,
                path = path,
                depth = depth,
                keyLabel = keyLabel,
                isLast = isLast,
                isExpanded = isExpanded,
                highlightedPaths = highlightedPaths,
                rows = rows,
            ) {
                val lastIndex = value.items.lastIndex
                value.items.forEachIndexed { index, child ->
                    appendJsonTreeRows(
                        child,
                        "$path[$index]",
                        depth + 1,
                        null,
                        index == lastIndex,
                        isExpanded,
                        highlightedPaths,
                        rows,
                    )
                }
            }
        is JsonValue.Str ->
            rows +=
                scalarRow(
                    path,
                    depth,
                    keyLabel,
                    isLast,
                    value.value.jsonQuoted(),
                    JsonScalarType.STRING,
                    highlightedPaths,
                )
        is JsonValue.Num ->
            rows += scalarRow(path, depth, keyLabel, isLast, value.raw, JsonScalarType.NUMBER, highlightedPaths)
        is JsonValue.Bool ->
            rows +=
                scalarRow(
                    path,
                    depth,
                    keyLabel,
                    isLast,
                    value.value.toString(),
                    JsonScalarType.BOOLEAN,
                    highlightedPaths,
                )
        JsonValue.Null ->
            rows += scalarRow(path, depth, keyLabel, isLast, "null", JsonScalarType.NULL, highlightedPaths)
    }
}

@Suppress("LongParameterList") // Mirrors JsonTreeRow's own fields; see appendJsonTreeRows' suppression.
private fun scalarRow(
    path: String,
    depth: Int,
    keyLabel: String?,
    isLast: Boolean,
    text: String,
    type: JsonScalarType,
    highlightedPaths: Set<String>,
) = JsonTreeRow(
    path,
    depth,
    keyLabel,
    JsonTreeRowContent.Scalar(text, type),
    trailingComma = !isLast,
    highlighted = path in highlightedPaths,
)

/**
 * A [JsonTreeRow]'s `LazyColumn` item key. [JsonTreeRow.path] alone isn't quite unique: an expanded
 * container contributes *two* rows -- its opening [JsonTreeRowContent.ContainerStart] and closing
 * [JsonTreeRowContent.ContainerEnd] -- that intentionally share one [JsonTreeRow.path] (both belong
 * to the same node), so this appends a `#start`/`#end` discriminator for exactly those two cases;
 * every other row already has a path no other row in the same flattened list can share.
 */
internal fun JsonTreeRow.lazyKey(): String =
    when (content) {
        is JsonTreeRowContent.ContainerStart -> "$path#start"
        is JsonTreeRowContent.ContainerEnd -> "$path#end"
        else -> path
    }

@Suppress("LongParameterList") // Mirrors appendJsonTreeRows' own payload; see its suppression.
private inline fun appendContainerRows(
    isArray: Boolean,
    childCount: Int,
    path: String,
    depth: Int,
    keyLabel: String?,
    isLast: Boolean,
    isExpanded: (String) -> Boolean,
    highlightedPaths: Set<String>,
    rows: MutableList<JsonTreeRow>,
    appendChildren: () -> Unit,
) {
    val highlighted = path in highlightedPaths
    if (!isExpanded(path)) {
        rows +=
            JsonTreeRow(
                path,
                depth,
                keyLabel,
                JsonTreeRowContent.ContainerCollapsed(isArray, childCount),
                !isLast,
                highlighted,
            )
        return
    }
    rows +=
        JsonTreeRow(
            path,
            depth,
            keyLabel,
            JsonTreeRowContent.ContainerStart(isArray),
            trailingComma = false,
            highlighted = highlighted,
        )
    appendChildren()
    rows += JsonTreeRow(path, depth, null, JsonTreeRowContent.ContainerEnd(isArray), !isLast, highlighted)
}
