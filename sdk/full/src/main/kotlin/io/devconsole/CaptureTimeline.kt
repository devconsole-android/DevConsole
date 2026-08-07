package io.devconsole

import io.devconsole.api.EventEnvelope
import io.devconsole.api.EventSeverity
import io.devconsole.network.NetworkTransaction
import io.devconsole.network.NetworkTransactionStore
import io.devconsole.push.PushEvent
import io.devconsole.push.PushStore
import io.devconsole.server.ktor.EventStreamHub
import io.devconsole.socket.SocketConnection
import io.devconsole.socket.SocketConnectionState
import io.devconsole.socket.SocketLifecycleEvent
import io.devconsole.socket.SocketLifecycleType
import io.devconsole.socket.SocketMessage
import io.devconsole.socket.SocketPayload
import io.devconsole.socket.SocketStore
import io.devconsole.storage.api.StoredEvent
import io.devconsole.timeline.TimelineAppender
import java.util.UUID

/**
 * Mirrors network, WebSocket, and push captures onto the shared timeline and the live event stream,
 * so the dashboard's unified log and live tail reflect app activity instead of only the per-inspector
 * tabs. Without this the timeline saw only logs and crashes, so running a sample produced nothing on
 * the timeline even as its Network/WebSocket/Push tabs filled up.
 *
 * Captures are already redacted by their recorders before they reach a store, so nothing is redacted
 * again here. The sequence source is shared with the log and crash appenders so the whole timeline
 * orders by one monotonic counter.
 *
 * Every emit also feeds [breadcrumbs]: a bounded ring buffer of already-redacted summaries that
 * [CrashCapture] reads synchronously to give a crash or ANR payload lead-up context. `null` (the
 * default) simply skips that step, which is what every existing caller not wired to a buffer gets.
 */
internal class CaptureTimelineBridge(
    private val sessionId: () -> String,
    private val appender: () -> TimelineAppender?,
    private val streamHub: () -> EventStreamHub?,
    private val nextSequence: () -> Long,
    private val breadcrumbs: BreadcrumbRingBuffer? = null,
) {
    constructor(
        sessionId: String,
        appender: () -> TimelineAppender?,
        streamHub: () -> EventStreamHub?,
        nextSequence: () -> Long,
    ) : this({ sessionId }, appender, streamHub, nextSequence)

    /**
     * Returns the id of the [io.devconsole.storage.api.StoredEvent] this call appended -- either a
     * freshly generated one, or [idOverride] when a caller needs the timeline event's id to match an
     * id it already handed to another system (for example [PlatformFacadeProvider.captureScreenshot],
     * which correlates the attachment write's `eventId` with this timeline event).
     *
     * Every field past the first five is optional and defaults away -- one per kind of metadata a
     * capture source may carry, which is why the parameter list is long.
     */
    @Suppress("LongParameterList")
    fun emit(
        pluginId: String,
        type: String,
        severity: EventSeverity,
        summary: String,
        tagsJson: String,
        correlationId: String? = null,
        tags: Map<String, String> = emptyMap(),
        attachmentId: String? = null,
        sessionIdOverride: String? = null,
        payloadJson: String? = null,
        wallTimeMsOverride: Long? = null,
        idOverride: String? = null,
    ): String {
        val id = idOverride?.let(UUID::fromString) ?: UUID.randomUUID()
        val activeSessionId = sessionIdOverride ?: sessionId()
        val sequence = nextSequence()
        val wallTimeMs = wallTimeMsOverride ?: System.currentTimeMillis()
        val monotonicNanos = System.nanoTime()
        appender()?.append(
            StoredEvent(
                id = id.toString(),
                sessionId = activeSessionId,
                sequence = sequence,
                pluginId = pluginId,
                type = type,
                wallTimeMs = wallTimeMs,
                monoTimeNs = monotonicNanos,
                severity = severity.ordinal,
                summary = summary,
                correlationId = correlationId,
                tagsJson = tagsJson,
                payloadJson = payloadJson,
                attachmentId = attachmentId,
            ),
        )
        streamHub()?.publish(
            EventEnvelope(
                id = id,
                sessionId = runCatching { UUID.fromString(activeSessionId) }.getOrDefault(UUID.randomUUID()),
                pluginId = pluginId,
                type = type,
                timestampEpochMs = wallTimeMs,
                monotonicNanos = monotonicNanos,
                sequence = sequence,
                severity = severity,
                summary = summary,
                correlationId = correlationId,
                tags = tags,
            ),
        )
        breadcrumbs?.record(Breadcrumb(wallTimeMs, pluginId, type, severity.ordinal, summary))
        return id.toString()
    }
}

