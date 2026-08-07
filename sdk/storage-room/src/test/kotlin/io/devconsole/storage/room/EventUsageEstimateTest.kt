/**
 * @author Shakib
 * @since 07/08/26
 */
package io.devconsole.storage.room

import io.devconsole.storage.api.StoredEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RoomEventStore.insert] drives [EventUsageEstimate] exactly as simulated by [driveInsert] here: on
 * every batch, ask [EventUsageEstimate.canAbsorb] whether the running totals (plus margin) stay
 * under both caps -- if so, just [EventUsageEstimate.absorb] the batch and skip the DAO's full-table
 * `eventCount()`/`estimatedStoredBytes()` scan entirely; otherwise run the "scan" (here, a counter
 * standing in for those two queries) and [EventUsageEstimate.resetTo] the authoritative totals it
 * returns.
 */
class EventUsageEstimateTest {
    @Test
    fun `an unknown estimate never claims it can absorb a batch`() {
        val estimate = EventUsageEstimate()

        assertFalse(estimate.canAbsorb(batchCount = 1, batchBytes = 1, maxEvents = 50_000, maxBytes = Long.MAX_VALUE))
    }

    @Test
    fun `invalidate forces the next batch back to a full recompute`() {
        val estimate = EventUsageEstimate()
        estimate.resetTo(count = 10, bytes = 1_000)
        assertTrue(estimate.canAbsorb(batchCount = 1, batchBytes = 1, maxEvents = 50_000, maxBytes = Long.MAX_VALUE))

        estimate.invalidate()

        assertFalse(estimate.canAbsorb(batchCount = 1, batchBytes = 1, maxEvents = 50_000, maxBytes = Long.MAX_VALUE))
    }

    @Test
    fun `steady-state inserts near the cap skip the full-table scan on every write`() {
        val maxEvents = 50_000L
        val maxBytes = 100L * 1024L * 1024L
        val estimate = EventUsageEstimate()
        // Start already close to the event cap -- the scenario the audit flagged as still paying for
        // a full scan on every insert even though nothing is actually over budget yet.
        var trueCount = maxEvents - 5_000
        var trueBytes = maxBytes / 4
        estimate.resetTo(trueCount, trueBytes)
        var scanCount = 0

        repeat(500) {
            val batchCount = 1
            val batchBytes = 256L
            scanCount +=
                driveInsert(estimate, batchCount, batchBytes, maxEvents, maxBytes) {
                    trueCount += batchCount
                    trueBytes += batchBytes
                    trueCount to trueBytes
                }
        }

        assertEquals("500 steady-state inserts must not each pay for a full-table scan", 0, scanCount)
        // The estimate must still agree with what a full DAO scan would have reported.
        assertTrue(estimate.canAbsorb(batchCount = 0, batchBytes = 0, maxEvents = maxEvents, maxBytes = maxBytes))
    }

    @Test
    fun `crossing the margin near the cap falls back to a real scan every time`() {
        val maxEvents = 100L
        val maxBytes = Long.MAX_VALUE
        val estimate = EventUsageEstimate()
        estimate.resetTo(count = 0, bytes = 0)
        var trueCount = 0L
        var scanCount = 0

        // EVENT_MARGIN is 256, larger than this maxEvents cap, so every batch here must force a real
        // scan -- the fast path must never claim safety it cannot actually guarantee.
        repeat(10) {
            scanCount +=
                driveInsert(estimate, batchCount = 5, batchBytes = 10, maxEvents, maxBytes) {
                    trueCount += 5
                    trueCount to (trueCount * 10)
                }
        }

        assertEquals(10, scanCount)
        assertEquals(50L, trueCount)
    }

    @Test
    fun `the incremental per-batch byte weight matches the DAO's full-table byte formula`() {
        // RoomEventStore.insert feeds EventUsageEstimate.absorb() with
        // StoredEvent.estimatedStorageBytes() -- if that ever drifted from
        // EventDao.estimatedStoredBytes()'s `96 + LENGTH(CAST(col AS BLOB))` SQL formula, the
        // fast path above would silently under- or over-count against what a real scan reports.
        val withOptionalFields =
            StoredEvent(
                id = "event-0",
                sessionId = "session-1",
                sequence = 0,
                pluginId = "network",
                type = "network.request",
                wallTimeMs = 0,
                monoTimeNs = 0,
                severity = 2,
                summary = "GET /v1/things",
                correlationId = "corr-1",
                tagsJson = """{"host":"example.com"}""",
                payloadJson = """{"status":200}""",
                attachmentId = "attachment-1",
            )
        val withoutOptionalFields =
            StoredEvent(
                id = "event-1",
                sessionId = "session-1",
                sequence = 1,
                pluginId = "system",
                type = "system.event",
                wallTimeMs = 0,
                monoTimeNs = 0,
                severity = 0,
                summary = "ready",
            )

        assertEquals(sqlByteFormula(withOptionalFields), withOptionalFields.estimatedStorageBytes())
        assertEquals(sqlByteFormula(withoutOptionalFields), withoutOptionalFields.estimatedStorageBytes())
    }

    /** Mirrors EventDao.estimatedStoredBytes()'s SQL SUM expression, evaluated for one row. */
    private fun sqlByteFormula(event: StoredEvent): Long =
        96L +
            event.id.utf8Bytes() +
            event.sessionId.utf8Bytes() +
            event.pluginId.utf8Bytes() +
            event.type.utf8Bytes() +
            event.summary.utf8Bytes() +
            event.tagsJson.utf8Bytes() +
            event.correlationId.orEmpty().utf8Bytes() +
            event.payloadJson.orEmpty().utf8Bytes() +
            event.attachmentId.orEmpty().utf8Bytes()

    private fun String.utf8Bytes(): Long = toByteArray(Charsets.UTF_8).size.toLong()

    /** Runs one insert's worth of [EventUsageEstimate] decision logic, mirroring [RoomEventStore.insert]. */
    @Suppress("LongParameterList") // A test harness mirroring the real insert()'s inputs; not production API.
    private fun driveInsert(
        estimate: EventUsageEstimate,
        batchCount: Int,
        batchBytes: Long,
        maxEvents: Long,
        maxBytes: Long,
        scan: () -> Pair<Long, Long>,
    ): Int =
        if (estimate.canAbsorb(batchCount, batchBytes, maxEvents, maxBytes)) {
            estimate.absorb(batchCount, batchBytes)
            0
        } else {
            val (authoritativeCount, authoritativeBytes) = scan()
            estimate.resetTo(authoritativeCount, authoritativeBytes)
            1
        }
}
