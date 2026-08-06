package io.devconsole.storage.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomTimelineAnnotationsTest {
    @Test
    fun `bookmarks and notes survive across store instances and empty metadata is removed`() {
        val dao = FakeTimelineAnnotationDao()
        val first = RoomTimelineAnnotations(dao)
        first.bookmark("event-1")
        first.setNote("event-1", "investigate")

        val restored = RoomTimelineAnnotations(dao)
        assertTrue(restored.get("event-1").bookmarked)
        assertEquals("investigate", restored.get("event-1").note)

        restored.removeBookmark("event-1")
        assertFalse(restored.get("event-1").bookmarked)
        restored.setNote("event-1", null)

        assertNull(dao.annotation("event-1"))
    }

    private class FakeTimelineAnnotationDao : TimelineAnnotationDao {
        private val annotations = mutableMapOf<String, TimelineAnnotationEntity>()

        override fun annotation(eventId: String): TimelineAnnotationEntity? = annotations[eventId]

        override fun upsert(annotation: TimelineAnnotationEntity) {
            annotations[annotation.eventId] = annotation
        }

        override fun delete(eventId: String) {
            annotations.remove(eventId)
        }

        // The real query joins annotations to events by session; this fake holds no events table,
        // so there is nothing to resolve. No test here exercises cross-table session deletion.
        override fun deleteSession(sessionId: String) = Unit

        override fun deleteEvents(eventIds: List<String>) {
            eventIds.forEach { annotations.remove(it) }
        }
    }
}
