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
 * Decides whether the keep-alive foreground service may run, from the host's merged manifest.
 *
 * The SDK ships zero `uses-permission` entries for this feature: the host opts in by declaring
 * `FOREGROUND_SERVICE` (plus `FOREGROUND_SERVICE_SPECIAL_USE` on API 34+) in its own debug
 * manifest. A host that declares nothing gets exactly the pre-feature behavior -- these checks
 * are what prevent `startForegroundService` from throwing `SecurityException` in that case.
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

    /**
     * The inspector-UI snackbar predicate: offer the grant only when it would change something
     * (server up, service opted in, permission declared by the host, not yet granted). Granting
     * an undeclared permission is a silent no-op on Android, so offering it would mislead.
     */
    fun shouldOfferNotificationPrompt(serverRunning: Boolean): Boolean =
        serverRunning &&
            canRunForegroundService() &&
            hostDeclaresPostNotifications() &&
            !notificationsGranted()
}
