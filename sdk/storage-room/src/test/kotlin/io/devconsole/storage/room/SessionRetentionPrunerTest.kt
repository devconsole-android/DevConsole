/**
 * @author Shakib
 * @since 05/08/26
 */
package io.devconsole.storage.room

import androidx.room.DatabaseConfiguration
import androidx.sqlite.db.SupportSQLiteOpenHelper
import io.devconsole.storage.api.EventStoreWriteResult
import io.devconsole.storage.api.SessionRetentionPolicy
import io.devconsole.storage.api.StoredSession
import io.devconsole.storage.api.StoredSessionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `retention keeps pinned runs even when they are over count and age limits`() =
        runBlocking {
            val eventDao = FakeTrimEventDao()
            val attachmentDao = FakeTrimAttachmentDao()
            val sessionDao = FakeTrimSessionDao(eventDao, attachmentDao)
            sessionDao.put(fakeSession("run-a", estimatedBytes = 10, recordCount = 1).withStatus("COMPLETED", 1))
            sessionDao.put(fakeSession("run-b", estimatedBytes = 10, recordCount = 1).withStatus("CRASHED", 2))
            assertEquals(1, sessionDao.setPinned("run-a", true))
            assertEquals(1, sessionDao.setPinned("run-b", true))
            val database = fakeTrimDatabase(sessionDao, eventDao, attachmentDao)
            val files = FileAttachmentStore(Files.createTempDirectory("prune-pin-test").toFile())
            val policy = SessionRetentionPolicy(maxSessions = 1, maxAgeMs = 1, maxBytes = 1_000)

            SessionRetentionPruner(database, files).prune(policy, activeSessionId = null, nowMs = 1_000)

            assertEquals(setOf("run-a", "run-b"), sessionDao.sessions().map { it.id }.toSet())
        }

    @Test
    fun `an explicit delete refuses the active run`() =
        runBlocking {
            val eventDao = FakeTrimEventDao()
            val attachmentDao = FakeTrimAttachmentDao()
            val sessionDao = FakeTrimSessionDao(eventDao, attachmentDao)
            sessionDao.put(fakeSession("active-run", estimatedBytes = 10, recordCount = 1))
            val database = fakeTrimDatabase(sessionDao, eventDao, attachmentDao)
            val files = FileAttachmentStore(Files.createTempDirectory("prune-active-test").toFile())

            assertFalse(SessionRetentionPruner(database, files).deleteFinished("active-run"))
            assertEquals(0, sessionDao.setPinned("active-run", true))
            assertEquals(listOf("active-run"), sessionDao.sessions().map { it.id })
        }

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
            // Five equal-sized events under a two-event budget must evict the three oldest rows.
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
            val eventWeight = trimEventBytes(eventDao.events.getValue("event-0"))
            sessionDao.put(fakeSession("session-1", estimatedBytes = eventWeight * 5, recordCount = 5))
            val database = fakeTrimDatabase(sessionDao, eventDao, attachmentDao)
            val files = FileAttachmentStore(Files.createTempDirectory("prune-trim-test").toFile())
            val policy =
                SessionRetentionPolicy(maxSessions = 10, maxAgeMs = Long.MAX_VALUE / 2, maxBytes = eventWeight * 2)

            SessionRetentionPruner(database, files).prune(policy, activeSessionId = "session-1", nowMs = 1_000)

            assertEquals(setOf("event-3", "event-4"), eventDao.events.keys)
            assertEquals(eventWeight * 2, sessionDao.session("session-1")?.estimatedBytes)
            assertEquals(2L, sessionDao.session("session-1")?.recordCount)
            // The trim path is expected to pay for full recomputes -- pruning is rare, unlike the
            // per-write path the no-op test above guards.
            assertTrue(sessionDao.refreshUsageCalls > 0)
        }

    @Test
    fun `active trim batches all selected rows and preserves bookmarked event`() =
        runBlocking {
            val eventDao = FakeTrimEventDao()
            val attachmentDao = FakeTrimAttachmentDao()
            val sessionDao = FakeTrimSessionDao(eventDao, attachmentDao)
            repeat(5) { index ->
                eventDao.events["event-$index"] = trimEvent("event-$index", "session-1", index, index.toLong())
            }
            eventDao.protected += "event-0"
            val eventWeight = trimEventBytes(eventDao.events.getValue("event-0"))
            sessionDao.put(fakeSession("session-1", estimatedBytes = eventWeight * 5, recordCount = 5))
            val database = fakeTrimDatabase(sessionDao, eventDao, attachmentDao)

            SessionRetentionPruner(
                database,
                FileAttachmentStore(Files.createTempDirectory("prune-batch-test").toFile()),
            ).prune(
                SessionRetentionPolicy(maxSessions = 10, maxAgeMs = Long.MAX_VALUE / 2, maxBytes = eventWeight * 2),
                "session-1",
                1_000,
            )

            assertEquals(setOf("event-0", "event-4"), eventDao.events.keys)
            assertEquals(1, eventDao.candidateScans)
            assertEquals(1, eventDao.deleteCalls)
        }

    @Test
    fun `failed attachment delete does not stop quota pruning`() =
        runBlocking {
            val eventDao = FakeTrimEventDao()
            val attachmentDao = FakeTrimAttachmentDao()
            val sessionDao = FakeTrimSessionDao(eventDao, attachmentDao)
            eventDao.events["event-0"] = trimEvent("event-0", "session-1", 0, 0)
            eventDao.events["event-1"] = trimEvent("event-1", "session-1", 1, 1)
            val attachment =
                AttachmentEntity(
                    "attachment",
                    "event-0",
                    "session-1",
                    "image/png",
                    1,
                    700,
                    false,
                    "hash",
                    true,
                    "session-1/attachments/file.bin",
                    0,
                    false,
                    true,
                    "NOT_APPLICABLE",
                )
            attachmentDao.insert(attachment)
            attachmentDao.pending += attachment.id
            eventDao.attached += "event-0"
            sessionDao.put(fakeSession("session-1", estimatedBytes = 900, recordCount = 3))
            val database = fakeTrimDatabase(sessionDao, eventDao, attachmentDao)
            SessionRetentionPruner(database) { FileDeletion.Failed }.prune(
                SessionRetentionPolicy(maxSessions = 10, maxAgeMs = Long.MAX_VALUE / 2, maxBytes = 100),
                "session-1",
                1_000,
            )
            assertEquals(setOf("event-0"), eventDao.events.keys)
            assertTrue(attachmentDao.pending.contains(attachment.id))
        }

    @Test
    fun `corruption recovery restores active session before retrying event insert`() =
        runBlocking {
            val staleEventDao = FakeTrimEventDao()
            val staleAttachmentDao = FakeTrimAttachmentDao()
            val staleSessionDao = FakeTrimSessionDao(staleEventDao, staleAttachmentDao)
            val staleDatabase =
                fakeTrimDatabase(staleSessionDao, staleEventDao, staleAttachmentDao, failSessionLookup = true)
            val freshEventDao = FakeTrimEventDao()
            val freshAttachmentDao = FakeTrimAttachmentDao()
            val freshSessionDao = FakeTrimSessionDao(freshEventDao, freshAttachmentDao)
            var currentDatabase = staleDatabase
            val coordinator =
                RoomRetentionCoordinator().apply {
                    activeSession = StoredSession("active-session", StoredSessionStatus.ACTIVE, 10L)
                }
            val store =
                RoomEventStore(staleDatabase, coordinator)
                    .withRecovery({ currentDatabase }, {
                        currentDatabase = fakeTrimDatabase(freshSessionDao, freshEventDao, freshAttachmentDao)
                    })
                    .withSessionFirstRetention()
            val session = StoredSession("active-session", StoredSessionStatus.ACTIVE, 10L)

            val result =
                store.insert(
                    listOf(
                        io.devconsole.storage.api.StoredEvent(
                            id = "event-1",
                            sessionId = session.id,
                            sequence = 1,
                            pluginId = "system",
                            type = "system.event",
                            wallTimeMs = 11,
                            monoTimeNs = 12,
                            severity = 1,
                            summary = "ready",
                            tagsJson = "{}",
                        ),
                    ),
                )

            assertEquals(EventStoreWriteResult.Success(1), result)
            assertEquals("ACTIVE", freshSessionDao.session(session.id)?.status)
        }

    @Test
    fun `recovery hook failure returns unavailable instead of escaping event insert`() =
        runBlocking {
            val eventDao = FakeTrimEventDao()
            val attachmentDao = FakeTrimAttachmentDao()
            val sessionDao = FakeTrimSessionDao(eventDao, attachmentDao)
            val staleDatabase = fakeTrimDatabase(sessionDao, eventDao, attachmentDao, failSessionLookup = true)
            val store =
                RoomEventStore(staleDatabase, RoomRetentionCoordinator())
                    .withRecovery({ staleDatabase }, { throw IllegalStateException("recovery failed") })
                    .withSessionFirstRetention()

            val result =
                store.insert(
                    listOf(
                        io.devconsole.storage.api.StoredEvent(
                            id = "event-1",
                            sessionId = "active-session",
                            sequence = 1,
                            pluginId = "system",
                            type = "system.event",
                            wallTimeMs = 11,
                            monoTimeNs = 12,
                            severity = 1,
                            summary = "ready",
                            tagsJson = "{}",
                        ),
                    ),
                )

            assertEquals(EventStoreWriteResult.Unavailable, result)
        }

    @Test
    fun `start remembers active session metadata on shared coordinator`() =
        runBlocking {
            val eventDao = FakeTrimEventDao()
            val attachmentDao = FakeTrimAttachmentDao()
            val sessionDao = FakeTrimSessionDao(eventDao, attachmentDao)
            val coordinator = RoomRetentionCoordinator()
            val store =
                RoomSessionStore(
                    fakeTrimDatabase(sessionDao, eventDao, attachmentDao),
                    FileAttachmentStore(Files.createTempDirectory("active-session-test").toFile()),
                    SessionRetentionPolicy(10, Long.MAX_VALUE / 2, 10_000),
                    coordinator,
                )
            val session = StoredSession("active-session", StoredSessionStatus.ACTIVE, 10L)

            store.start(session)

            assertEquals(session, coordinator.activeSession)
        }
}

