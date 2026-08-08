/**
 * @author Shakib
 * @since 08/08/26
 */
package io.devconsole

import io.devconsole.storage.api.EventStore
import io.devconsole.storage.api.EventStoreWriteResult
import io.devconsole.storage.api.StoredEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the "previous run crashed" banner against firing for runs that merely had their process
 * killed: [recordedUncaughtCrash] is the only thing standing between an ordinary swipe-away and a
 * `CRASHED` stored status.
 */
class StaleSessionOutcomeTest {
    @Test
    fun `a run that recorded an uncaught exception closes as crashed`() =
        runTest {
            val store = FakeEventStore(listOf(crashEvent("dead", type = "uncaught")))

            assertTrue(store.recordedUncaughtCrash("dead"))
        }

    @Test
    fun `a run killed without any crash record is not reported as crashed`() =
        runTest {
            val store =
                FakeEventStore(
                    listOf(
                        event("dead", pluginId = "net", type = "http"),
                        event("dead", pluginId = "logs", type = "log"),
                    ),
                )

            assertFalse(store.recordedUncaughtCrash("dead"))
        }

    /** A survived main-thread stall is not a crashed run, even though ANRs share the crash plugin. */
    @Test
    fun `an ANR alone is not a crash`() =
        runTest {
            val store = FakeEventStore(listOf(crashEvent("dead", type = "anr")))

            assertFalse(store.recordedUncaughtCrash("dead"))
        }

    /** Trailing ANR records must not push the one crash record out of the probe window. */
    @Test
    fun `a crash followed by ANR records is still found`() =
        runTest {
            val store =
                FakeEventStore(
                    listOf(crashEvent("dead", type = "uncaught")) +
                        List(3) { crashEvent("dead", type = "anr") },
                )

            assertTrue(store.recordedUncaughtCrash("dead"))
        }

    @Test
    fun `another run's crash never marks this one crashed`() =
        runTest {
            val store = FakeEventStore(listOf(crashEvent("other", type = "uncaught")))

            assertFalse(store.recordedUncaughtCrash("dead"))
        }

    /** An unreadable store must close the leftover row as completed, not fail the bootstrap. */
    @Test
    fun `an event store that throws reads as not crashed`() =
        runTest {
            val store =
                object : FakeEventStore(emptyList()) {
                    override suspend fun recentEventsForSession(
                        sessionId: String,
                        limit: Int,
                        pluginIds: Set<String>,
                    ): List<StoredEvent> = error("database unavailable")
                }

            assertFalse(store.recordedUncaughtCrash("dead"))
        }

    @Test
    fun `only crash plugin rows are read back`() =
        runTest {
            val store = FakeEventStore(listOf(crashEvent("dead", type = "uncaught")))

            store.recordedUncaughtCrash("dead")

            assertEquals(setOf("crash"), store.lastPluginIds)
        }

    private fun crashEvent(
        sessionId: String,
        type: String,
    ) = event(sessionId, pluginId = "crash", type = type)

    private fun event(
        sessionId: String,
        pluginId: String,
        type: String,
    ) = StoredEvent(
        id = "$sessionId-$pluginId-$type-${sequence++}",
        sessionId = sessionId,
        sequence = sequence.toLong(),
        pluginId = pluginId,
        type = type,
        wallTimeMs = 0,
        monoTimeNs = 0,
        severity = 4,
        summary = type,
    )

    private var sequence = 0

    /** Mirrors `RoomEventStore`: the plugin filter is applied before the newest-[limit] window. */
    private open class FakeEventStore(
        private val events: List<StoredEvent>,
    ) : EventStore {
        var lastPluginIds: Set<String>? = null
            private set

        override suspend fun recentEventsForSession(
            sessionId: String,
            limit: Int,
            pluginIds: Set<String>,
        ): List<StoredEvent> {
            lastPluginIds = pluginIds
            return events
                .filter { it.sessionId == sessionId && (pluginIds.isEmpty() || it.pluginId in pluginIds) }
                .takeLast(limit)
        }

        override suspend fun insert(events: List<StoredEvent>) = EventStoreWriteResult.Success(events.size)

        override suspend fun eventsForSession(sessionId: String): List<StoredEvent> =
            events.filter { it.sessionId == sessionId }

        override suspend fun deleteSession(sessionId: String) = Unit

        override suspend fun eventCount(): Long = events.size.toLong()
    }
}
