/**
 * @author Shakib
 * @since 25/07/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "UnusedPrivateMember", "TooManyFunctions", "MatchingDeclarationName")

package io.devconsole.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.devconsole.api.CaptureCategory

/**
 * Grouped callbacks for the read-only-by-default app file browser. Delete and share are both
 * capability-gated -- share hands the caller a real, unredacted file, the same reasoning that gates
 * the browser's download route behind the `files` capability.
 */
data class FileBrowserActions(
    val onOpenPath: (root: String, relativePath: String) -> Unit,
    val onPreview: (root: String, relativePath: String) -> Unit,
    val onClosePreview: () -> Unit,
    val onDelete: (root: String, relativePath: String) -> Unit,
    val onShare: (root: String, relativePath: String) -> Unit = { _, _ -> },
)

@Composable
internal fun FilesSection(
    state: InspectorState,
    canEdit: Boolean,
    actions: FileBrowserActions,
) {
    // Defensive: files belong to the INSPECTION category. Hiding the DATA destination entirely
    // (DevConsoleWorkspace.workspaceNavItems) is the primary gate, but this section stays inert on
    // its own too, in case something ever reaches it by a route other than that nav item.
    if (!state.captures(CaptureCategory.INSPECTION)) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TerminalSectionLabel("App files")
        when {
            state.fileRoots.isEmpty() -> SectionEmptyText("No file storage roots available.")
            else -> {
                FileRootChips(
                    roots = state.fileRoots,
                    selected = state.fileListing?.root,
                    onOpenRoot = actions.onOpenPath,
                )
                state.fileListing?.let { listing ->
                    FileListingCard(listing = listing, canEdit = canEdit, actions = actions)
                }
                state.filePreview?.let { preview ->
                    FilePreviewCard(preview = preview, onClose = actions.onClosePreview)
                }
            }
        }
    }
}

@Composable
private fun FileRootChips(
    roots: List<String>,
    selected: String?,
    onOpenRoot: (String, String) -> Unit,
) {
    val colors = DevConsoleTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        roots.forEach { root ->
            val isSelected = root == selected
            val background = if (isSelected) colors.signal else colors.surface2
            val foreground = if (isSelected) colors.signalInk else colors.muted
            Text(
                text = root,
                color = foreground,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = TerminalType.labelSmall,
                modifier =
                    Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(6.dp))
                        .background(background)
                        .clickable { onOpenRoot(root, "") }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .semantics { contentDescription = "Open $root storage root" },
            )
        }
    }
}

@Composable
private fun FileListingCard(
    listing: InspectorFileListingUi,
    canEdit: Boolean,
    actions: FileBrowserActions,
) {
    TerminalCard {
        val here = if (listing.relativePath.isEmpty()) listing.root else "${listing.root}/${listing.relativePath}"
        TerminalSectionLabel(here)
        if (listing.relativePath.isNotEmpty()) {
            FileRow(
                label = "..",
                description = "Go up one directory",
                isDirectory = true,
                onClick = { actions.onOpenPath(listing.root, listing.relativePath.parentPath()) },
            )
        }
        if (listing.entries.isEmpty()) {
            Text(
                text = "Empty directory.",
                color = DevConsoleTheme.colors.muted,
                style = TerminalType.bodySmall,
            )
        } else {
            listing.entries.forEach { entry ->
                FileEntryRow(root = listing.root, entry = entry, canEdit = canEdit, actions = actions)
            }
        }
    }
}

/**
 * [TonalListRow], restyled as a drill-in to the existing Database/Files sections. Share/delete
 * stay as their own small text actions below the row -- TonalListRow has one trailing slot, and
 * dropping these two capability-gated actions would be a real regression.
 */
