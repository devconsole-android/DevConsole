@file:Suppress("ReturnCount") // Guard-clause early returns are the clearest form for these recovery/retention checks.

package io.devconsole.storage.room

import io.devconsole.storage.api.SessionRetentionPolicy
import io.devconsole.storage.api.SessionStore
import io.devconsole.storage.api.StoredSession
import io.devconsole.storage.api.StoredSessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Room implementation of durable app-run lifecycle metadata. It is intentionally independent of
 * the runtime capture pipeline so callers can adopt it before switching their live session source.
 *
 * One small function per SessionStore operation plus the recovery/clock configuration helpers;
 * splitting further would fragment one cohesive durable-session boundary across multiple classes.
 */
@Suppress("TooManyFunctions")
class RoomSessionStore(
    private val database: DevConsoleDatabase,
    private val attachmentFiles: FileAttachmentStore,
    private var policy: SessionRetentionPolicy,
    private val coordinator: RoomRetentionCoordinator,
    private var clock: () -> Long = System::currentTimeMillis,
) : SessionStore {
    @Volatile private var databaseProvider: () -> DevConsoleDatabase = { database }

    @Volatile private var recover: ((Throwable) -> Unit)? = null

    fun withPolicy(value: SessionRetentionPolicy): RoomSessionStore = apply { policy = value }

    internal fun withClock(value: () -> Long): RoomSessionStore = apply { clock = value }

    fun withRecovery(
        databaseProvider: () -> DevConsoleDatabase,
        recover: (Throwable) -> Unit,
    ): RoomSessionStore =
        apply {
            this.databaseProvider = databaseProvider
            this.recover = recover
        }

    override suspend fun start(session: StoredSession) {
        require(session.id.isNotBlank()) { "session id must not be blank" }
        require(session.status == StoredSessionStatus.ACTIVE) { "started sessions must be ACTIVE" }
        coordinator.exclusive {
            withContext(Dispatchers.IO) {
                execute(Unit) { activeDatabase ->
                    activeDatabase.runInTransaction {
                        activeDatabase.sessionDao().insertIfAbsent(session.toEntity())
                        activeDatabase.sessionDao().refreshUsage(session.id)
                    }
                    SessionRetentionPruner(activeDatabase, attachmentFiles).prune(policy, session.id, clock())
                }
            }
        }
    }

    override suspend fun end(
        sessionId: String,
        endedAtMs: Long,
    ) = finish(sessionId, StoredSessionStatus.COMPLETED, endedAtMs)

    override suspend fun crash(
        sessionId: String,
        endedAtMs: Long,
    ) = finish(sessionId, StoredSessionStatus.CRASHED, endedAtMs)

    override suspend fun sessions(): List<StoredSession> =
        withContext(Dispatchers.IO) {
            execute(emptyList()) { activeDatabase ->
                activeDatabase.sessionDao().sessions().map(SessionEntity::toStoredSession)
            }
        }

    override suspend fun session(sessionId: String): StoredSession? =
        withContext(Dispatchers.IO) {
            execute(null) { activeDatabase -> activeDatabase.sessionDao().session(sessionId)?.toStoredSession() }
        }

    /** Applies the same retention pass after a caller has persisted more records for [activeSessionId]. */
    suspend fun enforceRetention(activeSessionId: String?) {
        coordinator.exclusive {
            withContext(Dispatchers.IO) {
                execute(Unit) { activeDatabase ->
                    SessionRetentionPruner(activeDatabase, attachmentFiles).prune(policy, activeSessionId, clock())
                }
            }
        }
    }

    private suspend fun finish(
        sessionId: String,
        status: StoredSessionStatus,
        endedAtMs: Long,
    ) {
        if (sessionId.isBlank()) return
        coordinator.exclusive {
            withContext(Dispatchers.IO) {
                execute(Unit) { activeDatabase ->
                    activeDatabase.sessionDao().refreshUsage(sessionId)
                    activeDatabase.sessionDao().finish(sessionId, status.name, endedAtMs)
                    SessionRetentionPruner(activeDatabase, attachmentFiles).prune(policy, null, clock())
                }
            }
        }
    }

    // Fail-open recovery boundary: any non-cancellation failure must trigger the recreate-and-retry
    // path below rather than crash the host, so it deliberately catches Exception broadly and has
    // more return/throw exits than the default guard-clause thresholds allow.
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private suspend fun <T> execute(
        unavailable: T,
        operation: suspend (DevConsoleDatabase) -> T,
    ): T {
        val first =
            try {
                operation(databaseProvider())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val recovery = recover ?: return unavailable
                if (!failure.isSqliteCorruption()) return unavailable
                try {
                    recovery(failure)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return unavailable
                }
                return try {
                    operation(databaseProvider())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    unavailable
                }
            }
        return first
    }
}

