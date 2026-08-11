/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "UnusedPrivateMember", "TooManyFunctions")

package io.devconsole.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
private fun PreviewSearchIcon() {
    InspectorGlyphIcon(InspectorGlyph.Search, contentDescription = null)
}

private val PreviewTopActions =
    listOf(
        InspectorTopAction("Search captures", {}, { PreviewSearchIcon() }),
    )

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun InspectorTopAreaPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        InspectorTopArea(subLine = "io.acmeship.android · 4.12.0", title = "Observe", actions = PreviewTopActions)
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun InspectorTabRowPreview() {
    var selected by remember { mutableStateOf(0) }
    val labels = listOf("Traffic", "Sockets", "Push", "Logs")
    val tabs = labels.mapIndexed { index, label -> InspectorTab(label, index == selected) { selected = index } }
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        InspectorTabRow(tabs)
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun InspectorSearchBarPreview() {
    var query by remember { mutableStateOf("") }
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        InspectorSearchBar(
            query = query,
            onQueryChange = { query = it },
            placeholder = "Search path, host or payload",
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun HeroCardPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        HeroCard(
            label = "Failing requests",
            value = "4",
            valueSuffix = "of 15",
            subtitle = "2 client, 2 server, 1 timeout.",
            labelColor = DevConsoleTheme.colors.error,
            valueColor = DevConsoleTheme.colors.error,
            onCollapse = {},
            ctaLabel = "Show only failures",
            onCtaClick = {},
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun HeroBarPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        HeroBar(value = "4", label = "of 15 requests failing", onExpand = {}, modifier = Modifier.padding(12.dp))
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun FilterChipRowPreview() {
    var selected by remember { mutableStateOf("all") }
    val chips =
        listOf(
            InspectorFilterChip("all", "All", selected == "all", "15"),
            InspectorFilterChip("fail", "Failing", selected == "fail", "4"),
            InspectorFilterChip("slow", "Slow", selected == "slow"),
        )
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        FilterChipRow(chips = chips, onChipClick = { selected = it.id }, modifier = Modifier.padding(12.dp))
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun TonalListRowPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        Column(modifier = Modifier.padding(12.dp)) {
            TonalListRow(
                leadText = "GET",
                leadColor = DevConsoleTheme.colors.signal,
                leadContainerColor = DevConsoleTheme.colors.signalSoft,
                title = "/v1/menu/store/8821",
                subtitle = "api.acmeship.com · 210 ms",
                trailValue = "200",
                trailValueColor = DevConsoleTheme.colors.signal,
                trailSubtitle = "210 ms",
                onClick = {},
            )
        }
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun WarnNotePreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        WarnNote(
            "authToken is not on the deny list, so its value is transmitted verbatim.",
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun EvidenceFabPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        Column(modifier = Modifier.padding(12.dp)) {
            EvidenceFab(label = "Evidence · 2", onClick = {})
        }
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun InspectorBottomNavPreview() {
    var selected by remember { mutableStateOf(0) }
    val destinations = listOf("Observe", "Control", "Data", "More")
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        InspectorNavigationBar(
            destinations.mapIndexed { index, label ->
                InspectorNavItem(label, index == selected, { selected = index }) {
                    InspectorGlyphIcon(InspectorGlyph.Check, contentDescription = null, size = 20.dp)
                }
            },
        )
    }
}

/** [InspectorUrlCard] had zero preview coverage for the running, "Open in a browser" variant. */
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun InspectorUrlCardRunningPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        val colors = DevConsoleTheme.colors
        InspectorUrlCard(
            dotColor = colors.signal,
            label = "Open in a browser",
            url = "http://192.168.1.42:8787/#s=ABCD1234",
            subtitle = "Session code ABCD1234 · rotates in 4h. Plaintext on your LAN — debug builds only.",
            actions =
                listOf(
                    InspectorUrlAction(
                        "Copy URL",
                        {},
                        colors.signal,
                        colors.signalInk,
                        flex = 2f,
                        icon = { InspectorGlyphIcon(InspectorGlyph.Copy, contentDescription = null, size = 15.dp) },
                    ),
                    InspectorUrlAction(
                        "Code",
                        {},
                        colors.surface3,
                        colors.ink,
                        icon = { InspectorGlyphIcon(InspectorGlyph.Copy, contentDescription = null, size = 15.dp) },
                    ),
                    InspectorUrlAction(
                        "QR",
                        {},
                        colors.surface3,
                        colors.ink,
                        icon = { ControlGlyphIcon(ControlGlyph.Eye, contentDescription = null, size = 15.dp) },
                    ),
                ),
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** [InspectorUrlCard] had zero preview coverage for the stopped, "no address" variant. */
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun InspectorUrlCardStoppedPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        val colors = DevConsoleTheme.colors
        InspectorUrlCard(
            dotColor = colors.borderStrong,
            label = "No address",
            url = "—",
            subtitle = "The URL appears here once the server is running.",
            actions =
                listOf(
                    InspectorUrlAction("Start server to get a URL", {}, colors.surface3, colors.muted),
                ),
            modifier = Modifier.padding(12.dp),
        )
    }
}
