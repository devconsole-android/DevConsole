/**
 * @author Md. Asaduzzaman Nur
 * @since 13/08/26
 */
package io.devconsole.ui.compose

import java.util.Locale

/**
 * Detail for one Remote Config key, opened by tapping its row on the Config tab.
 *
 * The Value section is an [InspectorDetailSectionBody.Formattable], which is the whole reason this
 * screen is worth opening: [formattableTextBody] pretty-prints the value into the collapsible JSON
 * tree when it genuinely parses, keeps the exact original text behind the Raw toggle, and -- since
 * a Remote Config value is a plain string on the wire, very often `on` or `v2` rather than JSON --
 * falls back to raw with no toggle at all when it does not. Nothing here ever re-quotes a
 * non-JSON value to make it parse: the quotes would be this screen's invention rather than
 * something the server sent, which is the same rule the source badge follows.
 */
internal fun remoteConfigDetailContent(
    provider: InspectorRemoteConfigUi,
    entry: InspectorRemoteConfigEntryUi,
    colors: DevConsoleColors,
    copyText: (String) -> Unit,
    shareText: (String, String) -> Unit,
): ObserveDetailContent {
    // A redacted entry carries no value to format, so it gets an explicit note instead of a
    // Raw/Formatted toggle over the literal placeholder -- the same treatment a binary body gets.
    val valueBody =
        if (entry.redacted) {
            InspectorDetailSectionBody.Empty("Value withheld by the redaction policy.")
        } else {
            formattableTextBody(entry.value, colors)
        }
    val detailEntries = remoteConfigDetailEntries(provider, entry, colors)
    return ObserveDetailContent(
        header = remoteConfigDetailHeader(provider, entry, colors, copyText, shareText),
        sections =
            listOf(
                InspectorDetailSectionSpec(
                    "value",
                    "Value",
                    valueBody,
                    copyDescription = "Copy value",
                    onCopy = if (entry.redacted) null else ({ copyText(entry.value) }),
                ),
                InspectorDetailSectionSpec(
                    "details",
                    "Details",
                    InspectorDetailSectionBody.KeyValues(detailEntries),
                    copyDescription = "Copy details",
                    onCopy = { copyText(detailEntries.toCopyText()) },
                ),
            ),
        // The value is what you opened this for; Details is one tap away when the value is not the
        // answer -- the same open/closed split every other capture detail uses for body vs general.
        initiallyOpenSectionKeys = setOf("value"),
        // Read-only surface: there is no override-from-device action to put here yet.
        footerActions = emptyList(),
        searchPlaceholder = "Find in this value",
    )
}

private fun remoteConfigDetailHeader(
    provider: InspectorRemoteConfigUi,
    entry: InspectorRemoteConfigEntryUi,
    colors: DevConsoleColors,
    copyText: (String) -> Unit,
    shareText: (String, String) -> Unit,
) = InspectorObserveDetailHeaderSpec(
    kindLabel = "Remote Config",
    leadText = remoteConfigLeadText(entry.source),
    leadColor = remoteConfigLeadColor(entry.source, colors),
    leadContainerColor = remoteConfigLeadContainerColor(entry.source, colors),
    title = entry.key,
    subtitle = "${provider.id} · ${remoteConfigFetchLine(provider)}",
    status = entry.source.uppercase(Locale.US),
    statusColor = if (entry.source == "remote") colors.signal else colors.muted,
    actions =
        listOf(
            InspectorTopAction(
                contentDescription = "Copy value",
                onClick = { copyText(entry.value) },
                icon = copyIconAction(colors.muted),
            ),
            InspectorTopAction(
                contentDescription = "Share value",
                onClick = { shareText(entry.value, "Share ${entry.key}") },
                icon = shareIconAction(colors.muted),
            ),
        ),
)

/** The provider's fetch state travels with the key: it is what tells a `default` apart from a bug. */
private fun remoteConfigDetailEntries(
    provider: InspectorRemoteConfigUi,
    entry: InspectorRemoteConfigEntryUi,
    colors: DevConsoleColors,
): List<InspectorKeyValue> =
    buildList {
        add(InspectorKeyValue("key", entry.key))
        add(InspectorKeyValue("source", entry.source, if (entry.source == "remote") null else colors.muted))
        add(InspectorKeyValue("provider", provider.id))
        add(InspectorKeyValue("last fetch", provider.lastFetchEpochMs?.let(::formatCaptureClockTime) ?: "never"))
        add(InspectorKeyValue("fetch status", provider.status.replace('_', ' ')))
        provider.minimumFetchIntervalSeconds?.let { add(InspectorKeyValue("min fetch interval", "${it}s")) }
        // Both are called out only when true: a "truncated: false" row on every key would be noise,
        // while a silently cut value is the thing that wastes an afternoon.
        if (entry.truncated) {
            add(InspectorKeyValue("truncated", "value was cut short on capture", colors.warn))
        }
        if (entry.redacted) {
            add(InspectorKeyValue("redacted", "withheld by the redaction policy", colors.warn))
        }
    }
