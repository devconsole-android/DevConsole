/**
 * @author Md. Asaduzzaman Nur
 * @since 13/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.devconsole.ui.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Remote Config tab: every provider's currently resolved keys, read-only.
 *
 * The source badge on each row, and the fetch line under each provider header, are the reason this
 * surface exists -- a key serving an in-app default because the last fetch was throttled looks
 * identical to a published one until you can see where it came from and when the fetch happened.
 * Moved here from a section on the Control screen: Remote Config is something you *observe*,
 * nothing on it can be changed from the device.
 *
 * Unlike every other Observe tab this one has no hero. The diagnostic here is per provider, not per
 * tab, so a single headline number would either restate the fetch line in the ordinary
 * one-provider case or average across providers and mislead.
 */
@Composable
internal fun RemoteConfigTabContent(
    state: InspectorState,
    ui: ObserveUiState,
    actions: ObserveActions,
) {
    val colors = DevConsoleTheme.colors
    if (state.remoteConfig.isEmpty()) {
        ObserveTabEmptyState("No Remote Config provider is registered for this session.")
        return
    }
    val query = ui.remoteConfigSearch.trim().lowercase(Locale.US)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = EvidenceFabScrollClearance),
    ) {
        stickyHeader {
            InspectorSearchBar(
                query = ui.remoteConfigSearch,
                onQueryChange = actions.onRemoteConfigSearchChange,
                placeholder = "Search key or value",
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
        state.remoteConfig.forEach { provider ->
            item(key = "rc-head-${provider.id}") { RemoteConfigHeader(provider, colors) }
            val shown = provider.entries.filter { it.matches(query) }
            when {
                provider.unavailableReason != null ->
                    item(key = "rc-unavailable-${provider.id}") {
                        WarnNote("Remote Config unavailable: ${provider.unavailableReason}")
                    }
                provider.entries.isEmpty() ->
                    item(key = "rc-empty-${provider.id}") { RemoteConfigEmptyNote(provider, colors) }
                shown.isEmpty() ->
                    item(key = "rc-nomatch-${provider.id}") { RemoteConfigNoMatchNote(colors) }
                else ->
                    items(shown, key = { "rc-${provider.id}-${it.key}" }) { entry ->
                        RemoteConfigRow(entry, colors) { actions.onOpenRemoteConfigDetail(provider.id, entry.key) }
                    }
            }
            item(key = "rc-space-${provider.id}") { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/**
 * Header for one Remote Config provider. The fetch line is the reason this screen exists: a value
 * serving an in-app default because the last fetch was throttled looks identical to a published one
 * until you can see when the fetch happened and how it ended.
 */
@Composable
private fun RemoteConfigHeader(
    provider: InspectorRemoteConfigUi,
    colors: DevConsoleColors,
) {
    GroupLabel("Remote Config · ${provider.id}")
    Text(
        text = remoteConfigFetchLine(provider),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        color = colors.text3,
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * The `NO_FETCH_YET` case, which is the most commonly misread state: an empty table alone reads as
 * "this app has no Remote Config", when the truth is usually that no fetch has completed yet.
 */
@Composable
private fun RemoteConfigEmptyNote(
    provider: InspectorRemoteConfigUi,
    colors: DevConsoleColors,
) {
    Text(
        text =
            if (provider.lastFetchEpochMs == null) {
                "No values — this provider has not completed a fetch yet."
            } else {
                "No values returned by the last fetch."
            },
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        color = colors.muted,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Distinct from [RemoteConfigEmptyNote]: the provider has values, the search just excluded them all. */
@Composable
private fun RemoteConfigNoMatchNote(colors: DevConsoleColors) {
    Text(
        text = "No key matches the current search.",
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        color = colors.muted,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun RemoteConfigRow(
    entry: InspectorRemoteConfigEntryUi,
    colors: DevConsoleColors,
    onClick: () -> Unit,
) {
    // Only ever a preview -- a config value is a string of any length, and the full text is one tap
    // away in the detail. `truncated` is the SDK's own capture cut, not this line's shortening, so
    // it stays called out rather than being conflated with the row simply running out of room.
    val sub =
        buildString {
            append(if (entry.redacted) "<redacted>" else entry.value)
            if (entry.truncated) append(" · truncated")
        }
    TonalListRow(
        leadText = remoteConfigLeadText(entry.source),
        leadColor = remoteConfigLeadColor(entry.source, colors),
        leadContainerColor = remoteConfigLeadContainerColor(entry.source, colors),
        title = entry.key,
        subtitle = sub,
        trailValue = entry.source,
        trailValueColor = if (entry.source == "remote") colors.signal else colors.muted,
        onClick = onClick,
    )
}
