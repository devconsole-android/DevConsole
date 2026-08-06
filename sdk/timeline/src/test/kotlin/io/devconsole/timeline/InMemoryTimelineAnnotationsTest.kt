package io.devconsole.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryTimelineAnnotationsTest {
    @Test
    fun `bookmark and note metadata can be independently updated and cleared`() {
        val annotations = InMemoryTimelineAnnotations()

        annotations.bookmark("event-1")
        annotations.setNote("event-1", "Check retry path")
        assertTrue(annotations.get("event-1").bookmarked)
        assertEquals("Check retry path", annotations.get("event-1").note)

        annotations.removeBookmark("event-1")
        assertFalse(annotations.get("event-1").bookmarked)
        assertEquals("Check retry path", annotations.get("event-1").note)
    }
}
