/**
 * @author Shakib
 * @since 05/08/26
 */
package io.devconsole.ui.compose

/**
 * Stable `LazyColumn` item keys for lists whose rows have no server-assigned id (socket frames,
 * push events): [baseKey] derives an identity from content that is stable across snapshot refreshes
 * (e.g. `"$socketId@$timestampEpochMs"`), and this dedupes exact collisions -- two rows sharing the
 * same [baseKey] (frames landing in the same millisecond, say) -- with a `#n` occurrence suffix.
 * The suffix is *not* a plain list index: it counts prior occurrences of that same [baseKey] within
 * [items] itself, so it stays put for existing rows as new ones are appended to the end of the list
 * (the normal case for a live capture feed), unlike keying directly on position, which reassigns
 * every row's key -- and forces a recomposition of every row -- each time a new item arrives.
 */
internal fun <T> uniqueKeys(
    items: List<T>,
    baseKey: (T) -> String,
): List<String> {
    val occurrences = HashMap<String, Int>()
    return items.map { item ->
        val base = baseKey(item)
        val occurrence = occurrences.getOrDefault(base, 0)
        occurrences[base] = occurrence + 1
        if (occurrence == 0) base else "$base#$occurrence"
    }
}
