/**
 * @author Shakib
 * @since 17/08/26
 */
package io.devconsole.ui.compose

/** Whether a search candidate represents a field name or its associated content. */
internal enum class InspectorSearchField {
    KEY,
    VALUE,
}

/** Search fields enabled by the Network detail search options sheet. */
internal enum class InspectorSearchMode(
    val label: String,
) {
    KEYS("Keys"),
    VALUES("Values"),
    KEYS_AND_VALUES("Keys + values"),
}

/** One searchable piece of rendered detail content. */
internal data class InspectorSearchCandidate(
    val sectionKey: String,
    val itemId: String,
    val field: InspectorSearchField,
    val text: String,
    val path: String? = null,
    val ancestorPaths: List<String> = emptyList(),
)

/** One exact query occurrence in a searchable candidate. */
internal data class InspectorDetailSearchMatch(
    val ordinal: Int,
    val sectionKey: String,
    val itemId: String,
    val field: InspectorSearchField,
    val start: Int,
    val endExclusive: Int,
    val path: String? = null,
    val ancestorPaths: List<String> = emptyList(),
)

internal data class InspectorDetailSearchSection(
    val key: String,
    val label: String,
)

internal data class InspectorDetailSearchOptions(
    val sections: List<InspectorDetailSearchSection>,
    val defaultSectionKeys: Set<String>,
    val defaultMode: InspectorSearchMode = InspectorSearchMode.KEYS,
)

internal val NetworkDetailSearchOptions =
    InspectorDetailSearchOptions(
        sections =
            listOf(
                InspectorDetailSearchSection("reqh", "Request headers"),
                InspectorDetailSearchSection("req", "Request body"),
                InspectorDetailSearchSection("resh", "Response headers"),
                InspectorDetailSearchSection("res", "Response body"),
            ),
        defaultSectionKeys = setOf("reqh", "req", "resh", "res"),
    )

internal fun searchInspectorSections(
    sections: List<InspectorDetailSectionSpec>,
    query: String,
    selectedSectionKeys: Set<String>,
    mode: InspectorSearchMode,
): List<InspectorDetailSearchMatch> =
    searchInspectorCandidates(
        candidates = sections.flatMap { spec -> searchInspectorBodyCandidates(spec.key, spec.body) },
        query = query,
        selectedSectionKeys = selectedSectionKeys,
        mode = mode,
    )

internal fun inspectorSearchScopeSummary(
    options: InspectorDetailSearchOptions,
    selectedSectionKeys: Set<String>,
): String {
    val selected = options.sections.filter { it.key in selectedSectionKeys }
    return when {
        selected.size == options.sections.size -> "All request + response"
        selected.size == 1 -> selected.single().label
        selected.isEmpty() -> "No sections"
        else -> "${selected.size} sections"
    }
}

/** Returns all non-overlapping, case-insensitive occurrences of [query] in [text]. */
private fun findInspectorQueryRanges(
    text: String,
    query: String,
): List<IntRange> {
    if (query.isEmpty() || query.length > text.length) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var start = 0
    while (start <= text.length - query.length) {
        val matchAt =
            (start..text.length - query.length).firstOrNull { index ->
                text.regionMatches(index, query, 0, query.length, ignoreCase = true)
            }
                ?: break
        ranges += matchAt until (matchAt + query.length)
        start = matchAt + query.length
    }
    return ranges
}

private fun InspectorSearchMode.includes(field: InspectorSearchField): Boolean =
    when (this) {
        InspectorSearchMode.KEYS -> field == InspectorSearchField.KEY
        InspectorSearchMode.VALUES -> field == InspectorSearchField.VALUE
        InspectorSearchMode.KEYS_AND_VALUES -> true
    }

/** Matches selected candidates in their supplied document order. */
internal fun searchInspectorCandidates(
    candidates: List<InspectorSearchCandidate>,
    query: String,
    selectedSectionKeys: Set<String>,
    mode: InspectorSearchMode,
): List<InspectorDetailSearchMatch> {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) return emptyList()

    return buildList {
        candidates.forEach { candidate ->
            if (candidate.sectionKey !in selectedSectionKeys || !mode.includes(candidate.field)) return@forEach
            findInspectorQueryRanges(candidate.text, trimmedQuery).forEach { range ->
                add(
                    InspectorDetailSearchMatch(
                        ordinal = size,
                        sectionKey = candidate.sectionKey,
                        itemId = candidate.itemId,
                        field = candidate.field,
                        start = range.first,
                        endExclusive = range.last + 1,
                        path = candidate.path,
                        ancestorPaths = candidate.ancestorPaths,
                    ),
                )
            }
        }
    }
}

internal fun nextInspectorMatchIndex(
    current: Int,
    total: Int,
): Int = if (total <= 0) 0 else (current.coerceIn(0, total - 1) + 1) % total

