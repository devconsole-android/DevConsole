/**
 * @author Shakib
 * @since 20/07/26
 */
@file:Suppress("ReturnCount") // Guard-clause early returns are the clearest form for these recovery/write checks.

package io.devconsole.storage.room

import io.devconsole.storage.api.AttachmentStore
import io.devconsole.storage.api.AttachmentWriteRequest
import io.devconsole.storage.api.AttachmentWriteResult
import io.devconsole.storage.api.RedactionApplicability
import io.devconsole.storage.api.StoredAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DEFAULT_MAX_ATTACHMENT_QUOTA_BYTES: Long = 100L * 1024 * 1024

/** Coordinates the attachment file data source with Room metadata, enforcing the storage quota on every write. */
class RoomAttachmentStore(
    private val database: DevConsoleDatabase,
    private val files: FileAttachmentStore,
    private val coordinator: RoomRetentionCoordinator,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAttachmentBytes: Long = DEFAULT_MAX_ATTACHMENT_QUOTA_BYTES,
) : AttachmentStore {
    @Volatile
    private var databaseProvider: () -> DevConsoleDatabase = { database }

    @Volatile
    private var recover: ((Throwable) -> Unit)? = null

    /** Phase 1B enables this with [RoomSessionStore] so retention is session-first, not row-first. */
    @Volatile
    private var sessionFirstRetention = false

    @Volatile
    private var limits =
        Limits(
            maxTotalBytes = maxAttachmentBytes,
            maxAgeMs = RoomEventStore.DEFAULT_MAX_AGE_MS,
        )

    fun withPolicy(
        maxTotalBytes: Long,
        maxAgeMs: Long,
    ): RoomAttachmentStore {
        require(maxTotalBytes > 0) { "maxTotalBytes must be positive" }
        require(maxAgeMs > 0) { "maxAgeMs must be positive" }
        limits = Limits(maxTotalBytes, maxAgeMs)
        return this
    }

    fun withSessionFirstRetention(enabled: Boolean = true): RoomAttachmentStore =
        apply {
            sessionFirstRetention = enabled
        }

    fun withRecovery(
        databaseProvider: () -> DevConsoleDatabase,
        recover: (Throwable) -> Unit,
    ): RoomAttachmentStore =
        apply {
            this.databaseProvider = databaseProvider
            this.recover = recover
        }

    override suspend fun write(request: AttachmentWriteRequest): AttachmentWriteResult {
        return coordinator.exclusive {
            val allowed =
                withContext(Dispatchers.IO) {
                    !sessionFirstRetention ||
                        executeWithSqliteRecovery(
                            unavailable = false,
                            resourceProvider = databaseProvider,
                            recover = recover,
                        ) { it.sessionDao().session(request.sessionId)?.status == "ACTIVE" }
                }
            if (!allowed) return@exclusive AttachmentWriteResult.Unavailable
            // A screenshot cannot be redacted -- NOT_APPLICABLE bypasses this assertion entirely so
            // that honestly-unredacted binary content is not forced into a false isRedacted=true.
            if (request.redactionApplicability == RedactionApplicability.APPLIED && !request.isRedacted) {
                return@exclusive AttachmentWriteResult.RejectedUnredactedContent
            }
            // NOT_APPLICABLE is a claim, not a bypass: it must still be honest for content redaction
            // could actually reach (text/JSON/XML). Mirrors FileAttachmentStore.redactsWithoutRedaction
            // so both stores enforce the same invariant even though this store calls files.prepare()
            // directly rather than files.write().
            if (request.redactionApplicability == RedactionApplicability.NOT_APPLICABLE &&
                request.mimeType.isRedactableMimeType()
            ) {
                return@exclusive AttachmentWriteResult.RejectedUnredactedContent
            }
            val prepared = files.prepare(request) ?: return@exclusive AttachmentWriteResult.Unavailable
            val current = limits

            val preparedInRoom =
                withContext(Dispatchers.IO) {
                    executeWithSqliteRecovery(
                        unavailable = false,
                        resourceProvider = databaseProvider,
                        recover = recover,
                    ) { activeDatabase ->
                        val dao = activeDatabase.attachmentDao()
                        activeDatabase.runInTransaction {
                            dao.insert(prepared.attachment.toEntity(clock(), pendingDeletion = true))
                            // Incremental correction: this row's own delta, not a full re-aggregation
                            // of the session's events/attachments (see SessionDao.incrementUsage).
                            activeDatabase.sessionDao().incrementUsage(
                                prepared.attachment.sessionId,
                                1,
                                prepared.attachment.storedLength,
                            )
                        }
                        dao.contains(prepared.attachment.id) == 1
                    }
                }
            if (!preparedInRoom) return@exclusive AttachmentWriteResult.Unavailable
            val materialized = files.materialize(prepared)
            if (materialized != FileMaterialization.Written) return@exclusive AttachmentWriteResult.Unavailable
            if (!finalizeAttachment(prepared, current)) return@exclusive AttachmentWriteResult.Unavailable
            AttachmentWriteResult.Success(prepared.attachment)
        }
    }

    /** Clears the pending-deletion tombstone and prunes to quota now that bytes are on disk. */
    private suspend fun finalizeAttachment(
        prepared: PreparedAttachment,
        current: Limits,
    ): Boolean =
        withContext(Dispatchers.IO) {
            executeWithSqliteRecovery(
                unavailable = false,
                resourceProvider = databaseProvider,
                recover = recover,
            ) { activeDatabase ->
                val dao = activeDatabase.attachmentDao()
                // Clearing the pending-deletion tombstone doesn't change record_count or
                // estimated_bytes: refreshUsage's aggregate query never filtered on pending_deletion,
                // so this row was already counted the moment it was inserted above. No counter update
                // needed here.
                dao.clearPendingDeletion(prepared.attachment.id)
                if (!sessionFirstRetention) {
                    AttachmentQuotaPruner(dao, files) { prunedSessionIds ->
                        // Prune is rare, so a full recompute per affected session here is fine --
                        // unlike the per-write increment above.
                        prunedSessionIds.forEach(activeDatabase.sessionDao()::refreshUsage)
                    }.pruneTo(
                        maxAttachmentBytes =
                            (current.maxTotalBytes - activeDatabase.eventDao().estimatedStoredBytes())
                                .coerceAtLeast(0),
                        cutoffEpochMs = (clock() - current.maxAgeMs).coerceAtLeast(0),
                    )
                }
                dao.attachment(prepared.attachment.id) != null
            }
        }

    override suspend fun deleteSession(sessionId: String) {
        coordinator.exclusive {
            val pending =
                withContext(Dispatchers.IO) {
                    executeWithSqliteRecovery(
                        unavailable = emptyList(),
                        resourceProvider = databaseProvider,
                        recover = recover,
                    ) { activeDatabase ->
                        activeDatabase.attachmentDao().markSessionPendingDeletion(sessionId)
                        activeDatabase.attachmentDao().pendingDeletionForSession(sessionId)
                    }
                }
            for (attachment in pending) {
                if (files.delete(attachment.toStoredAttachment()) == FileDeletion.Failed) continue
                withContext(Dispatchers.IO) {
                    executeWithSqliteRecovery(
                        unavailable = Unit,
                        resourceProvider = databaseProvider,
                        recover = recover,
                    ) { activeDatabase ->
                        activeDatabase.attachmentDao().deleteById(attachment.id)
                        // A precise per-row decrement, not a full re-aggregation: this loop already
                        // knows exactly which row (and how many bytes) it just removed.
                        activeDatabase.sessionDao().incrementUsage(sessionId, -1, -attachment.storedLength)
                    }
                }
            }
        }
    }

    /** Reads an attachment only when its Room metadata and on-disk digest still agree. */
    suspend fun read(attachmentId: String): ByteArray? {
        val attachment = metadata(attachmentId) ?: return null
        return files.read(attachment)
    }

    /**
     * Room metadata only -- no file I/O. Lets a caller (e.g. the attachment download route) report
     * authoritative fields such as [StoredAttachment.redactionApplicability] without paying for a
     * full byte read, and without having to re-derive them from the attachment's kind.
     */
    suspend fun metadata(attachmentId: String): StoredAttachment? {
        if (attachmentId.isBlank() || attachmentId.length > MAX_ATTACHMENT_ID_LENGTH) return null
        return withContext(Dispatchers.IO) {
            executeWithSqliteRecovery(
                unavailable = null,
                resourceProvider = databaseProvider,
                recover = recover,
            ) { activeDatabase ->
                activeDatabase.attachmentDao().attachment(attachmentId)?.toStoredAttachment()
            }
        }
    }

    private data class Limits(
        val maxTotalBytes: Long,
        val maxAgeMs: Long,
    )

    private companion object {
        const val MAX_ATTACHMENT_ID_LENGTH = 128
    }
}

