/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotPolicyTest {
    @Test
    fun `defaults match the design spec, including being off by default`() {
        val policy = ScreenshotPolicy()

        assertFalse(policy.enabled)
        assertEquals(1080, policy.maxLongestEdgePx)
        assertEquals(2L * 1024 * 1024, policy.maxBytes)
        assertTrue(policy.validationErrors().isEmpty())
    }

    @Test
    fun `maxLongestEdgePx boundary values`() {
        assertTrue(ScreenshotPolicy(maxLongestEdgePx = 240).validationErrors().isEmpty())
        assertTrue(ScreenshotPolicy(maxLongestEdgePx = 4096).validationErrors().isEmpty())
        assertEquals(
            listOf(ConfigValidationCode.INVALID_SCREENSHOT_MAX_LONGEST_EDGE),
            ScreenshotPolicy(maxLongestEdgePx = 239).validationErrors().map { it.code },
        )
        assertEquals(
            listOf(ConfigValidationCode.INVALID_SCREENSHOT_MAX_LONGEST_EDGE),
            ScreenshotPolicy(maxLongestEdgePx = 4097).validationErrors().map { it.code },
        )
    }

    @Test
    fun `maxBytes boundary values`() {
        assertTrue(ScreenshotPolicy(maxBytes = 65_536L).validationErrors().isEmpty())
        assertTrue(ScreenshotPolicy(maxBytes = 16_777_216L).validationErrors().isEmpty())
        assertEquals(
            listOf(ConfigValidationCode.INVALID_SCREENSHOT_MAX_BYTES),
            ScreenshotPolicy(maxBytes = 65_535L).validationErrors().map { it.code },
        )
        assertEquals(
            listOf(ConfigValidationCode.INVALID_SCREENSHOT_MAX_BYTES),
            ScreenshotPolicy(maxBytes = 16_777_217L).validationErrors().map { it.code },
        )
    }

    @Test
    fun `every field reports its own code when all are invalid simultaneously`() {
        val policy = ScreenshotPolicy(maxLongestEdgePx = 0, maxBytes = 0)

        assertEquals(
            setOf(
                ConfigValidationCode.INVALID_SCREENSHOT_MAX_LONGEST_EDGE,
                ConfigValidationCode.INVALID_SCREENSHOT_MAX_BYTES,
            ),
            policy.validationErrors().map { it.code }.toSet(),
        )
    }

    @Test
    fun `withScreenshotPolicy participates in config equivalence and validation`() {
        val custom = ScreenshotPolicy(enabled = true, maxLongestEdgePx = 720)
        val config = DevConsoleConfig.default().withScreenshotPolicy(custom)

        assertEquals(custom, config.screenshotPolicy)
        assertTrue(config.validationErrors().isEmpty())
        assertFalse(config.runtimeEquivalentTo(DevConsoleConfig.default()))
    }

    @Test
    fun `an invalid screenshotPolicy surfaces through DevConsoleConfig validationErrors`() {
        val config = DevConsoleConfig.default().withScreenshotPolicy(ScreenshotPolicy(maxBytes = 0))

        assertTrue(
            ConfigValidationCode.INVALID_SCREENSHOT_MAX_BYTES in config.validationErrors().map { it.code },
        )
    }

    @Test
    fun `java config builder carries the screenshot policy`() {
        val config =
            DevConsoleConfig
                .builder()
                .screenshotPolicy(ScreenshotPolicy(enabled = true, maxLongestEdgePx = 640))
                .build()

        assertTrue(config.screenshotPolicy.enabled)
        assertEquals(640, config.screenshotPolicy.maxLongestEdgePx)
    }

    @Test
    fun `default DevConsoleConfig keeps screenshot capture disabled`() {
        assertFalse(DevConsoleConfig.default().screenshotPolicy.enabled)
    }
}