/** Session-first retention: delete whole completed runs, then trim only an oversized active run. */
class SessionRetentionPruner(
    private val database: DevConsoleDatabase,
    private val attachmentFiles: FileAttachmentStore,
) {
    // The capacity-trim loop below breaks both when no further candidate exists and when a
    // deletion fails partway; a single-exit rewrite would obscure which condition stopped pruning.
    @Suppress("LoopWithTooManyJumpStatements")
    suspend fun prune(
        policy: SessionRetentionPolicy,
        activeSessionId: String?,
        nowMs: Long,
    ) {
        val sessions = database.sessionDao()
        val cutoff = (nowMs - policy.maxAgeMs).coerceAtLeast(0)
        for (session in sessions.deletingSessions()) {
            if (!completeSessionDeletion(session.id)) return
        }
        reconcilePending()
        for (session in sessions.expiredCompleted(cutoff)) {
            if (!deleteSession(session)) return
        }

        while (
            sessions.sessionCount() > policy.maxSessions ||
            sessions.totalEstimatedBytes() > policy.maxBytes
        ) {
            val candidate = sessions.completedOldestFirst().firstOrNull() ?: break
            if (!deleteSession(candidate)) break
        }

        val active = activeSessionId?.let(sessions::session) ?: return
        // prune() runs after every event batch flush and attachment write (see
        // RoomSessionStore.enforceRetention()), so trust the incrementally-maintained
        // estimatedBytes on the row just read rather than paying for a full re-aggregation on
        // every call. A full refreshUsage() only runs below, after trimActiveSession actually
        // deletes rows -- that path is rare (only once a session is over budget), unlike this
        // per-write check.
        if (active.estimatedBytes > policy.maxBytes) {
            trimActiveSession(active.id, policy.maxBytes)
            sessions.refreshUsage(active.id)
        }
    }

    private suspend fun deleteSession(session: SessionEntity): Boolean {
        // Tombstone the run and its files first. A crash leaves its bytes accounted and blocks
        // session-aware writers; the next retention pass completes the same deletion.
        database.runInTransaction {
            database.sessionDao().markDeleting(session.id)
            database.attachmentDao().markSessionPendingDeletion(session.id)
        }
        return completeSessionDeletion(session.id)
    }

    private suspend fun completeSessionDeletion(sessionId: String): Boolean {
        if (!reconcilePendingForSession(sessionId)) return false
        if (database.attachmentDao().pendingDeletionForSession(sessionId).isNotEmpty()) return false
        database.runInTransaction {
            database.timelineAnnotationDao().deleteSession(sessionId)
            database.attachmentDao().deleteSession(sessionId)
            database.eventDao().deleteSession(sessionId)
            // An evidence item or report left behind here would point at a session that no longer
            // exists -- the same class of bug this transaction already prevents for events and
            // attachments.
            database.evidenceItemDao().deleteSession(sessionId)
            database.evidenceReportDao().deleteSession(sessionId)
            database.sessionDao().delete(sessionId)
        }
        return true
    }

    private suspend fun trimActiveSession(
        sessionId: String,
        maxBytes: Long,
    ) {
        val attachments = database.attachmentDao()
        for (attachment in attachments.oldestUnbookmarkedForSession(sessionId)) {
            if (sessionBytes(sessionId) <= maxBytes) return
            database.runInTransaction { attachments.markPendingDeletion(attachment.id) }
            reconcilePendingForSession(sessionId)
        }
        while (sessionBytes(sessionId) > maxBytes) {
            val ids = database.eventDao().oldestUnbookmarkedLowSeverityFirstForSession(sessionId, 1)
            if (ids.isEmpty()) return
            database.timelineAnnotationDao().deleteEvents(ids)
            database.eventDao().deleteByIds(ids)
        }
    }

    private fun sessionBytes(sessionId: String): Long {
        database.sessionDao().refreshUsage(sessionId)
        return database
            .sessionDao()
            .session(sessionId)
            ?.estimatedBytes
            .orZero()
    }

    private suspend fun reconcilePending() {
        for (sessionId in database
            .attachmentDao()
            .pendingDeletion()
            .map(AttachmentEntity::sessionId)
            .distinct()) {
            reconcilePendingForSession(sessionId)
        }
    }

    private suspend fun reconcilePendingForSession(sessionId: String): Boolean {
        for (attachment in database.attachmentDao().pendingDeletionForSession(sessionId)) {
            if (attachmentFiles.delete(attachment.toStoredAttachment()) == FileDeletion.Failed) return false
            database.attachmentDao().deleteById(attachment.id)
        }
        database.sessionDao().refreshUsage(sessionId)
        return true
    }
}

private fun Long?.orZero(): Long = this ?: 0L

private fun StoredSession.toEntity(): SessionEntity =
    SessionEntity(
        id,
        status.name,
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
    )

private fun SessionEntity.toStoredSession(): StoredSession =
    StoredSession(
        id = id,
        status = StoredSessionStatus.valueOf(status),
        startedAtMs = startedAtMs,
        startedAtMonotonicNs = startedAtMonotonicNs,
        endedAtMs = endedAtMs,
        applicationId = applicationId,
        appVersionName = appVersionName,
        appVersionCode = appVersionCode,
        buildType = buildType,
        deviceModel = deviceModel,
        deviceApiLevel = deviceApiLevel,
        deviceOsVersion = deviceOsVersion,
        recordCount = recordCount,
        estimatedBytes = estimatedBytes,
    )
