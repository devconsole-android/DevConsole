/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.storage.room

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves [RoomEventStore.recentEventsForSession] and [RoomEventStore.recentEventsForPlugins] push
 * a `pluginId` filter into the query layer -- not "fetch the newest N rows, then filter in
 * Kotlin," which would let an unrelated flood of events crowd out a rare match (exactly the bug
 * `/api/v1/retained-events` had before pluginId filtering existed: pulling 500 mixed events just to
 * find one crash). [FakePluginFilterEventDao] deliberately makes the *unfiltered* DAO queries
 * return only recent non-matching rows, so if [RoomEventStore] ever regressed to calling the wrong
 * DAO method (or filtering post-hoc after an unfiltered fetch) instead of the dedicated
 * `...ByPlugin` query, these tests would see an empty result instead of the planted crash and fail.
 *
 * This module's plain JVM unit tests cannot run a real Room-built database (see
 * SessionRetentionPrunerTest's class KDoc); [RoomEventStore.recentEventsForSession] and
 * [RoomEventStore.recentEventsForPlugins] never touch `runInTransaction`, so a bare
 * [DevConsoleDatabase] subclass wired to a fake [EventDao] -- this module's established style, see
 * EventQuotaPrunerTest/SessionRetentionPrunerTest -- is enough to exercise them here. Real SQLite
 * execution of the `plugin_id IN (...)` queries themselves is exercised by
 * RoomEventStoreInstrumentedTest.
 */
class RoomEventStoreRetainedEventsFilterTest {
    @Test
    fun `recentEventsForSession filters by plugin before the limit trims rows, not after`() =
        runBlocking {
            val dao = FakePluginFilterEventDao()
            // The crash is the single oldest row in the session; every "wrong data" trap below
            // (recentEventsForSession's unfiltered top-N) would keep the 50 newer logs instead.
            dao.events["crash-1"] = pluginEvent("crash-1", "session-1", "crash", wallTimeMs = 0)
            repeat(50) { index ->
                val wallTimeMs = (index + 1).toLong()
                dao.events["log-$index"] = pluginEvent("log-$index", "session-1", "logs", wallTimeMs)
            }
            val store = RoomEventStore(fakePluginFilterDatabase(dao), RoomRetentionCoordinator())

            val result = store.recentEventsForSession("session-1", limit = 5, pluginIds = setOf("crash"))

            assertEquals(listOf("crash-1"), result.map { it.id })
        }

    @Test
    fun `recentEventsForSession with no pluginIds keeps the original unfiltered behavior`() =
        runBlocking {
            val dao = FakePluginFilterEventDao()
            repeat(10) { index ->
                val wallTimeMs = (index + 1).toLong()
                dao.events["log-$index"] = pluginEvent("log-$index", "session-1", "logs", wallTimeMs)
            }
            val store = RoomEventStore(fakePluginFilterDatabase(dao), RoomRetentionCoordinator())

            val result = store.recentEventsForSession("session-1", limit = 3)

            // FakePluginFilterEventDao's unfiltered recentEventsForSession returns the newest N --
            // log-7, log-8, log-9 -- exactly as it did before pluginIds existed.
            assertEquals(listOf("log-7", "log-8", "log-9"), result.map { it.id })
        }

    @Test
    fun `recentEventsForPlugins filters across every session before the limit trims rows, not after`() =
        runBlocking {
            val dao = FakePluginFilterEventDao()
            // The crash sits under a session that is no longer current; a flood of newer, unrelated
            // events in two other sessions must not crowd it out of a small cross-session limit.
            dao.events["crash-1"] = pluginEvent("crash-1", "previous-run", "crash", wallTimeMs = 0)
            repeat(25) { index ->
                val wallTimeMs = (index + 1).toLong()
                dao.events["log-a-$index"] = pluginEvent("log-a-$index", "run-a", "logs", wallTimeMs)
            }
            repeat(25) { index ->
                val wallTimeMs = (index + 26).toLong()
                dao.events["log-b-$index"] = pluginEvent("log-b-$index", "run-b", "logs", wallTimeMs)
            }
            val store = RoomEventStore(fakePluginFilterDatabase(dao), RoomRetentionCoordinator())

            val result = store.recentEventsForPlugins(setOf("crash"), limit = 5)

            assertEquals(listOf("crash-1"), result.map { it.id })
        }
}

