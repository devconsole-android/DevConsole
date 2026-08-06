/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashPolicyTest {
    @Test
    fun `defaults match the design spec`() {
        val policy = CrashPolicy()

        assertTrue(policy.crashCaptureEnabled)
        assertTrue(policy.anrWatchdogEnabled)
        assertEquals(5_000L, policy.anrThresholdMs)
        assertEquals(50, policy.breadcrumbDepth)
        assertEquals(32 * 1024, policy.maxStackChars)
        assertEquals(64, policy.maxThreadsInDump)
        assertEquals(64, policy.maxFramesPerThread)
        assertTrue(policy.validationErrors().isEmpty())
    }

    @Test
    fun `anrThresholdMs boundary values`() {
        assertTrue(CrashPolicy(anrThresholdMs = 1_000L).validationErrors().isEmpty())
        assertTrue(CrashPolicy(anrThresholdMs = 60_000L).validationErrors().isEmpty())
        assertEquals(
            listOf(ConfigValidationCode.INVALID_ANR_THRESHOLD),
            CrashPolicy(anrThresholdMs = 999L).validationErrors().map { it.code },
        )
        assertEquals(
            listOf(ConfigValidationCode.INVALID_ANR_THRESHOLD),
            CrashPolicy(anrThresholdMs = 60_001L).validationErrors().map { it.code },
        )
    }

    @Test
    fun `breadcrumbDepth boundary values`() {
        assertTrue(CrashPolicy(breadcrumbDepth = 0).validationErrors().isEmpty())
        assertTrue(CrashPolicy(breadcrumbDepth = 500).validationErrors().isEmpty())
        assertEquals(
            listOf(ConfigValidationCode.INVALID_BREADCRUMB_DEPTH),
            CrashPolicy(breadcrumbDepth = -1).validationErrors().map { it.code },
        )
        assertEquals(
            listOf(ConfigValidationCode.INVALID_BREADCRUMB_DEPTH),
            CrashPolicy(breadcrumbDepth = 501).validationErrors().map { it.code },
        )
    }

    @Test
    fun `maxStackChars boundary values`() {
        assertTrue(CrashPolicy(maxStackChars = 1_024).validationErrors().isEmpty())
        assertTrue(CrashPolicy(maxStackChars = 1_048_576).validationErrors().isEmpty())
        assertEquals(
            listOf(ConfigValidationCode.INVALID_MAX_STACK_CHARS),
            CrashPolicy(maxStackChars = 1_023).validationErrors().map { it.code },
        )
        assertEquals(
            listOf(ConfigValidationCode.INVALID_MAX_STACK_CHARS),
            CrashPolicy(maxStackChars = 1_048_577).validationErrors().map { it.code },
        )
    }

    @Test
    fun `maxThreadsInDump boundary values`() {
        assertTrue(CrashPolicy(maxThreadsInDump = 1).validationErrors().isEmpty())
        assertTrue(CrashPolicy(maxThreadsInDump = 512).validationErrors().isEmpty())
        assertEquals(
            listOf(ConfigValidationCode.INVALID_MAX_THREADS_IN_DUMP),
            CrashPolicy(maxThreadsInDump = 0).validationErrors().map { it.code },
        )
        assertEquals(
            listOf(ConfigValidationCode.INVALID_MAX_THREADS_IN_DUMP),
            CrashPolicy(maxThreadsInDump = 513).validationErrors().map { it.code },
        )
    }

    @Test
    fun `maxFramesPerThread boundary values`() {
        assertTrue(CrashPolicy(maxFramesPerThread = 1).validationErrors().isEmpty())
        assertTrue(CrashPolicy(maxFramesPerThread = 512).validationErrors().isEmpty())
        assertEquals(
            listOf(ConfigValidationCode.INVALID_MAX_FRAMES_PER_THREAD),
            CrashPolicy(maxFramesPerThread = 0).validationErrors().map { it.code },
        )
        assertEquals(
            listOf(ConfigValidationCode.INVALID_MAX_FRAMES_PER_THREAD),
            CrashPolicy(maxFramesPerThread = 513).validationErrors().map { it.code },
        )
    }

    @Test
    fun `every field reports its own code when all are invalid simultaneously`() {
        val policy =
            CrashPolicy(
                anrThresholdMs = 0,
                breadcrumbDepth = -5,
                maxStackChars = 0,
                maxThreadsInDump = 0,
                maxFramesPerThread = 0,
            )

        assertEquals(
            setOf(
                ConfigValidationCode.INVALID_ANR_THRESHOLD,
                ConfigValidationCode.INVALID_BREADCRUMB_DEPTH,
                ConfigValidationCode.INVALID_MAX_STACK_CHARS,
                ConfigValidationCode.INVALID_MAX_THREADS_IN_DUMP,
                ConfigValidationCode.INVALID_MAX_FRAMES_PER_THREAD,
            ),
            policy.validationErrors().map { it.code }.toSet(),
        )
    }

    @Test
    fun `withCrashPolicy participates in config equivalence and validation`() {
        val custom = CrashPolicy(anrThresholdMs = 10_000L, breadcrumbDepth = 10)
        val config = DevConsoleConfig.default().withCrashPolicy(custom)

        assertEquals(custom, config.crashPolicy)
        assertTrue(config.validationErrors().isEmpty())
        assertFalse(config.runtimeEquivalentTo(DevConsoleConfig.default()))
    }

    @Test
    fun `an invalid crashPolicy surfaces through DevConsoleConfig validationErrors`() {
        val config = DevConsoleConfig.default().withCrashPolicy(CrashPolicy(anrThresholdMs = 0))

        assertTrue(
            ConfigValidationCode.INVALID_ANR_THRESHOLD in config.validationErrors().map { it.code },
        )
    }

    @Test
    fun `java config builder carries the crash policy`() {
        val config =
            DevConsoleConfig
                .builder()
                .crashPolicy(CrashPolicy(crashCaptureEnabled = false, anrWatchdogEnabled = false))
                .build()

        assertFalse(config.crashPolicy.crashCaptureEnabled)
        assertFalse(config.crashPolicy.anrWatchdogEnabled)
    }
}
