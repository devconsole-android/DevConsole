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
    fun `hydration keeps events captured while the Room query was in flight`() {
        val store = RecordingStore()
        val writer = EventBatchWriter(store, CoroutineScope(SupervisorJob()))
        val timeline =
            PersistentTimeline(
                InMemoryTimeline(emptyList(), CursorCodec("merge-secret-1234".encodeToByteArray())),
                writer,
            )
        val persisted = storedEvent("persisted", sessionId = "session")
        val live = storedEvent("live", sessionId = "session", sequence = 2)

        timeline.append(live)
        timeline.replaceHydratedForSession("session", listOf(persisted))

        val page = timeline.page(TimelineQuery()) as TimelinePage.Success
        assertEquals(setOf("persisted", "live"), page.events.map(StoredEvent::id).toSet())
        assertEquals(0, store.inserted.size)
    }

    @Test
    fun `startup events wait for durable session before the writer starts`() =
        runTest {
            val store = RecordingStore()
            val writer = EventBatchWriter(store, this)
            val timeline =
                PersistentTimeline(
                    delegate = InMemoryTimeline(emptyList(), CursorCodec("startup-secret-123".encodeToByteArray())),
                    writer = writer,
                    persistenceReady = false,
                )
            val event = storedEvent("startup")

            timeline.append(event)
            assertEquals(emptyList<StoredEvent>(), store.inserted)

            timeline.setPersistenceReady(true)
            writer.flushAndStop()

            assertEquals(listOf("startup"), store.inserted.map(StoredEvent::id))
        }

    @Test
    fun `a ready timeline starts a writer that was stopped before construction`() =
        runTest {
            val store = RecordingStore()
            val writer = EventBatchWriter(store, this)
            writer.stop()
            val timeline =
                PersistentTimeline(
                    delegate = InMemoryTimeline(emptyList(), CursorCodec("ready-secret-1234".encodeToByteArray())),
                    writer = writer,
                    persistenceReady = true,
                )

            timeline.append(storedEvent("ready"))
            writer.flushAndStop()

            assertEquals(listOf("ready"), store.inserted.map(StoredEvent::id))
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

    private fun storedEvent(
        id: String,
        sessionId: String = "session",
        sequence: Long = 1,
    ) = StoredEvent(id, sessionId, sequence, "system", "event", 1, sequence, 1, "ready")

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
