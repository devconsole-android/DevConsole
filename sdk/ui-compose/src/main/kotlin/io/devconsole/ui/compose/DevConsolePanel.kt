package io.devconsole.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.devconsole.api.BindingMode
import io.devconsole.api.BrowserEndpoint
import io.devconsole.api.DevConsoleState

/**
 * Optional Compose launcher. Callbacks keep server lifecycle ownership in the host application.
 * [endpoint] is not derived from [state] -- `DevConsoleState.Running` carries no payload -- so the
 * host must capture it from the `StartResult.Started` its own `onStart` receives (and clear it back
 * to `null` once it calls `onStop`) for the running address to be displayed here.
 */
@Composable
fun DevConsolePanel(
    state: DevConsoleState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    endpoint: BrowserEndpoint? = null,
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DevConsoleStatusChip(state, endpoint = endpoint)
        lanTransportWarning(endpoint)?.let { warning ->
            Text(
                text = warning,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onStart,
                enabled =
                    state is DevConsoleState.Initialized ||
                        state is DevConsoleState.Stopped ||
                        state is DevConsoleState.PermissionRequired,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("Start") }
            OutlinedButton(
                onClick = onStop,
                enabled = state is DevConsoleState.Running || state is DevConsoleState.Starting,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("Stop") }
        }
    }
}

@Composable
fun DevConsoleStatusChip(
    state: DevConsoleState,
    modifier: Modifier = Modifier,
    endpoint: BrowserEndpoint? = null,
) {
    Text(
        text = DevConsoleComposeStatus.forState(state, endpoint),
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

internal const val LOCAL_NETWORK_HTTP_WARNING =
    "This debugging session uses local-network HTTP. Other participants on an untrusted network " +
        "may observe or modify traffic. Use ADB localhost mode or a trusted isolated network for sensitive testing."

internal fun lanTransportWarning(endpoint: BrowserEndpoint?): String? =
    LOCAL_NETWORK_HTTP_WARNING.takeIf { endpoint?.bindingMode == BindingMode.LAN }

object DevConsoleComposeStatus {
    fun forState(
        state: DevConsoleState,
        endpoint: BrowserEndpoint? = null,
    ): String =
        when (state) {
            DevConsoleState.Uninitialized -> "DevConsole is not initialized"
            DevConsoleState.DisabledForBuild -> "DevConsole is disabled for this build"
            DevConsoleState.Initialized, DevConsoleState.Stopped -> "DevConsole is ready"
            DevConsoleState.PermissionRequired -> "Local network permission is required"
            DevConsoleState.Starting -> "Starting DevConsole"
            DevConsoleState.Running ->
                if (endpoint != null) {
                    "DevConsole server is running at ${endpoint.host}:${endpoint.port}"
                } else {
                    "DevConsole server is running"
                }
            DevConsoleState.Stopping -> "Stopping DevConsole"
            is DevConsoleState.Failed -> "DevConsole failed: ${state.message}"
        }
}
