package io.devconsole

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.devconsole.api.DevConsoleConfig
import io.devconsole.api.InitResult
import io.devconsole.api.InspectorOpenResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InspectorLaunchTest {
    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `inspector cannot open before initialization`() {
        val provider = PlatformFacadeProvider()

        assertNull(provider.createInspectorIntent(application))
        assertEquals(InspectorOpenResult.NotInitialized, provider.openInspector(application))
    }

    @Test
    fun `initialized runtime creates an explicit new-task inspector intent`() {
        val provider = PlatformFacadeProvider()
        assertEquals(InitResult.Initialized, provider.initialize(application, DevConsoleConfig.default()))

        val intent = requireNotNull(provider.createInspectorIntent(application))

        assertEquals("io.devconsole.ui.compose.DevConsoleActivity", intent.component?.className)
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
