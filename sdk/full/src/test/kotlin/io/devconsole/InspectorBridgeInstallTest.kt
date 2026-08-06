/**
 * @author Shakib
 * @since 24/07/26
 */
package io.devconsole

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.devconsole.api.DevConsoleConfig
import io.devconsole.api.InitResult
import io.devconsole.ui.compose.DevConsoleInspectorBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InspectorBridgeInstallTest {
    private val application: Application = ApplicationProvider.getApplicationContext()

    @After
    fun resetBridge() {
        DevConsoleInspectorBridge.reset()
    }

    @Test
    fun `initializing the full runtime installs an available inspector data source`() {
        val provider = PlatformFacadeProvider()

        assertEquals(InitResult.Initialized, provider.initialize(application, DevConsoleConfig.default()))

        assertTrue(DevConsoleInspectorBridge.source().snapshot().available)
    }
}
