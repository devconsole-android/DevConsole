/**
 * @author Shakib
 * @since 24/08/26
 */
package io.devconsole.ui.compose

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
private const val NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS"
private const val LOCAL_NETWORK_PERMISSION_API = 37

private const val SETTINGS_ACTION_LABEL = "Settings"
private const val NOTIFICATION_VISIBILITY_MESSAGE =
    "Server will start, but Android will hide its notification until notifications are allowed."

/**
 * Handles the permission preflight produced by an in-app More-screen server start. The sample apps
 * own their start buttons and can request permissions directly; the SDK-owned inspector has to
 * provide the same bridge or a start would otherwise race a platform permission prompt.
 *
 * A denial gets a second snackbar with a Settings action. Android can stop showing the runtime
 * dialog after the user blocks a permission, and a plain retry would then look like a broken Start
 * button. Opening the app's settings is the only reliable recovery path in that state.
 */
@Composable
@Suppress("FunctionNaming") // Compose entry points are conventionally PascalCase.
internal fun ServerPermissionPromptEffect(
    permission: String?,
    snackbarHostState: SnackbarHostState,
    onPermissionResult: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var blockedPermission by remember { mutableStateOf<String?>(null) }
    var activeRequestPermission by remember { mutableStateOf<String?>(null) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val requestedPermission = activeRequestPermission
            activeRequestPermission = null
            if (!granted && requestedPermission != null) {
                if (requestedPermission == NOTIFICATION_PERMISSION) {
                    suppressKeepAliveNotificationPromptForThisProcess()
                    // Notification access affects discoverability of the running foreground
                    // service, not whether Android may start it. Keep this informational snackbar
                    // separate from the required-permission Settings recovery below.
                    scope.launch {
                        snackbarHostState.showSnackbar(NOTIFICATION_VISIBILITY_MESSAGE)
                    }
                } else {
                    blockedPermission = requestedPermission
                }
            }
            onPermissionResult(granted)
        }

    LaunchedEffect(permission) {
        val requestedPermission = permission ?: return@LaunchedEffect
        if (requestedPermission == LOCAL_NETWORK_PERMISSION && Build.VERSION.SDK_INT < LOCAL_NETWORK_PERMISSION_API) {
            // ACCESS_LOCAL_NETWORK does not exist on earlier Android versions, so a compatibility
            // PermissionRequired state must not strand this Start attempt behind a no-op prompt.
            onPermissionResult(true)
            return@LaunchedEffect
        }
        blockedPermission = null
        activeRequestPermission = requestedPermission
        val result =
            snackbarHostState.showSnackbar(
                message = permissionPromptMessage(requestedPermission),
                actionLabel = "Allow",
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
        when (result) {
            SnackbarResult.ActionPerformed -> permissionLauncher.launch(requestedPermission)
            SnackbarResult.Dismissed -> {
                activeRequestPermission = null
                if (requestedPermission == NOTIFICATION_PERMISSION) {
                    suppressKeepAliveNotificationPromptForThisProcess()
                    scope.launch {
                        snackbarHostState.showSnackbar(NOTIFICATION_VISIBILITY_MESSAGE)
                    }
                }
                onPermissionResult(false)
            }
        }
    }

    LaunchedEffect(blockedPermission) {
        val blocked = blockedPermission ?: return@LaunchedEffect
        val result =
            snackbarHostState.showSnackbar(
                message = "${permissionLabel(blocked)} is blocked. Enable it in App settings to start the server.",
                actionLabel = SETTINGS_ACTION_LABEL,
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
        if (result == SnackbarResult.ActionPerformed) openAppSettings(context)
        blockedPermission = null
    }
}

private fun permissionPromptMessage(permission: String): String =
    when (permission) {
        LOCAL_NETWORK_PERMISSION -> "Allow local network access before starting the Dev Console server"
        NOTIFICATION_PERMISSION -> "Allow notifications so the server stays visible in the notification shade"
        else -> "Allow ${permissionLabel(permission)} before starting the Dev Console server"
    }

private fun permissionLabel(permission: String): String =
    when (permission) {
        LOCAL_NETWORK_PERMISSION -> "Local network access"
        NOTIFICATION_PERMISSION -> "Notifications"
        else ->
            permission
                .substringAfterLast('.')
                .replace('_', ' ')
                .lowercase()
                .replaceFirstChar { it.uppercase() }
    }

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
