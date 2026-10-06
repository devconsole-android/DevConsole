/**
 * @author Shakib
 * @since 04/08/26
 */
package io.devconsole.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
        NET_TIMING_KEY,
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
        NET_REDACTIONS_KEY,
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
        val url = transaction.url.ifBlank { "https://${transaction.host}${transaction.pathWithQuery()}" }
        add(InspectorKeyValue("url", url))
        add(InspectorKeyValue("method", transaction.method))
        add(InspectorKeyValue("status", transaction.statusCode?.toString() ?: "no response received", statusColor))
        transaction.durationMs?.let { add(InspectorKeyValue("duration", "$it ms")) }
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
            InspectorExchangeSection.PRIMARY_BODY.key,
            "Request payload",
            transaction.requestPreview,
            requestBinary,
            requestEmptyText,
            colors,
            copyText,
            "Copy request payload",
        ),
        textPreviewSection(
            InspectorExchangeSection.SECONDARY_BODY.key,
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
            NET_GENERAL_KEY,
            "General",
            InspectorDetailSectionBody.KeyValues(generalEntries),
            copyDescription = "Copy general info",
            onCopy = { copyText(generalEntries.toCopyText()) },
        ),
        InspectorDetailSectionSpec(
            InspectorExchangeSection.PRIMARY_HEADERS.key,
            InspectorExchangeSection.PRIMARY_HEADERS.networkLabel,
            requestHeadersBody,
            copyDescription = "Copy request headers",
            onCopy = keyValuesCopyAction(requestHeadersBody.entries, copyText),
        ),
        requestSection,
        InspectorDetailSectionSpec(
            InspectorExchangeSection.SECONDARY_HEADERS.key,
            InspectorExchangeSection.SECONDARY_HEADERS.networkLabel,
            responseHeadersBody,
            copyDescription = "Copy response headers",
            onCopy = keyValuesCopyAction(responseHeadersBody.entries, copyText),
        ),
        responseSection,
        netTimingSection(transaction, colors, copyText),
        netRedactionsSection(transaction, colors, copyText),
    )
}

/**
 * Every action on the transaction in one bottom bar: share and flag as icon buttons, cURL, and
 * mock-from-capture as the labelled primary on the right.
 *
 * The top bar used to carry share and flag while the footer carried mock and cURL. The tabbed
 * layout clears the top bar so the header is one row, and a single action row is easier to scan
 * than two half-rows at opposite ends of the screen. Mocking keeps the filled primary slot because
 * it is the one action that changes what the host app receives on its next call; flagging only
 * files the capture for a report.
 *
 * The flag still reads its armed state at a glance: signal on a signal-soft container once
 * flagged, and its content description says "Flagged as evidence" so TalkBack announces the state
 * rather than just "flag". An already-mocked transaction gets the same armed treatment on its
 * "Unmock response" button.
 *
 * "Mock response" is shown regardless of the mocks capability for an unmocked transaction -- Save
 * on the sheet it opens dispatches UpsertMockRule, which already gates and shows a blocked toast,
 * the same pattern the Control screen's own mock affordances use. [onMockAction] is `null` when
 * this transaction is already mocked but its rule id is unknown: a mock/unmock button with no rule
 * to act on would be dead chrome, so it is dropped rather than shown disabled.
 */
@Suppress("LongParameterList") // Each action needs its own callback and state.
private fun netFooterActions(
    transaction: InspectorTransactionUi,
    colors: DevConsoleColors,
    isFlagged: Boolean,
    onToggleFlag: () -> Unit,
    shareText: (String, String) -> Unit,
    onMockAction: (() -> Unit)?,
    copyText: (String) -> Unit,
): List<InspectorFooterAction> =
    buildList {
        add(
            InspectorFooterAction(
                label = "",
                contentDescription = "Share transaction as JSON",
                onClick = { shareText(transaction.toJsonSnippet(), "Share transaction JSON") },
                icon = shareIconAction(colors.ink),
                containerColor = colors.surface3,
                contentColor = colors.ink,
            ),
        )
        add(
            InspectorFooterAction(
                label = "",
                contentDescription = if (isFlagged) "Flagged as evidence" else "Flag as evidence",
                onClick = onToggleFlag,
                icon = {
                    val tint = if (isFlagged) colors.signal else colors.ink
                    InspectorGlyphIcon(InspectorGlyph.Flag, contentDescription = null, tint = tint, size = 18.dp)
                },
                containerColor = if (isFlagged) colors.signalSoft else colors.surface3,
                contentColor = if (isFlagged) colors.signal else colors.ink,
            ),
        )
        add(
            InspectorFooterAction(
                label = "cURL",
                onClick = { copyText(transaction.toCurlCommand()) },
                // Sized to its label so the mock pill takes the rest of the bar.
                weight = null,
                icon = {
                    InspectorGlyphIcon(InspectorGlyph.Copy, contentDescription = null, tint = colors.ink, size = 18.dp)
                },
                containerColor = colors.surface3,
                contentColor = colors.ink,
            ),
        )
        if (onMockAction != null) {
            add(
                InspectorFooterAction(
                    label = if (transaction.isMocked) "Unmock response" else "Mock response",
                    compactLabel = if (transaction.isMocked) "Unmock" else "Mock",
                    onClick = onMockAction,
                    weight = 1f,
                    icon = {
                        val tint = if (transaction.isMocked) colors.signal else colors.signalInk
                        ObserveGlyphIcon(ObserveGlyph.Tag, contentDescription = null, tint = tint, size = 18.dp)
                    },
                    containerColor = if (transaction.isMocked) colors.signalSoft else colors.signal,
                    contentColor = if (transaction.isMocked) colors.signal else colors.signalInk,
                ),
            )
        }
    }