private fun pluginEvent(
    id: String,
    sessionId: String,
    pluginId: String,
    wallTimeMs: Long,
) = EventEntity(
    id,
    sessionId,
    wallTimeMs,
    pluginId,
    "$pluginId.event",
    wallTimeMs,
    wallTimeMs,
    1,
    "summary",
    null,
    "{}",
    null,
    null,
    1,
)

private fun fakePluginFilterDatabase(eventDao: EventDao): DevConsoleDatabase =
    object : DevConsoleDatabase() {
        override fun eventDao(): EventDao = eventDao

        override fun attachmentDao(): AttachmentDao = error("not touched by these tests")

        override fun timelineAnnotationDao(): TimelineAnnotationDao = error("not touched by these tests")

        override fun sessionDao(): SessionDao = error("not touched by these tests")

        override fun captureRuleDao(): CaptureRuleDao = error("not touched by these tests")

        override fun evidenceItemDao(): EvidenceItemDao = error("not touched by these tests")

        override fun evidenceReportDao(): EvidenceReportDao = error("not touched by these tests")

        override fun createInvalidationTracker(): androidx.room.InvalidationTracker =
            error("not touched by these tests")

        override fun clearAllTables(): Unit = error("not touched by these tests")
    }

/**
 * The unfiltered members ([recentEventsForSession], [recentEvents]) deliberately ignore
 * `plugin_id` -- exactly like the real DAO's own unfiltered queries -- so a test that reaches them
 * instead of the `...ByPlugin` queries when it shouldn't gets back the wrong rows and fails loudly,
 * rather than silently passing because the fake happened to filter everywhere.
 */
private class FakePluginFilterEventDao : EventDao {
    val events = linkedMapOf<String, EventEntity>()

    override fun insertAll(events: List<EventEntity>) {
        events.forEach { this.events[it.id] = it }
    }

    override fun eventsForSession(sessionId: String): List<EventEntity> =
        events.values.filter { it.sessionId == sessionId }

    override fun recentEventsForSession(
        sessionId: String,
        limit: Int,
    ): List<EventEntity> =
        events.values
            .filter { it.sessionId == sessionId }
            .sortedBy { it.wallTimeMs }
            .takeLast(limit)

    override fun recentEventsForSessionByPlugin(
        sessionId: String,
        pluginIds: List<String>,
        limit: Int,
    ): List<EventEntity> =
        events.values
            .filter { it.sessionId == sessionId && it.pluginId in pluginIds }
            .sortedBy { it.wallTimeMs }
            .takeLast(limit)

    override fun recentEventsByPlugin(
        pluginIds: List<String>,
        limit: Int,
    ): List<EventEntity> =
        events.values
            .filter { it.pluginId in pluginIds }
            .sortedBy { it.wallTimeMs }
            .takeLast(limit)

    override fun deleteSession(sessionId: String) {
        events.values.filter { it.sessionId == sessionId }.forEach { events.remove(it.id) }
    }

    override fun eventCount(): Long = events.size.toLong()

    override fun deleteOlderThan(cutoffEpochMs: Long): Int = 0

    override fun estimatedStoredBytes(): Long = 0

    override fun recentEvents(limit: Int): List<EventEntity> = events.values.sortedBy { it.wallTimeMs }.takeLast(limit)

    override fun oldestLowSeverityFirst(limit: Int): List<String> = emptyList()

    override fun deleteByIds(ids: List<String>) {
        ids.forEach { events.remove(it) }
    }

    override fun oldestUnbookmarkedLowSeverityFirstForSession(
        sessionId: String,
        limit: Int,
    ): List<String> = emptyList()

    override fun sessionIdsOlderThan(cutoffEpochMs: Long): List<String> = emptyList()

    override fun sessionIdsForIds(ids: List<String>): List<String> = emptyList()
}
