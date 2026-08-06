@file:Suppress("ReturnCount") // Guard-clause early returns are the clearest form for this recovery boundary.

package io.devconsole.storage.room

import io.devconsole.timeline.InMemoryTimelineAnnotations
import io.devconsole.timeline.TimelineAnnotation
import io.devconsole.timeline.TimelineAnnotations

/** Room-backed bookmark/note metadata with storage failure isolation. */
class RoomTimelineAnnotations private constructor(
    private var dao: () -> TimelineAnnotationDao,
) : TimelineAnnotations {
    @Volatile
    private var recover: ((Throwable) -> Unit)? = null

    constructor(database: DevConsoleDatabase) : this({ database.timelineAnnotationDao() })

    internal constructor(dao: TimelineAnnotationDao) : this({ dao })

    fun withRecovery(
        databaseProvider: () -> DevConsoleDatabase,
        recover: (Throwable) -> Unit,
    ): RoomTimelineAnnotations =
        apply {
            dao = { databaseProvider().timelineAnnotationDao() }
            this.recover = recover
        }

    override fun get(eventId: String): TimelineAnnotation =
        executeWithRecovery(TimelineAnnotation()) { activeDao ->
            activeDao.annotation(eventId)?.let {
                TimelineAnnotation(bookmarked = it.bookmarked, note = it.note)
            } ?: TimelineAnnotation()
        }

    override fun bookmark(eventId: String) {
        val current = get(eventId)
        persist(eventId, current.copy(bookmarked = true))
    }

    override fun removeBookmark(eventId: String) {
        val current = get(eventId)
        persist(eventId, current.copy(bookmarked = false))
    }

    override fun setNote(
        eventId: String,
        note: String?,
    ) {
        require(note == null || note.length <= InMemoryTimelineAnnotations.MAX_NOTE_LENGTH) {
            "note exceeds ${InMemoryTimelineAnnotations.MAX_NOTE_LENGTH} characters"
        }
        persist(eventId, get(eventId).copy(note = note?.takeIf(String::isNotBlank)))
    }

    private fun persist(
        eventId: String,
        annotation: TimelineAnnotation,
    ) {
        executeWithRecovery(Unit) { activeDao ->
            if (!annotation.bookmarked && annotation.note == null) {
                activeDao.delete(eventId)
            } else {
                activeDao.upsert(
                    TimelineAnnotationEntity(
                        eventId,
                        annotation.bookmarked,
                        annotation.note,
                    ),
                )
            }
        }
    }

    private inline fun <T> executeWithRecovery(
        unavailable: T,
        operation: (TimelineAnnotationDao) -> T,
    ): T {
        val first = runCatching { operation(dao()) }
        first.getOrNull()?.let { return it }
        val failure = first.exceptionOrNull() ?: return unavailable
        if (!failure.isSqliteCorruption()) return unavailable
        val recovery = recover ?: return unavailable
        if (runCatching { recovery(failure) }.isFailure) return unavailable
        return runCatching { operation(dao()) }.getOrDefault(unavailable)
    }
}
