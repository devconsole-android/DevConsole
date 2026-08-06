/**
 * @author Shakib
 * @since 20/07/26
 */
package io.devconsole.storage.room

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.devconsole.storage.api.StoredEvent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomEventStoreInstrumentedTest {
    private lateinit var database: DevConsoleDatabase

    @Before
    fun createDatabase() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    DevConsoleDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun insertingBeyondQuotaPrunesOldestLowSeverityEventsFirst() =
        runBlocking {
            val store = RoomEventStore(database, RoomRetentionCoordinator(), maxEvents = 3)
            val now = System.currentTimeMillis()
            val events =
                (0 until 5).map { index ->
                    StoredEvent(
                        id = "event-$index",
                        sessionId = "session-1",
                        sequence = index.toLong(),
                        pluginId = "system",
                        type = "system.event",
                        wallTimeMs = now + index,
                        monoTimeNs = index.toLong(),
                        severity = if (index < 3) 0 else 3,
                        summary = "ready",
                    )
                }

            store.insert(events)

            assertEquals(3L, store.eventCount())
            assertEquals(
                setOf("event-2", "event-3", "event-4"),
                store.eventsForSession("session-1").map { it.id }.toSet(),
            )
        }

    /**
     * Real-SQLite counterpart of RoomEventStoreRetainedEventsFilterTest's plain-JVM fake-DAO
     * proof: a crash written under a session that has already ended must still come back through
     * [RoomEventStore.recentEventsForPlugins] once a *different* session is current, and a small
     * [RoomEventStore.recentEventsForSession] limit must not let a flood of unrelated events in the
     * same session crowd the crash out -- the `plugin_id IN (...)` predicate has to run inside the
     * DAO's own query for that to hold, not as a Kotlin-side filter applied after the page is cut.
     */
    @Test
    fun retainedEventsPluginFilterSurvivesACrowdedSessionAndACrashedPreviousRun() =
        runBlocking {
            val store = RoomEventStore(database, RoomRetentionCoordinator())
            val crash =
                StoredEvent(
                    id = "crash-1",
                    sessionId = "previous-run",
                    sequence = 0,
                    pluginId = "crash",
                    type = "crash.uncaught",
                    wallTimeMs = 0,
                    monoTimeNs = 0,
                    severity = 5,
                    summary = "fatal",
                )
            val crowd =
                (0 until 50).map { index ->
                    StoredEvent(
                        id = "log-$index",
                        sessionId = "current",
                        sequence = index.toLong(),
                        pluginId = "logs",
                        type = "logs.line",
                        wallTimeMs = (index + 1).toLong(),
                        monoTimeNs = (index + 1).toLong(),
                        severity = 1,
                        summary = "line $index",
                    )
                }
            store.insert(listOf(crash) + crowd)

            val crossSession = store.recentEventsForPlugins(setOf("crash"), limit = 5)
            val scopedToCurrent = store.recentEventsForSession("current", limit = 5, pluginIds = setOf("crash"))

            assertEquals(listOf("crash-1"), crossSession.map { it.id })
            assertEquals(emptyList<String>(), scopedToCurrent.map { it.id })
        }
}
