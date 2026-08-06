/**
 * @author Shakib
 * @since 04/08/26
 */
package io.devconsole.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Copy-as-cURL, share-as-JSON, and mock-from-capture header actions. "Pin as diff baseline" is
 * dropped: nothing in [InspectorState] tracks a pinned baseline or renders a diff view, so a Pin
 * button here would be a no-op affordance -- only header actions with real behavior are included.
 * "Mock this response" is always shown regardless of the mocks capability for an unmocked
 * transaction -- Save on the sheet it opens dispatches UpsertMockRule, which already gates and shows
 * a blocked toast, the same pattern the Control screen's own mock affordances use. [onMockAction] is
 * `null` entirely when this transaction is already mocked but its rule id is unknown: a mock/unmock
 * button with no rule to act on would be dead chrome.
 */
private fun netHeaderActions(
    transaction: InspectorTransactionUi,
    colors: DevConsoleColors,
    copyText: (String) -> Unit,
    shareText: (String, String) -> Unit,
    onMockAction: (() -> Unit)?,
): List<InspectorTopAction> =
    buildList {
        add(
            InspectorTopAction(
                contentDescription = "Copy as cURL",
                onClick = { copyText(transaction.toCurlCommand()) },
                icon = copyIconAction(colors.muted),
            ),
        )
        add(
            InspectorTopAction(
                contentDescription = "Share transaction as JSON",
                onClick = { shareText(transaction.toJsonSnippet(), "Share transaction JSON") },
                icon = shareIconAction(colors.muted),
            ),
        )
        if (onMockAction != null) {
            add(
                InspectorTopAction(
                    contentDescription = if (transaction.isMocked) "Unmock this response" else "Mock this response",
                    onClick = onMockAction,
                    icon = {
                        ObserveGlyphIcon(ObserveGlyph.Tag, contentDescription = null, tint = colors.muted, size = 18.dp)
                    },
                ),
            )
        }
    }

/**
 * One [InspectorProgressStat] per captured phase, in wire order. A null phase is legitimately
 * absent (a pooled connection does no DNS/connect, a plaintext request has no TLS, a cached
 * response does no network work at all) and is skipped rather than rendered as a fabricated
 * zero-length bar. Each bar's fraction is relative to the sum of the *captured* phases, since that
 * is the only "whole" this method actually knows.
 */
private fun timingPhaseStats(
    phases: InspectorTimingPhasesUi,
    colors: DevConsoleColors,
): List<InspectorProgressStat> {
    val present =
        listOfNotNull(
            phases.dnsMs?.let { "DNS" to it },
            phases.connectMs?.let { "Connect" to it },
            phases.tlsMs?.let { "TLS handshake" to it },
            phases.sendMs?.let { "Send" to it },
            phases.waitMs?.let { "Waiting (TTFB)" to it },
            phases.receiveMs?.let { "Receive" to it },
        )
    val colorsByLabel =
        mapOf(
            "DNS" to colors.borderStrong,
            "Connect" to colors.muted,
            "TLS handshake" to colors.put,
            "Send" to colors.text3,
            "Waiting (TTFB)" to colors.signal,
            "Receive" to colors.warn,
        )
    val total = present.sumOf { (_, ms) -> ms }
    return present.map { (label, ms) ->
        InspectorProgressStat(
            label = label,
            valueText = "$ms ms",
            fraction = if (total > 0) ms.toFloat() / total.toFloat() else 0f,
            color = colorsByLabel.getValue(label),
        )
    }
}

/**
 * Per-phase bars when the transport captured [InspectorTransactionUi.timingPhases]; otherwise the
 * total-duration text this section has always shown -- a transaction with no phase data at all
 * (most transports, or a timed-out request) must never be presented as a zero-length breakdown.
 */
private fun netTimingSection(
    transaction: InspectorTransactionUi,
    colors: DevConsoleColors,
    copyText: (String) -> Unit,
): InspectorDetailSectionSpec {
    val duration = transaction.durationMs
    val phaseStats = timingPhaseStats(transaction.timingPhases, colors)
    val body =
        when {
            phaseStats.isNotEmpty() -> InspectorDetailSectionBody.Bars(phaseStats)
            transaction.statusCode == null && duration != null ->
                InspectorDetailSectionBody.Empty("Timed out after $duration ms with no response.")
            transaction.statusCode == null ->
                InspectorDetailSectionBody.Empty("Timed out — no response received.")
            duration != null ->
                InspectorDetailSectionBody.Empty(
                    "Per-phase timing is not captured on device. Total duration: $duration ms.",
                )
            else -> InspectorDetailSectionBody.Empty("Per-phase timing is not captured on device.")
        }
    val onCopy =
        phaseStats.takeIf { it.isNotEmpty() }?.let { stats ->
            { copyText(stats.joinToString("\n") { "${it.label}: ${it.valueText}" }) }
        }
    return InspectorDetailSectionSpec(
        "timing",
        "Timing",
        body,
        copyDescription = "Copy timing breakdown",
        onCopy = onCopy,
    )
}

