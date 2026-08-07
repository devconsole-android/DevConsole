/**
 * @author Shakib
 * @since 05/08/26
 */
package io.devconsole.storage.room

import androidx.room.DatabaseConfiguration
import androidx.sqlite.db.SupportSQLiteOpenHelper
import io.devconsole.storage.api.SessionRetentionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * [SessionRetentionPruner.prune] runs after *every* event batch flush and attachment write (see
 * [RoomSessionStore.enforceRetention], called from PlatformFacadeProvider in sdk/full on both
 * paths), so its active-session budget check must not pay for a full [SessionDao.refreshUsage]
 * re-aggregation on every call -- only when it actually trims something. These tests exercise
 * [SessionRetentionPruner] against fake DAOs (this module's established style for pruner tests --
 * see EventQuotaPrunerTest/AttachmentQuotaPrunerTest) wired into a bare [DevConsoleDatabase]
 * subclass, since the pruner takes the concrete database rather than individual DAOs. Both
 * scenarios here stay off the `runInTransaction` path (no attachments are trimmed), which needs a
 * real, Room-built database this module's plain JVM unit tests cannot construct.
 */
class SessionRetentionPrunerTest {
    @Test
    fun `a no-op pass on an already-under-budget active session never re-aggregates usage`() =
        runBlocking {
            val eventDao = FakeTrimEventDao()
            val attachmentDao = FakeTrimAttachmentDao()
            val sessionDao = FakeTrimSessionDao(eventDao, attachmentDao)
            sessionDao.put(fakeSession("session-1", estimatedBytes = 100, recordCount = 1))
            val database = fakeTrimDatabase(sessionDao, eventDao, attachmentDao)
            val files = FileAttachmentStore(Files.createTempDirectory("prune-noop-test").toFile())
            val policy = SessionRetentionPolicy(maxSessions = 10, maxAgeMs = Long.MAX_VALUE / 2, maxBytes = 1_000)

            SessionRetentionPruner(database, files).prune(policy, activeSessionId = "session-1", nowMs = 1_000)

            assertEquals(0, sessionDao.refreshUsageCalls)
        }

    @Test
    fun `trimming an over-budget active session evicts oldest low-severity events and corrects counters`() =
        runBlocking {
            val eventDao = FakeTrimEventDao()
            val attachmentDao = FakeTrimAttachmentDao()
            val sessionDao = FakeTrimSessionDao(eventDao, attachmentDao)
            // 5 events at the fake's fixed 100-byte weight each = 500 total; a 250-byte budget
            // must evict the 3 oldest-lowest-severity events (0, 1, 2), leaving 3 and 4.
            repeat(5) { index ->
                eventDao.events[
                    "event-$index",
                ] =
                    trimEvent(
                        id = "event-$index",
                        sessionId = "session-1",
                        severity = index,
                        wallTimeMs = index.toLong(),
                    )
            }
            sessionDao.put(fakeSession("session-1", estimatedBytes = 500, recordCount = 5))
            val database = fakeTrimDatabase(sessionDao, eventDao, attachmentDao)
            val files = FileAttachmentStore(Files.createTempDirectory("prune-trim-test").toFile())
            val policy = SessionRetentionPolicy(maxSessions = 10, maxAgeMs = Long.MAX_VALUE / 2, maxBytes = 250)

            SessionRetentionPruner(database, files).prune(policy, activeSessionId = "session-1", nowMs = 1_000)

            assertEquals(setOf("event-3", "event-4"), eventDao.events.keys)
            assertEquals(200L, sessionDao.session("session-1")?.estimatedBytes)
            assertEquals(2L, sessionDao.session("session-1")?.recordCount)
            // The trim path is expected to pay for full recomputes -- pruning is rare, unlike the
            // per-write path the no-op test above guards.
            assertTrue(sessionDao.refreshUsageCalls > 0)
        }
}

/**
 * A fixed per-event weight keeps the arithmetic easy to reason about; the real byte formula is
 * covered by EventUsageEstimateTest.
 */
private const val FAKE_EVENT_BYTE_WEIGHT = 100L

private fun fakeSession(
    id: String,
    estimatedBytes: Long,
    recordCount: Long,
) = SessionEntity(
    id,
    "ACTIVE",
    0,
    0,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    recordCount,
    estimatedBytes,
)

