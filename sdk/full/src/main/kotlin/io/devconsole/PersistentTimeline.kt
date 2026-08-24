package io.devconsole

import io.devconsole.core.EventBatchWriter
import io.devconsole.storage.api.StoredEvent
import io.devconsole.timeline.InMemoryTimeline
import io.devconsole.timeline.Timeline
import io.devconsole.timeline.TimelineAppender
import io.devconsole.timeline.TimelinePage
import io.devconsole.timeline.TimelineQuery
import java.util.ArrayDeque

/**
 * Serves reads from [delegate] while mirroring every appended event to durable storage through
 * [writer], so a crash or process death does not take the timeline with it — which is precisely the
 * moment the timeline matters most.
 *
 * Reads stay in memory deliberately: [Timeline.page] is synchronous and already implements cursors
 * and filtering, whereas [io.devconsole.storage.api.EventStore] is suspending and session-scoped.
 * Persistence is write-through once the active session row is durable; startup events are retained
 * in a bounded handoff queue until that gate opens.
 */
internal class PersistentTimeline(
    private val delegate: Timeline,
    writer: EventBatchWriter,
    persistenceReady: Boolean = true,
    private val pendingCapacity: Int = EventBatchWriter.DEFAULT_CAPACITY,
    private val onPendingDrop: (StoredEvent) -> Unit = {},
) : Timeline,
    TimelineAppender {
    private val persistenceLock = Any()

    @Volatile
    private var writer = writer

    private var persistenceReady = persistenceReady
    private val pendingEvents = ArrayDeque<StoredEvent>()

    init {
        require(pendingCapacity > 0) { "pendingCapacity must be positive" }
        if (persistenceReady) writer.start()
    }

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
        synchronized(persistenceLock) { writer = replacement }
    }

    /**
     * Enables Room writes after the current session row is durable. Starting the writer before
     * flipping the gate closes the small race where a capture could otherwise submit to a stopped
     * writer; queued startup events are drained only after the worker is live.
     */
    fun setPersistenceReady(ready: Boolean) {
        val handoff =
            synchronized(persistenceLock) {
                if (!ready) {
                    persistenceReady = false
                    null
                } else {
                    val writerToUse = writer
                    writerToUse.start()
                    persistenceReady = true
                    val queued =
                        buildList<StoredEvent> {
                            while (pendingEvents.isNotEmpty()) add(pendingEvents.removeFirst())
                        }
                    writerToUse to queued
                }
            }
        handoff?.let { (writerToUse, queued) -> queued.forEach(writerToUse::submit) }
    }

    /** Drops only events that were waiting for a session row that never became durable. */
    fun discardPendingPersistence() {
        synchronized(persistenceLock) {
            pendingEvents.clear()
        }
    }

    override fun append(event: StoredEvent) {
        if (!delegate.contains(event.id)) {
            (delegate as? TimelineAppender)?.append(event)
        }
        val writerToUse =
            synchronized(persistenceLock) {
                if (persistenceReady) {
                    writer
                } else {
                    if (pendingEvents.size == pendingCapacity) {
                        onPendingDrop(pendingEvents.removeFirst())
                    }
                    pendingEvents.addLast(event)
                    null
                }
            }
        writerToUse?.submit(event)
    }
}