/**
 * Fake usage totals use the production per-event estimate so trim selection and usage correction
 * agree exactly.
 */
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
    false,
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

private fun trimEventBytes(event: EventEntity): Long = event.toStoredEvent().estimatedStorageBytes()

private fun EventEntity.pruneCandidate(): EventPruneCandidate =
    EventPruneCandidate().also {
        it.id = id
        it.bytes = trimEventBytes(this)
    }

private fun fakeTrimDatabase(
    sessionDao: SessionDao,
    eventDao: EventDao,
    attachmentDao: AttachmentDao,
    failSessionLookup: Boolean = false,
): DevConsoleDatabase =
    object : DevConsoleDatabase() {
        // Room 2.7.x still declares createOpenHelper as abstract (it became non-abstract in 2.8+);
        // the fake DB never opens a real connection, so it is never reached.
        override fun createOpenHelper(config: DatabaseConfiguration): SupportSQLiteOpenHelper =
            error("not touched by these tests")

        override fun runInTransaction(body: Runnable) {
            body.run()
        }

        override fun <V> runInTransaction(body: java.util.concurrent.Callable<V>): V = body.call()

        override fun eventDao(): EventDao = eventDao

        override fun attachmentDao(): AttachmentDao = attachmentDao

        override fun timelineAnnotationDao(): TimelineAnnotationDao = FakeTrimTimelineAnnotationDao()

        override fun sessionDao(): SessionDao =
            if (failSessionLookup) {
                object : SessionDao by sessionDao {
                    override fun session(sessionId: String): SessionEntity? = error("database disk image is malformed")
                }
            } else {
                sessionDao
            }

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
 * Recomputes usage from the linked fake event/attachment DAOs using the production event estimate.
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
            it.status in setOf("COMPLETED", "CRASHED") && !it.pinned && (it.endedAtMs ?: it.startedAtMs) < cutoffEpochMs
        }

    override fun completedOldestFirst(): List<SessionEntity> =
        sessions.values.filter { it.status in setOf("COMPLETED", "CRASHED") && !it.pinned }

    override fun setPinned(
        sessionId: String,
        pinned: Boolean,
    ): Int {
        val current = sessions[sessionId]
        if (current == null || current.status !in setOf("COMPLETED", "CRASHED")) return 0
        sessions[sessionId] = current.withPinned(pinned)
        return 1
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
                .sumOf(::trimEventBytes) +
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
    pinned,
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
    pinned,
)

