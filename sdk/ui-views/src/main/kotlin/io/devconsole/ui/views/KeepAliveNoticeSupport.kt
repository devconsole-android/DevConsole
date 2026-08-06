/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.ui.views

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build

private const val PERMISSION_FOREGROUND_SERVICE = "android.permission.FOREGROUND_SERVICE"
private const val PERMISSION_FOREGROUND_SERVICE_SPECIAL_USE = "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"
internal const val PERMISSION_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

// POST_NOTIFICATIONS is a runtime permission starting at API 33 (Tiramisu); that's the earliest
// SDK level where there's a grant worth asking for.
private const val SDK_INT_WHERE_NOTIFICATIONS_BECOME_RUNTIME_PERMISSION = 33

// FOREGROUND_SERVICE_SPECIAL_USE only needs to be declared once targeting API 34 (Upside Down
// Cake) and above; below that, FOREGROUND_SERVICE alone covers the service type declaration.
private const val SDK_INT_REQUIRING_FOREGROUND_SERVICE_SPECIAL_USE = 34

/**
 * Pure mirror of sdk:full's `KeepAliveGate.shouldOfferNotificationPrompt` -- this module cannot
 * depend on sdk:full (it is a Compose-free, dependency-free launcher), so the rule is duplicated
 * here in testable form. Keep the two in sync when either changes.
 *
 * Written as a single boolean expression (rather than the guard-clause style of the original)
 * so it reads as one source of truth and stays under detekt's return-count limit without a
 * suppression.
 */
internal fun keepAliveNoticeNeeded(
    serverRunning: Boolean,
    declared: Set<String>,
    notificationsGranted: Boolean,
    sdkInt: Int,
): Boolean =
    serverRunning &&
        !notificationsGranted &&
        sdkInt >= SDK_INT_WHERE_NOTIFICATIONS_BECOME_RUNTIME_PERMISSION &&
        PERMISSION_POST_NOTIFICATIONS in declared &&
        PERMISSION_FOREGROUND_SERVICE in declared &&
        (
            sdkInt < SDK_INT_REQUIRING_FOREGROUND_SERVICE_SPECIAL_USE ||
                PERMISSION_FOREGROUND_SERVICE_SPECIAL_USE in declared
        )

/** Framework adapter for [keepAliveNoticeNeeded]; untestable here by module policy, kept trivial. */
internal fun keepAliveNoticeNeeded(
    context: Context,
    serverRunning: Boolean,
): Boolean =
    keepAliveNoticeNeeded(
        serverRunning = serverRunning,
        declared =
            runCatching {
                context.packageManager
                    .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                    .requestedPermissions
                    ?.toSet()
                    .orEmpty()
            }.getOrDefault(emptySet()),
        notificationsGranted =
            context.checkSelfPermission(PERMISSION_POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        sdkInt = Build.VERSION.SDK_INT,
    )

/** Unwraps ContextWrapper layers (theme wrappers, etc.) to the owning Activity, if any. */
internal fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
