/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole

import androidx.test.core.app.ApplicationProvider
import io.devconsole.api.BindingMode
import io.devconsole.api.CrashPolicy
import io.devconsole.api.DevConsoleConfig
import io.devconsole.api.InitResult
import io.devconsole.api.StartRequest
import io.devconsole.api.StopReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [CrashPolicy]'s two enable gates must actually stop the SDK from touching global JVM/process
 * state, not merely suppress reporting after the fact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashPolicyGatingTest {
    private fun anrWatchdogThreadRunning(): Boolean = Thread.getAllStackTraces().keys.any { it.name == ANR_THREAD_NAME }

    /**
     * Thread start/interrupt-triggered exit are asynchronous relative to the call that triggers
     * them, so a single immediate check would be flaky under load; poll briefly instead.
     */
    private fun awaitThreadState(
        expectedRunning: Boolean,
        timeoutMs: Long = 2_000L,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (anrWatchdogThreadRunning() == expectedRunning) return true
            Thread.sleep(20)
        }
        return anrWatchdogThreadRunning() == expectedRunning
    }

    @Test
    fun `crashCaptureEnabled = false leaves the host's uncaught-exception handler completely untouched`() =
        runTest {
            val original = Thread.getDefaultUncaughtExceptionHandler()
            try {
                val provider = PlatformFacadeProvider()
                val config = DevConsoleConfig.default().withCrashPolicy(CrashPolicy(crashCaptureEnabled = false))

                assertEquals(
                    InitResult.Initialized,
                    provider.initialize(ApplicationProvider.getApplicationContext(), config),
                )

                assertSame(
                    "installing must be skipped entirely, not merely made a no-op afterwards",
                    original,
                    Thread.getDefaultUncaughtExceptionHandler(),
                )
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(original)
            }
        }

    @Test
    fun `crashCaptureEnabled = true (default) installs and chains to the host handler`() =
        runTest {
            val original = Thread.getDefaultUncaughtExceptionHandler()
            try {
                val provider = PlatformFacadeProvider()

                provider.initialize(ApplicationProvider.getApplicationContext(), DevConsoleConfig.default())

                assertTrue(
                    "expected the default handler to change once installed",
                    Thread.getDefaultUncaughtExceptionHandler() !== original,
                )
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(original)
            }
        }

    @Test
    fun `anrWatchdogEnabled = false never starts the watchdog thread`() =
        runTest {
            val provider = PlatformFacadeProvider()
            val config = DevConsoleConfig.default().withCrashPolicy(CrashPolicy(anrWatchdogEnabled = false))
            provider.initialize(ApplicationProvider.getApplicationContext(), config)

            provider.startBrowser(StartRequest(BindingMode.LOOPBACK, 8700..8719))

            assertTrue(
                "the ANR watchdog thread must never start",
                awaitThreadState(expectedRunning = false, timeoutMs = 200L),
            )
            provider.stop(StopReason.UserRequested)
        }

    @Test
    fun `anrWatchdogEnabled = true (default) starts the watchdog thread on browser start`() =
        runTest {
            val provider = PlatformFacadeProvider()
            provider.initialize(ApplicationProvider.getApplicationContext(), DevConsoleConfig.default())

            provider.startBrowser(StartRequest(BindingMode.LOOPBACK, 8720..8739))

            assertTrue("expected the ANR watchdog thread to be running", awaitThreadState(expectedRunning = true))
            provider.stop(StopReason.UserRequested)
        }

    private companion object {
        const val ANR_THREAD_NAME = "devconsole-anr-watchdog"
    }
}
