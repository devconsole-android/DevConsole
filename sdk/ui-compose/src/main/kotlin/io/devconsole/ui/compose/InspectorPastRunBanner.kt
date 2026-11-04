/**
 * @author Shakib
 * @since 03/11/26
 */
@file:Suppress("FunctionNaming")

package io.devconsole.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shown while a retained past run is selected: back to live, pin against retention, or delete. */
@Composable
internal fun PastRunBanner(
    session: InspectorSessionUi,
    onReturnToLive: () -> Unit,
    onSetPinned: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = DevConsoleTheme.colors
    var confirmDelete by rememberSaveable(session.id) { mutableStateOf(false) }
    Column(modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp)) {
        TonalListRow(
            leadText = "RUN",
            leadColor = colors.ink,
            leadContainerColor = colors.panel,
            title = "Viewing past run",
            // "Pinned" leads the subtitle: appended to the title it was the first thing ellipsized away.
            subtitle =
                (if (session.pinned) "Pinned · " else "") +
                    "${session.label} · ${formatCaptureClockTime(session.startedAtEpochMs)}",
            trailValue = "Back to live",
            trailValueColor = colors.signal,
            containerColor = colors.panel,
            onClick = onReturnToLive,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onSetPinned(!session.pinned) }) {
                Text(if (session.pinned) "Unpin" else "Pin", color = colors.ink, fontSize = 14.sp)
            }
            TextButton(onClick = { confirmDelete = true }) {
                Text("Delete run", color = colors.error, fontSize = 14.sp)
            }
        }
    }
    if (confirmDelete) {
        DeleteRunDialog(
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun DeleteRunDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DevConsoleTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this run?") },
        text = {
            Text(
                "Its captures, logs, crashes and attachments are removed from the device. This cannot be undone.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = colors.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = colors.ink) }
        },
    )
}