/** Wraps a [NetworkTransactionStore] so every recorded transaction also lands on the timeline. */
internal class TeeingNetworkTransactionStore(
    private val delegate: NetworkTransactionStore,
    private val bridge: CaptureTimelineBridge,
    private val activeSessionId: () -> String? = { null },
    private val liveCaptureLock: Any = Any(),
) : NetworkTransactionStore by delegate {
    override fun record(transaction: NetworkTransaction) {
        synchronized(liveCaptureLock) {
            if (transaction.sessionId != null && transaction.sessionId != activeSessionId()) return
            delegate.record(transaction)
            val request = transaction.capture.request
            val status = transaction.capture.response?.statusCode
            val timelineTags =
                buildMap {
                    putAll(request.metadata.tags)
                    put("host", request.url.host)
                    put("method", request.method)
                    status?.let { put("status", it.toString()) }
                }
            bridge.emit(
                pluginId = "network",
                type = "network.transaction",
                severity =
                    if (status != null &&
                        status >= HTTP_ERROR_FLOOR
                    ) {
                        EventSeverity.ERROR
                    } else {
                        EventSeverity.INFO
                    },
                summary =
                    buildString {
                        append(request.method).append(' ').append(request.url.host).append(request.url.path)
                        status?.let { append(" → ").append(it) }
                    },
                tagsJson = timelineTags.toTimelineJson(),
                correlationId = request.correlationId,
                tags = timelineTags,
                attachmentId = transaction.capture.response?.attachmentId ?: request.attachmentId,
                sessionIdOverride = transaction.sessionId,
                payloadJson = CapturePayloadCodec.network(transaction),
                wallTimeMsOverride = transaction.completedAtEpochMs ?: transaction.startedAtEpochMs,
            )
        }
    }

    private companion object {
        const val HTTP_ERROR_FLOOR = 400
    }
}

private fun Map<String, String>.toTimelineJson(): String =
    entries
        .sortedBy(Map.Entry<String, String>::key)
        .joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${key.escapeJson()}\":\"${value.escapeJson()}\""
        }