/**
 * Overview / Request / Response. The overview holds the short reads (General, Timing, and
 * Redactions only when something was actually masked -- "nothing redacted" is not worth a panel),
 * while each side of the exchange gets Body and Headers chips so the body on screen has the full
 * height. A transaction that failed before any response opens on the overview, where General
 * carries its error; every other one opens on the response body, the thing most often inspected.
 */
private fun netDetailTabs(
    transaction: InspectorTransactionUi,
    sections: List<InspectorDetailSectionSpec>,
): InspectorDetailTabs {
    val redactions = sections.firstOrNull { it.key == NET_REDACTIONS_KEY }
    val overviewKeys =
        listOfNotNull(
            NET_GENERAL_KEY,
            NET_TIMING_KEY,
            redactions?.takeIf { it.body !is InspectorDetailSectionBody.Empty }?.key,
        )
    val tabs =
        listOf(
            InspectorDetailTabSpec(NET_OVERVIEW_TAB, "Overview", InspectorDetailTabLayout.Stacked(overviewKeys)),
            InspectorDetailTabSpec(
                NET_REQUEST_TAB,
                "Request",
                InspectorDetailTabLayout.Chips(
                    listOf(
                        InspectorDetailTabChip(InspectorExchangeSection.PRIMARY_BODY.key, "Body"),
                        InspectorDetailTabChip(InspectorExchangeSection.PRIMARY_HEADERS.key, "Headers"),
                    ),
                ),
            ),
            InspectorDetailTabSpec(
                NET_RESPONSE_TAB,
                "Response",
                InspectorDetailTabLayout.Chips(
                    listOf(
                        InspectorDetailTabChip(InspectorExchangeSection.SECONDARY_BODY.key, "Body"),
                        InspectorDetailTabChip(InspectorExchangeSection.SECONDARY_HEADERS.key, "Headers"),
                    ),
                ),
            ),
        )
    val initialTab = if (transaction.statusCode == null) NET_OVERVIEW_TAB else NET_RESPONSE_TAB
    return InspectorDetailTabs(tabs, initialTab)
}

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
    // Promoted to the header rather than requiring "General" to be opened to notice a capture was
    // mocked -- parity with the web dashboard's header-level "MOCK RULE …".
    val mockNote = if (transaction.isMocked) "MOCK RULE ${transaction.mockRuleId ?: "unknown"}" else null
    val sections = netSections(transaction, colors, statusColor, copyText, mockDiff)
    return ObserveDetailContent(
        header =
            InspectorObserveDetailHeaderSpec(
                kindLabel = "HTTP transaction",
                leadText = methodLeadText(transaction.method),
                leadColor = leadColor,
                leadContainerColor = leadBg,
                title = transaction.path,
                // The duration lives on the overview (General + Timing); the header keeps to identity.
                subtitle = "",
                status = transaction.statusCode?.toString() ?: "ERR",
                statusColor = statusColor,
                titleOverline = transaction.host,
                note = mockNote,
            ),
        sections = sections,
        // Request payload + Response body both open by default --
        // an operator debugging a transaction usually needs to compare both sides at a glance.
        initiallyOpenSectionKeys = InspectorExchangeSection.keysOf(InspectorExchangeSection.defaultSearchScope),
        footerActions =
            netFooterActions(transaction, colors, isFlagged, onToggleFlag, shareText, onMockAction, copyText),
        searchOptions = NetworkDetailSearchOptions,
        tabs = netDetailTabs(transaction, sections),
    )
}

private const val NET_GENERAL_KEY = "general"
private const val NET_TIMING_KEY = "timing"
private const val NET_REDACTIONS_KEY = "redact"
private const val NET_OVERVIEW_TAB = "overview"
private const val NET_REQUEST_TAB = "request"
private const val NET_RESPONSE_TAB = "response"