/**
 * Deletes least-recently-created unbookmarked attachments until their tracked bytes fit quota.
 *
 * This prune is global, not session-scoped, so it can remove attachments belonging to any session --
 * not just the one whose write triggered it. [onSessionsPruned] is the correction hook for the
 * incremental `sessions.record_count`/`estimated_bytes` counters those deleted rows fed: every
 * session touched by a delete is reported once pruning finishes so the caller can run a full
 * [SessionDao.refreshUsage] for just those sessions. That full recompute is deliberately not done
 * here -- pruning is rare, unlike the per-write increments those counters are optimized for.
 */
class AttachmentQuotaPruner(
    private val attachmentDao: AttachmentDao,
    private val files: FileAttachmentStore,
    private val onSessionsPruned: (Collection<String>) -> Unit = {},
) {
    suspend fun pruneTo(maxAttachmentBytes: Long): Int = pruneTo(maxAttachmentBytes, Long.MIN_VALUE)

    // The eviction loop below breaks both when the remaining candidates are within cutoff/quota
    // and when an on-disk delete fails; a single-exit rewrite would obscure which condition stopped.
    @Suppress("LoopWithTooManyJumpStatements")
    suspend fun pruneTo(
        maxAttachmentBytes: Long,
        cutoffEpochMs: Long,
    ): Int {
        require(maxAttachmentBytes >= 0) { "maxAttachmentBytes must not be negative" }
        var totalBytes = attachmentDao.totalStoredBytes()
        var deleted = 0
        val affectedSessions = mutableSetOf<String>()
        for (entity in attachmentDao.oldestUnbookmarked()) {
            if (entity.createdWallTimeMs >= cutoffEpochMs && totalBytes <= maxAttachmentBytes) break
            if (files.delete(entity.toStoredAttachment()) == FileDeletion.Failed) break
            attachmentDao.deleteById(entity.id)
            affectedSessions += entity.sessionId
            totalBytes -= entity.storedLength
            deleted++
        }
        if (affectedSessions.isNotEmpty()) onSessionsPruned(affectedSessions)
        return deleted
    }
}

internal fun StoredAttachment.toEntity(
    createdWallTimeMs: Long,
    pendingDeletion: Boolean = false,
): AttachmentEntity =
    AttachmentEntity(
        id,
        eventId,
        sessionId,
        mimeType,
        originalLength,
        storedLength,
        truncated,
        sha256,
        isRedacted,
        relativePath,
        createdWallTimeMs,
        false,
        pendingDeletion,
        redactionApplicability.name,
    )

internal fun AttachmentEntity.toStoredAttachment(): StoredAttachment =
    StoredAttachment(
        id = id,
        eventId = eventId,
        sessionId = sessionId,
        mimeType = mimeType,
        originalLength = originalLength,
        storedLength = storedLength,
        truncated = truncated,
        sha256 = sha256,
        isRedacted = isRedacted,
        relativePath = relativePath,
        redactionApplicability = RedactionApplicability.valueOf(redactionApplicability),
    )
