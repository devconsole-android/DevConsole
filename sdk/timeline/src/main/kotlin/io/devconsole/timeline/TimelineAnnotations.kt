package io.devconsole.timeline

data class TimelineAnnotation(
    val bookmarked: Boolean = false,
    val note: String? = null,
)

interface TimelineAnnotations {
    fun get(eventId: String): TimelineAnnotation

    fun bookmark(eventId: String)

    fun removeBookmark(eventId: String)

    fun setNote(
        eventId: String,
        note: String?,
    )
}

/** Reference annotation store; the Room-backed implementation will persist the same metadata. */
class InMemoryTimelineAnnotations : TimelineAnnotations {
    private val annotations = mutableMapOf<String, TimelineAnnotation>()

    override fun get(eventId: String): TimelineAnnotation = annotations[eventId] ?: TimelineAnnotation()

    override fun bookmark(eventId: String) {
        annotations[eventId] = get(eventId).copy(bookmarked = true)
    }

    override fun removeBookmark(eventId: String) {
        annotations[eventId] = get(eventId).copy(bookmarked = false)
    }

    override fun setNote(
        eventId: String,
        note: String?,
    ) {
        require(note == null || note.length <= MAX_NOTE_LENGTH) { "note exceeds $MAX_NOTE_LENGTH characters" }
        annotations[eventId] = get(eventId).copy(note = note?.takeIf(String::isNotBlank))
    }

    companion object {
        const val MAX_NOTE_LENGTH = 4_096
    }
}
