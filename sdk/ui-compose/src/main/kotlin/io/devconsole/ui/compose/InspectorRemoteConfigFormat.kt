/**
 * @author Md. Asaduzzaman Nur
 * @since 13/08/26
 */
@file:Suppress("MagicNumber")

package io.devconsole.ui.compose

import java.util.Locale

// Pure presentation logic for Remote Config, shared by the Config tab's rows and the key detail
// they open. Kept out of both composable files so a source badge or a fetch line can never read one
// way in the list and another way on the screen the list opens.

/**
 * The row's and the detail header's shared lead badge. Colour carries the same meaning as the flag
 * rows on Control: signal = came from the server, warn = a local override is masking it, muted =
 * never reached the server at all. Shared so a source can never read one way in the list and
 * another way on the screen the list opens.
 */
internal fun remoteConfigLeadText(source: String): String = source.take(SOURCE_BADGE_CHARS).uppercase(Locale.US)

internal fun remoteConfigLeadColor(
    source: String,
    colors: DevConsoleColors,
) = when (source) {
    "remote" -> colors.signal
    "override" -> colors.warn
    else -> colors.text3
}

internal fun remoteConfigLeadContainerColor(
    source: String,
    colors: DevConsoleColors,
) = when (source) {
    "remote" -> colors.signalSoft
    "override" -> colors.warnSoft
    else -> colors.surface3
}

/** `REM`/`DEF`/`OVE`/`STA`/`UNK` — the width every other lead badge in the console uses. */
private const val SOURCE_BADGE_CHARS = 3

/** "Never fetched" is spelled out rather than shown as an epoch date. */
internal fun remoteConfigFetchLine(provider: InspectorRemoteConfigUi): String =
    buildString {
        append("last fetch: ")
        append(
            provider.lastFetchEpochMs?.let(::formatCaptureClockTime) ?: "never",
        )
        append(" · ")
        append(provider.status.replace('_', ' '))
        provider.minimumFetchIntervalSeconds?.let { append(" · min interval ${it}s") }
    }

/** Value as well as key: finding which key holds a given variant is as common as recalling its name. */
internal fun InspectorRemoteConfigEntryUi.matches(query: String): Boolean =
    query.isEmpty() ||
        key.lowercase(Locale.US).contains(query) ||
        (!redacted && value.lowercase(Locale.US).contains(query))

/** Hand-built fixture shared by the Observe preview and the detail preview. */
internal fun remoteConfigPreviewProviders(): List<InspectorRemoteConfigUi> =
    listOf(
        InspectorRemoteConfigUi(
            id = "firebase",
            entries =
                listOf(
                    InspectorRemoteConfigEntryUi(
                        key = "checkout_v2",
                        value = """{"enabled":true,"variant":"b","rollout":0.25}""",
                        source = "remote",
                    ),
                    InspectorRemoteConfigEntryUi(key = "banner_copy", value = "Free delivery today", source = "remote"),
                    InspectorRemoteConfigEntryUi(key = "retry_budget", value = "3", source = "default"),
                    InspectorRemoteConfigEntryUi(key = "api_key", value = "", source = "remote", redacted = true),
                ),
            lastFetchEpochMs = 1_760_000_000_000,
            status = "success",
            minimumFetchIntervalSeconds = 3600,
        ),
    )
