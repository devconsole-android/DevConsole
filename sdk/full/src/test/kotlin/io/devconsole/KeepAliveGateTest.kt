/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class KeepAliveGateTest {
    private val application: Application = ApplicationProvider.getApplicationContext()

    private fun declarePermissions(vararg permissions: String) {
        val info = shadowOf(application.packageManager).getInternalMutablePackageInfo(application.packageName)
        info.requestedPermissions = arrayOf(*permissions)
    }

    @Test
    @Config(sdk = [27])
    fun `foreground service allowed below api 28 with no declarations`() {
        declarePermissions()
        assertTrue(KeepAliveGate(application).canRunForegroundService())
    }

    @Test
    @Config(sdk = [33])
    fun `foreground service blocked on api 33 when host declares nothing`() {
        declarePermissions()
        assertFalse(KeepAliveGate(application).canRunForegroundService())
    }

    @Test
    @Config(sdk = [33])
    fun `foreground service allowed on api 33 with foreground service permission`() {
        declarePermissions(Manifest.permission.FOREGROUND_SERVICE)
        assertTrue(KeepAliveGate(application).canRunForegroundService())
    }

    @Test
    @Config(sdk = [34])
    fun `api 34 additionally requires the special use permission`() {
        declarePermissions(Manifest.permission.FOREGROUND_SERVICE)
        assertFalse(KeepAliveGate(application).canRunForegroundService())

        declarePermissions(
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE,
        )
        assertTrue(KeepAliveGate(application).canRunForegroundService())
    }

    @Test
    @Config(sdk = [33])
    fun `post notifications declaration is read from the merged manifest`() {
        declarePermissions(Manifest.permission.FOREGROUND_SERVICE)
        assertFalse(KeepAliveGate(application).hostDeclaresPostNotifications())

        declarePermissions(Manifest.permission.FOREGROUND_SERVICE, Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(KeepAliveGate(application).hostDeclaresPostNotifications())
    }

    @Test
    @Config(sdk = [27])
    fun `notifications count as granted below api 33`() {
        assertTrue(KeepAliveGate(application).notificationsGranted())
    }

    @Test
    @Config(sdk = [33])
    fun `notifications not granted on api 33 until the runtime grant lands`() {
        assertFalse(KeepAliveGate(application).notificationsGranted())

        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(KeepAliveGate(application).notificationsGranted())
    }

    @Test
    @Config(sdk = [33])
    fun `prompt offered only when running, opted in, declared, and not yet granted`() {
        declarePermissions(Manifest.permission.FOREGROUND_SERVICE, Manifest.permission.POST_NOTIFICATIONS)
        val gate = KeepAliveGate(application)

        assertTrue(gate.shouldOfferNotificationPrompt(serverRunning = true))
        assertFalse(gate.shouldOfferNotificationPrompt(serverRunning = false))

        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(gate.shouldOfferNotificationPrompt(serverRunning = true))
    }

    @Test
    @Config(sdk = [33])
    fun `prompt never offered when host skipped the foreground service opt in`() {
        declarePermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(KeepAliveGate(application).shouldOfferNotificationPrompt(serverRunning = true))
    }
}
