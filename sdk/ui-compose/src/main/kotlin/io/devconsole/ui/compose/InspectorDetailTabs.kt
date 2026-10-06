/**
 * @author Shakib
 * @since 06/10/26
 */
package io.devconsole.ui.compose

/** One chip of an [InspectorDetailTabLayout.Chips] tab: picks which of the tab's sections is on screen. */
internal data class InspectorDetailTabChip(
    val sectionKey: String,
    val label: String,
)

/** How a tab lays out the sections it holds. */
internal sealed interface InspectorDetailTabLayout {
    val sectionKeys: List<String>

    /** Every section stacked as an always-open panel -- the overview's short, read-at-a-glance sections. */
    data class Stacked(
        override val sectionKeys: List<String>,
    ) : InspectorDetailTabLayout

    /**
     * One section at a time, picked with chips, so the one on screen gets the full height -- a
     * request or response body is the long read the tabbed layout exists to make room for.
     */
    data class Chips(
        val chips: List<InspectorDetailTabChip>,
    ) : InspectorDetailTabLayout {
        override val sectionKeys: List<String> get() = chips.map { it.sectionKey }
    }
}

/** One tab of a tabbed detail screen; its sections are keys into the screen's own section list. */
internal data class InspectorDetailTabSpec(
    val key: String,
    val label: String,
    val layout: InspectorDetailTabLayout,
) {
    val sectionKeys: List<String> get() = layout.sectionKeys
}

/** A detail screen's tabs, and the one it opens on. */
internal data class InspectorDetailTabs(
    val tabs: List<InspectorDetailTabSpec>,
    val initialTabKey: String,
)

/**
 * The count a tab shows beside its label while a query is active: every match in any of its
 * sections, so a hit on a tab the user isn't looking at is still announced. Null (no badge) when
 * there is no query or nothing on this tab matches.
 */
internal fun InspectorDetailTabSpec.matchBadge(
    matches: List<InspectorDetailSearchMatch>,
    hasQuery: Boolean,
): String? {
    if (!hasQuery) return null
    val keys = sectionKeys.toSet()
    return matches.count { it.sectionKey in keys }.takeIf { it > 0 }?.toString()
}

/**
 * A chip's trailing count: its match count while a query is active, otherwise how many headers a
 * key-value section holds. A body chip shows nothing without a query -- its line count says
 * nothing a reader needs before opening it.
 */
internal fun detailChipCount(
    body: InspectorDetailSectionBody,
    matchCount: Int,
    hasQuery: Boolean,
): String? =
    when {
        hasQuery -> matchCount.takeIf { it > 0 }?.toString()
        body is InspectorDetailSectionBody.KeyValues ->
            body.entries.size
                .takeIf { it > 0 }
                ?.toString()
        else -> null
    }

/** The tabbed find bar's "position/count" over the matches in the section on screen only. */
internal fun tabbedSearchMatchLabel(
    query: String,
    position: Int,
    count: Int,
): String =
    when {
        query.isBlank() -> ""
        count == 0 -> "0/0"
        else -> "${position.coerceIn(0, count - 1) + 1}/$count"
    }

/**
 * The global ordinal of the match at [position] among [visibleMatches] -- what the body renderers
 * compare against to draw the active highlight. A position left over from a longer list clamps to
 * the last match rather than pointing at nothing.
 */
internal fun activeVisibleMatchOrdinal(
    visibleMatches: List<InspectorDetailSearchMatch>,
    position: Int,
): Int? = visibleMatches.getOrNull(position.coerceIn(0, (visibleMatches.size - 1).coerceAtLeast(0)))?.ordinal
