/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import io.devconsole.api.ScreenshotPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/** Result of capturing and encoding the foreground window, before it is ever handed to storage. */
internal sealed interface CapturedScreenshot {
    data class Bytes(
        val png: ByteArray,
        val widthPx: Int,
        val heightPx: Int,
    ) : CapturedScreenshot

    /**
     * The window is `FLAG_SECURE`, its secure state could not be confirmed with confidence, or the
     * platform copy reported a secure/unavailable source; nothing was captured.
     */
    data object SecureWindow : CapturedScreenshot

    data class Failed(
        val reason: String,
        /**
         * True only when [reason] is the bounded-capture timeout, so callers/tests can branch on it
         * without string matching.
         */
        val timedOut: Boolean = false,
    ) : CapturedScreenshot
}

/**
 * Outcome of a single platform-level capture attempt, before it is translated into [CapturedScreenshot].
 * Internal rather than private so tests can exercise [toCaptureAttemptResult]'s `PixelCopy` result
 * mapping directly -- Robolectric's `PixelCopy` shadow has no way to synthesize `ERROR_SOURCE_NO_DATA`
 * to exercise that mapping end-to-end, and no device/emulator was available to do it for real.
 */
internal enum class CaptureAttemptResult { SUCCESS, SECURE, FAILED }

/**
 * Captures an `Activity`'s window into a bounded PNG.
 *
 * API 26+ uses [PixelCopy.request], which -- unlike drawing the view tree by hand -- also reflects
 * SurfaceView/TextureView content composited by the system. API 24-25 (`minSdk`) has no `PixelCopy`
 * overload for a `Window`, so it falls back to [View.draw] against the decor view, which must run on
 * the main thread. Both paths are safe to call from any dispatcher: [capture] hops to the main thread
 * itself wherever the framework requires it.
 *
 * [android.view.WindowManager.LayoutParams.FLAG_SECURE] is checked before any capture is attempted --
 * that flag is what makes `PixelCopy` fail or return a blank bitmap in the first place, so checking it
 * directly is the deterministic way to report [CapturedScreenshot.SecureWindow] rather than trying to
 * infer "secure" from a possibly-blank result afterward. That check alone is TOCTOU, though: [capture]
 * dispatches to the main thread asynchronously, so an app that flips `FLAG_SECURE` in `onResume()` on a
 * background coroutine's watch could pass this check and still have the draw land on a now-secure
 * screen. To close that window, [captureWithPixelCopy] and [captureWithCanvas] both re-check the flag
 * a second time on the main thread, immediately before the draw/copy is issued, inside the same posted
 * block. The API 24-25 canvas path goes further: `FLAG_SECURE` is not enforced by the platform at all
 * for a hand-drawn canvas, so this pre-check is the *only* protection on `minSdk` -- if the decor view
 * is not attached to a window at draw time (activity mid-teardown, window replaced) the secure state
 * cannot be confirmed, and that ambiguity is treated as secure rather than captured.
 *
 * Residual gaps, undocumented before this pass and still real after it:
 * - **Child windows are out of scope.** `FLAG_SECURE` on a `Dialog`/`DialogFragment`'s own `Window`, or
 *   `SurfaceView.setSecure(true)`, never appears in `activity.window.attributes.flags` and this class
 *   has no reliable way to enumerate an activity's child windows from here. A secure dialog shown over
 *   a non-secure activity is not detected.
 * - **OEM `PixelCopy`/SurfaceFlinger behavior is not exercised.** No emulator or device was available
 *   while hardening this path; the `ERROR_SOURCE_NO_DATA` -> [CapturedScreenshot.SecureWindow] mapping
 *   and the re-check-before-copy ordering are implemented per the documented `PixelCopy` contract and
 *   covered by Robolectric unit tests only, not verified against real hardware.
 * - **A timed-out `PixelCopy` request cannot be cancelled.** [capture] bounds the wait and recycles its
 *   bitmap on timeout, but `PixelCopy.request` has no cancel API; if the system-side copy is still
 *   in flight when the timeout fires, it is racing a bitmap this class has already recycled. This is
 *   believed low-risk in practice (a `PixelCopy` copy is a single-frame operation, not the kind of
 *   thing a stalled main thread should be able to delay), but it is not something this class can rule
 *   out from application code.
 */
