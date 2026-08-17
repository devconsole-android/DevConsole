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

internal fun List<InspectorDetailSearchMatch>.highlightsFor(
    itemId: String,
    field: InspectorSearchField,
    currentMatchOrdinal: Int?,
): List<InspectorSearchHighlight> =
    filter { match -> match.itemId == itemId && match.field == field }
        .map { match ->
            InspectorSearchHighlight(
                start = match.start,
                endExclusive = match.endExclusive,
                active = match.ordinal == currentMatchOrdinal,
            )
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
