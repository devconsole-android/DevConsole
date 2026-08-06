/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.ui.compose

/**
 * Result of [computeJsonMockDiff]: the served mock body's fields that differ from the original
 * transaction it was created from.
 *
 * [highlightedPaths] is every field/array element that either changed value or is new in the mocked
 * body, in exactly the [JsonTreeRow.path] format [flattenJsonTree] produces for that same mocked
 * body's tree -- a caller can pass this straight through as the tree's highlight set with no
 * translation. [removedCount] is fields present in the original but absent from the mock; per the
 * feature's contract those are counted only, never rendered (there is no row in the mocked body's
 * tree for content that isn't there), so they never appear in [highlightedPaths].
 */
internal data class JsonMockDiffResult(
    val highlightedPaths: Set<String>,
    val removedCount: Int,
) {
    /** "N fields differ from original" per the contract -- every highlighted field plus every removed one, once. */
    val totalCount: Int
        get() = highlightedPaths.size + removedCount
}

/**
 * Structural diff of [mockedText] (the body a mock rule actually served) against [originalText] (the
 * `sourceBodySnapshot` captured when the rule was created from a live transaction via "Mock this
 * response"), for the mocked capture-detail screen's "N fields differ from original" notice and its
 * response-body JSON tree highlight. Reuses [MinimalJsonParser] -- the same parser [flattenJsonTree]
 * already builds its rows from -- so every path in the result lands in exactly the [JsonTreeRow.path]
 * format that tree uses, with no separate path scheme to keep in sync.
 *
 * Either body failing to parse as JSON returns null: the feature is silently absent (no highlights,
 * no count), never a wrong or partial diff computed over malformed input.
 *
 * A field is counted/highlighted as changed when it exists at the same structural position in both
 * bodies but its value differs -- compared by parsed-value equality, so e.g. a string vs. a number at
 * the same key counts as changed even if their text looks similar. A field is counted/highlighted as
 * added when it exists in [mockedText] but not in [originalText] -- the whole new field is credited as
 * one, its own descendants (if it's an object/array) are not separately walked or counted, since
 * they're all part of the one new thing. A field only in [originalText] is counted as removed and
 * never highlighted (see [JsonMockDiffResult.removedCount]'s own doc).
 *
 * Number comparison is pinned to match the web dashboard's `diffMockBody`, which compares
 * `JSON.parse`d values (always IEEE-754 doubles) rather than source text -- see [jsonValuesEqual].
 * Concretely: `1.0` and `1` compare equal, as do `1e3` and `1000`, and `-0` and `0`; integers past
 * 2^53 can silently compare equal despite being numerically distinct, the same way `JSON.parse`
 * loses precision on the web. Both platforms must keep this exact rule -- changing one without the
 * other reintroduces the divergence this doc exists to prevent.
 */
@Suppress("ReturnCount") // Each malformed-body early exit is an honest guard, not a branch worth merging.
internal fun computeJsonMockDiff(
    originalText: String,
    mockedText: String,
): JsonMockDiffResult? {
    val original = runCatching { MinimalJsonParser(originalText).parseDocument() }.getOrNull() ?: return null
    val mocked = runCatching { MinimalJsonParser(mockedText).parseDocument() }.getOrNull() ?: return null
    val accumulator = JsonDiffAccumulator()
    diffJsonValues(original, mocked, path = "$", accumulator)
    return JsonMockDiffResult(accumulator.highlightedPaths, accumulator.removedCount)
}

private class JsonDiffAccumulator {
    val highlightedPaths = mutableSetOf<String>()
    var removedCount = 0
}

/**
 * Diffs one node already known to exist at [path] in both bodies. Objects and arrays recurse
 * field-by-field/index-by-index so a change deep inside a large body only highlights the field that
 * actually changed, not every ancestor container around it; anything else (scalars, or a value that
 * changed shape entirely -- e.g. a string replaced by an object) is compared as a single unit via
 * [JsonValue]'s own structural `equals`, which every [JsonValue] variant here supports as a data
 * class/object.
 */
private fun diffJsonValues(
    original: JsonValue,
    mocked: JsonValue,
    path: String,
    acc: JsonDiffAccumulator,
) {
    when {
        original is JsonValue.Obj && mocked is JsonValue.Obj -> diffObjects(original, mocked, path, acc)
        original is JsonValue.Arr && mocked is JsonValue.Arr -> diffArrays(original, mocked, path, acc)
        !jsonValuesEqual(original, mocked) -> acc.highlightedPaths += path
    }
}

/**
 * Scalar equality for the diff -- structural [JsonValue.equals] for everything except two
 * [JsonValue.Num]s, which compare by parsed [Double] value instead of [JsonValue.Num.raw]'s source
 * text. [JsonValue.Num] deliberately keeps raw text for lossless pretty-printing (see its own doc),
 * so raw-text equality would flag `1.0` vs `1` as changed even though every JSON consumer -- including
 * the web dashboard's `JSON.parse`-based diff this must match -- treats them as the same number. Both
 * operands are statically typed `Double` (not boxed/nullable) at the `==` below, which is what gives
 * this IEEE-754 `==` semantics (`-0.0 == 0.0`) rather than [Double.equals]'s bitwise semantics
 * (`-0.0.equals(0.0)` is false) -- see [computeJsonMockDiff]'s doc for why that specific behavior is
 * the intended, cross-platform-matching one, not an accident of which equality got called.
 */
private fun jsonValuesEqual(
    original: JsonValue,
    mocked: JsonValue,
): Boolean {
    if (original is JsonValue.Num && mocked is JsonValue.Num) {
        val originalNum = original.raw.toDoubleOrNull()
        val mockedNum = mocked.raw.toDoubleOrNull()
        if (originalNum != null && mockedNum != null) return originalNum == mockedNum
    }
    return original == mocked
}

/** Object diff: mirrors `appendJsonTreeRows`'s own `path/index:key` path scheme exactly. */
private fun diffObjects(
    original: JsonValue.Obj,
    mocked: JsonValue.Obj,
    path: String,
    acc: JsonDiffAccumulator,
) {
    // First occurrence wins for a duplicate key -- an edge case, but the one a standard-conforming
    // JSON consumer would also land on if it re-parsed this same document into a real map.
    val originalByKey = LinkedHashMap<String, JsonValue>()
    original.entries.forEach { (key, value) -> originalByKey.putIfAbsent(key, value) }
    val mockedKeys = mutableSetOf<String>()
    mocked.entries.forEachIndexed { index, (key, mockedChild) ->
        mockedKeys += key
        val childPath = "$path/$index:$key"
        val originalChild = originalByKey[key]
        if (originalChild == null) {
            acc.highlightedPaths += childPath
        } else {
            diffJsonValues(originalChild, mockedChild, childPath, acc)
        }
    }
    acc.removedCount += originalByKey.keys.count { it !in mockedKeys }
}

/** Array diff: mirrors `appendJsonTreeRows`'s own `path[index]` path scheme exactly. */
private fun diffArrays(
    original: JsonValue.Arr,
    mocked: JsonValue.Arr,
    path: String,
    acc: JsonDiffAccumulator,
) {
    mocked.items.forEachIndexed { index, mockedChild ->
        val childPath = "$path[$index]"
        val originalChild = original.items.getOrNull(index)
        if (originalChild == null) {
            acc.highlightedPaths += childPath
        } else {
            diffJsonValues(originalChild, mockedChild, childPath, acc)
        }
    }
    if (original.items.size > mocked.items.size) acc.removedCount += original.items.size - mocked.items.size
}
