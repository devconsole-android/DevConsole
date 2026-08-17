/**
 * @author Shakib
 * @since 04/08/26
 */
package io.devconsole.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private fun logToJsonSnippet(log: InspectorLogUi): String =
    buildString {
        append("{\n")
        append("  \"kind\": ").append(log.kind.jsonQuoted()).append(",\n")
        append("  \"source\": ").append(log.source.jsonQuoted()).append(",\n")
        append("  \"summary\": ").append(log.summary.jsonQuoted()).append(",\n")
        append("  \"timestampEpochMs\": ").append(log.timestampEpochMs.toString()).append(",\n")
        append("  \"detail\": ").append(log.detail.jsonQuotedOrNull()).append('\n')
        append('}')
    }

private fun logSections(
    log: InspectorLogUi,
    colors: DevConsoleColors,
    leadColor: Color,
    copyText: (String) -> Unit,
): List<InspectorDetailSectionSpec> {
    val generalEntries =
        listOf(
            InspectorKeyValue("summary", log.summary),
            InspectorKeyValue("level", log.kind, leadColor),
            InspectorKeyValue("source", log.source),
            InspectorKeyValue("at", formatCaptureClockTime(log.timestampEpochMs)),
        )
    val contextBody =
        log.detail?.let { detail -> formattableTextBody(detail, colors) }
            ?: InspectorDetailSectionBody.Empty("No additional context captured.")
    val contextCopy =
        (contextBody as? InspectorDetailSectionBody.Formattable)?.let { formattable ->
            { copyText(formattable.rawText) }
        }
    return listOf(
        InspectorDetailSectionSpec(
            "general",
            "General",
            InspectorDetailSectionBody.KeyValues(generalEntries),
            copyDescription = "Copy general info",
            onCopy = { copyText(generalEntries.toCopyText()) },
        ),
        InspectorDetailSectionSpec(
            InspectorExchangeSection.PRIMARY_BODY.key,
            "Context",
            contextBody,
            copyDescription = "Copy context",
            onCopy = contextCopy,
        ),
        InspectorDetailSectionSpec(
            InspectorExchangeSection.SECONDARY_HEADERS.key,
            "Related transaction",
            InspectorDetailSectionBody.Empty("This event is not linked to a transaction."),
        ),
    )
}

private fun logFooterActions(
    colors: DevConsoleColors,
    isBookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    copyPayload: String,
    copyText: (String) -> Unit,
): List<InspectorFooterAction> =
    listOf(
        InspectorFooterAction(
            label = if (isBookmarked) "Bookmarked" else "Bookmark event",
            onClick = onToggleBookmark,
            weight = 2f,
            icon = {
                val tint = if (isBookmarked) colors.signal else colors.signalInk
                InspectorGlyphIcon(InspectorGlyph.Flag, contentDescription = null, tint = tint, size = 18.dp)
            },
            containerColor = if (isBookmarked) colors.signalSoft else colors.signal,
            contentColor = if (isBookmarked) colors.signal else colors.signalInk,
        ),
        InspectorFooterAction(
            label = "Copy",
            onClick = { copyText(copyPayload) },
            weight = 1f,
            icon = {
                InspectorGlyphIcon(InspectorGlyph.Copy, contentDescription = null, tint = colors.ink, size = 18.dp)
            },
            containerColor = colors.surface3,
            contentColor = colors.ink,
        ),
    )

/** Builds the log/crash/ANR capture detail, degraded to real fields only. */
@Suppress("LongParameterList") // Every field here is independently needed to render the event detail.
internal fun logDetailContent(
    log: InspectorLogUi,
    colors: DevConsoleColors,
    isBookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    copyText: (String) -> Unit,
    shareText: (String, String) -> Unit,
): ObserveDetailContent {
    val (leadColor, leadBg) = logLevelTint(log.kind, colors)
    val logJson = logToJsonSnippet(log)
    val copyPayload = log.summary + (log.detail?.let { "\n$it" } ?: "")
    return ObserveDetailContent(
        header =
            InspectorObserveDetailHeaderSpec(
                kindLabel = "Log event",
                leadText = logLevelShortLabel(log.kind),
                leadColor = leadColor,
                leadContainerColor = leadBg,
                title = log.summary,
                subtitle = "${log.source} · ${log.kind}",
                status = formatCaptureClockTime(log.timestampEpochMs),
                statusColor = colors.muted,
                actions =
                    listOf(
                        InspectorTopAction(
                            contentDescription = "Copy log event",
                            onClick = { copyText(copyPayload) },
                            icon = copyIconAction(colors.muted),
                        ),
                        InspectorTopAction(
                            contentDescription = "Share log event as JSON",
                            onClick = { shareText(logJson, "Share event JSON") },
                            icon = shareIconAction(colors.muted),
                        ),
                    ),
            ),
        sections = logSections(log, colors, leadColor, copyText),
        // No body section exists for a log event -- keep General (the first section) open so the
        // screen doesn't land all-collapsed.
        initiallyOpenSectionKeys = setOf("general"),
        footerActions = logFooterActions(colors, isBookmarked, onToggleBookmark, copyPayload, copyText),
    )
}
