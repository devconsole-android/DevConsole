/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View.MeasureSpec
import android.view.WindowManager
import io.devconsole.api.ScreenshotPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenshotCaptureTest {
    @Test
    fun `an oversized source is scaled down to the configured longest edge`() {
        val oversized = Bitmap.createBitmap(4000, 2000, Bitmap.Config.ARGB_8888)

        val scaled = oversized.downscaledTo(1080)

        assertEquals(1080, maxOf(scaled.width, scaled.height))
        // Aspect ratio is preserved: 4000x2000 is 2:1, so the shorter edge halves the longer one.
        assertEquals(540, minOf(scaled.width, scaled.height))
    }

    @Test
    fun `a bitmap already within the cap is returned unscaled`() {
        val small = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)

        val result = small.downscaledTo(1080)

        assertTrue(result === small)
        assertEquals(300, result.width)
        assertEquals(200, result.height)
    }

    @Test
    fun `a result beyond maxBytes is rejected rather than stored`() =
        runTest {
            val oversizedEncoder: (Bitmap) -> ByteArray = { ByteArray(1_000) }
            val capture = ScreenshotCapture(pngEncoder = oversizedEncoder)
            val activity = activityWithSize(width = 100, height = 100)
            val policy = ScreenshotPolicy(enabled = true, maxBytes = 500)

            val result = capture.capture(activity, policy)

            assertTrue("expected a Failed result, got $result", result is CapturedScreenshot.Failed)
            assertTrue((result as CapturedScreenshot.Failed).reason.contains("exceeds"))
        }

    @Test
    fun `an encoded result within maxBytes is captured with the scaled dimensions`() =
        runTest {
            val smallEncoder: (Bitmap) -> ByteArray = { ByteArray(10) }
            val capture = ScreenshotCapture(pngEncoder = smallEncoder)
            val activity = activityWithSize(width = 100, height = 100)
            val policy = ScreenshotPolicy(enabled = true, maxLongestEdgePx = 1080, maxBytes = 2L * 1024 * 1024)

            val result = capture.capture(activity, policy)

            assertTrue("expected Bytes, got $result", result is CapturedScreenshot.Bytes)
            result as CapturedScreenshot.Bytes
            assertEquals(10, result.png.size)
            assertEquals(100, result.widthPx)
            assertEquals(100, result.heightPx)
        }

    @Test
    fun `a FLAG_SECURE window is reported as SecureWindow without attempting capture`() =
        runTest {
            val capture = ScreenshotCapture()
            val activity = activityWithSize(width = 100, height = 100)
            activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

            val result = capture.capture(activity, ScreenshotPolicy(enabled = true))

            assertEquals(CapturedScreenshot.SecureWindow, result)
        }

    @Test
    fun `a window with no laid-out size fails rather than crashing`() =
        runTest {
            val capture = ScreenshotCapture()
            val activity = Robolectric.buildActivity(Activity::class.java).create().get()

            val result = capture.capture(activity, ScreenshotPolicy(enabled = true))

            assertTrue("expected a Failed result, got $result", result is CapturedScreenshot.Failed)
        }

    // --- Finding 1: TOCTOU on the FLAG_SECURE pre-check --------------------------------------------

    @Test
    fun `a secure flag set after the pre-check but before the PixelCopy request is caught`() =
        runTest {
            // A paused main looper plus a Handler that signals when the recheck is posted lets this
            // test land the flag flip deterministically inside the real TOCTOU window: after capture()'s
            // deterministic pre-check has already passed (evaluated on the background dispatcher below,
            // mirroring the finding's "background coroutine" scenario) but before the posted recheck runs.
            val mainLooper = shadowOf(Looper.getMainLooper())
            mainLooper.pause()
            val posted = CountDownLatch(1)
            val handler = SignalingHandler(Looper.getMainLooper()) { posted.countDown() }
            val capture = ScreenshotCapture(mainHandler = handler)
            val activity = activityWithSize(width = 100, height = 100)
            val policy = ScreenshotPolicy(enabled = true)

            val deferred = async(Dispatchers.IO) { capture.capture(activity, policy) }
            assertTrue(
                "expected the PixelCopy recheck to be posted to the main looper",
                posted.await(5, TimeUnit.SECONDS),
            )
            // Stands in for the test's main-thread hook: flips FLAG_SECURE only now, after the
            // pre-check already ran clean, simulating an app doing this in onResume() concurrently.
            activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            mainLooper.idle()

            assertEquals(CapturedScreenshot.SecureWindow, deferred.await())
        }

    @Test
    @Config(sdk = [24])
    fun `a secure flag set after the pre-check but before the canvas draw is caught`() =
        runTest {
            val mainLooper = shadowOf(Looper.getMainLooper())
            mainLooper.pause()
            val posted = CountDownLatch(1)
            val handler = SignalingHandler(Looper.getMainLooper()) { posted.countDown() }
            val capture = ScreenshotCapture(mainHandler = handler)
            val activity = activityWithSize(width = 100, height = 100)
            val policy = ScreenshotPolicy(enabled = true)

            val deferred = async(Dispatchers.IO) { capture.capture(activity, policy) }
            assertTrue(
                "expected the canvas-draw recheck to be posted to the main looper",
                posted.await(5, TimeUnit.SECONDS),
            )
            activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            mainLooper.idle()

            assertEquals(CapturedScreenshot.SecureWindow, deferred.await())
        }

    @Test
    @Config(sdk = [24])
    fun `the canvas path refuses when the decor view is detached and its secure state is ambiguous`() =
        runTest {
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            val activity = controller.get()
            activity.window.decorView.measure(
                MeasureSpec.makeMeasureSpec(100, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(100, MeasureSpec.EXACTLY),
            )
            activity.window.decorView.layout(0, 0, 100, 100)
            val decorView = activity.window.decorView
            // Simulate the window being torn down mid-flight: the activity is destroyed after this
            // class's own pre-check would have run, but before this test invokes capture(). The decor
            // view keeps its last-measured size (capture()'s size guard would otherwise short-circuit
            // before ever reaching the ambiguity check this test exists to exercise).
            controller.pause().stop().destroy()
            assertFalse(
                "test setup assumption: destroy() must detach the decor view for this to be a real test",
                decorView.isAttachedToWindow,
            )

            val capture = ScreenshotCapture()
            val result = capture.capture(activity, ScreenshotPolicy(enabled = true))

            assertEquals(CapturedScreenshot.SecureWindow, result)
        }

    // --- Finding 1: PixelCopy result mapping --------------------------------------------------------

    @Test
    fun `ERROR_SOURCE_NO_DATA maps to a secure outcome rather than a generic failure`() {
        assertEquals(CaptureAttemptResult.SECURE, PixelCopy.ERROR_SOURCE_NO_DATA.toCaptureAttemptResult())
    }

    @Test
    fun `SUCCESS and other PixelCopy error codes map as expected`() {
        assertEquals(CaptureAttemptResult.SUCCESS, PixelCopy.SUCCESS.toCaptureAttemptResult())
        assertEquals(CaptureAttemptResult.FAILED, PixelCopy.ERROR_TIMEOUT.toCaptureAttemptResult())
        assertEquals(CaptureAttemptResult.FAILED, PixelCopy.ERROR_UNKNOWN.toCaptureAttemptResult())
        assertEquals(CaptureAttemptResult.FAILED, PixelCopy.ERROR_DESTINATION_INVALID.toCaptureAttemptResult())
    }

    // --- Finding 2: bounded capture + no leaked bitmap ----------------------------------------------

    @Test
    fun `a capture that never resolves times out, reports timedOut, and recycles its bitmap`() =
        runTest {
            var capturedBitmap: Bitmap? = null
            val handler = SwallowingHandler(Looper.getMainLooper())
            val capture =
                ScreenshotCapture(
                    mainHandler = handler,
                    captureTimeoutMs = 20,
                    bitmapFactory = { w, h ->
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { capturedBitmap = it }
                    },
                )
            val activity = activityWithSize(width = 100, height = 100)

            // Called from a real background thread, like the finding's "background coroutine" scenario:
            // the SwallowingHandler stands in for a main thread stalled past the ANR threshold -- the
            // posted request/draw runnable is accepted but never actually runs.
            val result = withContext(Dispatchers.IO) { capture.capture(activity, ScreenshotPolicy(enabled = true)) }

            assertTrue("expected a Failed result, got $result", result is CapturedScreenshot.Failed)
            result as CapturedScreenshot.Failed
            assertTrue(result.timedOut)
            assertTrue("bitmap must not be leaked past a timeout", requireNotNull(capturedBitmap).isRecycled)
        }

    @Test
    fun `cancelling an in-flight capture recycles the bitmap instead of leaking it`() =
        runTest {
            val allocated = CountDownLatch(1)
            var capturedBitmap: Bitmap? = null
            val handler = SwallowingHandler(Looper.getMainLooper())
            val capture =
                ScreenshotCapture(
                    mainHandler = handler,
                    // Long enough that the timeout itself never fires -- this test is about explicit
                    // cancellation, not the timeout path already covered above.
                    captureTimeoutMs = TimeUnit.MINUTES.toMillis(5),
                    bitmapFactory = { w, h ->
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                            capturedBitmap = it
                            allocated.countDown()
                        }
                    },
                )
            val activity = activityWithSize(width = 100, height = 100)

            val job = launch(Dispatchers.IO) { capture.capture(activity, ScreenshotPolicy(enabled = true)) }
            assertTrue("expected the capture bitmap to be allocated", allocated.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()

            assertTrue("bitmap must not be leaked past cancellation", requireNotNull(capturedBitmap).isRecycled)
        }

    /** Robolectric's window is 0x0 until laid out; force a real decor-view size for tests that need one. */
    private fun activityWithSize(
        width: Int,
        height: Int,
    ): Activity {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.window.decorView.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        activity.window.decorView.layout(0, 0, width, height)
        return activity
    }

    /**
     * Signals every enqueue before delegating to the real queue, so a test can land work
     * deterministically inside a race window. `Handler.post` itself is `final`; every post/postDelayed
     * call routes through [sendMessageAtTime], which is not, so that is the seam to override.
     */
    private class SignalingHandler(
        looper: Looper,
        private val onPost: () -> Unit,
    ) : Handler(looper) {
        override fun sendMessageAtTime(
            msg: android.os.Message,
            uptimeMillis: Long,
        ): Boolean {
            onPost()
            return super.sendMessageAtTime(msg, uptimeMillis)
        }
    }

    /**
     * Accepts every posted [Runnable] but never runs it -- stands in for a main thread stalled past an
     * ANR. Overrides [sendMessageAtTime] rather than the `final` `Handler.post` -- see [SignalingHandler].
     */
    private class SwallowingHandler(
        looper: Looper,
    ) : Handler(looper) {
        override fun sendMessageAtTime(
            msg: android.os.Message,
            uptimeMillis: Long,
        ): Boolean = true
    }
}
