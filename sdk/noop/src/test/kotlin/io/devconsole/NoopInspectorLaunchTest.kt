package io.devconsole

import android.app.Application
import io.devconsole.api.DevConsoleConfig
import io.devconsole.api.InspectorOpenResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoopInspectorLaunchTest {
    @Test
    fun `protected build never resolves or opens the inspector`() {
        val application = Application()
        DevConsole.initialize(application, DevConsoleConfig.default())

        assertNull(DevConsole.createIntent(application))
        assertEquals(InspectorOpenResult.DisabledForBuild, DevConsole.open(application))
    }
}
