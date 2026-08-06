/**
 * @author Shakib
 * @since 20/07/26
 */
package io.devconsole.storage.room

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeEventDao : EventDao {
    val events = linkedMapOf<String, EventEntity>()
    val requestedDeleteLimits = mutableListOf<Int>()

    override fun insertAll(events: List<EventEntity>) {
        events.forEach { this.events[it.id] = it }
    }

    override fun eventsForSession(sessionId: String) = events.values.filter { it.sessionId == sessionId }

    override fun deleteSession(sessionId: String) {
        this.events.values
            .filter { it.sessionId == sessionId }
            .forEach { this.events.remove(it.id) }
    }

    override fun eventCount(): Long = events.size.toLong()

    override fun deleteOlderThan(cutoffEpochMs: Long): Int {
        val expired = events.values.filter { it.wallTimeMs < cutoffEpochMs }.map { it.id }
        deleteByIds(expired)
        return expired.size
    }

    override fun estimatedStoredBytes(): Long =
        events.values.sumOf {
            96L + it.summary.encodeToByteArray().size + (it.payloadJson?.encodeToByteArray()?.size ?: 0)
        }

    override fun recentEvents(limit: Int): List<EventEntity> =
        events.values
            .sortedWith(compareBy<EventEntity> { it.monoTimeNs }.thenBy { it.sequence }.thenBy { it.id })
            .takeLast(limit)

    override fun oldestLowSeverityFirst(limit: Int): List<String> =
        events.values
            .sortedWith(compareBy({ it.severity }, { it.wallTimeMs }))
            .take(limit)
            .map { it.id }
            .also { requestedDeleteLimits += limit }

    override fun recentEventsForSession(
        sessionId: String,
        limit: Int,
    ): List<EventEntity> =
        events.values
            .filter { it.sessionId == sessionId }
            .sortedWith(
                compareByDescending<EventEntity> { it.wallTimeMs }
                    .thenByDescending { it.sequence }
                    .thenByDescending { it.id },
            ).take(limit)
            .sortedWith(compareBy<EventEntity> { it.wallTimeMs }.thenBy { it.sequence }.thenBy { it.id })

    override fun recentEventsForSessionByPlugin(
        sessionId: String,
        pluginIds: List<String>,
        limit: Int,
    ): List<EventEntity> =
        events.values
            .filter { it.sessionId == sessionId && it.pluginId in pluginIds }
            .sortedWith(
                compareByDescending<EventEntity> { it.wallTimeMs }
                    .thenByDescending { it.sequence }
                    .thenByDescending { it.id },
            ).take(limit)
            .sortedWith(compareBy<EventEntity> { it.wallTimeMs }.thenBy { it.sequence }.thenBy { it.id })

    override fun recentEventsByPlugin(
        pluginIds: List<String>,
        limit: Int,
    ): List<EventEntity> =
        events.values
            .filter { it.pluginId in pluginIds }
            .sortedWith(
                compareByDescending<EventEntity> { it.wallTimeMs }
                    .thenByDescending { it.sequence }
                    .thenByDescending { it.id },
            ).take(limit)
            .sortedWith(compareBy<EventEntity> { it.wallTimeMs }.thenBy { it.sequence }.thenBy { it.id })

    override fun oldestUnbookmarkedLowSeverityFirstForSession(
        sessionId: String,
        limit: Int,
    ): List<String> =
        events.values
            .filter { it.sessionId == sessionId }
            .sortedWith(compareBy({ it.severity }, { it.wallTimeMs }))
            .take(limit)
            .map { it.id }

    override fun deleteByIds(ids: List<String>) {
        ids.forEach { events.remove(it) }
    }

    override fun sessionIdsOlderThan(cutoffEpochMs: Long): List<String> =
        events.values
            .filter { it.wallTimeMs < cutoffEpochMs }
            .map { it.sessionId }
            .distinct()

    override fun sessionIdsForIds(ids: List<String>): List<String> = ids.mapNotNull { events[it]?.sessionId }.distinct()
}

private fun event(
    id: String,
    severity: Int,
    wallTimeMs: Long,
    sessionId: String = "session-1",
) = EventEntity(
    id,
    sessionId,
    wallTimeMs,
    "system",
    "system.event",
    wallTimeMs,
    wallTimeMs,
    severity,
    "ready",
    null,
    "{}",
    null,
    null,
    1,
)

class EventQuotaPrunerTest {
    @Test
    fun `keeps event count under quota by deleting oldest low-severity events first`() =
        runBlocking {
            val dao = FakeEventDao()
            dao.insertAll(
                listOf(
                    event("event-0", severity = 0, wallTimeMs = 0),
                    event("event-1", severity = 0, wallTimeMs = 1),
                    event("event-2", severity = 0, wallTimeMs = 2),
                    event("event-3", severity = 3, wallTimeMs = 3),
                    event("event-4", severity = 3, wallTimeMs = 4),
                ),
            )

            val deleted = EventQuotaPruner(dao).pruneTo(maxEvents = 3)

            assertEquals(2, deleted)
            assertEquals(3L, dao.eventCount())
            assertEquals(setOf("event-2", "event-3", "event-4"), dao.events.keys)
        }

    @Test
    fun `does nothing when already under quota`() =
        runBlocking {
            val dao = FakeEventDao()
            dao.insertAll(listOf(event("event-0", severity = 0, wallTimeMs = 0)))

            val deleted = EventQuotaPruner(dao).pruneTo(maxEvents = 50_000)

            assertEquals(0, deleted)
            assertEquals(1L, dao.eventCount())
        }

