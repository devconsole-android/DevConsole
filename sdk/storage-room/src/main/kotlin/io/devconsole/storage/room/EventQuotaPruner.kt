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
