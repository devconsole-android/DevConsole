/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.api

/**
 * Controls uncaught-exception and ANR capture depth.
 *
 * [crashCaptureEnabled] gates whether [io.devconsole.DevConsole] ever chains itself into the host's
 * `Thread.defaultUncaughtExceptionHandler`; when `false` the handler is left completely untouched.
 * [anrWatchdogEnabled] gates whether the ANR-detection thread ever starts.
 *
 * The remaining fields bound the depth of what gets captured once those two gates are open:
 * [anrThresholdMs] is how long the main thread must go unresponsive before an ANR is reported;
 * [breadcrumbDepth] is the size of the ring buffer of recent, already-redacted timeline summaries
 * serialized into the crash/ANR payload; [maxStackChars], [maxThreadsInDump], and
 * [maxFramesPerThread] bound the all-thread dump captured on an ANR.
 */
data class CrashPolicy(
    val crashCaptureEnabled: Boolean = true,
    val anrWatchdogEnabled: Boolean = true,
    val anrThresholdMs: Long = DEFAULT_ANR_THRESHOLD_MS,
    val breadcrumbDepth: Int = DEFAULT_BREADCRUMB_DEPTH,
    val maxStackChars: Int = DEFAULT_MAX_STACK_CHARS,
    val maxThreadsInDump: Int = DEFAULT_MAX_THREADS_IN_DUMP,
    val maxFramesPerThread: Int = DEFAULT_MAX_FRAMES_PER_THREAD,
) {
    fun validationErrors(): List<ConfigValidationError> =
        buildList {
            if (anrThresholdMs !in ANR_THRESHOLD_RANGE) add(invalidAnrThresholdError())
            if (breadcrumbDepth !in BREADCRUMB_DEPTH_RANGE) add(invalidBreadcrumbDepthError())
            if (maxStackChars !in MAX_STACK_CHARS_RANGE) add(invalidMaxStackCharsError())
            if (maxThreadsInDump !in MAX_THREADS_IN_DUMP_RANGE) add(invalidMaxThreadsInDumpError())
            if (maxFramesPerThread !in MAX_FRAMES_PER_THREAD_RANGE) add(invalidMaxFramesPerThreadError())
        }

    companion object {
        const val DEFAULT_ANR_THRESHOLD_MS: Long = 5_000L
        const val DEFAULT_BREADCRUMB_DEPTH: Int = 50
        const val DEFAULT_MAX_STACK_CHARS: Int = 32 * 1024
        const val DEFAULT_MAX_THREADS_IN_DUMP: Int = 64
        const val DEFAULT_MAX_FRAMES_PER_THREAD: Int = 64

        val ANR_THRESHOLD_RANGE: LongRange = 1_000L..60_000L
        val BREADCRUMB_DEPTH_RANGE: IntRange = 0..500
        val MAX_STACK_CHARS_RANGE: IntRange = 1_024..1_048_576
        val MAX_THREADS_IN_DUMP_RANGE: IntRange = 1..512
        val MAX_FRAMES_PER_THREAD_RANGE: IntRange = 1..512
    }
}

private fun invalidAnrThresholdError() =
    ConfigValidationError(
        ConfigValidationCode.INVALID_ANR_THRESHOLD,
        "crashPolicy.anrThresholdMs",
        "anrThresholdMs must be within ${CrashPolicy.ANR_THRESHOLD_RANGE}",
    )

private fun invalidBreadcrumbDepthError() =
    ConfigValidationError(
        ConfigValidationCode.INVALID_BREADCRUMB_DEPTH,
        "crashPolicy.breadcrumbDepth",
        "breadcrumbDepth must be within ${CrashPolicy.BREADCRUMB_DEPTH_RANGE}",
    )

private fun invalidMaxStackCharsError() =
    ConfigValidationError(
        ConfigValidationCode.INVALID_MAX_STACK_CHARS,
        "crashPolicy.maxStackChars",
        "maxStackChars must be within ${CrashPolicy.MAX_STACK_CHARS_RANGE}",
    )

private fun invalidMaxThreadsInDumpError() =
    ConfigValidationError(
        ConfigValidationCode.INVALID_MAX_THREADS_IN_DUMP,
        "crashPolicy.maxThreadsInDump",
        "maxThreadsInDump must be within ${CrashPolicy.MAX_THREADS_IN_DUMP_RANGE}",
    )

private fun invalidMaxFramesPerThreadError() =
    ConfigValidationError(
        ConfigValidationCode.INVALID_MAX_FRAMES_PER_THREAD,
        "crashPolicy.maxFramesPerThread",
        "maxFramesPerThread must be within ${CrashPolicy.MAX_FRAMES_PER_THREAD_RANGE}",
    )
