package io.devconsole

import androidx.test.core.app.ApplicationProvider
import io.devconsole.api.BindingMode
import io.devconsole.api.DevConsoleConfig
import io.devconsole.api.StartRequest
import io.devconsole.api.StartResult
import io.devconsole.api.StopReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wiring half of [BindingMode.AUTO]: that `startLocked` consults [AutoBinding] at all, and that
 * a bare `StartRequest()` -- the shape most hosts write -- reaches the network.
 *
 * Only the LAN-available branch is reachable here. Robolectric always presents an eligible interface
 * and reports API 34, below the API 37 line where `ACCESS_LOCAL_NETWORK` starts being enforced, so
 * the two fallback branches cannot be staged in this runner; [AutoBindingTest] covers that decision
 * directly instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformFacadeProviderAutoBindingTest {
    @Test
    fun `a default start request binds LAN when the device has a network`() =
        runTest {
            val provider = PlatformFacadeProvider()
            provider.initialize(ApplicationProvider.getApplicationContext(), DevConsoleConfig.default())

            val started = provider.startBrowser(StartRequest(portRange = 8660..8679)) as StartResult.Started
            assertEquals(BindingMode.LAN, started.endpoint.bindingMode)

            provider.stop(StopReason.UserRequested)
        }

    /**
     * AUTO resolves to a concrete socket, so it must never survive into the endpoint the host reads
     * back -- the connect URL, the QR code, and the "LAN MODE — UNENCRYPTED" banner all key off this
     * field and would be lying if it said AUTO.
     */
    @Test
    fun `AUTO never surfaces as the bound mode`() =
        runTest {
            val provider = PlatformFacadeProvider()
            provider.initialize(ApplicationProvider.getApplicationContext(), DevConsoleConfig.default())

            val started =
                provider.startBrowser(
                    StartRequest(bindingMode = BindingMode.AUTO, portRange = 8680..8699),
                ) as StartResult.Started

            assertTrue(started.endpoint.bindingMode in setOf(BindingMode.LOOPBACK, BindingMode.LAN))

            provider.stop(StopReason.UserRequested)
        }

    @Test
    fun `an explicit loopback request is untouched by AUTO handling`() =
        runTest {
            val provider = PlatformFacadeProvider()
            provider.initialize(ApplicationProvider.getApplicationContext(), DevConsoleConfig.default())

            val started =
                provider.startBrowser(
                    StartRequest(bindingMode = BindingMode.LOOPBACK, portRange = 8700..8719),
                ) as StartResult.Started

            assertEquals(BindingMode.LOOPBACK, started.endpoint.bindingMode)
            assertEquals("127.0.0.1", started.endpoint.host)

            provider.stop(StopReason.UserRequested)
        }
}
