/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole.logs.timber

import android.util.Log
import io.devconsole.logs.LogEntry
import io.devconsole.logs.LogLevel
import io.devconsole.logs.LogRecorder
import io.devconsole.logs.LogSink
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

class DevConsoleTimberTreeTest {
    private val redaction = RedactionEngine(RedactionPolicy.recommended())
    private val emitted = mutableListOf<LogEntry>()
    private val recorder = LogRecorder(redaction, LogSink(emitted::add), nowEpochMs = { FIXED_TIME_MS })

    @After
    fun tearDown() {
        Timber.uprootAll()
    }

    @Test
    fun `records an info line under the fallback tag`() {
        Timber.plant(DevConsoleTimberTree(recorder))

        Timber.i("Cart opened with 3 items")

        val entry = emitted.single()
        assertEquals(LogLevel.INFO, entry.level)
        assertEquals(FALLBACK_TAG, entry.tag)
        assertEquals("Cart opened with 3 items", entry.message)
        assertNull(entry.stackTrace)
    }

    @Test
    fun `maps every android priority`() {
        Timber.plant(DevConsoleTimberTree(recorder))
        val expected =
            mapOf(
                Log.VERBOSE to LogLevel.VERBOSE,
                Log.DEBUG to LogLevel.DEBUG,
                Log.INFO to LogLevel.INFO,
                Log.WARN to LogLevel.WARN,
                Log.ERROR to LogLevel.ERROR,
                Log.ASSERT to LogLevel.ASSERT,
            )

        expected.keys.forEach { priority -> Timber.log(priority, "priority $priority") }

        assertEquals(expected.values.toList(), emitted.map { it.level })
    }

    @Test
    fun `records an unknown priority as debug`() {
        Timber.plant(DevConsoleTimberTree(recorder))

        Timber.log(UNKNOWN_PRIORITY, "unmapped priority")

        assertEquals(LogLevel.DEBUG, emitted.single().level)
    }

    @Test
    fun `uses an explicit timber tag`() {
        Timber.plant(DevConsoleTimberTree(recorder))

        Timber.tag("Checkout").i("Cart opened with 3 items")

        assertEquals("Checkout", emitted.single().tag)
    }

    @Test
    fun `strips timber's appended stack trace and passes the throwable instead`() {
        Timber.plant(DevConsoleTimberTree(recorder))
        val failure = IllegalStateException("Card issuer returned code 05")

        Timber.e(failure, "Payment declined")

        val entry = emitted.single()
        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals("Payment declined", entry.message)
        assertTrue(requireNotNull(entry.stackTrace).contains("IllegalStateException"))
    }

    @Test
    fun `records only the stack trace when the message is empty`() {
        Timber.plant(DevConsoleTimberTree(recorder))
        val failure = IllegalStateException("Card issuer returned code 05")

        Timber.e(failure)

        val entry = emitted.single()
        assertEquals("", entry.message)
        assertTrue(requireNotNull(entry.stackTrace).contains("IllegalStateException"))
    }

    @Test
    fun `drops lines below the minimum priority`() {
        Timber.plant(DevConsoleTimberTree(recorder, Log.WARN))

        Timber.d("dropped")
        Timber.w("kept")

        assertEquals(listOf(LogLevel.WARN), emitted.map { it.level })
    }

    private companion object {
        const val FIXED_TIME_MS = 42L
        const val UNKNOWN_PRIORITY = 99
        const val FALLBACK_TAG = "Timber"
    }
}
