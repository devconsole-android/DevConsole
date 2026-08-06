package io.devconsole.logs

import io.devconsole.security.RedactionEngine

/** Mirrors `android.util.Log` priorities so a host can map its own logger onto them directly. */
enum class LogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    ASSERT,
}

/** A single redacted log line, ready to become a timeline event. */
data class LogEntry(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val timestampEpochMs: Long,
    val stackTrace: String? = null,
)

/**
 * Where recorded entries go. The platform facade supplies an implementation that turns each entry
 * into a timeline event; this module deliberately knows nothing about timelines or storage.
 */
fun interface LogSink {
    fun emit(entry: LogEntry)
}

/**
 * Captures host log lines for the timeline.
 *
 * Redaction runs on the calling thread, so both the message and any stack trace are truncated
 * first — an unbounded log line must not turn into an unbounded regex scan on whatever thread the
 * host happened to be logging from. Everything else is delegated to [sink], which is expected not
 * to block.
 *
 * When [enabled] is false, [record] returns before redacting or allocating anything, matching the
 * no-op behaviour of the other recorders.
 */
class LogRecorder(
    private val redaction: RedactionEngine,
    private val sink: LogSink,
    private val enabled: Boolean = true,
    private val maxMessageChars: Int = DEFAULT_MAX_MESSAGE_CHARS,
    private val maxStackTraceChars: Int = DEFAULT_MAX_STACK_TRACE_CHARS,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    fun record(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (!enabled) return
        runCatching {
            sink.emit(
                LogEntry(
                    level = level,
                    tag = tag.take(MAX_TAG_CHARS),
                    message = redaction.redactText(message.take(maxMessageChars), maxMessageChars),
                    timestampEpochMs = nowEpochMs(),
                    stackTrace =
                        throwable?.let {
                            redaction.redactText(
                                it.stackTraceToString().take(maxStackTraceChars),
                                maxStackTraceChars,
                            )
                        },
                ),
            )
        }
    }

    companion object {
        const val DEFAULT_MAX_MESSAGE_CHARS: Int = 8 * 1024
        const val DEFAULT_MAX_STACK_TRACE_CHARS: Int = 16 * 1024
        const val MAX_TAG_CHARS: Int = 128
    }
}
