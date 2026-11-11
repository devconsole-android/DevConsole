/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.ui.compose

import io.devconsole.api.ScreenshotResult

/** Formats screenshot results for the More screen. */
internal fun ScreenshotResult.toFlashMessage(): String =
    when (this) {
        is ScreenshotResult.Captured ->
            "Screenshot captured — $widthPx×$heightPx, ${formatByteSize(byteCount.toLong())}"
        ScreenshotResult.Disabled -> "Screenshots are off in this build. A developer can enable screenshotPolicy."
        ScreenshotResult.DisabledForBuild -> "Screenshot capture isn't available in this release build"
        ScreenshotResult.NoForegroundActivity ->
            "No foreground activity to capture — bring the app to the front and try again"
        ScreenshotResult.SecureWindow -> "This screen is FLAG_SECURE and cannot be captured"
        is ScreenshotResult.Failed -> "Screenshot failed — $reason"
    }
