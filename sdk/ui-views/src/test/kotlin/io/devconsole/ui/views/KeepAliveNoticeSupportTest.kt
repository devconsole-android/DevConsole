/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.ui.views

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveNoticeSupportTest {
    private val optedIn =
        setOf(
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.POST_NOTIFICATIONS",
        )

    @Test
    fun `notice shown when running, opted in, declared, and not granted`() {
        assertTrue(
            keepAliveNoticeNeeded(serverRunning = true, declared = optedIn, notificationsGranted = false, sdkInt = 34),
        )
    }

    @Test
    fun `no notice when server not running`() {
        assertFalse(
            keepAliveNoticeNeeded(serverRunning = false, declared = optedIn, notificationsGranted = false, sdkInt = 34),
        )
    }

    @Test
    fun `no notice when grant already given`() {
        assertFalse(
            keepAliveNoticeNeeded(serverRunning = true, declared = optedIn, notificationsGranted = true, sdkInt = 34),
        )
    }

    @Test
    fun `no notice when host never declared post notifications`() {
        val withoutNotifications = optedIn - "android.permission.POST_NOTIFICATIONS"
        assertFalse(
            keepAliveNoticeNeeded(
                serverRunning = true,
                declared = withoutNotifications,
                notificationsGranted = false,
                sdkInt = 34,
            ),
        )
    }

    @Test
    fun `no notice when host skipped the foreground service opt in`() {
        val notificationsOnly = setOf("android.permission.POST_NOTIFICATIONS")
        assertFalse(
            keepAliveNoticeNeeded(
                serverRunning = true,
                declared = notificationsOnly,
                notificationsGranted = false,
                sdkInt = 34,
            ),
        )
    }

    @Test
    fun `api 33 does not require the special use permission`() {
        val legacyOptIn = setOf("android.permission.FOREGROUND_SERVICE", "android.permission.POST_NOTIFICATIONS")
        assertTrue(
            keepAliveNoticeNeeded(
                serverRunning = true,
                declared = legacyOptIn,
                notificationsGranted = false,
                sdkInt = 33,
            ),
        )
    }

    @Test
    fun `below api 33 there is no grant to ask for`() {
        assertFalse(
            keepAliveNoticeNeeded(serverRunning = true, declared = optedIn, notificationsGranted = false, sdkInt = 32),
        )
    }
}
