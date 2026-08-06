/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.ui.compose

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Process-scoped dismissal: closing the snackbar silences it until the next app run, matching the
 * spec ("in-memory, reappears next app run") without any persistence.
 *
 * Kept as a top-level property rather than the brief's `object KeepAlivePromptState` wrapper --
 * detekt's `MatchingDeclarationName` flags a lone top-level object whose name doesn't match the
 * file name (`KeepAliveNotificationPrompt.kt`), and this file's name is fixed by the task spec.
 * A private top-level `var` is JVM-static-backed, so it's the same process-scoped storage with
 * one fewer top-level type for the rule to trip on.
 */
private var keepAlivePromptDismissedThisProcess: Boolean = false

/**
 * Offers the POST_NOTIFICATIONS grant that makes the keep-alive foreground service's notification
 * visible. Only ever shown when the full adapter's KeepAliveGate said so ([promptNeeded]) -- which
 * implies API 33+, host opt-in, and a manifest-declared permission -- so the launcher below never
 * fires a request Android would silently drop.
 */
@Composable
internal fun KeepAliveNotificationPromptEffect(
    promptNeeded: Boolean,
    snackbarHostState: SnackbarHostState,
    onPermissionResult: () -> Unit,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            onPermissionResult()
        }
    LaunchedEffect(promptNeeded) {
        if (!promptNeeded || keepAlivePromptDismissedThisProcess) return@LaunchedEffect
        val result =
            snackbarHostState.showSnackbar(
                message = "Allow notifications so the server can stay alive in the background",
                actionLabel = "Allow",
                withDismissAction = true,
                // Indefinite would hold the shared SnackbarHostState's mutex forever, starving the
                // Control screen's own flash messages until this prompt is acted on. Long still
                // gives the user a real window to react; a timeout is treated the same as an
                // explicit dismissal below (this-process-only, reappears next app run) -- an
                // accepted trade-off versus blocking every other snackbar on this screen.
                duration = SnackbarDuration.Long,
            )
        when (result) {
            SnackbarResult.ActionPerformed -> permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            SnackbarResult.Dismissed -> keepAlivePromptDismissedThisProcess = true
        }
    }
}
