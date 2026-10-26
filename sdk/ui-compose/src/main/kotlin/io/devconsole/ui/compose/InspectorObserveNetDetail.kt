/**
 * @author Shakib
 * @since 04/08/26
 */
package io.devconsole.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Evidence and sharing stay available as quiet header icons. */
private fun netHeaderActions(
    transaction: InspectorTransactionUi,
    colors: DevConsoleColors,
    shareText: (String, String) -> Unit,
    isFlagged: Boolean,
    onToggleFlag: () -> Unit,
): List<InspectorTopAction> =
    listOf(
        InspectorTopAction(
            contentDescription = if (isFlagged) "Flagged as evidence" else "Flag as evidence",
            onClick = onToggleFlag,
            icon = {
                val tint = if (isFlagged) colors.ink else colors.muted
                InspectorGlyphIcon(InspectorGlyph.Flag, contentDescription = null, tint = tint, size = 18.dp)
            },
        ),
        InspectorTopAction(
            contentDescription = "Share transaction as JSON",
            onClick = { shareText(transaction.toJsonSnippet(), "Share transaction JSON") },
            icon = shareIconAction(colors.muted),
        ),
    )

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
            phases.tlsMs?.let { "TLS" to it },
            phases.sendMs?.let { "Send" to it },
            phases.waitMs?.let { "Wait" to it },
            phases.receiveMs?.let { "Receive" to it },
        )
    val colorsByLabel =
        mapOf(
            "DNS" to colors.borderStrong,
            "Connect" to colors.muted,
            "TLS" to colors.text3,
            "Send" to colors.text3,
            "Wait" to colors.ink.copy(alpha = 0.55f),
            "Receive" to colors.borderStrong,
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
 * Per-phase bars when captured; the Timing tab shows a single empty line otherwise. The header
 * retains the total duration and response status even when no phase breakdown was captured.
 */
private fun netTimingSection(
    transaction: InspectorTransactionUi,
    colors: DevConsoleColors,
    copyText: (String) -> Unit,
): InspectorDetailSectionSpec {
    val phaseStats = timingPhaseStats(transaction.timingPhases, colors)
    val body =
        if (phaseStats.isNotEmpty()) {
            InspectorDetailSectionBody.Bars(phaseStats)
        } else {
            InspectorDetailSectionBody.Empty("No timing captured")
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
        tab = InspectorDetailTab.TIMING,
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
        val url = transaction.url.ifBlank { "https://${transaction.host}${transaction.pathWithQuery()}" }
        add(InspectorKeyValue("URL", url))
        add(InspectorKeyValue("Host", transaction.host))
        add(InspectorKeyValue("Method", transaction.method))
        add(InspectorKeyValue("Status", transaction.statusCode?.toString() ?: "no response received", statusColor))
        transaction.responseHeaders.entries
            .firstOrNull { it.key.equals("content-length", ignoreCase = true) }
            ?.value
            ?.toLongOrNull()
            ?.let { add(InspectorKeyValue("Size", formatByteSize(it))) }
        if (transaction.isMocked) {
            val ruleId = transaction.mockRuleId ?: "unknown"
            val diffSuffix = mockDiffNoticeSuffix(mockDiff).orEmpty()
            add(InspectorKeyValue("source", "mock rule $ruleId$diffSuffix", colors.warn))
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
        ).copy(tab = InspectorDetailTab.REQUEST),
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
        ).copy(tab = InspectorDetailTab.RESPONSE),
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
        responseSection,
        InspectorDetailSectionSpec(
            InspectorExchangeSection.SECONDARY_HEADERS.key,
            InspectorExchangeSection.SECONDARY_HEADERS.networkLabel,
            responseHeadersBody,
            copyDescription = "Copy response headers",
            onCopy = keyValuesCopyAction(responseHeadersBody.entries, copyText),
            tab = InspectorDetailTab.RESPONSE,
        ),
        requestSection,
        InspectorDetailSectionSpec(
            InspectorExchangeSection.PRIMARY_HEADERS.key,
            InspectorExchangeSection.PRIMARY_HEADERS.networkLabel,
            requestHeadersBody,
            copyDescription = "Copy request headers",
            onCopy = keyValuesCopyAction(requestHeadersBody.entries, copyText),
            tab = InspectorDetailTab.REQUEST,
        ),
        InspectorDetailSectionSpec(
            "general",
            "General",
            InspectorDetailSectionBody.KeyValues(generalEntries),
            copyDescription = "Copy general info",
            onCopy = { copyText(generalEntries.toCopyText()) },
        ),
        netRedactionsSection(transaction, colors, copyText),
        netTimingSection(transaction, colors, copyText),
    )
}