private fun trimEvent(
    id: String,
    sessionId: String,
    severity: Int,
    wallTimeMs: Long,
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

private fun fakeTrimDatabase(
    sessionDao: SessionDao,
    eventDao: EventDao,
    attachmentDao: AttachmentDao,
): DevConsoleDatabase =
    object : DevConsoleDatabase() {
        // Room 2.7.x still declares createOpenHelper as abstract (it became non-abstract in 2.8+);
        // the fake DB never opens a real connection, so it is never reached.
        override fun createOpenHelper(config: DatabaseConfiguration): SupportSQLiteOpenHelper =
            error("not touched by these tests")

        override fun eventDao(): EventDao = eventDao

        override fun attachmentDao(): AttachmentDao = attachmentDao

        override fun timelineAnnotationDao(): TimelineAnnotationDao = FakeTrimTimelineAnnotationDao()

        override fun sessionDao(): SessionDao = sessionDao

        override fun captureRuleDao(): CaptureRuleDao = error("SessionRetentionPruner never touches capture rules")

        // Neither test in this file reaches the runInTransaction cascade-delete path (see the class
        // KDoc), which is where evidence rows are touched -- that path needs a real, Room-built
        // database and is covered by EvidenceCascadeDeletionInstrumentedTest instead.
        override fun evidenceItemDao(): EvidenceItemDao = error("SessionRetentionPruner never touches evidence")

        override fun evidenceReportDao(): EvidenceReportDao = error("SessionRetentionPruner never touches evidence")

        override fun createInvalidationTracker(): androidx.room.InvalidationTracker =
            error("SessionRetentionPruner never touches the invalidation tracker")

        override fun clearAllTables(): Unit = error("SessionRetentionPruner never clears tables")
    }

/**
 * Recomputes usage from the linked fake event/attachment DAOs, mirroring refreshUsage's real
 * aggregation closely enough for these tests: a fixed per-event weight instead of the exact byte
 * formula (see FAKE_EVENT_BYTE_WEIGHT).
 */
private class FakeTrimSessionDao(
    private val eventDao: FakeTrimEventDao,
    private val attachmentDao: FakeTrimAttachmentDao,
) : SessionDao {
    private val sessions = linkedMapOf<String, SessionEntity>()
    var refreshUsageCalls = 0
        private set

    fun put(session: SessionEntity) {
        sessions[session.id] = session
    }

    override fun insertIfAbsent(session: SessionEntity): Long {
        if (sessions.containsKey(session.id)) return -1
        sessions[session.id] = session
        return 0
    }

    override fun session(sessionId: String): SessionEntity? = sessions[sessionId]

    override fun sessions(): List<SessionEntity> = sessions.values.toList()

    override fun expiredCompleted(cutoffEpochMs: Long): List<SessionEntity> =
        sessions.values.filter {
            it.status in setOf("COMPLETED", "CRASHED") && (it.endedAtMs ?: it.startedAtMs) < cutoffEpochMs
        }

    override fun completedOldestFirst(): List<SessionEntity> =
        sessions.values.filter {
            it.status in
                setOf("COMPLETED", "CRASHED")
        }

    override fun deletingSessions(): List<SessionEntity> = sessions.values.filter { it.status == "DELETING" }

    override fun sessionCount(): Long = sessions.size.toLong()

    override fun totalEstimatedBytes(): Long = sessions.values.sumOf { it.estimatedBytes }

    override fun finish(
        sessionId: String,
        status: String,
        endedAtMs: Long,
    ) {
        val current = sessions[sessionId] ?: return
        sessions[sessionId] = current.withStatus(status, endedAtMs)
    }

    override fun markDeleting(sessionId: String): Int {
        val current = sessions[sessionId]
        if (current == null || current.status !in setOf("COMPLETED", "CRASHED")) return 0
        sessions[sessionId] = current.withStatus("DELETING", current.endedAtMs)
        return 1
    }

    override fun refreshUsage(sessionId: String) {
        refreshUsageCalls++
        val current = sessions[sessionId] ?: return
        val recordCount =
            eventDao.events.values.count { it.sessionId == sessionId } +
                attachmentDao.attachments.values.count { it.sessionId == sessionId }
        val estimatedBytes =
            eventDao.events.values
                .filter { it.sessionId == sessionId }
                .size * FAKE_EVENT_BYTE_WEIGHT +
                attachmentDao.attachments.values
                    .filter { it.sessionId == sessionId }
                    .sumOf { it.storedLength }
        sessions[sessionId] = current.withUsage(recordCount.toLong(), estimatedBytes)
    }

    override fun incrementUsage(
        sessionId: String,
        deltaCount: Long,
        deltaBytes: Long,
    ) {
        val current = sessions[sessionId] ?: return
        sessions[sessionId] = current.withUsage(current.recordCount + deltaCount, current.estimatedBytes + deltaBytes)
    }

    override fun delete(sessionId: String) {
        sessions.remove(sessionId)
    }
}

private fun SessionEntity.withStatus(
    newStatus: String,
    newEndedAtMs: Long?,
) = SessionEntity(
    id,
    newStatus,
    startedAtMs,
    startedAtMonotonicNs,
    newEndedAtMs,
    applicationId,
    appVersionName,
    appVersionCode,
    buildType,
    deviceModel,
    deviceApiLevel,
    deviceOsVersion,
    recordCount,
    estimatedBytes,
)

private fun SessionEntity.withUsage(
    newRecordCount: Long,
    newEstimatedBytes: Long,
) = SessionEntity(
    id,
    status,
    startedAtMs,
    startedAtMonotonicNs,
    endedAtMs,
    applicationId,
    appVersionName,
    appVersionCode,
    buildType,
    deviceModel,
    deviceApiLevel,
    deviceOsVersion,
    newRecordCount,
    newEstimatedBytes,
)

private class FakeTrimEventDao : EventDao {
    val events = linkedMapOf<String, EventEntity>()

    override fun insertAll(events: List<EventEntity>) {
        events.forEach { this.events[it.id] = it }
    }

    override fun eventsForSession(sessionId: String): List<EventEntity> =
        events.values.filter {
            it.sessionId ==
                sessionId
        }

    override fun deleteSession(sessionId: String) {
        events.values.filter { it.sessionId == sessionId }.forEach { events.remove(it.id) }
    }

    override fun eventCount(): Long = events.size.toLong()

    override fun deleteOlderThan(cutoffEpochMs: Long): Int {
        val expired = events.values.filter { it.wallTimeMs < cutoffEpochMs }.map { it.id }
        deleteByIds(expired)
        return expired.size
    }

    override fun estimatedStoredBytes(): Long = events.size * FAKE_EVENT_BYTE_WEIGHT

    override fun recentEvents(limit: Int): List<EventEntity> = events.values.toList().takeLast(limit)

    override fun oldestLowSeverityFirst(limit: Int): List<String> =
        events.values
            .sortedWith(compareBy({ it.severity }, { it.wallTimeMs }))
            .take(limit)
            .map { it.id }

    override fun recentEventsForSession(
        sessionId: String,
        limit: Int,
    ): List<EventEntity> = events.values.filter { it.sessionId == sessionId }.takeLast(limit)

    override fun recentEventsForSessionByPlugin(
        sessionId: String,
        pluginIds: List<String>,
        limit: Int,
    ): List<EventEntity> =
        events.values.filter { it.sessionId == sessionId && it.pluginId in pluginIds }.takeLast(limit)

    override fun recentEventsByPlugin(
        pluginIds: List<String>,
        limit: Int,
    ): List<EventEntity> = events.values.filter { it.pluginId in pluginIds }.takeLast(limit)

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

private class FakeTrimAttachmentDao : AttachmentDao {
    val attachments = linkedMapOf<String, AttachmentEntity>()
    private val pending = mutableSetOf<String>()

    override fun insert(attachment: AttachmentEntity) {
        attachments[attachment.id] = attachment
    }

    override fun oldestUnbookmarked(): List<AttachmentEntity> =
        attachments.values.filter { !it.isBookmarked && it.id !in pending }.sortedBy { it.createdWallTimeMs }

    override fun totalStoredBytes(): Long = attachments.values.sumOf { it.storedLength }

    override fun contains(id: String): Int = if (attachments.containsKey(id)) 1 else 0

    override fun attachment(id: String): AttachmentEntity? = attachments[id]?.takeIf { it.id !in pending }

    override fun deleteById(id: String) {
        attachments.remove(id)
        pending.remove(id)
    }

    override fun markPendingDeletion(id: String): Int = if (attachments.containsKey(id) && pending.add(id)) 1 else 0

    override fun clearPendingDeletion(id: String): Int = if (pending.remove(id)) 1 else 0

    override fun pendingDeletion(): List<AttachmentEntity> =
        attachments.values.filter { it.id in pending }.sortedBy { it.createdWallTimeMs }

    override fun pendingDeletionForSession(sessionId: String): List<AttachmentEntity> =
        attachments.values.filter { it.id in pending && it.sessionId == sessionId }.sortedBy { it.createdWallTimeMs }

    override fun markSessionPendingDeletion(sessionId: String) {
        attachments.values.filter { it.sessionId == sessionId }.forEach { pending.add(it.id) }
    }

    override fun deleteSession(sessionId: String) {
        attachments.values.filter { it.sessionId == sessionId }.forEach {
            attachments.remove(it.id)
            pending.remove(it.id)
        }
    }

    override fun oldestUnbookmarkedForSession(sessionId: String): List<AttachmentEntity> =
        attachments.values
            .filter { it.sessionId == sessionId && !it.isBookmarked && it.id !in pending }
            .sortedBy { it.createdWallTimeMs }
}

private class FakeTrimTimelineAnnotationDao : TimelineAnnotationDao {
    override fun annotation(eventId: String): TimelineAnnotationEntity? = null

    override fun upsert(annotation: TimelineAnnotationEntity) = Unit

    override fun delete(eventId: String) = Unit

    override fun deleteSession(sessionId: String) = Unit

    override fun deleteEvents(eventIds: List<String>) = Unit
}
