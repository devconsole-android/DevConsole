package io.devconsole.core

import io.devconsole.api.EventSeverity
import io.devconsole.storage.api.EventStore
import io.devconsole.storage.api.EventStoreWriteResult
import io.devconsole.storage.api.StoredEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class EventBatchWriterTest {
    @Test
    fun `batches already-redacted envelopes at the configured event threshold`() =
        runTest {
            val store = RecordingEventStore()
            val writer = EventBatchWriter(store, this, capacity = 2, maxBatchSize = 2, flushIntervalMs = 1000)
            writer.start()

            writer.submit(event("Bearer first-secret"))
            writer.submit(event("Bearer second-secret"))
            testScheduler.runCurrent()

            assertEquals(1, store.batches.size)
            assertFalse(
                store.batches
                    .single()
                    .joinToString()
                    .contains("first-secret"),
            )
            assertEquals(2, store.batches.single().size)
            writer.stop()
        }

    @Test
    fun `full persistence queue evicts the oldest lowest severity event`() =
        runTest {
            val dropped = mutableListOf<Pair<StoredEvent, EventBatchDropReason>>()
            val store = RecordingEventStore()
            val writer =
                EventBatchWriter(
                    store,
                    this,
                    capacity = 2,
                    onDrop = { event, reason -> dropped += event to reason },
                )

            assertTrue(writer.submit(event("first", EventSeverity.DEBUG)))
            assertTrue(writer.submit(event("second", EventSeverity.ERROR)))
            assertTrue(writer.submit(event("third", EventSeverity.WARN)))
            writer.start()
            testScheduler.runCurrent()
            writer.flushAndStop()

            assertEquals(listOf("first"), dropped.map { it.first.summary })
            assertEquals(listOf(EventBatchDropReason.QUEUE_FULL), dropped.map { it.second })
            assertEquals(listOf("second", "third"), store.batches.flatten().map(StoredEvent::summary))
        }

    @Test
    fun `byte capacity rejects an individual event larger than the entire queue budget`() {
        val dropped = mutableListOf<Pair<StoredEvent, EventBatchDropReason>>()
        val writer =
            EventBatchWriter(
                RecordingEventStore(),
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
                capacity = 10,
                onDrop = { event, reason -> dropped += event to reason },
            ).withByteCapacity(256)

        assertFalse(writer.submit(event("x".repeat(1_000))))

        assertEquals(EventBatchDropReason.QUEUE_FULL, dropped.single().second)
        writer.stop()
    }

    @Test
    fun `flushes on lifecycle stop and accepts events after restart`() =
        runTest {
            val store = RecordingEventStore()
            val writer =
                EventBatchWriter(
                    store,
                    this,
                    maxBatchSize = 100,
                    flushIntervalMs = 10_000,
                )
            writer.start()
            assertTrue(writer.submit(event("before stop")))
            testScheduler.runCurrent()

            writer.flushAndStop()
            writer.start()
            assertTrue(writer.submit(event("after restart")))
            testScheduler.runCurrent()
            writer.flushAndStop()

            assertEquals(
                listOf("before stop", "after restart"),
                store.batches.flatten().map(StoredEvent::summary),
            )
        }

    private fun event(
        summary: String,
        severity: EventSeverity = EventSeverity.INFO,
    ) = EventPipeline(UUID.randomUUID(), 2)
        .publish(EventDraft("network", "request", severity, summary))

    private class RecordingEventStore : EventStore {
        val batches = mutableListOf<List<StoredEvent>>()

        override suspend fun insert(events: List<StoredEvent>): EventStoreWriteResult {
            batches += events
            return EventStoreWriteResult.Success(events.size)
        }

        override suspend fun eventsForSession(sessionId: String): List<StoredEvent> = emptyList()

        override suspend fun deleteSession(sessionId: String) = Unit

        override suspend fun eventCount(): Long = batches.sumOf { it.size }.toLong()
    }
}