/** Real redaction flags only: a value equal to the SDK's redaction marker (see [looksRedacted]), never a guess. */
private fun netRedactionsSection(
    transaction: InspectorTransactionUi,
    colors: DevConsoleColors,
    copyText: (String) -> Unit,
): InspectorDetailSectionSpec {
    val redacted =
        buildList {
            transaction.requestHeaders.forEach { (key, value) ->
                if (value.looksRedacted()) add(InspectorKeyValue("$key (request)", "masked on device", colors.warn))
            }
            transaction.responseHeaders.forEach { (key, value) ->
                if (value.looksRedacted()) add(InspectorKeyValue("$key (response)", "masked on device", colors.warn))
            }
        }
    val body =
        if (redacted.isEmpty()) {
            InspectorDetailSectionBody.Empty("No redacted fields detected in this capture.")
        } else {
            InspectorDetailSectionBody.KeyValues(redacted)
        }
    return InspectorDetailSectionSpec(
        "redact",
        "Redactions",
        body,
        copyDescription = "Copy redactions",
        onCopy = keyValuesCopyAction(redacted, copyText),
    )
}

/** "N fields differ from original" per the contract, or null when there's nothing to add (no snapshot, or N is 0). */
private fun mockDiffNoticeSuffix(mockDiff: JsonMockDiffResult?): String? {
    val count = mockDiff?.totalCount?.takeIf { it > 0 } ?: return null
    val plural = if (count == 1) "field differs" else "fields differ"
    return " · $count $plural from original"
}

private fun netGeneralEntries(
    transaction: InspectorTransactionUi,
    colors: DevConsoleColors,
    statusColor: Color,
    mockDiff: JsonMockDiffResult?,
): List<InspectorKeyValue> =
    buildList {
        val url = transaction.url.ifBlank { "https://${transaction.host}${transaction.path}" }
        add(InspectorKeyValue("url", url))
        add(InspectorKeyValue("method", transaction.method))
        add(InspectorKeyValue("status", transaction.statusCode?.toString() ?: "no response received", statusColor))
        if (transaction.isMocked) {
            val ruleId = transaction.mockRuleId ?: "unknown"
            val diffSuffix = mockDiffNoticeSuffix(mockDiff).orEmpty()
            add(InspectorKeyValue("source", "mock rule $ruleId$diffSuffix", colors.put))
        }
        transaction.error?.let { add(InspectorKeyValue("error", it, colors.error)) }
    }

/** The request payload + response body sections, in that order -- see [textPreviewSection]. */
private fun netBodySections(
    transaction: InspectorTransactionUi,
    colors: DevConsoleColors,
    copyText: (String) -> Unit,
    mockDiff: JsonMockDiffResult?,
): List<InspectorDetailSectionSpec> {
    val requestBinary = transaction.requestBodyKind == InspectorBodyKind.BINARY
    val requestEmptyText = "${transaction.method} request — no body sent."
    val responseEmptyText =
        if (transaction.statusCode == null) {
            "No response — the request failed first."
        } else {
            "No response body captured."
        }
    return listOf(
        textPreviewSection(
            "req",
            "Request payload",
            transaction.requestPreview,
            requestBinary,
            requestEmptyText,
            colors,
            copyText,
            "Copy request payload",
        ),
        textPreviewSection(
            "res",
            "Response body",
            transaction.responsePreview,
            isBinaryPlaceholder = false,
            emptyText = responseEmptyText,
            colors = colors,
            copyText = copyText,
            copyDescription = "Copy response body",
            // Only the response body can differ from a mock rule's sourceBodySnapshot -- the diff
            // exists to compare what a mock *served* against what the transaction it was created
            // from originally returned (contract: "response-body JSON viewer").
            jsonHighlightPaths = mockDiff?.highlightedPaths ?: emptySet(),
        ),
    )
}

