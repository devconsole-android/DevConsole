/**
 * @author Shakib
 * @since 06/08/26
 *
 * Runs against a real device compositor (verified on a Pixel 6, API 37, 03/11/26): the real
 * `PixelCopy` path and the `FLAG_SECURE` rejection. [ScreenshotCaptureTest] covers the same code under
 * Robolectric's shadow.
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

            val result = runBlocking { capture.capture(scenario.currentActivity(), policy) }

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

            val result = runBlocking { capture.capture(scenario.currentActivity(), ScreenshotPolicy(enabled = true)) }

            assertEquals(CapturedScreenshot.SecureWindow, result)
        }
    }
}

/**
 * The activity, handed back to the instrumentation thread. capture() must be awaited from here, not
 * inside onActivity: PixelCopy delivers its result on the main looper, so blocking main to wait for
 * it can only ever time out -- which is how this test first failed on a real device.
 */
private fun ActivityScenario<ScreenshotProbeActivity>.currentActivity(): ScreenshotProbeActivity {
    var current: ScreenshotProbeActivity? = null
    onActivity { current = it }
    return checkNotNull(current)
}
