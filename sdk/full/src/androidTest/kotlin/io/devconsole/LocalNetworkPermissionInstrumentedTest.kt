package io.devconsole

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.devconsole.api.BindingMode
import io.devconsole.api.DevConsoleConfig
import io.devconsole.api.StartRequest
import io.devconsole.api.StartResult
import io.devconsole.api.StopReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket

@RunWith(AndroidJUnit4::class)
class LocalNetworkPermissionInstrumentedTest {
    @Test
    fun deniedLocalNetworkPermissionPreservesLoopbackStartup() =
        runBlocking {
            assumeTrue(Build.VERSION.SDK_INT >= 37)
            val application = ApplicationProvider.getApplicationContext<Application>()
            assumeTrue(
                application.checkSelfPermission(LOCAL_NETWORK_PERMISSION) !=
                    PackageManager.PERMISSION_GRANTED,
            )
            val provider = PlatformFacadeProvider()
            provider.initialize(application, DevConsoleConfig.default())
            val port = ServerSocket(0).use { it.localPort }

            assertEquals(
                StartResult.PermissionRequired(LOCAL_NETWORK_PERMISSION),
                provider.startBrowser(StartRequest(BindingMode.LAN, port..port)),
            )
            assertTrue(provider.startBrowser(StartRequest(BindingMode.LOOPBACK, port..port)) is StartResult.Started)

            provider.stop(StopReason.UserRequested)
        }

    private companion object {
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
