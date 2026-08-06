/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.devconsole.api.StopReason

/**
 * Process-pinning shell for the keep-alive feature. The server already runs inside the host
 * process; this service exists only so the OS keeps that process alive while the host app is
 * backgrounded, and to own the (possibly hidden) status notification.
 *
 * Callers are responsible for gating: this service assumes [KeepAliveGate.canRunForegroundService]
 * already passed. On API 33+ with `POST_NOTIFICATIONS` denied the OS hides the notification but
 * the service -- and therefore the server -- keeps running; that is the intended degraded mode.
 */
internal class DevConsoleForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> {
                runCatching {
                    promoteToForeground(intent.getStringExtra(EXTRA_ENDPOINT_URL).orEmpty())
                }.onFailure {
                    // API 31+ can throw ForegroundServiceStartNotAllowedException/SecurityException
                    // from a background start; without a matching startForeground call the OS
                    // watchdog kills the process for "did not call startForeground in time" -- stop
                    // ourselves first so that never fires.
                    logcatInfo("DevConsole", "Foreground promotion failed: ${it.javaClass.simpleName}")
                    stopSelf()
                }
            }
            ACTION_STOP -> stopServerAndSelf()
        }
        // If the process is ever killed while this service is running, the server died with it;
        // START_NOT_STICKY keeps the OS from resurrecting a serverless shell.
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION") // FOREGROUND_SERVICE_TYPE_NONE is deprecated on API 37 but is still
    // the correct "no type" sentinel ServiceCompat.startForeground expects below API 34; androidx's
    // own ServiceCompat internals suppress the same warning.
    private fun promoteToForeground(endpointUrl: String) {
        createNotificationChannel()
        val notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("Dev Console server running")
                .setContentText(endpointUrl)
                .setOngoing(true)
                .setContentIntent(hostLaunchIntent())
                .addAction(0, "Stop server", stopActionIntent())
                .build()
        val foregroundServiceType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
            }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundServiceType)
    }

    private fun stopServerAndSelf() {
        // Same public stop path the dashboard uses; PlatformFacadeProvider's stopLocked will also
        // call stopService, which is a harmless no-op once this instance has stopped itself.
        // Wait for the async stop (IO flush, session-row end) to actually finish before dropping
        // the foreground pin -- otherwise the process can be LMK-killed mid-stop. The callback runs
        // on a background thread; stopForeground/stopSelf are thread-safe Service methods.
        DevConsole.stopAsync(StopReason.UserRequested) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Dev Console", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun hostLaunchIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun stopActionIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val ACTION_START = "io.devconsole.keepalive.START"
        const val ACTION_STOP = "io.devconsole.keepalive.STOP"
        const val EXTRA_ENDPOINT_URL = "endpointUrl"
        private const val CHANNEL_ID = "devconsole_keepalive"
        private const val NOTIFICATION_ID = 0xDC0

        fun startIntent(
            context: Context,
            endpointUrl: String,
        ): Intent =
            Intent(context, DevConsoleForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ENDPOINT_URL, endpointUrl)

        fun stopIntent(context: Context): Intent =
            Intent(context, DevConsoleForegroundService::class.java).setAction(ACTION_STOP)
    }
}