/** Wraps a [SocketStore] so WebSocket open/close lifecycle lands on the timeline. */
internal class TeeingSocketStore(
    private val delegate: SocketStore,
    private val bridge: CaptureTimelineBridge,
    private val liveCaptureLock: Any = Any(),
) : SocketStore by delegate {
    private val lifecycleKeys = linkedSetOf<String>()

    fun resetSession() {
        synchronized(liveCaptureLock) { lifecycleKeys.clear() }
    }

    override fun open(connection: SocketConnection) {
        synchronized(liveCaptureLock) {
            delegate.open(connection)
            emitLifecycleOnce(
                SocketLifecycleEvent(
                    connectionId = connection.id,
                    type =
                        if (connection.state == SocketConnectionState.CREATED) {
                            SocketLifecycleType.CREATED
                        } else {
                            SocketLifecycleType.OPENED
                        },
                    timestampEpochMs = connection.openedAtEpochMs,
                ),
                connectionUrl = connection.url,
            )
        }
    }

    override fun append(message: SocketMessage) {
        synchronized(liveCaptureLock) {
            if (delegate.connection(message.connectionId) == null) return
            delegate.append(message)
            val frame =
                message.metadata.frameType.name
                    .lowercase()
            val direction = message.direction.name.lowercase()
            val detail =
                when (val payload = message.payload) {
                    is SocketPayload.Text -> "${payload.preview.length} chars"
                    is SocketPayload.Binary -> "${payload.length} bytes"
                }
            bridge.emit(
                pluginId = "websocket",
                type = "socket.frame.$frame.$direction",
                severity = EventSeverity.INFO,
                summary = "WebSocket $direction $frame ($detail)",
                tagsJson =
                    buildString {
                        append("{\"connectionId\":\"").append(message.connectionId.escapeJson())
                        append("\",\"direction\":\"").append(message.direction.name)
                        append("\",\"frameType\":\"").append(message.metadata.frameType.name).append("\"}")
                    },
                payloadJson = CapturePayloadCodec.socketMessage(message),
                wallTimeMsOverride = message.timestampEpochMs,
            )
        }
    }

    override fun appendLifecycle(event: SocketLifecycleEvent) {
        synchronized(liveCaptureLock) {
            if (delegate.connection(event.connectionId) == null) return
            delegate.appendLifecycle(event)
            emitLifecycleOnce(event)
        }
    }

    override fun transition(
        connectionId: String,
        state: SocketConnectionState,
        timestampEpochMs: Long,
        code: Int?,
        reason: String?,
        error: String?,
    ) {
        synchronized(liveCaptureLock) {
            if (delegate.connection(connectionId) == null) return
            delegate.transition(connectionId, state, timestampEpochMs, code, reason, error)
            emitLifecycleOnce(
                SocketLifecycleEvent(
                    connectionId = connectionId,
                    type = state.toLifecycleType(),
                    timestampEpochMs = timestampEpochMs,
                    code = code,
                    reason = reason,
                    error = error,
                ),
            )
        }
    }

    override fun close(
        connectionId: String,
        state: SocketConnectionState,
        timestampEpochMs: Long,
        error: String?,
    ) {
        synchronized(liveCaptureLock) {
            if (delegate.connection(connectionId) == null) return
            delegate.close(connectionId, state, timestampEpochMs, error)
            emitLifecycleOnce(
                SocketLifecycleEvent(
                    connectionId = connectionId,
                    type = state.toLifecycleType(),
                    timestampEpochMs = timestampEpochMs,
                    error = error,
                ),
            )
        }
    }

    private fun emitLifecycleOnce(
        event: SocketLifecycleEvent,
        connectionUrl: String? = null,
    ) {
        val key = "${event.connectionId}|${event.type}|${event.timestampEpochMs}"
        run {
            if (!lifecycleKeys.add(key)) return
            while (lifecycleKeys.size > MAX_LIFECYCLE_KEYS) {
                lifecycleKeys.remove(lifecycleKeys.first())
            }
        }
        val detail = event.error ?: event.reason ?: connectionUrl
        bridge.emit(
            pluginId = "websocket",
            type = "socket.${event.type.name.lowercase()}",
            severity = if (event.type == SocketLifecycleType.FAILED) EventSeverity.ERROR else EventSeverity.INFO,
            summary = "WebSocket ${event.type.name.lowercase()}${detail?.let { ": $it" } ?: ""}",
            tagsJson =
                "{\"connectionId\":\"${event.connectionId.escapeJson()}\"," +
                    "\"state\":\"${event.type.name}\"}",
            payloadJson = CapturePayloadCodec.socketLifecycle(event, delegate.connection(event.connectionId)),
            wallTimeMsOverride = event.timestampEpochMs,
        )
    }

    private fun SocketConnectionState.toLifecycleType(): SocketLifecycleType =
        when (this) {
            SocketConnectionState.CREATED -> SocketLifecycleType.CREATED
            SocketConnectionState.OPEN -> SocketLifecycleType.OPENED
            SocketConnectionState.CLOSING -> SocketLifecycleType.CLOSING
            SocketConnectionState.CLOSED -> SocketLifecycleType.CLOSED
            SocketConnectionState.FAILED -> SocketLifecycleType.FAILED
        }

    private companion object {
        const val MAX_LIFECYCLE_KEYS = 4_096
    }
}

/** Wraps a [PushStore] so every recorded push also lands on the timeline. */
internal class TeeingPushStore(
    private val delegate: PushStore,
    private val bridge: CaptureTimelineBridge,
    private val liveCaptureLock: Any = Any(),
) : PushStore by delegate {
    override fun append(event: PushEvent) {
        synchronized(liveCaptureLock) {
            delegate.append(event)
            // Fail-open: a push can be recorded before the runtime session exists (no
            // sessionIdOverride here, so bridge.emit falls back to resolving one), which throws
            // IllegalStateException("Capture before runtime session") pre-initialize. The push itself
            // is already durably kept by delegate.append above; only the timeline mirror is skipped,
            // matching every other capture category's fail-open gating rather than crashing the caller.
            runCatching {
                bridge.emit(
                    pluginId = "push",
                    type = "push.${event.lifecycle.name.lowercase()}",
                    severity = EventSeverity.INFO,
                    summary = "Push ${event.lifecycle.name.lowercase()}: ${event.messageId ?: event.provider}",
                    tagsJson =
                        "{\"provider\":\"${event.provider.escapeJson()}\",\"simulated\":\"${event.simulated}\"}",
                    payloadJson = CapturePayloadCodec.push(event),
                    wallTimeMsOverride = event.receivedAtEpochMs,
                )
            }
        }
    }
}
