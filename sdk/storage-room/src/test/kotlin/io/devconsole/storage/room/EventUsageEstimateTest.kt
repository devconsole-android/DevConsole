/**
 * @author Shakib
 * @since 05/08/26
 */
package io.devconsole.storage.room

import io.devconsole.storage.api.StoredEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [StoredEvent.estimatedStorageBytes] is the Kotlin-side mirror of the byte estimate
 * [SessionDao.refreshUsage] computes in SQL (`96 + LENGTH(CAST(col AS BLOB))` per column), used so
 * [SessionDao.incrementUsage] can move `estimated_bytes` by a batch's delta without a full
 * re-aggregation. These tests recompute that same formula independently -- summing UTF-8 byte
 * lengths by hand rather than delegating back to the function under test -- so a mistake in the
 * production formula (e.g. char length instead of byte length) would actually be caught.
 */
class EventUsageEstimateTest {
    @Test
    fun `matches the SQL LENGTH(CAST(col AS BLOB)) formula for an event with every optional field set`() {
        val event =
            StoredEvent(
                id = "event-1",
                sessionId = "session-1",
                sequence = 7,
                pluginId = "network",
                type = "network.transaction",
                wallTimeMs = 11,
                monoTimeNs = 12,
                severity = 3,
                summary = "<redacted>",
                correlationId = "trace-1",
                tagsJson = "{\"route\":\"/orders\"}",
                payloadJson = "{\"body\":\"<redacted>\"}",
                attachmentId = "attachment-1",
            )

        assertEquals(expectedSqlFormulaBytes(event), event.estimatedStorageBytes())
    }

    @Test
    fun `matches the SQL formula when every optional field is null, using empty-string byte length`() {
        val event =
            StoredEvent(
                id = "event-1",
                sessionId = "session-1",
                sequence = 0,
                pluginId = "system",
                type = "system.event",
                wallTimeMs = 0,
                monoTimeNs = 0,
                severity = 0,
                summary = "ready",
                correlationId = null,
                payloadJson = null,
                attachmentId = null,
            )

        assertEquals(expectedSqlFormulaBytes(event), event.estimatedStorageBytes())
    }

    @Test
    fun `counts multi-byte UTF-8 characters by byte length, not by Kotlin char count`() {
        // "café" is 4 chars but 5 UTF-8 bytes; an emoji is 2 chars (a surrogate pair) but 4
        // UTF-8 bytes. LENGTH(CAST(x AS BLOB)) in SQLite counts encoded bytes, so the Kotlin mirror
        // must too -- String.length would silently under-count both cases.
        val event =
            StoredEvent(
                id = "event-1",
                sessionId = "session-1",
                sequence = 0,
                pluginId = "system",
                type = "system.event",
                wallTimeMs = 0,
                monoTimeNs = 0,
                severity = 0,
                summary = "café 😀",
                correlationId = null,
                payloadJson = null,
                attachmentId = null,
            )

        assertEquals(expectedSqlFormulaBytes(event), event.estimatedStorageBytes())
    }

    @Test
    fun `a batch's summed per-event estimate matches refreshUsage recomputing the whole batch`() {
        val events =
            (0 until 5).map { index ->
                StoredEvent(
                    id = "event-$index",
                    sessionId = "session-1",
                    sequence = index.toLong(),
                    pluginId = "network",
                    type = "network.transaction",
                    wallTimeMs = index.toLong(),
                    monoTimeNs = index.toLong(),
                    severity = index,
                    summary = "summary-$index",
                    correlationId = if (index % 2 == 0) "trace-$index" else null,
                    payloadJson = if (index % 2 == 0) "{\"n\":$index}" else null,
                    attachmentId = null,
                )
            }

        val incrementalTotal = events.sumOf { it.estimatedStorageBytes() }
        val recomputedTotal = events.sumOf { expectedSqlFormulaBytes(it) }

        assertEquals(recomputedTotal, incrementalTotal)
    }

    /** Independently re-derives the SQL formula's expected byte count for one event. */
    private fun expectedSqlFormulaBytes(event: StoredEvent): Long =
        96L +
            event.id.byteLen() +
            event.sessionId.byteLen() +
            event.pluginId.byteLen() +
            event.type.byteLen() +
            event.summary.byteLen() +
            event.tagsJson.byteLen() +
            event.correlationId.orEmpty().byteLen() +
            event.payloadJson.orEmpty().byteLen() +
            event.attachmentId.orEmpty().byteLen()

    private fun String.byteLen(): Long = toByteArray(Charsets.UTF_8).size.toLong()
}
