/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.ui.compose

import io.devconsole.api.ScreenshotResult

/**
 * Turns the More screen's [ScreenshotResult] into a distinct, human-readable flash message for
 * every variant -- in particular [ScreenshotResult.Disabled] names the exact config property the
 * host has to set (matching how every other gated control in this product explains itself, e.g.
 * [InspectorCommandResult.Disabled]'s "$capability capability is off"), and
 * [ScreenshotResult.SecureWindow] explains the real platform reason (`FLAG_SECURE`) rather than just
 * failing silently.
 */
internal fun ScreenshotResult.toFlashMessage(): String =
    when (this) {
        is ScreenshotResult.Captured ->
            "Screenshot captured — $widthPx×$heightPx, ${formatByteSize(byteCount.toLong())}"
        ScreenshotResult.Disabled ->
            "Screenshot capture is off — set screenshotPolicy.enabled = true on DevConsoleConfig " +
                "(DevConsoleConfig().withScreenshotPolicy(ScreenshotPolicy(enabled = true))) to turn it on"
        ScreenshotResult.DisabledForBuild -> "Screenshot capture isn't available in this release build"
        ScreenshotResult.NoForegroundActivity ->
            "No foreground activity to capture — bring the app to the front and try again"
        ScreenshotResult.SecureWindow -> "This screen is FLAG_SECURE and cannot be captured"
        is ScreenshotResult.Failed -> "Screenshot failed — $reason"
    }
