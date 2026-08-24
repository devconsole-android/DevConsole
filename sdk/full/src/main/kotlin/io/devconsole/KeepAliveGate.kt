/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Decides whether the keep-alive foreground service may run, from the app's merged manifest.
 *
 * The `sdk:full` manifest declares `FOREGROUND_SERVICE` and
 * `FOREGROUND_SERVICE_SPECIAL_USE`, so the service is enabled by default for the full debug
 * runtime. The check remains defensive: a host can remove a merged permission, use an unusual
 * manifest setup, or run an older platform where the type-specific permission is not needed.
 * In those cases the server continues without keep-alive instead of receiving a permission
 * exception from the service start.
 */
internal class KeepAliveGate(
    private val context: Context,
) {
    private fun declaredPermissions(): Set<String> =
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                ?.toSet()
                .orEmpty()
        }.getOrDefault(emptySet())

    fun canRunForegroundService(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        val declared = declaredPermissions()
        return when {
            Manifest.permission.FOREGROUND_SERVICE !in declared -> false
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> true
            else -> Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE in declared
        }
    }

    fun hostDeclaresPostNotifications(): Boolean = Manifest.permission.POST_NOTIFICATIONS in declaredPermissions()

    fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Same manifest/foreground-service guard as the snackbar, evaluated before a new server start. */
    fun shouldRequestNotificationBeforeStart(): Boolean =
        canRunForegroundService() &&
            hostDeclaresPostNotifications() &&
            !notificationsGranted()

    /**
     * The inspector-UI snackbar predicate: offer the grant only when it would change something
     * (server up, the full runtime's service permissions are present, notification permission is
     * declared, and the grant is missing). Granting an undeclared permission is a silent no-op on
     * Android, so offering it would mislead when a host has removed the merged declaration.
     */
    fun shouldOfferNotificationPrompt(serverRunning: Boolean): Boolean =
        serverRunning &&
            shouldRequestNotificationBeforeStart()
}
