package io.devconsole.storage.room

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SqliteCorruptionDetectionTest {
    @Test
    fun `detects only explicit sqlite corruption signatures`() {
        assertTrue(RuntimeException("database disk image is malformed").isSqliteCorruption())
        assertTrue(RuntimeException("outer", RuntimeException("file is not a database")).isSqliteCorruption())
        assertFalse(RuntimeException("database or disk is full").isSqliteCorruption())
        assertFalse(IllegalStateException("programming failure").isSqliteCorruption())
    }

    @Test
    fun `retries a corrupt operation against the recovered resource exactly once`() =
        runBlocking {
            var activeResource = "stale"
            var recoveryCount = 0
            val attempts = mutableListOf<String>()

            val result =
                executeWithSqliteRecovery(
                    unavailable = false,
                    resourceProvider = { activeResource },
                    recover = {
                        recoveryCount++
                        activeResource = "fresh"
                    },
                ) { resource ->
                    attempts += resource
                    if (resource == "stale") {
                        // Reproduces the exact bare SQLiteException message the recovery path keys on;
                        // a more specific type would not exercise the string-match detection under test.
                        @Suppress("TooGenericExceptionThrown")
                        throw RuntimeException("database disk image is malformed")
                    }
                    true
                }

            assertTrue(result)
            assertTrue(attempts == listOf("stale", "fresh"))
            assertTrue(recoveryCount == 1)
        }

    @Test
    fun `never converts cancellation into an unavailable result`() =
        runBlocking {
            val cancellation = CancellationException("stop")

            try {
                executeWithSqliteRecovery(
                    unavailable = false,
                    resourceProvider = { "resource" },
                    recover = {},
                ) {
                    throw cancellation
                }
                fail("Expected cancellation")
            } catch (actual: CancellationException) {
                assertSame(cancellation, actual)
            }
        }
}
