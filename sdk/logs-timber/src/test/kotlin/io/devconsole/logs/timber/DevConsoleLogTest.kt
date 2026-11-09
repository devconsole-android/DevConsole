/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole.logs.timber

import io.devconsole.logs.LogEntry
import io.devconsole.logs.LogLevel
import io.devconsole.logs.LogRecorder
import io.devconsole.logs.LogSink
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevConsoleLogTest {
    private val redaction = RedactionEngine(RedactionPolicy.recommended())
    private val emitted = mutableListOf<LogEntry>()
    private val recorder = LogRecorder(redaction, LogSink(emitted::add), nowEpochMs = { FIXED_TIME_MS })

    @After
    fun tearDown() {
        DevConsoleLog.install(LogRecorder(redaction, LogSink {}, enabled = false))
    }

    @Test
    fun `records every level with its android counterpart`() {
        DevConsoleLog.install(recorder)

        DevConsoleLog.v("Tag", "verbose")
        DevConsoleLog.d("Tag", "debug")
        DevConsoleLog.i("Tag", "info")
        DevConsoleLog.w("Tag", "warn")
        DevConsoleLog.e("Tag", "error")
        DevConsoleLog.wtf("Tag", "assert")

        assertEquals(
            listOf(
                LogLevel.VERBOSE,
                LogLevel.DEBUG,
                LogLevel.INFO,
                LogLevel.WARN,
                LogLevel.ERROR,
                LogLevel.ASSERT,
            ),
            emitted.map { it.level },
        )
    }

    @Test
    fun `forwards the tag message and stack trace`() {
        DevConsoleLog.install(recorder)
        val failure = IllegalStateException("boom")

        DevConsoleLog.w("Checkout", "Payment declined", failure)

        val entry = emitted.single()
        assertEquals(LogLevel.WARN, entry.level)
        assertEquals("Checkout", entry.tag)
        assertEquals("Payment declined", entry.message)
        assertTrue(requireNotNull(entry.stackTrace).contains("IllegalStateException"))
    }

    @Test
    fun `returns the platform log result`() {
        DevConsoleLog.install(recorder)

        assertEquals(PLATFORM_LOG_RESULT, DevConsoleLog.i("Tag", "info"))
    }

    @Test
    fun `records nothing while the installed recorder is disabled`() {
        DevConsoleLog.install(LogRecorder(redaction, LogSink(emitted::add), enabled = false))

        DevConsoleLog.i("Tag", "info")

        assertTrue(emitted.isEmpty())
    }

    private companion object {
        const val FIXED_TIME_MS = 42L
        const val PLATFORM_LOG_RESULT = 0
    }
}
