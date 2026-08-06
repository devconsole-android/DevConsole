/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "UnusedPrivateMember", "TooManyFunctions")

package io.devconsole.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
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
private fun PreviewCopyIcon() {
    InspectorGlyphIcon(InspectorGlyph.Copy, contentDescription = null)
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun InspectorDetailHeaderPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        InspectorDetailHeader(
            kindLabel = "Network capture",
            leadText = "GET",
            leadColor = DevConsoleTheme.colors.signal,
            leadContainerColor = DevConsoleTheme.colors.signalSoft,
            title = "/v1/menu/store/8821",
            subtitle = "api.acmeship.com · 210 ms",
            status = "200",
            statusColor = DevConsoleTheme.colors.signal,
            onBack = {},
            actions = listOf(InspectorTopAction("Copy as cURL", {}, { PreviewCopyIcon() })),
        )
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun InspectorDetailSearchFieldPreview() {
    var query by remember { mutableStateOf("") }
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        InspectorDetailSearchField(
            query = query,
            onQueryChange = { query = it },
            matchLabel = "3/12",
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun CollapsibleSectionPreview() {
    var expanded by remember { mutableStateOf(true) }
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        CollapsibleSection(
            label = "Request headers",
            expanded = expanded,
            onToggle = { expanded = !expanded },
            meta = "6 keys",
            onCopy = {},
            modifier = Modifier.padding(12.dp),
        ) {
            InspectorKeyValueList(
                listOf(
                    InspectorKeyValue("authorization", "Bearer ••••••••"),
                    InspectorKeyValue("content-type", "application/json"),
                ),
            )
        }
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun InspectorCodeBlockPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        InspectorCodeBlock(
            lines =
                listOf(
                    InspectorCodeLine(value = "{", valueColor = DevConsoleTheme.colors.muted),
                    InspectorCodeLine(
                        indent = "  ",
                        key = "\"ok\"",
                        value = ": true",
                        valueColor = DevConsoleTheme.colors.jsonBoolean,
                        highlighted = true,
                    ),
                    InspectorCodeLine(value = "}", valueColor = DevConsoleTheme.colors.muted),
                ),
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun InspectorCodeFullScreenOverlayPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        InspectorCodeFullScreenOverlay(
            title = "Response body",
            lines =
                listOf(
                    InspectorCodeLine(value = "{", valueColor = DevConsoleTheme.colors.muted),
                    InspectorCodeLine(
                        indent = "  ",
                        key = "\"ok\"",
                        value = ": true",
                        valueColor = DevConsoleTheme.colors.jsonBoolean,
                    ),
                    InspectorCodeLine(value = "}", valueColor = DevConsoleTheme.colors.muted),
                ),
            onDismiss = {},
        )
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun InspectorProgressBarsPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        InspectorProgressBars(
            stats =
                listOf(
                    InspectorProgressStat("Events", "412 / 500", 0.82f, DevConsoleTheme.colors.signal),
                    InspectorProgressStat("Storage", "18 / 20 MB", 0.9f, DevConsoleTheme.colors.warn),
                ),
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun InspectorDetailFooterBarPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        InspectorDetailFooterBar(
            actions =
                listOf(
                    InspectorFooterAction(
                        label = "Copy",
                        onClick = {},
                        weight = 1f,
                        containerColor = DevConsoleTheme.colors.surface3,
                        contentColor = DevConsoleTheme.colors.ink,
                    ),
                    InspectorFooterAction(label = "Clone in dashboard", onClick = {}, weight = 2f),
                ),
        )
    }
}
