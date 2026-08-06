/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Locale

/** "Crash" for an uncaught exception, "ANR" for a main-thread stall -- mirrors the internal `CrashKind`. */
private fun crashKindLabel(kind: String): String = if (kind.uppercase(Locale.US) == "ANR") "ANR" else "Crash"

private fun breadcrumbEntries(breadcrumbs: List<InspectorBreadcrumbUi>): List<InspectorKeyValue> =
    breadcrumbs.map { crumb ->
        InspectorKeyValue(
            formatCaptureClockTime(crumb.timestampEpochMs),
            "${crumb.plugin}/${crumb.type} · ${crumb.summary}",
        )
    }

/** General/breadcrumbs/all-thread-dump sections: summary, kind, thread, the breadcrumb strip, and the dump. */
private fun crashSections(
    crash: InspectorCrashUi,
    colors: DevConsoleColors,
    leadColor: Color,
    copyText: (String) -> Unit,
): List<InspectorDetailSectionSpec> {
    val generalEntries =
        listOf(
            InspectorKeyValue("summary", crash.summary),
            InspectorKeyValue("kind", crashKindLabel(crash.kind), leadColor),
            InspectorKeyValue("thread", crash.thread.ifBlank { "unknown" }),
            InspectorKeyValue("at", formatCaptureClockTime(crash.timestampEpochMs)),
        )
    val crumbs = breadcrumbEntries(crash.breadcrumbs)
    val breadcrumbsBody =
        if (crumbs.isEmpty()) {
            InspectorDetailSectionBody.Empty("No breadcrumbs captured before this event.")
        } else {
            InspectorDetailSectionBody.KeyValues(crumbs)
        }
    // A Code-typed section (not Formattable/plain text) so InspectorObserveDetailScreen's existing
    // expand-full-screen wiring picks it up automatically -- a long all-thread dump belongs in
    // InspectorCodeFullScreenOverlay, not a cramped inline block.
    val dumpLines =
        crash.stackTrace
            .takeIf(String::isNotBlank)
            ?.lines()
            ?.map { it.toRedactionAwareCodeLine(colors) }
    val dumpBody =
        if (dumpLines.isNullOrEmpty()) {
            InspectorDetailSectionBody.Empty("No stack trace captured.")
        } else {
            InspectorDetailSectionBody.Code(dumpLines)
        }
    // Copies crash.stackTrace itself (the untruncated source dumpLines was built from), not a
    // rejoin of the redaction-aware lines -- same "raw source, not the rendered lines" rule
    // InspectorFormattableBody's rawText follows.
    val dumpCopy = dumpLines?.takeIf { it.isNotEmpty() }?.let { { copyText(crash.stackTrace) } }
    return listOf(
        InspectorDetailSectionSpec(
            "general",
            "General",
            InspectorDetailSectionBody.KeyValues(generalEntries),
            copyDescription = "Copy general info",
            onCopy = { copyText(generalEntries.toCopyText()) },
        ),
        InspectorDetailSectionSpec(
            "crumbs",
            "Breadcrumbs",
            breadcrumbsBody,
            copyDescription = "Copy breadcrumbs",
            onCopy = keyValuesCopyAction(crumbs, copyText),
        ),
        InspectorDetailSectionSpec(
            "dump",
            "All-thread dump",
            dumpBody,
            copyDescription = "Copy stack trace",
            onCopy = dumpCopy,
        ),
    )
}

private fun crashFooterActions(
    colors: DevConsoleColors,
    isFlagged: Boolean,
    onToggleFlag: () -> Unit,
    copyPayload: String,
    copyText: (String) -> Unit,
): List<InspectorFooterAction> =
    listOf(
        InspectorFooterAction(
            label = if (isFlagged) "Flagged as evidence" else "Flag as evidence",
            onClick = onToggleFlag,
            weight = 2f,
            icon = {
                val tint = if (isFlagged) colors.signal else colors.signalInk
                InspectorGlyphIcon(InspectorGlyph.Flag, contentDescription = null, tint = tint, size = 18.dp)
            },
            containerColor = if (isFlagged) colors.signalSoft else colors.signal,
            contentColor = if (isFlagged) colors.signal else colors.signalInk,
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

/**
 * Builds the Crashes tab's detail: summary, kind, thread, the breadcrumb strip, and the
 * all-thread dump, reusing [InspectorDetailScaffold]/[InspectorObserveDetailScreen] exactly like
 * [logDetailContent] and [netDetailContent] already do for their own capture kinds. Evidence flagging
 * here goes through the durable `EvidenceStore` via [InspectorAction.ToggleCrashFlag], the same
 * mechanism the net detail's "Flag as evidence" uses -- [isFlagged] reflects
 * [ObserveUiState.flaggedCrashIds], which mirrors [InspectorState.flaggedCrashIds], so a crash
 * flagged from the dashboard shows as flagged here too.
 */
@Suppress("LongParameterList") // Mirrors every other *DetailContent builder's per-capture payload.
internal fun crashDetailContent(
    crash: InspectorCrashUi,
    colors: DevConsoleColors,
    isFlagged: Boolean,
    onToggleFlag: () -> Unit,
    copyText: (String) -> Unit,
    shareText: (String, String) -> Unit,
): ObserveDetailContent {
    val (leadColor, leadBg) = logLevelTint(crash.kind, colors)
    val copyPayload = "${crash.summary}\n${crash.stackTrace}"
    val kindLabel = crashKindLabel(crash.kind)
    val threadLabel = crash.thread.ifBlank { "unknown thread" }
    return ObserveDetailContent(
        header =
            InspectorObserveDetailHeaderSpec(
                kindLabel = "$kindLabel report",
                leadText = logLevelShortLabel(crash.kind),
                leadColor = leadColor,
                leadContainerColor = leadBg,
                title = crash.summary,
                subtitle = "$threadLabel · $kindLabel",
                status = formatCaptureClockTime(crash.timestampEpochMs),
                statusColor = colors.muted,
                actions =
                    listOf(
                        InspectorTopAction(
                            contentDescription = "Copy crash report",
                            onClick = { copyText(copyPayload) },
                            icon = copyIconAction(colors.muted),
                        ),
                        InspectorTopAction(
                            contentDescription = "Share crash report",
                            onClick = { shareText(copyPayload, "Share crash report") },
                            icon = shareIconAction(colors.muted),
                        ),
                    ),
            ),
        sections = crashSections(crash, colors, leadColor, copyText),
        // General, breadcrumbs and the dump all open by default -- this screen exists to answer "what
        // happened and what led up to it" at a glance, not to require expanding every section first.
        initiallyOpenSectionKeys = setOf("general", "crumbs", "dump"),
        footerActions = crashFooterActions(colors, isFlagged, onToggleFlag, copyPayload, copyText),
    )
}
