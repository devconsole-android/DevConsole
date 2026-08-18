/**
 * @author Shakib
 * @since 18/08/26
 */
package io.devconsole.ui.compose

internal fun jsonSearchCandidates(
    sectionKey: String,
    root: JsonValue,
): List<InspectorSearchCandidate> =
    buildList {
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
        is JsonValue.Str -> candidates += jsonLeafSearchCandidate(sectionKey, path, value.value.jsonQuoted())
        is JsonValue.Num -> candidates += jsonLeafSearchCandidate(sectionKey, path, value.raw)
        is JsonValue.Bool -> candidates += jsonLeafSearchCandidate(sectionKey, path, value.value.toString())
        JsonValue.Null -> candidates += jsonLeafSearchCandidate(sectionKey, path, "null")
    }
}

/** Builds a VALUE candidate for a JSON scalar leaf at [path]. */
private fun jsonLeafSearchCandidate(
    sectionKey: String,
    path: String,
    text: String,
): InspectorSearchCandidate =
    InspectorSearchCandidate(
        sectionKey = sectionKey,
        itemId = path,
        field = InspectorSearchField.VALUE,
        text = text,
        path = path,
        ancestorPaths = jsonAncestorPaths(path),
    )

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
