/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.api

/**
 * Outcome of [io.devconsole.DevConsole.captureScreenshot].
 *
 * A screenshot is the most sensitive artifact the SDK can emit -- see [ScreenshotPolicy] for why
 * capture is off by default -- so every way it can fail to produce a usable image gets its own
 * variant rather than folding into a single boolean or a generic failure string.
 */
sealed interface ScreenshotResult {
    /**
     * The capture succeeded and was written through `AttachmentStore` with
     * `RedactionApplicability.NOT_APPLICABLE`, then mirrored onto the timeline as a `"screenshot"`
     * event carrying [attachmentId]. [eventId] is that timeline event's id.
     */
    data class Captured(
        val attachmentId: String,
        val eventId: String,
        val widthPx: Int,
        val heightPx: Int,
        val byteCount: Int,
    ) : ScreenshotResult

    /** [ScreenshotPolicy.enabled] is false. Nothing was captured. */
    data object Disabled : ScreenshotResult

    /** Release build wired to `devconsole-noop`. */
    data object DisabledForBuild : ScreenshotResult

    /** No `Activity` is currently resumed, so there is nothing on screen to capture. */
    data object NoForegroundActivity : ScreenshotResult

    /**
     * The foreground window is `FLAG_SECURE`. A secure window makes the underlying capture API fail
     * or return a blank bitmap; this is returned instead of ever storing that blank result as if it
     * were a real screenshot.
     */
    data object SecureWindow : ScreenshotResult

    /** Capture, downscale, encoding, or storage failed for a reason captured in [reason]. */
    data class Failed(
        val reason: String,
    ) : ScreenshotResult
}
