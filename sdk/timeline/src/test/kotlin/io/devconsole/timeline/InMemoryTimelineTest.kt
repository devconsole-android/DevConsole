package io.devconsole.timeline

import io.devconsole.storage.api.StoredEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryTimelineTest {
    private val events =
        listOf(
            event(id = "a", sequence = 1, plugin = "system", summary = "started"),
            event(id = "b", sequence = 2, plugin = "network", summary = "request"),
            event(id = "c", sequence = 3, plugin = "network", summary = "response"),
        )

    @Test
    fun `append evicts oldest events and removes their ids`() {
        val timeline = InMemoryTimeline(emptyList(), CursorCodec("cursor-secret-128".encodeToByteArray()), capacity = 3)
        val appended = (1L..5L).map { event("event-$it", it, "system", "event $it") }

        appended.forEach(timeline::append)

        val page = timeline.page(TimelineQuery(limit = 10)) as TimelinePage.Success
        assertEquals(listOf("event-3", "event-4", "event-5"), page.events.map(StoredEvent::id))
        assertTrue(!timeline.contains("event-1"))
        assertTrue(!timeline.contains("event-2"))
        assertTrue(timeline.contains("event-3"))
    }

    @Test
    fun `append deduplicates event ids`() {
        val timeline = InMemoryTimeline(emptyList(), CursorCodec("cursor-secret-128".encodeToByteArray()), capacity = 2)
        val event = event("same", 1, "system", "original")

        timeline.append(event)
        timeline.append(event.copy(summary = "duplicate"))

        val page = timeline.page(TimelineQuery()) as TimelinePage.Success
        assertEquals(listOf("original"), page.events.map(StoredEvent::summary))
    }

    @Test
    fun `append remains practical across a large bounded stream`() {
        val timeline =
            InMemoryTimeline(
                emptyList(),
                CursorCodec("cursor-secret-128".encodeToByteArray()),
                capacity = 5_000,
            )
        val startedAt = System.nanoTime()

        (1L..100_000L).forEach { sequence ->
            timeline.append(event("event-$sequence", sequence, "system", "event $sequence"))
        }

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("100k bounded appends took ${elapsedMs}ms", elapsedMs < 10_000)
        assertFalse(timeline.contains("event-1"))
        assertTrue(timeline.contains("event-100000"))
        val newest = timeline.page(TimelineQuery(limit = 500, sort = TimelineSort.DESC)) as TimelinePage.Success
        assertEquals(500, newest.events.size)
        assertEquals("event-100000", newest.events.first().id)
    }

    @Test
    fun `paginates by opaque cursor without duplicating events`() {
        val timeline = InMemoryTimeline(events, CursorCodec("cursor-secret-128".encodeToByteArray()))

        val first = timeline.page(TimelineQuery(limit = 2)) as TimelinePage.Success
        val second = timeline.page(TimelineQuery(limit = 2, cursor = first.nextCursor)) as TimelinePage.Success

        assertEquals(listOf("a", "b"), first.events.map { it.id })
        assertEquals(listOf("c"), second.events.map { it.id })
        assertTrue(second.nextCursor == null)
    }

    @Test
    fun `rejects cursor reused with different filters`() {
        val timeline = InMemoryTimeline(events, CursorCodec("cursor-secret-128".encodeToByteArray()))
        val cursor = (timeline.page(TimelineQuery(limit = 1)) as TimelinePage.Success).nextCursor!!

        val result = timeline.page(TimelineQuery(limit = 1, pluginIds = setOf("network"), cursor = cursor))

        assertTrue(result is TimelinePage.InvalidCursor)
    }

    @Test
    fun `orders and paginates by monotonic time then sequence then id`() {
        val timeline =
            InMemoryTimeline(
                listOf(
                    event("late", sequence = 1, plugin = "system", summary = "late", monoTimeNs = 30),
                    event("same-b", sequence = 3, plugin = "system", summary = "same b", monoTimeNs = 10),
                    event("same-a", sequence = 2, plugin = "system", summary = "same a", monoTimeNs = 10),
                ),
                CursorCodec("cursor-secret-128".encodeToByteArray()),
            )

        val first = timeline.page(TimelineQuery(limit = 1)) as TimelinePage.Success
        val second = timeline.page(TimelineQuery(limit = 2, cursor = first.nextCursor)) as TimelinePage.Success

        assertEquals(listOf("same-a"), first.events.map(StoredEvent::id))
        assertEquals(listOf("same-b", "late"), second.events.map(StoredEvent::id))
    }

    @Test
    fun `filters an inclusive wall clock range and scopes its cursor`() {
        val timeline = InMemoryTimeline(events, CursorCodec("cursor-secret-128".encodeToByteArray()))
        val query = TimelineQuery(limit = 1).withTimeRange(fromEpochMs = 2, toEpochMs = 3)
        val first = timeline.page(query) as TimelinePage.Success

        assertEquals(listOf("b"), first.events.map(StoredEvent::id))
        assertTrue(
            timeline.page(
                TimelineQuery(limit = 1, cursor = first.nextCursor).withTimeRange(fromEpochMs = 1, toEpochMs = 3),
            ) is TimelinePage.InvalidCursor,
        )
    }

    @Test
    fun `cursor continuation preserves an additive time range`() {
        val timeline = InMemoryTimeline(events, CursorCodec("cursor-secret-128".encodeToByteArray()))
        val firstQuery = TimelineQuery(limit = 1).withTimeRange(fromEpochMs = 2, toEpochMs = 3)
        val first = timeline.page(firstQuery) as TimelinePage.Success

        val second =
            timeline.page(firstQuery.withCursor(first.nextCursor)) as TimelinePage.Success

        assertEquals(listOf("b"), first.events.map(StoredEvent::id))
        assertEquals(listOf("c"), second.events.map(StoredEvent::id))
    }

    private fun event(
        id: String,
        sequence: Long,
        plugin: String,
        summary: String,
        monoTimeNs: Long = sequence,
    ) = StoredEvent(
        id = id,
        sessionId = "session",
        sequence = sequence,
        pluginId = plugin,
        type = "event",
        wallTimeMs = sequence,
        monoTimeNs = monoTimeNs,
        severity = 1,
        summary = summary,
    )
}
