/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole.logs.timber

import android.util.Log
import io.devconsole.logs.LogLevel
import io.devconsole.logs.LogRecorder
import timber.log.Timber

// Top-level private, not companion consts: a companion `const` compiles to a public static field
// on the tree class and would leak into the published API.
private const val DEFAULT_TAG: String = "Timber"
private const val TIMBER_STACK_TRACE_SEPARATOR: String = "\n"

/**
 * A [Timber.Tree] that forwards every planted log line to a [LogRecorder], so the host's existing
 * Timber calls join the DevConsole timeline without touching the call sites.
 *
 * Priorities at or above [minimumPriority] are recorded; anything lower is dropped. Android
 * priorities map one-to-one onto [LogLevel], and an unrecognised priority is recorded as
 * [LogLevel.DEBUG]. Timber appends a throwable's stack trace to `message` before calling [log], so
 * that appended copy is stripped here and the throwable is passed to the recorder instead — the
 * entry then carries the stack trace exactly once, redacted as its own field.
 *
 * This module contains no capture runtime: it only forwards to whatever [LogRecorder] the host
 * passes in, which is DevConsole's disabled recorder in protected variants, so it is release-safe
 * and is deliberately absent from the Gradle plugin's protected artifact lists.
 */
class DevConsoleTimberTree
    @JvmOverloads
    constructor(
        private val recorder: LogRecorder,
        private val minimumPriority: Int = Log.VERBOSE,
    ) : Timber.Tree() {
        override fun isLoggable(
            tag: String?,
            priority: Int,
        ): Boolean = priority >= minimumPriority

        override fun log(
            priority: Int,
            tag: String?,
            message: String,
            t: Throwable?,
        ) {
            runCatching {
                recorder.record(
                    level = priority.toLogLevel(),
                    tag = tag ?: DEFAULT_TAG,
                    message = message.withoutTimberStackTrace(t),
                    throwable = t,
                )
            }
        }

        private fun String.withoutTimberStackTrace(throwable: Throwable?): String {
            if (throwable == null) return this
            val stackTrace = throwable.stackTraceToString()
            return if (this == stackTrace) "" else removeSuffix(TIMBER_STACK_TRACE_SEPARATOR + stackTrace)
        }

        private fun Int.toLogLevel(): LogLevel =
            when (this) {
                Log.VERBOSE -> LogLevel.VERBOSE
                Log.DEBUG -> LogLevel.DEBUG
                Log.INFO -> LogLevel.INFO
                Log.WARN -> LogLevel.WARN
                Log.ERROR -> LogLevel.ERROR
                Log.ASSERT -> LogLevel.ASSERT
                else -> LogLevel.DEBUG
            }
    }