@Composable
private fun FileEntryRow(
    root: String,
    entry: InspectorFileEntryUi,
    canEdit: Boolean,
    actions: FileBrowserActions,
) {
    val colors = DevConsoleTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        TonalListRow(
            leadText = if (entry.isDirectory) "DIR" else "FILE",
            leadColor = if (entry.isDirectory) colors.signal else colors.put,
            leadContainerColor = if (entry.isDirectory) colors.signalSoft else colors.putSoft,
            title = entry.name,
            subtitle = if (entry.isDirectory) "directory" else "${entry.sizeBytes} B",
            trailValue = if (entry.isDirectory) "›" else "",
            trailValueColor = colors.muted,
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            onClick = {
                if (entry.isDirectory) {
                    actions.onOpenPath(root, entry.relativePath)
                } else {
                    actions.onPreview(root, entry.relativePath)
                }
            },
        )
        if (canEdit && !entry.isDirectory) FileEntryActions(root, entry, actions)
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(start = 76.dp),
            color = colors.line
        )
    }
}

@Composable
private fun FileEntryActions(
    root: String,
    entry: InspectorFileEntryUi,
    actions: FileBrowserActions,
) {
    Row(modifier = Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FileEntryActionText("SHARE", "Share file ${entry.name}") { actions.onShare(root, entry.relativePath) }
        FileEntryActionText("DEL", "Delete file ${entry.name}") { actions.onDelete(root, entry.relativePath) }
    }
}

@Composable
private fun FileEntryActionText(
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    val colors = DevConsoleTheme.colors
    Text(
        text = label,
        color = colors.put,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        style = TerminalType.labelSmall,
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(6.dp))
                .background(colors.putSoft)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .semantics { contentDescription = description },
    )
}

@Composable
private fun FileRow(
    label: String,
    description: String,
    isDirectory: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = DevConsoleTheme.colors
    Text(
        text = label,
        color = if (isDirectory) colors.signal else colors.ink,
        fontFamily = FontFamily.Monospace,
        style = TerminalType.bodySmall,
        modifier =
            modifier
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp)
                .semantics { contentDescription = description },
    )
}

@Composable
private fun FilePreviewCard(
    preview: InspectorFilePreviewUi,
    onClose: () -> Unit,
) {
    val colors = DevConsoleTheme.colors
    TerminalCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TerminalSectionLabel("Preview")
            Text(
                text = "CLOSE",
                color = colors.signal,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = TerminalType.labelSmall,
                modifier =
                    Modifier
                        .clickable(onClick = onClose)
                        .semantics { contentDescription = "Close file preview" },
            )
        }
        when (preview) {
            is InspectorFilePreviewUi.Text -> {
                Text(
                    text = preview.content,
                    color = colors.ink,
                    fontFamily = FontFamily.Monospace,
                    style = TerminalType.bodySmall,
                )
                if (preview.truncated) {
                    Text(
                        text = "… truncated",
                        color = colors.muted,
                        style = TerminalType.labelSmall,
                    )
                }
            }
            is InspectorFilePreviewUi.Binary ->
                Text(
                    text = "Binary file, ${preview.sizeBytes} bytes — preview withheld.",
                    color = colors.muted,
                    style = TerminalType.bodySmall,
                )
            is InspectorFilePreviewUi.Unavailable ->
                Text(
                    text = preview.reason,
                    color = colors.muted,
                    style = TerminalType.bodySmall,
                )
        }
    }
}

private fun String.parentPath(): String = substringBeforeLast('/', missingDelimiterValue = "")

@Preview(showBackground = true, backgroundColor = 0xFF090D0B)
@Composable
private fun FilesSectionPreview() {
    DevConsoleTheme {
        FilesSection(
            state =
                InspectorState(
                    available = true,
                    capabilities = InspectorEditingUi(files = true),
                    fileRoots = listOf("files", "cache"),
                    fileListing =
                        InspectorFileListingUi(
                            root = "files",
                            relativePath = "logs",
                            entries =
                                listOf(
                                    InspectorFileEntryUi("archive", "logs/archive", true, 0, 0),
                                    InspectorFileEntryUi("today.log", "logs/today.log", false, 2048, 0),
                                ),
                        ),
                    filePreview = InspectorFilePreviewUi.Text("2026-07-25 boot ok", truncated = false),
                ),
            canEdit = true,
            actions = FileBrowserActions({ _, _ -> }, { _, _ -> }, {}, { _, _ -> }),
        )
    }
}
