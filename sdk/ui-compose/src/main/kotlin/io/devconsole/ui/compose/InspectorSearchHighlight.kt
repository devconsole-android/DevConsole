/**
 * @author Shakib
 * @since 17/08/26
 */
package io.devconsole.ui.compose

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

internal data class InspectorSearchHighlight(
    val start: Int,
    val endExclusive: Int,
    val active: Boolean,
)

internal data class InspectorSearchHighlightTarget(
    val itemId: String,
    val field: InspectorSearchField,
)

internal class InspectorSearchHighlightIndex internal constructor(
    private val highlights: Map<InspectorSearchHighlightTarget, List<InspectorSearchHighlight>>,
) {
    fun highlightsFor(
        itemId: String,
        field: InspectorSearchField,
    ): List<InspectorSearchHighlight> = highlights[InspectorSearchHighlightTarget(itemId, field)].orEmpty()
}

/** Groups matches once so each rendered field can retrieve its spans in constant time. */
internal fun indexInspectorSearchHighlights(
    matches: List<InspectorDetailSearchMatch>,
    currentMatchOrdinal: Int?,
): InspectorSearchHighlightIndex {
    val indexed = mutableMapOf<InspectorSearchHighlightTarget, MutableList<InspectorSearchHighlight>>()
    matches.forEach { match ->
        val target = InspectorSearchHighlightTarget(match.itemId, match.field)
        indexed.getOrPut(target, ::mutableListOf) +=
            InspectorSearchHighlight(
                start = match.start,
                endExclusive = match.endExclusive,
                active = match.ordinal == currentMatchOrdinal,
            )
    }
    return InspectorSearchHighlightIndex(indexed)
}

internal fun inspectorHighlightedText(
    text: String,
    highlights: List<InspectorSearchHighlight>,
    colors: DevConsoleColors,
): AnnotatedString =
    buildAnnotatedString {
        append(text)
        highlights.forEach { highlight ->
            val start = highlight.start.coerceIn(0, text.length)
            val end = highlight.endExclusive.coerceIn(start, text.length)
            if (start < end) {
                addStyle(
                    SpanStyle(
                        background = if (highlight.active) colors.signal else colors.signalSoft,
                    ),
                    start,
                    end,
                )
            }
        }
    }
