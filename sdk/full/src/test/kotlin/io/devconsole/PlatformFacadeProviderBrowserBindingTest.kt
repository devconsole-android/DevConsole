/**
 * @author Shakib
 * @since 07/08/26
 */
package io.devconsole

import androidx.test.core.app.ApplicationProvider
import io.devconsole.api.BindingMode
import io.devconsole.api.DevConsoleConfig
import io.devconsole.api.StartRequest
import io.devconsole.api.StartResult
import io.devconsole.api.StopReason
import io.devconsole.ui.compose.DevConsoleInspectorBridge
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression guard for H4: the More surface's browser binding must reflect the mode the server
 * actually bound to ([BrowserEndpoint.bindingMode]), not the not-yet-consumed
 * [io.devconsole.api.BrowserConfig.binding] declaration. The default config declares LOOPBACK, so
 * a LAN start is the case that distinguishes the two -- only the real bound mode surfaces "LAN",
 * which is what drives the "LAN MODE — UNENCRYPTED" banner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformFacadeProviderBrowserBindingTest {
    @Test
    fun `More surface browser binding reflects the mode the server actually bound to`() =
        runTest {
            val provider = PlatformFacadeProvider()
            provider.initialize(ApplicationProvider.getApplicationContext(), DevConsoleConfig.default())

            val started = provider.startBrowser(StartRequest(BindingMode.LAN, 8600..8619)) as StartResult.Started
            assertEquals(BindingMode.LAN, started.endpoint.bindingMode)

            val browser = DevConsoleInspectorBridge.source().snapshot().browser
            assertEquals("LAN", browser?.binding)

            provider.stop(StopReason.UserRequested)
        }
}
