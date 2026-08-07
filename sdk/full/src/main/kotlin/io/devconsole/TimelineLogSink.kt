package io.devconsole

import io.devconsole.api.EventSeverity
import io.devconsole.logs.LogEntry
import io.devconsole.logs.LogLevel
import io.devconsole.logs.LogSink
import io.devconsole.timeline.TimelineAppender
import java.util.concurrent.atomic.AtomicLong

/**
 * Turns captured log lines into timeline events, so logs sit in the same correlated stream as
 * network calls and crashes rather than in a tab of their own.
 *
 * Emits through [CaptureTimelineBridge] -- the same path the network/socket/push tees use -- rather
 * than appending to a raw [TimelineAppender] directly, because the bridge is the only place that
 * also records a [Breadcrumb] into the shared ring buffer [CrashCapture] reads at crash/ANR time.
 * Appending straight to the appender (the previous shape of this class) put logs on the timeline
 * but silently left them out of every crash's breadcrumb lead-up.
 */
internal class TimelineLogSink(
    private val bridge: CaptureTimelineBridge,
) : LogSink {
    /**
     * Back-compat shape for callers that only have an appender, not a shared [CaptureTimelineBridge]
     * (and so no shared breadcrumb ring or live-stream hub). Prefer the primary constructor -- wiring
     * the caller's own [CaptureTimelineBridge] here is what makes log breadcrumbs actually appear.
     */
    constructor(
        sessionId: () -> String,
        appender: () -> TimelineAppender?,
        nextSequence: () -> Long = AtomicLong(0)::incrementAndGet,
    ) : this(CaptureTimelineBridge(sessionId, appender, { null }, nextSequence))

    constructor(
        sessionId: String,
        appender: () -> TimelineAppender?,
        nextSequence: () -> Long = AtomicLong(0)::incrementAndGet,
    ) : this({ sessionId }, appender, nextSequence)

    override fun emit(entry: LogEntry) {
        bridge.emit(
            pluginId = PLUGIN_ID,
            type = EVENT_TYPE,
            severity = entry.level.toEventSeverity(),
            summary = entry.message.take(SUMMARY_CHARS),
            tagsJson = """{"tag":"${entry.tag.escapeJson()}","level":"${entry.level.name}"}""",
            tags = mapOf("tag" to entry.tag, "level" to entry.level.name),
            payloadJson = payloadJson(entry),
            wallTimeMsOverride = entry.timestampEpochMs,
        )
    }

    private fun payloadJson(entry: LogEntry): String =
        buildString {
            append("""{"message":"""").append(entry.message.escapeJson()).append('"')
            entry.stackTrace?.let { append(""","stackTrace":"""").append(it.escapeJson()).append('"') }
            append('}')
        }

    private companion object {
        const val PLUGIN_ID = "logs"
        const val EVENT_TYPE = "log"
        const val SUMMARY_CHARS = 200
    }
}

/** [LogLevel] has six levels, [EventSeverity] four; VERBOSE folds into DEBUG and ASSERT into ERROR. */
private fun LogLevel.toEventSeverity(): EventSeverity =
    when (this) {
        LogLevel.VERBOSE, LogLevel.DEBUG -> EventSeverity.DEBUG
        LogLevel.INFO -> EventSeverity.INFO
        LogLevel.WARN -> EventSeverity.WARN
        LogLevel.ERROR, LogLevel.ASSERT -> EventSeverity.ERROR
    }

/** RFC 8259 requires every character below 0x20 to be escaped, not just the named ones. */
internal fun String.escapeJson(): String =
    buildString(length) {
        for (character in this@escapeJson) {
            when {
                character == '\\' -> append("\\\\")
                character == '"' -> append("\\\"")
                character == '\n' -> append("\\n")
                character == '\r' -> append("\\r")
                character == '\t' -> append("\\t")
                character == '\b' -> append("\\b")
                character == '' -> append("\\f")
                character < ' ' -> append("\\u%04x".format(character.code))
                else -> append(character)
            }
        }
    }