/** Mock/unmock and cURL retain their callbacks in the detail action panel. */
private fun netFooterActions(
    transaction: InspectorTransactionUi,
    colors: DevConsoleColors,
    onMockAction: (() -> Unit)?,
    copyText: (String) -> Unit,
): List<InspectorFooterAction> =
    buildList {
        if (onMockAction != null) {
            add(
                InspectorFooterAction(
                    label = if (transaction.isMocked) "Unmock this response" else "Mock this response",
                    onClick = onMockAction,
                    weight = 2f,
                    icon = {
                        val tint = if (transaction.isMocked) colors.signal else colors.signalInk
                        ObserveGlyphIcon(ObserveGlyph.Tag, contentDescription = null, tint = tint, size = 18.dp)
                    },
                    containerColor = if (transaction.isMocked) colors.signalSoft else colors.signal,
                    contentColor = if (transaction.isMocked) colors.signal else colors.signalInk,
                ),
            )
        }
        add(
            InspectorFooterAction(
                label = "Copy as cURL",
                onClick = { copyText(transaction.toCurlCommand()) },
                weight = 1f,
                icon = {
                    InspectorGlyphIcon(InspectorGlyph.Copy, contentDescription = null, tint = colors.ink, size = 18.dp)
                },
                containerColor = colors.surface3,
                contentColor = colors.ink,
            ),
        )
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
    val subtitle =
        buildList {
            httpStatusDescription(transaction.statusCode).takeIf { it.isNotEmpty() }?.let { add(it) }
            transaction.durationMs?.let { add("$it ms") }
            if (transaction.startedAtEpochMs > 0) add(formatCaptureClockTime(transaction.startedAtEpochMs))
        }.joinToString(" · ")
    val notices =
        buildList {
            if (transaction.isMocked) {
                add("Mock rule ${transaction.mockRuleId ?: "unknown"}${mockDiffNoticeSuffix(mockDiff).orEmpty()}")
            }
            val redactedCount =
                (transaction.requestHeaders.values + transaction.responseHeaders.values).count {
                    it.looksRedacted()
                }
            if (redactedCount > 0) add("$redactedCount header fields masked on device")
        }
    return ObserveDetailContent(
        header =
            InspectorObserveDetailHeaderSpec(
                kindLabel = "HTTP transaction",
                leadText = methodLeadText(transaction.method),
                leadColor = leadColor,
                leadContainerColor = leadBg,
                title = "${transaction.method} ${transaction.pathWithQuery()}",
                subtitle = subtitle,
                status = transaction.statusCode?.toString() ?: "ERR",
                statusColor = statusColor,
                actions = netHeaderActions(transaction, colors, shareText, isFlagged, onToggleFlag),
                notices = notices,
            ),
        sections = netSections(transaction, colors, statusColor, copyText, mockDiff),
        // Both bodies stay expanded when moving between Response and Request.
        initiallyOpenSectionKeys =
            InspectorExchangeSection.keysOf(InspectorExchangeSection.defaultSearchScope) +
                setOf("general", "timing"),
        footerActions = netFooterActions(transaction, colors, onMockAction, copyText),
        searchOptions = NetworkDetailSearchOptions,
    )
}