private class FakeTrimEventDao : EventDao {
    val events = linkedMapOf<String, EventEntity>()
    val protected = mutableSetOf<String>()
    val attached = mutableSetOf<String>()
    var candidateScans = 0
    var deleteCalls = 0

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

    override fun estimatedStoredBytes(): Long = events.values.sumOf(::trimEventBytes)

    override fun recentEvents(limit: Int): List<EventEntity> = events.values.toList().takeLast(limit)

    override fun quotaCandidates(): List<EventPruneCandidate> =
        events.values
            .filter { it.id !in protected }
            .sortedWith(compareBy({ it.severity }, { it.wallTimeMs }, { it.sequence }, { it.id }))
            .map { it.pruneCandidate() }

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

    override fun activeSessionTrimCandidates(sessionId: String): List<EventPruneCandidate> =
        events.values
            .filter {
                it.sessionId == sessionId &&
                    it.id !in protected &&
                    it.id !in attached
            }.sortedWith(compareBy({ it.severity }, { it.wallTimeMs }, { it.sequence }, { it.id }))
            .map { it.pruneCandidate() }
            .also { candidateScans++ }

    override fun deleteByIds(ids: List<String>) {
        deleteCalls++
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
    val pending = mutableSetOf<String>()

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

private fun SessionEntity.withPinned(newPinned: Boolean) =
    SessionEntity(
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
        recordCount,
        estimatedBytes,
        newPinned,
    )
