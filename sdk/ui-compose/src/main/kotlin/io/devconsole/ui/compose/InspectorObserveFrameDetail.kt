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
        "TEXT" -> "0x1 text"
        "BINARY" -> "0x2 binary"
        "PING", "PONG" -> "control ($frameType)"
        else -> frameType.lowercase()
    }

private fun frameToJsonSnippet(
    socket: InspectorSocketUi,
    frame: InspectorSocketFrameUi,
): String =
    buildString {
        append("{\n")
        append("  \"direction\": ").append(frame.direction.jsonQuoted()).append(",\n")
        append("  \"frameType\": ").append(frame.frameType.jsonQuoted()).append(",\n")
        append("  \"preview\": ").append(frame.preview.jsonQuotedOrNull()).append(",\n")
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
    val leadColor = if (frame.direction == "RECEIVED") colors.put else colors.signal
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
            InspectorKeyValue("state", socket.state, if (socket.state == "OPEN") colors.signal else colors.muted),
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
        ),
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
            label = "Copy payload",
            onClick = { copyText(frame.preview.orEmpty()) },
            weight = 2f,
            icon = {
                val tint = colors.signalInk
                InspectorGlyphIcon(InspectorGlyph.Copy, contentDescription = null, tint = tint, size = 18.dp)
            },
        ),
        InspectorFooterAction(
            label = "JSONL",
            onClick = {
                val jsonl = socket.frames.joinToString("\n") { frameToJsonSnippet(socket, it) }
                shareText(jsonl, "Share frame log as JSONL")
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
    val leadColor = if (received) colors.put else colors.signal
    val leadBg = if (received) colors.putSoft else colors.signalSoft
    val sizeLabel = socketFrameSizeLabel(frame)
    val frameJson = frameToJsonSnippet(socket, frame)
    val isMqtt = socket.protocol == "mqtt"
    // Mirrors SocketFrameRow's title preference: an MQTT frame's topic is the meaningful identifier,
    // not the broker URL every message on the connection shares.
    val title = frame.topic ?: socket.url.removePrefix("wss://").removePrefix("ws://")
    return ObserveDetailContent(
        header =
            InspectorObserveDetailHeaderSpec(
                kindLabel = if (isMqtt) "MQTT message" else "WebSocket frame",
                leadText = if (received) "↓" else "↑",
                leadColor = leadColor,
                leadContainerColor = leadBg,
                title = title,
                subtitle = "${formatCaptureClockTime(frame.timestampEpochMs)} · ${frame.frameType} · $sizeLabel",
                status = sizeLabel,
                statusColor = colors.ink,
                actions =
                    listOf(
                        InspectorTopAction(
                            contentDescription = "Copy frame payload",
                            onClick = { copyText(frame.preview.orEmpty()) },
                            icon = copyIconAction(colors.muted),
                        ),
                        InspectorTopAction(
                            contentDescription = "Share frame as JSON",
                            onClick = { shareText(frameJson, "Share frame JSON") },
                            icon = shareIconAction(colors.muted),
                        ),
                    ),
            ),
        sections = frameSections(socket, frame, colors, sizeLabel, copyText),
        initiallyOpenSectionKeys = setOf(InspectorExchangeSection.PRIMARY_BODY.key),
        footerActions = frameFooterActions(socket, frame, colors, copyText, shareText),
    )
}