internal fun previousInspectorMatchIndex(
    current: Int,
    total: Int,
): Int = if (total <= 0) 0 else (current.coerceIn(0, total - 1) - 1 + total) % total

/** Extracts candidates in the same order that a detail body presents them. */
internal fun searchInspectorBodyCandidates(
    sectionKey: String,
    body: InspectorDetailSectionBody,
): List<InspectorSearchCandidate> =
    when (body) {
        is InspectorDetailSectionBody.KeyValues ->
            buildList {
                body.entries.forEachIndexed { index, entry ->
                    add(InspectorSearchCandidate(sectionKey, "row:$index", InspectorSearchField.KEY, entry.key))
                    add(InspectorSearchCandidate(sectionKey, "row:$index", InspectorSearchField.VALUE, entry.value))
                }
            }
        is InspectorDetailSectionBody.Code ->
            buildList {
                body.lines.forEachIndexed { index, line ->
                    if (line.key.isNotEmpty()) {
                        add(InspectorSearchCandidate(sectionKey, "line:$index", InspectorSearchField.KEY, line.key))
                    }
                    if (line.value.isNotEmpty()) {
                        add(InspectorSearchCandidate(sectionKey, "line:$index", InspectorSearchField.VALUE, line.value))
                    }
                }
            }
        is InspectorDetailSectionBody.Formattable ->
            when (val formatted = body.formatted) {
                is FormattedBody.Json -> jsonSearchCandidates(sectionKey, formatted.root)
                is FormattedBody.Xml ->
                    formatted.text.lines().mapIndexed { index, line ->
                        InspectorSearchCandidate(sectionKey, "line:$index", InspectorSearchField.VALUE, line)
                    }
                null ->
                    body.rawLines.mapIndexed { index, line ->
                        InspectorSearchCandidate(sectionKey, "line:$index", InspectorSearchField.VALUE, line.value)
                    }
            }
        is InspectorDetailSectionBody.Bars, is InspectorDetailSectionBody.Empty -> emptyList()
    }

private fun jsonSearchCandidates(
    sectionKey: String,
    root: JsonValue,
): List<InspectorSearchCandidate> = buildList {
    appendJsonSearchCandidates(this, sectionKey, root, path = "$")
}

private fun appendJsonSearchCandidates(
    candidates: MutableList<InspectorSearchCandidate>,
    sectionKey: String,
    value: JsonValue,
    path: String,
) {
    when (value) {
        is JsonValue.Obj ->
            value.entries.forEachIndexed { index, (key, child) ->
                val childPath = "$path/$index:$key"
                candidates +=
                    InspectorSearchCandidate(
                        sectionKey = sectionKey,
                        itemId = childPath,
                        field = InspectorSearchField.KEY,
                        text = key.jsonQuoted(),
                        path = childPath,
                        ancestorPaths = jsonAncestorPaths(childPath),
                    )
                appendJsonSearchCandidates(candidates, sectionKey, child, childPath)
            }
        is JsonValue.Arr ->
            value.items.forEachIndexed { index, child ->
                appendJsonSearchCandidates(candidates, sectionKey, child, "$path[$index]")
            }
        is JsonValue.Str ->
            candidates +=
                InspectorSearchCandidate(
                    sectionKey = sectionKey,
                    itemId = path,
                    field = InspectorSearchField.VALUE,
                    text = value.value.jsonQuoted(),
                    path = path,
                    ancestorPaths = jsonAncestorPaths(path),
                )
        is JsonValue.Num ->
            candidates +=
                InspectorSearchCandidate(
                    sectionKey = sectionKey,
                    itemId = path,
                    field = InspectorSearchField.VALUE,
                    text = value.raw,
                    path = path,
                    ancestorPaths = jsonAncestorPaths(path),
                )
        is JsonValue.Bool ->
            candidates +=
                InspectorSearchCandidate(
                    sectionKey = sectionKey,
                    itemId = path,
                    field = InspectorSearchField.VALUE,
                    text = value.value.toString(),
                    path = path,
                    ancestorPaths = jsonAncestorPaths(path),
                )
        JsonValue.Null ->
            candidates +=
                InspectorSearchCandidate(
                    sectionKey = sectionKey,
                    itemId = path,
                    field = InspectorSearchField.VALUE,
                    text = "null",
                    path = path,
                    ancestorPaths = jsonAncestorPaths(path),
                )
    }
}

private fun jsonAncestorPaths(path: String): List<String> {
    if (path == "$") return emptyList()
    val ancestors = mutableListOf<String>()
    var cursor = path
    while (cursor != "$") {
        val separator = maxOf(cursor.lastIndexOf('/'), cursor.lastIndexOf('['))
        cursor = if (separator <= 0) "$" else cursor.substring(0, separator)
        ancestors += cursor
    }
    return ancestors.asReversed()
}
