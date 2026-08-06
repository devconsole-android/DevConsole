/**
 * @author Shakib
 * @since 06/08/26
 *
 * NOTE: written to compile against a real device/emulator window, but not executed in this change --
 * no emulator was available in this environment. Do not treat this file as evidence the real
 * `PixelCopy` path or the `FLAG_SECURE` rejection have been run; [ScreenshotCaptureTest] (Robolectric,
 * `sdk:full` unit tests) exercises the same capture code path including a real `PixelCopy.request`
 * dispatch under Robolectric's shadow, but that is not a substitute for verifying against an actual
 * device compositor.
 */
package io.devconsole

import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.devconsole.api.ScreenshotPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenshotCaptureInstrumentedTest {
    @Test
    fun realPixelCopyCapturesTheLiveWindowWithinThePolicyCap() {
        ActivityScenario.launch(ScreenshotProbeActivity::class.java).use { scenario ->
            val policy = ScreenshotPolicy(enabled = true, maxLongestEdgePx = 512, maxBytes = 4L * 1024 * 1024)
            val capture = ScreenshotCapture()

            val result =
                runBlocking {
                    var outcome: CapturedScreenshot? = null
                    scenario.onActivity { activity ->
                        outcome = runBlocking { capture.capture(activity, policy) }
                    }
                    outcome
                }

            assertTrue("expected a real capture, got $result", result is CapturedScreenshot.Bytes)
            result as CapturedScreenshot.Bytes
            assertTrue(result.png.isNotEmpty())
            assertTrue(maxOf(result.widthPx, result.heightPx) <= policy.maxLongestEdgePx)
            // PNG magic number: 0x89 'P' 'N' 'G'.
            assertEquals(0x89.toByte(), result.png[0])
            assertEquals('P'.code.toByte(), result.png[1])
        }
    }

    @Test
    fun aFlagSecureWindowIsReportedAsSecureWindowOnARealDevice() {
        ActivityScenario.launch(ScreenshotProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            }
            val capture = ScreenshotCapture()

            val result =
                runBlocking {
                    var outcome: CapturedScreenshot? = null
                    scenario.onActivity { activity ->
                        outcome = runBlocking { capture.capture(activity, ScreenshotPolicy(enabled = true)) }
                    }
                    outcome
                }

            assertEquals(CapturedScreenshot.SecureWindow, result)
        }
    }
}
