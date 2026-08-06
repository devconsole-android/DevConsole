/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.api

/**
 * Controls whether and how [io.devconsole.DevConsole.captureScreenshot] may capture the foreground
 * window.
 *
 * [enabled] defaults to `false` -- a deliberate product decision, not an oversight. A screenshot
 * cannot be redacted (see `RedactionApplicability.NOT_APPLICABLE` in `sdk:storage-api`), so it is the
 * single most sensitive artifact this SDK can produce; a host must opt in explicitly. [maxLongestEdgePx]
 * bounds the downscaled image's longer edge; [maxBytes] bounds the encoded PNG, and a capture that
 * would exceed it is rejected rather than stored.
 */
data class ScreenshotPolicy(
    val enabled: Boolean = false,
    val maxLongestEdgePx: Int = DEFAULT_MAX_LONGEST_EDGE_PX,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    fun validationErrors(): List<ConfigValidationError> =
        buildList {
            if (maxLongestEdgePx !in MAX_LONGEST_EDGE_PX_RANGE) add(invalidMaxLongestEdgePxError())
            if (maxBytes !in MAX_BYTES_RANGE) add(invalidMaxBytesError())
        }

    companion object {
        const val DEFAULT_MAX_LONGEST_EDGE_PX: Int = 1080
        const val DEFAULT_MAX_BYTES: Long = 2L * 1024 * 1024

        val MAX_LONGEST_EDGE_PX_RANGE: IntRange = 240..4096
        val MAX_BYTES_RANGE: LongRange = 65_536L..16_777_216L
    }
}

private fun invalidMaxLongestEdgePxError() =
    ConfigValidationError(
        ConfigValidationCode.INVALID_SCREENSHOT_MAX_LONGEST_EDGE,
        "screenshotPolicy.maxLongestEdgePx",
        "maxLongestEdgePx must be within ${ScreenshotPolicy.MAX_LONGEST_EDGE_PX_RANGE}",
    )

private fun invalidMaxBytesError() =
    ConfigValidationError(
        ConfigValidationCode.INVALID_SCREENSHOT_MAX_BYTES,
        "screenshotPolicy.maxBytes",
        "maxBytes must be within ${ScreenshotPolicy.MAX_BYTES_RANGE}",
    )
