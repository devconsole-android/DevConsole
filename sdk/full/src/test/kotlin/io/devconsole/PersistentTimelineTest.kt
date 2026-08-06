package io.devconsole

import io.devconsole.core.EventBatchWriter
import io.devconsole.storage.api.EventStore
import io.devconsole.storage.api.EventStoreWriteResult
import io.devconsole.storage.api.StoredEvent
import io.devconsole.timeline.CursorCodec
import io.devconsole.timeline.InMemoryTimeline
import io.devconsole.timeline.TimelinePage
import io.devconsole.timeline.TimelineQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PersistentTimelineTest {
    @Test
    fun `hydration exposes durable history without writing it back or duplicating ids`() {
        val store = RecordingStore()
        val writer = EventBatchWriter(store, CoroutineScope(SupervisorJob()))
        val timeline =
            PersistentTimeline(
                InMemoryTimeline(emptyList(), CursorCodec("persistent-secret".encodeToByteArray())),
                writer,
            )
        val event = storedEvent("persisted")

        timeline.hydrate(listOf(event, event))

        val page = timeline.page(TimelineQuery()) as TimelinePage.Success
        assertEquals(listOf("persisted"), page.events.map(StoredEvent::id))
        assertEquals(0, store.inserted.size)
    }

    @Test
    fun `replacing the persistence writer routes subsequent events to the configured queue`() =
        runTest {
            val initialStore = RecordingStore()
            val replacementStore = RecordingStore()
            val initialWriter = EventBatchWriter(initialStore, this)
            val replacementWriter = EventBatchWriter(replacementStore, this, capacity = 1)
            val timeline =
                PersistentTimeline(
                    InMemoryTimeline(emptyList(), CursorCodec("replacement-secret".encodeToByteArray())),
                    initialWriter,
                )
            initialWriter.stop()
            replacementWriter.start()

            timeline.replaceWriter(replacementWriter)
            timeline.append(storedEvent("replacement"))
            testScheduler.runCurrent()
            replacementWriter.flushAndStop()

            assertEquals(emptyList<StoredEvent>(), initialStore.inserted)
            assertEquals(listOf("replacement"), replacementStore.inserted.map(StoredEvent::id))
        }

    private fun storedEvent(id: String) = StoredEvent(id, "session", 1, "system", "event", 1, 1, 1, "ready")

    private class RecordingStore : EventStore {
        val inserted = mutableListOf<StoredEvent>()

        override suspend fun insert(events: List<StoredEvent>): EventStoreWriteResult {
            inserted += events
            return EventStoreWriteResult.Success(events.size)
        }

        override suspend fun eventsForSession(sessionId: String) = inserted.filter { it.sessionId == sessionId }

        override suspend fun deleteSession(sessionId: String) = Unit

        override suspend fun eventCount(): Long = inserted.size.toLong()
    }
}
