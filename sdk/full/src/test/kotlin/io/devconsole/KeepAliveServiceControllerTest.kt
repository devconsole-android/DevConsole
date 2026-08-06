/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeepAliveServiceControllerTest {
    private val application: Application = ApplicationProvider.getApplicationContext()

    private fun declarePermissions(vararg permissions: String) {
        val info = shadowOf(application.packageManager).getInternalMutablePackageInfo(application.packageName)
        info.requestedPermissions = arrayOf(*permissions)
    }

    @Test
    fun `starts the foreground service when the host opted in`() {
        declarePermissions(Manifest.permission.FOREGROUND_SERVICE)
        val controller = KeepAliveServiceController(KeepAliveGate(application))

        controller.onServerStarted(application, "http://192.168.0.5:8081")

        val started = shadowOf(application).nextStartedService
        assertEquals(DevConsoleForegroundService::class.java.name, started.component?.className)
        assertEquals(DevConsoleForegroundService.ACTION_START, started.action)
        assertEquals(
            "http://192.168.0.5:8081",
            started.getStringExtra(DevConsoleForegroundService.EXTRA_ENDPOINT_URL),
        )
    }

    @Test
    fun `does nothing when the host declared no foreground service permission`() {
        declarePermissions()
        val controller = KeepAliveServiceController(KeepAliveGate(application))

        controller.onServerStarted(application, "http://192.168.0.5:8081")

        assertNull(shadowOf(application).nextStartedService)
    }

    @Test
    fun `server stop tears the service down`() {
        declarePermissions(Manifest.permission.FOREGROUND_SERVICE)
        val controller = KeepAliveServiceController(KeepAliveGate(application))
        controller.onServerStarted(application, "http://192.168.0.5:8081")
        shadowOf(application).clearStartedServices()

        controller.onServerStopped(application)

        val stopped = shadowOf(application).nextStoppedService
        assertEquals(DevConsoleForegroundService::class.java.name, stopped.component?.className)
    }
}
