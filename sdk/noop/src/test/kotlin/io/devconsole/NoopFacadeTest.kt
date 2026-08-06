package io.devconsole

import android.app.Application
import io.devconsole.api.DevConsoleConfig
import io.devconsole.api.DevConsoleState
import io.devconsole.api.InitResult
import io.devconsole.api.ScreenshotResult
import io.devconsole.api.StartResult
import io.devconsole.network.NetworkRequestInput
import io.devconsole.push.PushInput
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoopFacadeTest {
    @Test
    fun `FR-BUILD-004 noop facade is disabled and does not start`() =
        runTest {
            assertEquals(InitResult.Disabled, DevConsole.initialize(Application(), DevConsoleConfig.default()))
            assertEquals(DevConsoleState.DisabledForBuild, DevConsole.state().value)
            assertEquals(StartResult.DisabledForBuild, DevConsole.startBrowser())
        }

    @Test
    fun `FR-BUILD-004 noop screenshot capture always reports DisabledForBuild`() =
        runTest {
            assertEquals(InitResult.Disabled, DevConsole.initialize(Application(), DevConsoleConfig.default()))

            assertEquals(ScreenshotResult.DisabledForBuild, DevConsole.captureScreenshot())
        }

    @Test
    fun `FR-BUILD-004 noop capability accessors never capture, redact, or store`() {
        DevConsole.networkRecorder().record(
            request = NetworkRequestInput("GET", "https://api.test/orders?access_token=secret"),
            response = null,
            startedAtEpochMs = 0,
            completedAtEpochMs = 0,
        )

        val event = DevConsole.recordPush(PushInput("fcm", mapOf("access_token" to "raw")))

        assertTrue(event.data.isEmpty())
    }
}
