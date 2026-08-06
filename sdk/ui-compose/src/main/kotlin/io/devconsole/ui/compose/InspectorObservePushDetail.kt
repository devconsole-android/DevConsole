/**
 * @author Shakib
 * @since 04/08/26
 */
package io.devconsole.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Locale

private fun pushToJsonSnippet(push: InspectorPushUi): String =
    buildString {
        append("{\n")
        append("  \"provider\": ").append(push.provider.jsonQuoted()).append(",\n")
        append("  \"messageId\": ").append(push.messageId.jsonQuotedOrNull()).append(",\n")
        append("  \"lifecycle\": ").append(push.lifecycle.jsonQuoted()).append(",\n")
        append("  \"simulated\": ").append(push.simulated.toString()).append(",\n")
        append("  \"receivedAtEpochMs\": ").append(push.receivedAtEpochMs.toString()).append(",\n")
        append("  \"data\": ").append(push.dataPreview.jsonObjectSnippet()).append('\n')
        append('}')
    }

private fun pushSections(
    push: InspectorPushUi,
    colors: DevConsoleColors,
    copyText: (String) -> Unit,
): List<InspectorDetailSectionSpec> {
    val generalEntries =
        buildList {
            add(InspectorKeyValue("provider", push.provider))
            push.messageId?.let { add(InspectorKeyValue("messageId", it)) }
            add(InspectorKeyValue("lifecycle", push.lifecycle))
            val origin = if (push.simulated) "simulated from the dashboard" else "captured from the platform"
            add(InspectorKeyValue("origin", origin, if (push.simulated) colors.warn else null))
        }
    val payloadEntries =
        push.dataPreview.entries
            .map { (key, value) -> InspectorKeyValue(key, value, if (value.looksRedacted()) colors.warn else null) }
            .takeIf { it.isNotEmpty() }
    val payloadBody =
        payloadEntries?.let { InspectorDetailSectionBody.KeyValues(it) }
            ?: InspectorDetailSectionBody.Empty("No payload captured.")
    val lifecycleEntries =
        listOf(
            InspectorKeyValue("received", formatCaptureClockTime(push.receivedAtEpochMs)),
            InspectorKeyValue("current stage", push.lifecycle),
        )
    return listOf(
        InspectorDetailSectionSpec(
            "general",
            "General",
            InspectorDetailSectionBody.KeyValues(generalEntries),
            copyDescription = "Copy general info",
            onCopy = { copyText(generalEntries.toCopyText()) },
        ),
        InspectorDetailSectionSpec(
            "req",
            "Payload",
            payloadBody,
            copyDescription = "Copy payload",
            onCopy = keyValuesCopyAction(payloadEntries.orEmpty(), copyText),
        ),
        InspectorDetailSectionSpec(
            "resh",
            "Lifecycle",
            InspectorDetailSectionBody.KeyValues(lifecycleEntries),
            copyDescription = "Copy lifecycle info",
            onCopy = { copyText(lifecycleEntries.toCopyText()) },
        ),
    )
}

private fun pushFooterActions(
    colors: DevConsoleColors,
    payloadText: String,
    copyText: (String) -> Unit,
): List<InspectorFooterAction> =
    listOf(
        InspectorFooterAction(
            // No push-replay API exists in InspectorDataSource at all -- this button can never
            // succeed regardless of capability, so it's demoted to a disabled, outlined affordance.
            label = "Replay to device",
            onClick = {},
            enabled = false,
            outlined = true,
            supportingText = "Replay is not available yet",
            weight = 2f,
            icon = {
                ObserveGlyphIcon(ObserveGlyph.Refresh, contentDescription = null, tint = colors.muted, size = 18.dp)
            },
            containerColor = Color.Transparent,
            contentColor = colors.muted,
        ),
        InspectorFooterAction(
            label = "Copy",
            onClick = { copyText(payloadText) },
            weight = 1f,
            icon = {
                InspectorGlyphIcon(InspectorGlyph.Copy, contentDescription = null, tint = colors.ink, size = 18.dp)
            },
            containerColor = colors.surface3,
            contentColor = colors.ink,
        ),
    )

/** Builds the push event capture detail, degraded to real fields only. */
@Suppress("LongParameterList") // Push, colors, copy, and share operations.
internal fun pushDetailContent(
    push: InspectorPushUi,
    colors: DevConsoleColors,
    copyText: (String) -> Unit,
    shareText: (String, String) -> Unit,
): ObserveDetailContent {
    val leadColor = if (push.simulated) colors.warn else colors.signal
    val leadBg = if (push.simulated) colors.warnSoft else colors.signalSoft
    val title = push.dataPreview["title"] ?: push.messageId ?: "Push notification"
    val pushJson = pushToJsonSnippet(push)
    val payloadText = push.dataPreview.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    val whenLabel = "${push.lifecycle.lowercase(Locale.US)} at ${formatCaptureClockTime(push.receivedAtEpochMs)}"
    return ObserveDetailContent(
        header =
            InspectorObserveDetailHeaderSpec(
                kindLabel = "Push event",
                leadText = push.provider.uppercase(Locale.US),
                leadColor = leadColor,
                leadContainerColor = leadBg,
                title = title,
                subtitle = "${push.messageId ?: "no id"} · $whenLabel",
                status = pushLifecycleShortLabel(push.lifecycle),
                statusColor = colors.muted,
                actions =
                    listOf(
                        InspectorTopAction(
                            contentDescription = "Copy push payload",
                            onClick = { copyText(payloadText) },
                            icon = copyIconAction(colors.muted),
                        ),
                        InspectorTopAction(
                            contentDescription = "Share push as JSON",
                            onClick = { shareText(pushJson, "Share push JSON") },
                            icon = shareIconAction(colors.muted),
                        ),
                    ),
            ),
        sections = pushSections(push, colors, copyText),
        initiallyOpenSectionKeys = setOf("req"),
        footerActions = pushFooterActions(colors, payloadText, copyText),
    )
}