private fun netSections(
    transaction: InspectorTransactionUi,
    colors: DevConsoleColors,
    statusColor: Color,
    copyText: (String) -> Unit,
    mockDiff: JsonMockDiffResult?,
): List<InspectorDetailSectionSpec> {
    val generalEntries = netGeneralEntries(transaction, colors, statusColor, mockDiff)
    val (requestSection, responseSection) = netBodySections(transaction, colors, copyText, mockDiff)
    val requestHeadersBody = headerRowsBody(transaction.requestHeaders, colors)
    val responseHeadersBody = headerRowsBody(transaction.responseHeaders, colors)
    return listOf(
        InspectorDetailSectionSpec(
            "general",
            "General",
            InspectorDetailSectionBody.KeyValues(generalEntries),
            copyDescription = "Copy general info",
            onCopy = { copyText(generalEntries.toCopyText()) },
        ),
        InspectorDetailSectionSpec(
            "reqh",
            "Request headers",
            requestHeadersBody,
            copyDescription = "Copy request headers",
            onCopy = keyValuesCopyAction(requestHeadersBody.entries, copyText),
        ),
        requestSection,
        InspectorDetailSectionSpec(
            "resh",
            "Response headers",
            responseHeadersBody,
            copyDescription = "Copy response headers",
            onCopy = keyValuesCopyAction(responseHeadersBody.entries, copyText),
        ),
        responseSection,
        netTimingSection(transaction, colors, copyText),
        netRedactionsSection(transaction, colors, copyText),
    )
}

private fun netFooterActions(
    transaction: InspectorTransactionUi,
    colors: DevConsoleColors,
    isFlagged: Boolean,
    onToggleFlag: () -> Unit,
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
            label = "cURL",
            onClick = { copyText(transaction.toCurlCommand()) },
            weight = 1f,
            icon = {
                InspectorGlyphIcon(InspectorGlyph.Copy, contentDescription = null, tint = colors.ink, size = 18.dp)
            },
            containerColor = colors.surface3,
            contentColor = colors.ink,
        ),
    )

/**
 * Builds the full net (HTTP transaction) capture detail, degraded to real fields only. [mockDiff] is
 * the caller's already-computed (and `remember`-cached) structural diff of this transaction's
 * response body against its serving rule's `sourceBodySnapshot`, or null when the transaction isn't
 * mocked, its rule carries no snapshot, or either body failed to parse as JSON -- see
 * [computeJsonMockDiff].
 */
@Suppress("LongParameterList") // Every field here is independently needed to render the transaction detail.
internal fun netDetailContent(
    transaction: InspectorTransactionUi,
    colors: DevConsoleColors,
    isFlagged: Boolean,
    onToggleFlag: () -> Unit,
    copyText: (String) -> Unit,
    shareText: (String, String) -> Unit,
    onMockThisResponse: () -> Unit,
    onUnmockThisResponse: (String) -> Unit,
    mockDiff: JsonMockDiffResult? = null,
): ObserveDetailContent {
    // Prefilling a *new* mock rule from a response that's already mocked is pointless, so a mocked
    // transaction gets an "Unmock" action (disables the serving rule) instead -- unless its rule id
    // is missing, in which case there's nothing to unmock and the action is dropped.
    val onMockAction: (() -> Unit)? =
        if (transaction.isMocked) {
            transaction.mockRuleId?.let { ruleId -> { onUnmockThisResponse(ruleId) } }
        } else {
            onMockThisResponse
        }
    val (leadColor, leadBg) = methodTint(transaction.method, colors)
    val statusColor = statusTint(transaction.statusCode, colors)
    val timingSubtitle =
        when {
            transaction.statusCode == null -> "timeout"
            transaction.durationMs != null -> "${transaction.durationMs} ms"
            else -> transaction.host
        }
    // Promoted to the header rather than requiring "General" to be expanded to notice a capture was
    // mocked -- parity with the web dashboard's header-level "MOCK RULE …".
    val subtitle =
        if (transaction.isMocked) {
            "$timingSubtitle · MOCK RULE ${transaction.mockRuleId ?: "unknown"}"
        } else {
            timingSubtitle
        }
    return ObserveDetailContent(
        header =
            InspectorObserveDetailHeaderSpec(
                kindLabel = "HTTP transaction",
                leadText = methodLeadText(transaction.method),
                leadColor = leadColor,
                leadContainerColor = leadBg,
                title = transaction.host + transaction.path,
                subtitle = subtitle,
                status = transaction.statusCode?.toString() ?: "ERR",
                statusColor = statusColor,
                actions = netHeaderActions(transaction, colors, copyText, shareText, onMockAction),
            ),
        sections = netSections(transaction, colors, statusColor, copyText, mockDiff),
        // Request payload + Response body both open by default --
        // an operator debugging a transaction usually needs to compare both sides at a glance.
        initiallyOpenSectionKeys = setOf("req", "res"),
        footerActions = netFooterActions(transaction, colors, isFlagged, onToggleFlag, copyText),
    )
}
