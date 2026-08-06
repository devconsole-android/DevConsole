package io.devconsole.logs

import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRecorderTest {
    private val redaction = RedactionEngine(RedactionPolicy.default())
    private val emitted = mutableListOf<LogEntry>()
    private val sink = LogSink(emitted::add)

    @Test
    fun `records a redacted entry`() {
        LogRecorder(redaction, sink, nowEpochMs = { 42L })
            .record(LogLevel.INFO, "Checkout", "calling with Authorization: Bearer abc123def")

        val entry = emitted.single()
        assertEquals(LogLevel.INFO, entry.level)
        assertEquals("Checkout", entry.tag)
        assertEquals(42L, entry.timestampEpochMs)
        assertTrue("expected the bearer token to be redacted, got: ${entry.message}", "abc123def" !in entry.message)
        assertNull(entry.stackTrace)
    }

    @Test
    fun `a disabled recorder emits nothing`() {
        LogRecorder(redaction, sink, enabled = false).record(LogLevel.ERROR, "Tag", "message")

        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `truncates an oversized message instead of scanning all of it`() {
        LogRecorder(redaction, sink, maxMessageChars = 32).record(LogLevel.DEBUG, "Tag", "x".repeat(10_000))

        assertEquals(32, emitted.single().message.length)
    }

    @Test
    fun `captures a bounded stack trace when a throwable is supplied`() {
        LogRecorder(redaction, sink, maxStackTraceChars = 64)
            .record(LogLevel.ERROR, "Tag", "boom", IllegalStateException("failed"))

        val trace = requireNotNull(emitted.single().stackTrace)
        assertTrue(trace.length <= 64)
        assertTrue("IllegalStateException" in trace)
    }

    @Test
    fun `a throwing sink never propagates to the caller`() {
        LogRecorder(redaction, { error("sink exploded") }).record(LogLevel.WARN, "Tag", "message")
    }

    @Test
    fun `truncates an oversized tag`() {
        LogRecorder(redaction, sink).record(LogLevel.INFO, "t".repeat(500), "message")

        assertEquals(LogRecorder.MAX_TAG_CHARS, emitted.single().tag.length)
    }
}
