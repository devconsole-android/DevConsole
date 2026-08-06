package io.devconsole

import io.devconsole.core.EventBatchWriter
import io.devconsole.storage.api.StoredEvent
import io.devconsole.timeline.InMemoryTimeline
import io.devconsole.timeline.Timeline
import io.devconsole.timeline.TimelineAppender
import io.devconsole.timeline.TimelinePage
import io.devconsole.timeline.TimelineQuery

/**
 * Serves reads from [delegate] while mirroring every appended event to durable storage through
 * [writer], so a crash or process death does not take the timeline with it — which is precisely the
 * moment the timeline matters most.
 *
 * Reads stay in memory deliberately: [Timeline.page] is synchronous and already implements cursors
 * and filtering, whereas [io.devconsole.storage.api.EventStore] is suspending and session-scoped.
 * Persistence here is write-through, not read-through.
 */
internal class PersistentTimeline(
    private val delegate: Timeline,
    writer: EventBatchWriter,
) : Timeline,
    TimelineAppender {
    @Volatile
    private var writer = writer

    override fun page(query: TimelineQuery): TimelinePage = delegate.page(query)

    override fun contains(eventId: String): Boolean = delegate.contains(eventId)

    /** Hydrates process-recreated history without mirroring the same rows back into Room. */
    fun hydrate(events: List<StoredEvent>) {
        val appender = delegate as? TimelineAppender ?: return
        events.forEach { event ->
            if (!delegate.contains(event.id)) appender.append(event)
        }
    }

    /** Replaces rather than merges history so restarts never display the prior live app run. */
    fun replaceHydratedForSession(
        sessionId: String,
        events: List<StoredEvent>,
    ) {
        (delegate as? InMemoryTimeline)?.replaceSession(sessionId, events)
            ?: hydrate(events.filter { it.sessionId == sessionId })
    }

    /** Rebinds persistence after an auto-initialized config is replaced by the host config. */
    fun replaceWriter(replacement: EventBatchWriter) {
        writer = replacement
    }

    override fun append(event: StoredEvent) {
        if (!delegate.contains(event.id)) {
            (delegate as? TimelineAppender)?.append(event)
        }
        // Bounded channel with drop-oldest: a storage stall degrades history rather than the app.
        writer.submit(event)
    }
}