internal class ScreenshotCapture(
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    /** Overridable only for tests that need a deterministic encoded size without real PNG compression. */
    private val pngEncoder: (Bitmap) -> ByteArray = Bitmap::toPngBytes,
    /**
     * Bounds how long a single capture attempt may keep the `Activity`/`Window`/bitmap strongly
     * reachable from the suspended coroutine. Defaults to the same order of magnitude as
     * [AnrWatchdog.DEFAULT_THRESHOLD_MS]: a main thread stalled long enough to be an ANR is long
     * enough that this capture should give up rather than keep holding memory hostage to it.
     */
    private val captureTimeoutMs: Long = DEFAULT_CAPTURE_TIMEOUT_MS,
    /**
     * Overridable only so tests can retain a reference to the exact `Bitmap` a capture allocates and
     * assert on [Bitmap.isRecycled] afterward -- there is otherwise no way to observe that a timed-out
     * or cancelled capture actually released it rather than leaking it.
     */
    private val bitmapFactory: (width: Int, height: Int) -> Bitmap = { width, height ->
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    },
) {
    // Guard-clause early returns (no window, secure, no size, capture failure) are the clearest
    // form for a step-by-step capture pipeline -- see RoomAttachmentStore.kt for the same rationale.
    @Suppress("ReturnCount")
    suspend fun capture(
        activity: Activity,
        policy: ScreenshotPolicy,
    ): CapturedScreenshot {
        val window = activity.window ?: return CapturedScreenshot.Failed("Activity has no window")
        if (window.isSecure()) return CapturedScreenshot.SecureWindow

        val decorView = window.decorView
        val width = decorView.width
        val height = decorView.height
        if (width <= 0 || height <= 0) return CapturedScreenshot.Failed("Window has no size to capture")

        val bitmap =
            runCatching { bitmapFactory(width, height) }
                .getOrElse { return CapturedScreenshot.Failed("Unable to allocate capture bitmap: ${it.message}") }

        // Every branch below owns the bitmap and must either hand it to encode() or recycle it --
        // including external cancellation of this coroutine, which is why the timed section is wrapped
        // in try/catch rather than relying solely on the `null` (timeout) result of withTimeoutOrNull.
        val outcome =
            try {
                withTimeoutOrNull(captureTimeoutMs) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        captureWithPixelCopy(window, bitmap)
                    } else {
                        captureWithCanvas(window, decorView, bitmap)
                    }
                }
            } catch (cancelled: CancellationException) {
                bitmap.recycle()
                throw cancelled
            }

        return when (outcome) {
            null -> {
                bitmap.recycle()
                CapturedScreenshot.Failed("Screenshot capture timed out after ${captureTimeoutMs}ms", timedOut = true)
            }
            CaptureAttemptResult.SUCCESS -> encode(bitmap, policy)
            CaptureAttemptResult.SECURE -> {
                bitmap.recycle()
                CapturedScreenshot.SecureWindow
            }
            CaptureAttemptResult.FAILED -> {
                bitmap.recycle()
                CapturedScreenshot.Failed("Screen capture did not complete")
            }
        }
    }

    private fun encode(
        bitmap: Bitmap,
        policy: ScreenshotPolicy,
    ): CapturedScreenshot {
        val scaled = bitmap.downscaledTo(policy.maxLongestEdgePx)
        val png = pngEncoder(scaled)
        val widthPx = scaled.width
        val heightPx = scaled.height
        if (scaled !== bitmap) bitmap.recycle()
        scaled.recycle()
        return if (png.size.toLong() > policy.maxBytes) {
            CapturedScreenshot.Failed(
                "Encoded screenshot (${png.size} bytes) exceeds the ${policy.maxBytes}-byte cap",
            )
        } else {
            CapturedScreenshot.Bytes(png, widthPx, heightPx)
        }
    }

    /**
     * `PixelCopy.request` posts its own re-entrant check: a [mainHandler] on the main looper is invoked
     * inline. The secure-flag re-check and the `request` call itself are both issued from the same
     * posted block so no window opens between "we decided it's safe" and "we asked the platform to
     * copy" -- and [continuation.invokeOnCancellation][kotlinx.coroutines.CancellableContinuation.invokeOnCancellation]
     * removes that pending block from the looper if a timeout or external cancellation lands first.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun captureWithPixelCopy(
        window: Window,
        bitmap: Bitmap,
    ): CaptureAttemptResult =
        suspendCancellableCoroutine { continuation ->
            val issueRequest =
                Runnable {
                    if (!continuation.isActive) return@Runnable
                    if (window.isSecure()) {
                        continuation.resume(CaptureAttemptResult.SECURE)
                        return@Runnable
                    }
                    try {
                        PixelCopy.request(
                            window,
                            bitmap,
                            { result ->
                                if (continuation.isActive) continuation.resume(result.toCaptureAttemptResult())
                            },
                            mainHandler,
                        )
                    } catch (failure: IllegalArgumentException) {
                        android.util.Log.w("DevConsole", "PixelCopy.request rejected the capture request", failure)
                        if (continuation.isActive) continuation.resume(CaptureAttemptResult.FAILED)
                    }
                }
            continuation.invokeOnCancellation { mainHandler.removeCallbacks(issueRequest) }
            if (Looper.myLooper() == Looper.getMainLooper()) issueRequest.run() else mainHandler.post(issueRequest)
        }

    /**
     * API 24-25 fallback: `View.draw(Canvas)` must run on the main thread, and `FLAG_SECURE` is not
     * enforced by the platform for it at all -- this class's own re-check immediately before the draw
     * is the only thing standing between a secure window and a captured one. If that secure state
     * cannot be confirmed (the decor view is no longer attached to a window -- e.g. the activity was
     * paused/destroyed or its window replaced while this was in flight), the ambiguity is treated as
     * secure: a refused screenshot is a minor annoyance, a captured secure screen is a security failure.
     */
    private suspend fun captureWithCanvas(
        window: Window,
        decorView: View,
        bitmap: Bitmap,
    ): CaptureAttemptResult =
        suspendCancellableCoroutine { continuation ->
            val draw =
                Runnable {
                    if (!continuation.isActive) return@Runnable
                    val outcome =
                        when {
                            window.isSecure() -> CaptureAttemptResult.SECURE
                            !decorView.isAttachedToWindow -> CaptureAttemptResult.SECURE
                            else -> {
                                val drew = runCatching { decorView.draw(Canvas(bitmap)) }.isSuccess
                                if (drew) CaptureAttemptResult.SUCCESS else CaptureAttemptResult.FAILED
                            }
                        }
                    continuation.resume(outcome)
                }
            continuation.invokeOnCancellation { mainHandler.removeCallbacks(draw) }
            if (Looper.myLooper() == Looper.getMainLooper()) draw.run() else mainHandler.post(draw)
        }

    private companion object {
        /** Same order of magnitude as `AnrWatchdog.DEFAULT_THRESHOLD_MS` -- see the class doc. */
        const val DEFAULT_CAPTURE_TIMEOUT_MS = 5_000L
    }
}

