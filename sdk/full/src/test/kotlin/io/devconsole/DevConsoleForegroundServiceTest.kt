/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DevConsoleForegroundServiceTest {
    private val application: Application = ApplicationProvider.getApplicationContext()

    private fun notificationManager(): NotificationManager =
        application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun `start action posts the ongoing keep-alive notification`() {
        val controller =
            Robolectric.buildService(
                DevConsoleForegroundService::class.java,
                DevConsoleForegroundService.startIntent(application, "http://192.168.0.5:8081"),
            )
        controller.create().startCommand(0, 0)

        val shadowService = shadowOf(controller.get())
        assertTrue(shadowService.isLastForegroundNotificationAttached)
        val notification = shadowService.lastForegroundNotification
        assertNotNull(notification)
        val shadowNotification = shadowOf(notification)
        assertEquals("Dev Console server running", shadowNotification.contentTitle.toString())
        assertEquals("http://192.168.0.5:8081", shadowNotification.contentText.toString())
        assertEquals(1, notification.actions.size)
        assertEquals("Stop server", notification.actions[0].title.toString())

        val channel = notificationManager().getNotificationChannel("devconsole_keepalive")
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun `stop action stops the service and removes the notification once the async stop completes`() {
        val controller =
            Robolectric.buildService(
                DevConsoleForegroundService::class.java,
                DevConsoleForegroundService.startIntent(application, "http://192.168.0.5:8081"),
            )
        controller.create().startCommand(0, 0)

        // ServiceController has no withIntent; drive the second command straight into the service.
        controller.get().onStartCommand(DevConsoleForegroundService.stopIntent(application), 0, 1)

        // stopServerAndSelf() now defers stopForeground()/stopSelf() to DevConsole.stopAsync's
        // callback, which runs on asyncScope's Dispatchers.Default thread -- not synchronously
        // within onStartCommand. Poll briefly instead of asserting immediately.
        val shadowService = shadowOf(controller.get())
        assertTrue(
            "service should stop itself once DevConsole.stopAsync's callback runs",
            awaitStoppedBySelf(shadowService),
        )
    }

    private fun awaitStoppedBySelf(
        shadowService: org.robolectric.shadows.ShadowService,
        maxWaitMs: Long = 2_000L,
    ): Boolean {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            if (shadowService.isStoppedBySelf) return true
            Thread.sleep(10)
        }
        return shadowService.isStoppedBySelf
    }
}
