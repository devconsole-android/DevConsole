/**
 * @author Shakib
 * @since 20/07/26
 */
@file:Suppress("ReturnCount") // Guard-clause early returns are the clearest form for these recovery/insert checks.

package io.devconsole.storage.room

import androidx.sqlite.db.SimpleSQLiteQuery
import io.devconsole.storage.api.EventStore
import io.devconsole.storage.api.EventStoreWriteResult
import io.devconsole.storage.api.StoredEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Room-backed event data source. Callers pass redacted [StoredEvent] values only.
 *
 * One small function per EventStore operation plus the policy/clock/recovery configuration
 * helpers; splitting further would fragment one cohesive event-store boundary across classes.
 */
@Suppress("TooManyFunctions")
class RoomEventStore(
    private val database: DevConsoleDatabase,
    private val coordinator: RoomRetentionCoordinator,
    private val maxEvents: Long = 50_000,
) : EventStore {
    @Volatile
    private var databaseProvider: () -> DevConsoleDatabase = { database }

    @Volatile
    private var recover: ((Throwable) -> Unit)? = null

    @Volatile
    private var limits =
        Limits(
            maxEvents = maxEvents,
            maxAgeMs = DEFAULT_MAX_AGE_MS,
            maxBytes = DEFAULT_MAX_BYTES,
        )

    @Volatile
    private var clock: () -> Long = System::currentTimeMillis

    /** Phase 1B enables this with [RoomSessionStore] so retention is session-first, not row-first. */
    @Volatile
    private var sessionFirstRetention = false

    /**
     * Row-first retention's running totals, see [EventUsageEstimate]. Only touched from [insert] and
     * [deleteSession], both of which run inside [coordinator], so a single instance never has two
     * writers racing this state.
     */
    private val usageEstimate = EventUsageEstimate()

    fun withPolicy(
        maxEvents: Long,
        maxAgeMs: Long,
        maxBytes: Long,
    ): RoomEventStore {
        require(maxEvents > 0) { "maxEvents must be positive" }
        require(maxAgeMs > 0) { "maxAgeMs must be positive" }
        require(maxBytes > 0) { "maxBytes must be positive" }
        limits = Limits(maxEvents, maxAgeMs, maxBytes)
        return this
    }

    internal fun withClock(clock: () -> Long): RoomEventStore = apply { this.clock = clock }

    fun withSessionFirstRetention(enabled: Boolean = true): RoomEventStore =
        apply {
            // While session-first retention is on, row-first inserts still land in the table but skip
            // the usage estimate, so on any mode change the estimate is stale -- invalidate it to force
            // a fresh authoritative scan on the next row-first insert rather than trusting a baseline
            // captured before an unbounded number of session-first inserts. Without this the row-first
            // quota can be silently defeated across a stop/startBrowser cycle.
            if (sessionFirstRetention != enabled) usageEstimate.invalidate()
            sessionFirstRetention = enabled
        }

    fun withRecovery(
        databaseProvider: () -> DevConsoleDatabase,
        recover: (Throwable) -> Unit,
    ): RoomEventStore =
        apply {
            this.databaseProvider = databaseProvider
            this.recover = recover
        }

    override suspend fun insert(events: List<StoredEvent>): EventStoreWriteResult =
        coordinator.exclusive {
            withContext(Dispatchers.IO) {
                executeWithRecovery(EventStoreWriteResult.Unavailable) { activeDatabase ->
                    val sessionIds = events.map(StoredEvent::sessionId).distinct()
                    if (
                        sessionFirstRetention &&
                        sessionIds.any { activeDatabase.sessionDao().session(it)?.status != "ACTIVE" }
                    ) {
                        return@executeWithRecovery EventStoreWriteResult.Unavailable
                    }
                    if (events.isNotEmpty()) {
                        activeDatabase.runInTransaction {
                            activeDatabase.eventDao().insertAll(events.map(StoredEvent::toEntity))
                            // Incremental correction: this batch's own delta, not a full re-aggregation
                            // of the session's events/attachments (see SessionDao.incrementUsage).
                            events
                                .groupBy(StoredEvent::sessionId)
                                .forEach { (sessionId, sessionEvents) ->
                                    activeDatabase.sessionDao().incrementUsage(
                                        sessionId,
                                        sessionEvents.size.toLong(),
                                        sessionEvents.sumOf(StoredEvent::estimatedStorageBytes),
                                    )
                                }
                        }
                        if (!sessionFirstRetention) {
                            val current = limits
                            val attachmentBytes = activeDatabase.attachmentDao().totalStoredBytes()
                            val adjustedMaxBytes = (current.maxBytes - attachmentBytes).coerceAtLeast(0)
                            val batchBytes = events.sumOf(StoredEvent::estimatedStorageBytes)
                            if (usageEstimate.canAbsorb(events.size, batchBytes, current.maxEvents, adjustedMaxBytes)) {
                                // Steady-state fast path: stay well under both caps without paying for
                                // EventQuotaPruner.pruneTo's full-table COUNT/SUM scans on this insert.
                                usageEstimate.absorb(events.size, batchBytes)
                            } else {
                                val result =
                                    EventQuotaPruner(activeDatabase.eventDao()) { prunedSessionIds ->
                                        // Prune is rare and cross-session, so a full recompute per affected
                                        // session here is fine -- unlike the per-batch increment above.
                                        prunedSessionIds.forEach(activeDatabase.sessionDao()::refreshUsage)
                                    }.pruneTo(
                                        maxEvents = current.maxEvents,
                                        maxBytes = adjustedMaxBytes,
                                        cutoffEpochMs = (clock() - current.maxAgeMs).coerceAtLeast(0),
                                    )
                                usageEstimate.resetTo(result.remainingCount, result.remainingBytes)
                                if (result.expiredCount + result.quotaCount >= INCREMENTAL_VACUUM_THRESHOLD) {
                                    reclaimFreedPages(activeDatabase)
                                }
                            }
                        }
                    }
                    EventStoreWriteResult.Success(events.size)
                }
            }
        }

    override suspend fun eventsForSession(sessionId: String): List<StoredEvent> =
        withContext(Dispatchers.IO) {
            executeWithRecovery(emptyList()) {
                it.eventDao().eventsForSession(sessionId).map(EventEntity::toStoredEvent)
            }
        }

    override suspend fun recentEventsForSession(
        sessionId: String,
        limit: Int,
        pluginIds: Set<String>,
    ): List<StoredEvent> {
        require(limit > 0) { "limit must be positive" }
        return withContext(Dispatchers.IO) {
            executeWithRecovery(emptyList()) {
                // pluginIds empty -> the unfiltered query, same as before this parameter existed.
                // Non-empty routes to a dedicated query with plugin_id IN (...) inside the same
                // inner-SELECT-then-LIMIT shape, so filtering happens in SQL before the page size
                // trims rows -- not a fetch-everything-then-filter-in-Kotlin pass.
                if (pluginIds.isEmpty()) {
                    it.eventDao().recentEventsForSession(sessionId, limit)
                } else {
                    it.eventDao().recentEventsForSessionByPlugin(sessionId, pluginIds.toList(), limit)
                }.map(EventEntity::toStoredEvent)
            }
        }
    }

    override suspend fun recentEventsForPlugins(
        pluginIds: Set<String>,
        limit: Int,
    ): List<StoredEvent> {
        require(limit > 0) { "limit must be positive" }
        require(pluginIds.isNotEmpty()) { "pluginIds must not be empty" }
        return withContext(Dispatchers.IO) {
            executeWithRecovery(emptyList()) {
                it.eventDao().recentEventsByPlugin(pluginIds.toList(), limit).map(EventEntity::toStoredEvent)
            }
        }
    }

    override suspend fun deleteSession(sessionId: String) {
        coordinator.exclusive {
            withContext(Dispatchers.IO) {
                executeWithRecovery(Unit) { it.eventDao().deleteSession(sessionId) }
            }
            // An unknown number of this session's rows were just removed; force the next insert to
            // recompute the real totals rather than trusting a now-stale estimate.
            usageEstimate.invalidate()
        }
    }

    override suspend fun eventCount(): Long =
        withContext(Dispatchers.IO) {
            executeWithRecovery(0L) { it.eventDao().eventCount() }
        }

    suspend fun recentEvents(limit: Int = limits.maxEventsAsInt): List<StoredEvent> {
        require(limit > 0) { "limit must be positive" }
        return withContext(Dispatchers.IO) {
            executeWithRecovery(emptyList()) {
                it.eventDao().recentEvents(limit).map(EventEntity::toStoredEvent)
            }
        }
    }

    // Fail-open recovery boundary: any non-cancellation failure must trigger the recreate-and-retry
    // path below rather than crash the host, so it deliberately catches Exception broadly and has
    // more return/throw exits than the default guard-clause thresholds allow.
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private inline fun <T> executeWithRecovery(
        unavailable: T,
        operation: (DevConsoleDatabase) -> T,
    ): T {
        val failure =
            try {
                return operation(databaseProvider())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failure
            }
        if (!failure.isSqliteCorruption()) return unavailable
        val recovery = recover ?: return unavailable
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

    data class Limits(
        val maxEvents: Long,
        val maxAgeMs: Long,
        val maxBytes: Long,
    ) {
        val maxEventsAsInt: Int get() = maxEvents.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    companion object {
        const val DEFAULT_MAX_AGE_MS: Long = 24L * 60L * 60L * 1000L
        const val DEFAULT_MAX_BYTES: Long = 100L * 1024L * 1024L

        /** Only worth an incremental_vacuum pass once a prune freed at least a full delete batch. */
        private const val INCREMENTAL_VACUUM_THRESHOLD = 256
    }
}

/**
 * Reclaims pages freed by a large prune, keeping the on-disk file from drifting past [DevConsoleDatabase]'s
 * logical [EventDao.estimatedStoredBytes] estimate as index/free-list/WAL overhead accumulates.
 *
 * `PRAGMA incremental_vacuum` only returns pages once `PRAGMA auto_vacuum=INCREMENTAL` is set, which
 * SQLite honors only on an empty database or right after a full `VACUUM`. That pragma is wired in
 * `RecoveringDevConsoleDatabase` (module `sdk/full`, outside this module's Room schema ownership)
 * via a `RoomDatabase.Callback.onOpen` hook that enables it for new/small databases -- so this
 * reclaims space for those hosts, and is a harmless no-op for a large pre-existing database that
 * kept the default mode. `runCatching` because a failed housekeeping PRAGMA must never fail the
 * insert it rides along with.
 */
private fun reclaimFreedPages(database: DevConsoleDatabase) {
    runCatching { database.query(SimpleSQLiteQuery("PRAGMA incremental_vacuum")).close() }
}

internal fun Throwable.isSqliteCorruption(): Boolean =
    generateSequence(this) { it.cause }
        .any { failure ->
            failure.javaClass.simpleName.contains("SQLiteDatabaseCorruptException") ||
                failure.message.orEmpty().contains("database disk image is malformed", ignoreCase = true) ||
                failure.message.orEmpty().contains("file is not a database", ignoreCase = true)
        }

internal fun StoredEvent.toEntity(): EventEntity =
    EventEntity(
        id,
        sessionId,
        sequence,
        pluginId,
        type,
        wallTimeMs,
        monoTimeNs,
        severity,
        summary,
        correlationId,
        tagsJson,
        payloadJson,
        attachmentId,
        schemaVersion,
    )

/**
 * Mirrors [SessionDao.refreshUsage]'s per-event byte estimate (`96 + LENGTH(CAST(col AS BLOB))` for
 * each of these columns) so [SessionDao.incrementUsage] can move a session's `estimated_bytes` by
 * this batch's delta instead of re-summing the whole session. SQLite's `LENGTH(CAST(x AS BLOB))`
 * measures the UTF-8-encoded byte length of a TEXT column -- UTF-8 is SQLite's default text
 * encoding -- which is exactly what [String.toByteArray] with [Charsets.UTF_8] computes here, so the
 * two should always agree; this is called out as the one place the two formulas could in principle
 * diverge (e.g. a database opened with a non-UTF-8 encoding).
 */
internal fun StoredEvent.estimatedStorageBytes(): Long =
    EVENT_BASE_BYTES +
        id.utf8ByteLength() +
        sessionId.utf8ByteLength() +
        pluginId.utf8ByteLength() +
        type.utf8ByteLength() +
        summary.utf8ByteLength() +
        tagsJson.utf8ByteLength() +
        correlationId.orEmpty().utf8ByteLength() +
        payloadJson.orEmpty().utf8ByteLength() +
        attachmentId.orEmpty().utf8ByteLength()

private const val EVENT_BASE_BYTES = 96L

private fun String.utf8ByteLength(): Long = toByteArray(Charsets.UTF_8).size.toLong()

internal fun EventEntity.toStoredEvent(): StoredEvent =
    StoredEvent(
        id = id,
        sessionId = sessionId,
        sequence = sequence,
        pluginId = pluginId,
        type = type,
        wallTimeMs = wallTimeMs,
        monoTimeNs = monoTimeNs,
        severity = severity,
        summary = summary,
        correlationId = correlationId,
        tagsJson = tagsJson,
        payloadJson = payloadJson,
        attachmentId = attachmentId,
        schemaVersion = schemaVersion,
    )
