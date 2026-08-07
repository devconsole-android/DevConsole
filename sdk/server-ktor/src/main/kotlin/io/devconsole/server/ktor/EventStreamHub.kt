package io.devconsole.server.ktor

import io.devconsole.api.EventEnvelope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

/** Bounded live stream of envelopes that have already crossed the capture redaction boundary. */
class EventStreamHub(
    capacity: Int = DEFAULT_CAPACITY,
) {
    private val sequence = AtomicLong(0)
    private val mutableEvents =
        MutableSharedFlow<EventEnvelope>(
            replay = 0,
            extraBufferCapacity = capacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val events = mutableEvents.asSharedFlow()
    val currentSequence: Long get() = sequence.get()

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    fun publish(event: EventEnvelope): Boolean {
        // Not AtomicLong.accumulateAndGet (API 24): CAS-loop the running max so it works below minSdk 24.
        while (true) {
            val existing = sequence.get()
            if (event.sequence <= existing || sequence.compareAndSet(existing, event.sequence)) break
        }
        return mutableEvents.tryEmit(event)
    }

    companion object {
        const val DEFAULT_CAPACITY = 2_000
    }
}

internal fun EventEnvelope.toStreamMessage(): String =
    "{\"type\":\"event.appended\",\"sequence\":$sequence,\"event\":{\"id\":\"$id\"," +
        "\"pluginId\":\"${pluginId.escapeJson()}\",\"type\":\"${type.escapeJson()}\"," +
        "\"timestampEpochMs\":$timestampEpochMs,\"monotonicNanos\":$monotonicNanos,\"sequence\":$sequence," +
        "\"severity\":${severity.ordinal},\"summary\":\"${summary.escapeJson()}\"," +
        "\"correlationId\":${correlationId?.let { "\"${it.escapeJson()}\"" } ?: "null"}," +
        "\"tags\":${tags.toStreamJson()},\"schemaVersion\":$schemaVersion}}"

private fun Map<String, String>.toStreamJson(): String =
    entries
        .sortedBy(Map.Entry<String, String>::key)
        .joinToString(prefix = "{", postfix = "}") { (name, value) ->
            "\"${name.escapeJson()}\":\"${value.escapeJson()}\""
        }

private fun String.escapeJson(): String =
    buildString(length + 16) {
        for (char in this@escapeJson) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        append("\\u").append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
    }
