/**
 * @author Shakib
 * @since 04/11/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")

package io.devconsole.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The "Provider value: <value> (<source>)" line the editor shows while an override is active, or
 * null for a key still serving the provider's own value. Returns the line rather than painting it
 * so the same text can be asserted without a Compose test.
 */
internal fun remoteConfigProviderValueLabel(entry: InspectorRemoteConfigEntryUi): String? =
    if (!entry.overridden) {
        null
    } else {
        val value = entry.providerValue ?: UNKNOWN_PROVIDER_VALUE
        "Provider value: $value (${entry.providerSource})"
    }

private const val UNKNOWN_PROVIDER_VALUE = "<unknown>"
private const val REDACTED_VALUE = "<redacted>"

/**
 * Local override editor for one Remote Config key. The text field is prefilled with the value the
 * app currently resolves, so the operator edits from truth rather than retyping it; "Clear override"
 * exists only while an override is actually masking the provider's value.
 */
@Composable
internal fun RemoteConfigOverrideDialog(
    entry: InspectorRemoteConfigEntryUi,
    onOverride: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DevConsoleTheme.colors
    var value by rememberSaveable(entry.key) { mutableStateOf(if (entry.redacted) "" else entry.value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.key) },
        text = {
            Column {
                Text(
                    "Current value: ${if (entry.redacted) REDACTED_VALUE else entry.value}",
                    color = colors.muted,
                    fontSize = 12.5.sp,
                )
                remoteConfigProviderValueLabel(entry)?.let { label ->
                    Text(label, color = colors.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Override value") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("remoteConfigOverrideValue"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onOverride(value) },
                modifier = Modifier.testTag("remoteConfigOverrideSet"),
            ) {
                Text("Override")
            }
        },
        dismissButton = {
            Row {
                if (entry.overridden) {
                    TextButton(
                        onClick = onClear,
                        modifier = Modifier.testTag("remoteConfigOverrideClear"),
                    ) {
                        Text("Clear override", color = colors.warn)
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("remoteConfigOverrideCancel"),
                ) {
                    Text("Cancel", color = colors.muted)
                }
            }
        },
    )
}