private fun Window.isSecure(): Boolean = attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

/**
 * `ERROR_SOURCE_NO_DATA` is what `PixelCopy` reports when the source window is secure (or otherwise has
 * nothing to copy); it genuinely means "secure/unavailable", not "failed", so it must not collapse into
 * the same [CaptureAttemptResult.FAILED] bucket as e.g. `ERROR_TIMEOUT` or `ERROR_DESTINATION_INVALID`.
 */
internal fun Int.toCaptureAttemptResult(): CaptureAttemptResult =
    when (this) {
        PixelCopy.SUCCESS -> CaptureAttemptResult.SUCCESS
        PixelCopy.ERROR_SOURCE_NO_DATA -> CaptureAttemptResult.SECURE
        else -> CaptureAttemptResult.FAILED
    }

/** Scales down so the longer edge is at most [maxLongestEdgePx]; returns the same instance if already within it. */
internal fun Bitmap.downscaledTo(maxLongestEdgePx: Int): Bitmap {
    val longestEdge = maxOf(width, height)
    if (longestEdge <= maxLongestEdgePx || longestEdge <= 0) return this
    val scale = maxLongestEdgePx.toFloat() / longestEdge
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
}

internal fun Bitmap.toPngBytes(): ByteArray =
    ByteArrayOutputStream().use { out ->
        compress(Bitmap.CompressFormat.PNG, PNG_QUALITY_IGNORED_BUT_REQUIRED, out)
        out.toByteArray()
    }

/** PNG is lossless; `Bitmap.compress`'s quality parameter has no effect but still must be supplied. */
private const val PNG_QUALITY_IGNORED_BUT_REQUIRED = 100