    @Test
    fun `does nothing when the event count sits exactly at the cap`() =
        runBlocking {
            val dao = FakeEventDao()
            dao.insertAll(
                listOf(
                    event("event-0", severity = 0, wallTimeMs = 0),
                    event("event-1", severity = 0, wallTimeMs = 1),
                    event("event-2", severity = 0, wallTimeMs = 2),
                ),
            )

            val result =
                EventQuotaPruner(dao).pruneTo(maxEvents = 3, maxBytes = Long.MAX_VALUE, cutoffEpochMs = Long.MIN_VALUE)

            assertEquals(0, result.quotaCount)
            assertEquals(setOf("event-0", "event-1", "event-2"), dao.events.keys)
        }

    @Test
    fun `does nothing when stored bytes sit exactly at the byte cap`() =
        runBlocking {
            val dao = FakeEventDao()
            dao.insertAll(listOf(event("event-0", severity = 0, wallTimeMs = 0)))
            val exactBytes = dao.estimatedStoredBytes()

            val result =
                EventQuotaPruner(dao).pruneTo(maxEvents = 50_000, maxBytes = exactBytes, cutoffEpochMs = Long.MIN_VALUE)

            assertEquals(0, result.quotaCount)
            assertEquals(1L, dao.eventCount())
        }

    @Test
    fun `lowering the cap on a later call evicts further, oldest lowest-severity first`() =
        runBlocking {
            val dao = FakeEventDao()
            dao.insertAll(
                listOf(
                    event("event-0", severity = 0, wallTimeMs = 0),
                    event("event-1", severity = 0, wallTimeMs = 1),
                    event("event-2", severity = 0, wallTimeMs = 2),
                    event("event-3", severity = 0, wallTimeMs = 3),
                    event("event-4", severity = 0, wallTimeMs = 4),
                ),
            )
            val pruner = EventQuotaPruner(dao)

            val firstPass = pruner.pruneTo(maxEvents = 3)
            assertEquals(2, firstPass)
            assertEquals(setOf("event-2", "event-3", "event-4"), dao.events.keys)

            // The cap tightens on a later call (e.g. a host reconfiguring RetentionPolicy at
            // runtime); the pruner must keep evicting from where it left off rather than treating
            // the earlier prune as having already satisfied the new, smaller cap.
            val secondPass = pruner.pruneTo(maxEvents = 1)
            assertEquals(2, secondPass)
            assertEquals(setOf("event-4"), dao.events.keys)
        }

    @Test
    fun `expires old rows before enforcing the shared byte budget`() =
        runBlocking {
            val dao = FakeEventDao()
            dao.insertAll(
                listOf(
                    event("expired", severity = 3, wallTimeMs = 10),
                    event("old-debug", severity = 0, wallTimeMs = 100),
                    event("new-error", severity = 3, wallTimeMs = 200),
                ),
            )

            val result =
                EventQuotaPruner(dao).pruneTo(
                    maxEvents = 50_000,
                    maxBytes = 150,
                    cutoffEpochMs = 50,
                )

            assertEquals(1, result.expiredCount)
            assertEquals(1, result.quotaCount)
            assertEquals(setOf("new-error"), dao.events.keys)
            assertTrue(dao.requestedDeleteLimits.all { it <= 256 })
            assertTrue(result.remainingBytes <= 150)
        }

    @Test
    fun `reports every distinct session touched by an age-based expiry so their usage counters can be corrected`() =
        runBlocking {
            val dao = FakeEventDao()
            dao.insertAll(
                listOf(
                    event("expired-a", severity = 0, wallTimeMs = 10, sessionId = "session-a"),
                    event("expired-b", severity = 0, wallTimeMs = 20, sessionId = "session-b"),
                    event("kept", severity = 0, wallTimeMs = 200, sessionId = "session-a"),
                ),
            )
            val prunedSessions = mutableListOf<String>()

            EventQuotaPruner(dao) { prunedSessions += it }.pruneTo(
                maxEvents = 50_000,
                maxBytes = Long.MAX_VALUE,
                cutoffEpochMs = 100,
            )

            assertEquals(setOf("session-a", "session-b"), prunedSessions.toSet())
        }

    @Test
    fun `reports every distinct session touched by quota eviction so their usage counters can be corrected`() =
        runBlocking {
            val dao = FakeEventDao()
            dao.insertAll(
                listOf(
                    event("event-0", severity = 0, wallTimeMs = 0, sessionId = "session-a"),
                    event("event-1", severity = 0, wallTimeMs = 1, sessionId = "session-b"),
                    event("event-2", severity = 3, wallTimeMs = 2, sessionId = "session-a"),
                ),
            )
            val prunedSessions = mutableListOf<String>()

            EventQuotaPruner(dao) { prunedSessions += it }.pruneTo(maxEvents = 1)

            // Two lowest-severity events are evicted (event-0, event-1); the surviving event-2
            // belongs to session-a, so only session-a and session-b were touched by the eviction.
            assertEquals(setOf("session-a", "session-b"), prunedSessions.toSet())
        }

    @Test
    fun `never reports pruning when nothing was deleted`() =
        runBlocking {
            val dao = FakeEventDao()
            dao.insertAll(listOf(event("event-0", severity = 0, wallTimeMs = 0)))
            val prunedSessions = mutableListOf<String>()

            EventQuotaPruner(dao) { prunedSessions += it }.pruneTo(maxEvents = 50_000)

            assertTrue(prunedSessions.isEmpty())
        }
}
