/**
 * @author Shakib
 * @since 08/08/26
 */
package io.devconsole

import androidx.test.core.app.ApplicationProvider
import io.devconsole.api.BindingMode
import io.devconsole.api.BrowserBinding
import io.devconsole.api.BrowserConfig
import io.devconsole.api.BrowserEndpoint
import io.devconsole.api.DevConsoleConfig
import io.devconsole.api.StopReason
import io.devconsole.ui.compose.DevConsoleInspectorBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The More screen's Start button issues no [io.devconsole.api.StartRequest] of its own, so what it
 * binds is decided entirely by [BrowserConfig.binding]. Before that field was consumed this surface
 * always bound loopback, which meant a host configured for LAN still got a `127.0.0.1` connect URL
 * whenever the start came from the device rather than from its own code.
 *
 * Both directions are asserted: the default config binds LAN, and a host can explicitly select
 * loopback as the secondary safer choice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformFacadeProviderMoreScreenBindingTest {
    @Test
    fun `More screen start binds LAN when the host configured LAN`() =
        runTest {
            val provider = PlatformFacadeProvider()
            provider.initialize(
                ApplicationProvider.getApplicationContext(),
                DevConsoleConfig.default().withBrowserConfig(
                    BrowserConfig(binding = BrowserBinding.LAN, portRange = 8620..8639),
                ),
            )

            assertEquals(BindingMode.LAN, provider.startFromMoreScreen().bindingMode)

            provider.stop(StopReason.UserRequested)
        }

    @Test
    fun `More screen start binds LAN under the default config`() =
        runTest {
            val provider = PlatformFacadeProvider()
            provider.initialize(ApplicationProvider.getApplicationContext(), DevConsoleConfig.default())

            assertEquals(BindingMode.LAN, provider.startFromMoreScreen().bindingMode)

            provider.stop(StopReason.UserRequested)
        }

    @Test
    fun `More screen start binds loopback when the host explicitly configures loopback`() =
        runTest {
            val provider = PlatformFacadeProvider()
            provider.initialize(
                ApplicationProvider.getApplicationContext(),
                DevConsoleConfig.default().withBrowserConfig(
                    BrowserConfig(binding = BrowserBinding.LOOPBACK, portRange = 8640..8659),
                ),
            )

            assertEquals(BindingMode.LOOPBACK, provider.startFromMoreScreen().bindingMode)

            provider.stop(StopReason.UserRequested)
        }

    /**
     * Drives the real More-screen path -- `setServerRunning(true)` on the inspector data source --
     * rather than calling `startBrowser` directly, since the whole point is which request that
     * surface builds. The command is fire-and-forget by contract, so the bound endpoint is polled
     * out of the facade rather than read from a return value.
     *
     * The poll runs on [Dispatchers.Default] deliberately: the start it is waiting for is launched
     * on the facade's own `Dispatchers.Default` scope, so polling on `runTest`'s virtual clock would
     * exhaust the timeout in an instant while the real work had not begun.
     */
    private suspend fun PlatformFacadeProvider.startFromMoreScreen(): BrowserEndpoint =
        withContext(Dispatchers.Default) {
            DevConsoleInspectorBridge.source().setServerRunning(true)
            withTimeout(START_TIMEOUT_MS) {
                var bound = endpoint()
                while (bound == null) {
                    delay(POLL_INTERVAL_MS)
                    bound = endpoint()
                }
                bound
            }
        }

    private companion object {
        const val START_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 20L
    }
}
