/**
 * @author Shakib
 * @since 04/08/26
 */
package io.devconsole.ui.compose

import androidx.compose.ui.unit.dp

/**
 * A [InspectorSocketFrameUi]'s size, for the socket signals row and this detail's General/header
 * fields. [InspectorSocketFrameUi.byteLength] is the true full frame length for a BINARY frame
 * (captured before any preview truncation), so that case is always shown as a measured size. For a
 * truncated TEXT frame, [InspectorSocketFrameUi.byteLength] only describes the captured preview, not
 * the original message, so it is labelled "preview" instead of implying a measured size. Falls back to
 * the preview's character count when no adapter has populated [InspectorSocketFrameUi.byteLength] yet.
 */
internal fun socketFrameSizeLabel(frame: InspectorSocketFrameUi): String {
    val byteLength = frame.byteLength ?: return "${frame.preview?.length ?: 0} ch"
    val formatted = formatByteSize(byteLength)
    val isPreviewOnlySize = frame.frameType == "TEXT" && frame.truncated
    return if (isPreviewOnlySize) "$formatted preview" else formatted
}

/** "MQTT" for an MQTT-protocol connection, "WebSocket" otherwise. */
private fun InspectorSocketUi.protocolLabel(): String = if (protocol == "mqtt") "MQTT" else "WebSocket"

private fun frameOpcodeLabel(frameType: String): String =
    when (frameType) {
        "TEXT" -> "Text"
        "BINARY" -> "Binary"
        "PING" -> "Ping"
        "PONG" -> "Pong"
        "CLOSE" -> "Close"
        else -> frameType.replaceFirstChar { it.titlecase() }
    }

private fun frameToJsonSnippet(
    socket: InspectorSocketUi,
    frame: InspectorSocketFrameUi,
): String =
    buildString {
        append("{\n")
        append("  \"direction\": ").append(frame.direction.jsonQuoted()).append(",\n")
        append("  \"frameType\": ").append(frame.frameType.jsonQuoted()).append(",\n")
        append("  \"preview\": ").append(frame.preview.jsonEmbeddedOrQuoted()).append(",\n")
        append("  \"timestampEpochMs\": ").append(frame.timestampEpochMs.toString()).append(",\n")
        append("  \"socketUrl\": ").append(socket.url.jsonQuoted()).append('\n')
        append('}')
    }

private fun frameSections(
    socket: InspectorSocketUi,
    frame: InspectorSocketFrameUi,
    colors: DevConsoleColors,
    sizeLabel: String,
    copyText: (String) -> Unit,
): List<InspectorDetailSectionSpec> {
    val leadColor = colors.ink
    val generalEntries =
        buildList {
            add(InspectorKeyValue("direction", frame.direction, leadColor))
            add(InspectorKeyValue("opcode", frameOpcodeLabel(frame.frameType)))
            // MQTT-only fields -- omitted entirely for a plain WebSocket frame rather than shown as
            // an empty "topic —" row, since [InspectorSocketFrameUi.topic]/[qos] are only ever
            // populated by an MQTT-protocol connection.
            frame.topic?.let { add(InspectorKeyValue("topic", it)) }
            frame.qos?.let { add(InspectorKeyValue("qos", it.toString())) }
            add(InspectorKeyValue("size", sizeLabel))
            add(InspectorKeyValue("at", formatCaptureClockTime(frame.timestampEpochMs)))
        }
    val connectionEntries =
        listOf(
            InspectorKeyValue("protocol", socket.protocolLabel()),
            InspectorKeyValue("url", socket.url),
            InspectorKeyValue("state", socket.state),
            InspectorKeyValue("opened", formatCaptureClockTime(socket.openedAtEpochMs)),
            InspectorKeyValue("frames", "${socket.sentCount} sent · ${socket.receivedCount} received"),
        )
    return listOf(
        InspectorDetailSectionSpec(
            "general",
            "General",
            InspectorDetailSectionBody.KeyValues(generalEntries),
            copyDescription = "Copy general info",
            onCopy = { copyText(generalEntries.toCopyText()) },
        ),
        textPreviewSection(
            key = InspectorExchangeSection.PRIMARY_BODY.key,
            label = "Payload",
            preview = frame.preview,
            isBinaryPlaceholder = false,
            emptyText = "No payload preview captured.",
            colors = colors,
            copyText = copyText,
            copyDescription = "Copy payload",
        ).copy(tab = InspectorDetailTab.PAYLOAD),
        InspectorDetailSectionSpec(
            InspectorExchangeSection.SECONDARY_HEADERS.key,
            "Connection",
            InspectorDetailSectionBody.KeyValues(connectionEntries),
            copyDescription = "Copy connection info",
            onCopy = { copyText(connectionEntries.toCopyText()) },
        ),
    )
}

