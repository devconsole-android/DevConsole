package io.devconsole

import io.devconsole.logs.LogEntry
import io.devconsole.logs.LogSink
import io.devconsole.storage.api.StoredEvent
import io.devconsole.timeline.TimelineAppender
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Turns captured log lines into timeline events, so logs sit in the same correlated stream as
 * network calls and crashes rather than in a tab of their own.
 *
 * Sequence numbers come from a counter rather than by querying the timeline for its last event:
 * logging is high-frequency, and a page query per line would be quadratic.
 */
internal class TimelineLogSink(
    private val sessionId: () -> String,
    private val appender: () -> TimelineAppender?,
    private val nextSequence: () -> Long = AtomicLong(0)::incrementAndGet,
) : LogSink {
    constructor(
        sessionId: String,
        appender: () -> TimelineAppender?,
        nextSequence: () -> Long = AtomicLong(0)::incrementAndGet,
    ) : this({ sessionId }, appender, nextSequence)

    override fun emit(entry: LogEntry) {
        val sink = appender() ?: return
        sink.append(
            StoredEvent(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId(),
                sequence = nextSequence(),
                pluginId = PLUGIN_ID,
                type = EVENT_TYPE,
                wallTimeMs = entry.timestampEpochMs,
                monoTimeNs = System.nanoTime(),
                severity = entry.level.ordinal,
                summary = entry.message.take(SUMMARY_CHARS),
                tagsJson = """{"tag":"${entry.tag.escapeJson()}","level":"${entry.level.name}"}""",
                payloadJson = payloadJson(entry),
            ),
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
