/**
 * @author Shakib
 * @since 20/07/26
 */
package io.devconsole.storage.room

data class EventPruneResult(
    val expiredCount: Int,
    val quotaCount: Int,
    val remainingCount: Long,
    val remainingBytes: Long,
)

/**
 * Deletes expired events, then bounded batches of the oldest lowest-severity quota candidates.
 *
 * This prune is global (row-first), not session-scoped, so it can remove rows belonging to any
 * session -- not just the one whose write triggered it. [onSessionsPruned] is the correction hook
 * for the incremental `sessions.record_count`/`estimated_bytes` counters those deleted rows fed:
 * every session touched by a delete is reported once pruning finishes so the caller can run a full
 * [SessionDao.refreshUsage] for just those sessions. That full recompute is deliberately not done
 * here -- pruning is rare, unlike the per-event-batch writes those counters are optimized for.
 */
class EventQuotaPruner(
    private val eventDao: EventDao,
    private val onSessionsPruned: (Collection<String>) -> Unit = {},
) {
    suspend fun pruneTo(maxEvents: Long): Int {
        val result =
            pruneTo(
                maxEvents = maxEvents,
                maxBytes = Long.MAX_VALUE,
                cutoffEpochMs = Long.MIN_VALUE,
            )
        return result.expiredCount + result.quotaCount
    }

    suspend fun pruneTo(
        maxEvents: Long,
        maxBytes: Long,
        cutoffEpochMs: Long,
    ): EventPruneResult {
        require(maxEvents >= 0) { "maxEvents must not be negative" }
        require(maxBytes >= 0) { "maxBytes must not be negative" }
        val affectedSessions = mutableSetOf<String>()
        val expired =
            if (cutoffEpochMs == Long.MIN_VALUE) {
                0
            } else {
                affectedSessions += eventDao.sessionIdsOlderThan(cutoffEpochMs)
                eventDao.deleteOlderThan(cutoffEpochMs)
            }
        var count = eventDao.eventCount()
        var bytes = eventDao.estimatedStoredBytes()
        var quotaDeleted = 0
        while (count > maxEvents || bytes > maxBytes) {
            val countExcess = (count - maxEvents).coerceAtLeast(0)
            val limit =
                countExcess
                    .coerceAtLeast(1)
                    .coerceAtMost(PRUNE_BATCH_SIZE.toLong())
                    .toInt()
            val ids = eventDao.oldestLowSeverityFirst(limit)
            if (ids.isEmpty()) break
            affectedSessions += eventDao.sessionIdsForIds(ids)
            eventDao.deleteByIds(ids)
            quotaDeleted += ids.size
            count = eventDao.eventCount()
            bytes = eventDao.estimatedStoredBytes()
        }
        if (affectedSessions.isNotEmpty()) onSessionsPruned(affectedSessions)
        return EventPruneResult(
            expiredCount = expired,
            quotaCount = quotaDeleted,
            remainingCount = count,
            remainingBytes = bytes,
        )
    }

    private companion object {
        const val PRUNE_BATCH_SIZE = 256
    }
}

/**
 * Running totals [RoomEventStore] keeps between inserts so most writes can skip
 * [EventDao.eventCount]/[EventDao.estimatedStoredBytes] -- a full-table scan (the latter sums nine
 * `LENGTH(CAST(... AS BLOB))` expressions per row) that [EventQuotaPruner.pruneTo] would otherwise
 * run on every single insert, steady-state or not.
 *
 * The totals are an estimate, not a cache of ground truth: [canAbsorb] only reports "safe to skip"
 * when the projected totals, plus a safety margin, still land comfortably under both caps, so the
 * bound between quota checks is the margin, not unbounded drift. [resetTo] replaces the estimate
 * with the authoritative post-prune totals every time a real check does run, so error never
 * compounds across skipped checks. Not thread-safe on its own; callers must serialize access (see
 * [RoomRetentionCoordinator]).
 */
class EventUsageEstimate {
    private var count: Long = UNKNOWN
    private var bytes: Long = UNKNOWN

    private val isKnown: Boolean
        get() = count >= 0 && bytes >= 0

    /** Whether adding this batch would still leave both totals comfortably under their caps. */
    fun canAbsorb(
        batchCount: Int,
        batchBytes: Long,
        maxEvents: Long,
        maxBytes: Long,
    ): Boolean {
        if (!isKnown) return false
        val projectedCount = count + batchCount
        val projectedBytes = bytes + batchBytes
        return projectedCount + EVENT_MARGIN < maxEvents && projectedBytes + byteMargin(maxBytes) < maxBytes
    }

    /** Moves the running totals by this batch's delta, without touching the database. */
    fun absorb(
        batchCount: Int,
        batchBytes: Long,
    ) {
        count += batchCount
        bytes += batchBytes
    }

    /** Replaces the estimate with the authoritative totals a real prune just computed. */
    fun resetTo(
        count: Long,
        bytes: Long,
    ) {
        this.count = count
        this.bytes = bytes
    }

    /** Forces the next [canAbsorb] call to fail, so a full recompute happens before trusting the estimate again. */
    fun invalidate() {
        count = UNKNOWN
        bytes = UNKNOWN
    }

    private companion object {
        const val UNKNOWN = -1L
        const val EVENT_MARGIN = 256L
        const val MIN_BYTE_MARGIN = 64L * 1024L

        /** The byte-margin is 1% of the cap (i.e. cap / 100), floored at [MIN_BYTE_MARGIN]. */
        const val BYTE_MARGIN_DIVISOR = 100L

        fun byteMargin(maxBytes: Long): Long = (maxBytes / BYTE_MARGIN_DIVISOR).coerceAtLeast(MIN_BYTE_MARGIN)
    }
}