private fun frameFooterActions(
    socket: InspectorSocketUi,
    frame: InspectorSocketFrameUi,
    colors: DevConsoleColors,
    copyText: (String) -> Unit,
    shareText: (String, String) -> Unit,
): List<InspectorFooterAction> =
    listOf(
        InspectorFooterAction(
            label = "Copy message",
            onClick = { copyText(frame.preview.orEmpty()) },
            weight = 2f,
            icon = {
                val tint = colors.signalInk
                InspectorGlyphIcon(InspectorGlyph.Copy, contentDescription = null, tint = tint, size = 18.dp)
            },
        ),
        InspectorFooterAction(
            label = "Share all messages",
            onClick = {
                val jsonl = socket.frames.joinToString("\n") { frameToJsonSnippet(socket, it) }
                shareText(jsonl, "Share all messages")
            },
            weight = 1f,
            icon = {
                ObserveGlyphIcon(ObserveGlyph.Download, contentDescription = null, tint = colors.ink, size = 18.dp)
            },
            containerColor = colors.surface3,
            contentColor = colors.ink,
        ),
    )

/** Builds the WebSocket frame capture detail, degraded to real fields only. */
internal fun frameDetailContent(
    socket: InspectorSocketUi,
    frame: InspectorSocketFrameUi,
    colors: DevConsoleColors,
    copyText: (String) -> Unit,
    shareText: (String, String) -> Unit,
): ObserveDetailContent {
    val received = frame.direction == "RECEIVED"
    val leadColor = colors.ink
    val leadBg = if (received) colors.putSoft else colors.signalSoft
    val sizeLabel = socketFrameSizeLabel(frame)
    val frameJson = frameToJsonSnippet(socket, frame)
    val isMqtt = socket.protocol == "mqtt"
    val title = frame.topic ?: socket.url.substringAfter("://")
    return ObserveDetailContent(
        header =
            InspectorObserveDetailHeaderSpec(
                kindLabel = if (isMqtt) "MQTT message" else "WebSocket message",
                leadText = if (received) "↓" else "↑",
                leadColor = leadColor,
                leadContainerColor = leadBg,
                title = title,
                subtitle =
                    listOfNotNull(
                        if (received) "Received" else "Sent",
                        frameOpcodeLabel(frame.frameType).takeUnless { frame.frameType == "TEXT" },
                        sizeLabel,
                        formatCaptureClockTime(frame.timestampEpochMs),
                    ).joinToString(" · "),
                status = "",
                statusColor = colors.ink,
                actions =
                    listOf(
                        InspectorTopAction(
                            contentDescription = "Share message as JSON",
                            onClick = { shareText(frameJson, "Share message JSON") },
                            icon = shareIconAction(colors.muted),
                        ),
                    ),
            ),
        sections =
            frameSections(socket, frame, colors, sizeLabel, copyText)
                .map { it.copy(secondary = it.tab != InspectorDetailTab.PAYLOAD) },
        initiallyOpenSectionKeys =
            setOf("general", InspectorExchangeSection.PRIMARY_BODY.key, InspectorExchangeSection.SECONDARY_HEADERS.key),
        footerActions = frameFooterActions(socket, frame, colors, copyText, shareText),
        singlePage = true,
    )
}
