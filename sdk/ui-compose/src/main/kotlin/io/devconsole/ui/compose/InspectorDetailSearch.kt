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
