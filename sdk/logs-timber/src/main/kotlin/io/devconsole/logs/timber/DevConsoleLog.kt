/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole.logs.timber

import android.util.Log
import io.devconsole.logs.LogLevel
import io.devconsole.logs.LogRecorder

/**
 * Drop-in replacement for [Log] whose calls also reach the DevConsole timeline.
 *
 * Each method delegates to the matching [Log] method first and returns that result unchanged, then
 * forwards the same line to the [LogRecorder] installed by [install]. Forwarding is a no-op until
 * [install] is called, and in protected variants the installed recorder is DevConsole's disabled
 * one, so nothing is captured — the platform log call is unaffected either way.
 */
object DevConsoleLog {
    @Volatile
    private var recorder: LogRecorder? = null

    /** Installs [recorder] as the destination for all subsequent lines. Safe to call from any thread. */
    @JvmStatic
    fun install(recorder: LogRecorder) {
        this.recorder = recorder
    }

    /** Logs a verbose line through [Log] and the installed recorder, returning [Log]'s result. */
    @JvmStatic
    @JvmOverloads
    fun v(
        tag: String,
        msg: String,
        tr: Throwable? = null,
    ): Int = forward(LogLevel.VERBOSE, tag, msg, tr) { Log.v(tag, msg, tr) }

    /** Logs a debug line through [Log] and the installed recorder, returning [Log]'s result. */
    @JvmStatic
    @JvmOverloads
    fun d(
        tag: String,
        msg: String,
        tr: Throwable? = null,
    ): Int = forward(LogLevel.DEBUG, tag, msg, tr) { Log.d(tag, msg, tr) }

    /** Logs an info line through [Log] and the installed recorder, returning [Log]'s result. */
    @JvmStatic
    @JvmOverloads
    fun i(
        tag: String,
        msg: String,
        tr: Throwable? = null,
    ): Int = forward(LogLevel.INFO, tag, msg, tr) { Log.i(tag, msg, tr) }

    /** Logs a warning line through [Log] and the installed recorder, returning [Log]'s result. */
    @JvmStatic
    @JvmOverloads
    fun w(
        tag: String,
        msg: String,
        tr: Throwable? = null,
    ): Int = forward(LogLevel.WARN, tag, msg, tr) { Log.w(tag, msg, tr) }

    /** Logs an error line through [Log] and the installed recorder, returning [Log]'s result. */
    @JvmStatic
    @JvmOverloads
    fun e(
        tag: String,
        msg: String,
        tr: Throwable? = null,
    ): Int = forward(LogLevel.ERROR, tag, msg, tr) { Log.e(tag, msg, tr) }

    /** Logs an assert line through [Log] and the installed recorder, returning [Log]'s result. */
    @JvmStatic
    @JvmOverloads
    fun wtf(
        tag: String,
        msg: String,
        tr: Throwable? = null,
    ): Int = forward(LogLevel.ASSERT, tag, msg, tr) { Log.wtf(tag, msg, tr) }

    private inline fun forward(
        level: LogLevel,
        tag: String,
        msg: String,
        tr: Throwable?,
        platformCall: () -> Int,
    ): Int {
        val result = platformCall()
        val installed = recorder
        runCatching { installed?.record(level, tag, msg, tr) }
        return result
    }
}
