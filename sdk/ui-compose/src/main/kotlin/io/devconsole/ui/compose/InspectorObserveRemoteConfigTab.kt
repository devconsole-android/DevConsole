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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    if (state.remoteConfig.isEmpty() && !ui.searchOpen) {
        ObserveTabEmptyState("No Remote Config provider is registered for this session.", EmptySpot.RemoteConfig)
        return
    }
    val query = ui.remoteConfigSearch.trim().lowercase(Locale.US)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
    ) {
        if (ui.searchOpen) {
            stickyHeader {
                InspectorSearchBar(
                    query = ui.remoteConfigSearch,
                    onQueryChange = actions.onRemoteConfigSearchChange,
                    placeholder = "Search key or value",
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (state.remoteConfig.isEmpty()) {
            item {
                ObserveTabEmptyState(
                    "No Remote Config provider is registered for this session.",
                    EmptySpot.RemoteConfig,
                )
            }
        }
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
                    item(key = "rc-nomatch-${provider.id}") { RemoteConfigNoMatchNote() }
                else ->
                    itemsIndexed(shown, key = { _, entry -> "rc-${provider.id}-${entry.key}" }) { index, entry ->
                        RemoteConfigRow(
                            entry,
                            colors,
                            Modifier.groupedPanelItem(GroupPosition.of(index, shown.size)),
                        ) { actions.onOpenRemoteConfigDetail(provider.id, entry.key) }
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
        color = colors.muted,
        fontSize = 12.5.sp,
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
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        color = colors.muted,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Distinct from [RemoteConfigEmptyNote]: the provider has values, the search just excluded them all. */
@Composable
private fun RemoteConfigNoMatchNote() {
    InspectorEmptyState(EmptySpot.Search, "No key matches the current search.", spotSize = 64.dp)
}

@Composable
private fun RemoteConfigRow(
    entry: InspectorRemoteConfigEntryUi,
    colors: DevConsoleColors,
    modifier: Modifier = Modifier,
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
        leadText = entry.source,
        leadColor = colors.muted,
        leadContainerColor = Color.Transparent,
        title = entry.key,
        subtitle = sub,
        trailValue =
            if (entry.redacted) {
                ""
            } else {
                formatByteSize(
                    entry.value
                        .toByteArray()
                        .size
                        .toLong(),
                )
            },
        trailValueColor = colors.muted,
        modifier = modifier,
        onClick = onClick,
    )
}
