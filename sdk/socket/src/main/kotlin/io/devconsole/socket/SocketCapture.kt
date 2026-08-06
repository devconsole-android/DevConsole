package io.devconsole.socket

import io.devconsole.security.RedactionEngine
import java.util.Base64

enum class SocketDirection { SENT, RECEIVED }

enum class SocketConnectionState { CREATED, OPEN, CLOSING, CLOSED, FAILED }

enum class SocketLifecycleType { CREATED, OPENED, CLOSING, CLOSED, FAILED }

enum class SocketFrameType { TEXT, BINARY, PING, PONG }

enum class SocketTextFormat { TEXT, JSON }

enum class BinaryPreviewPolicy { NONE, HEX, BASE64 }

enum class BinaryPreviewEncoding { HEX, BASE64 }

sealed interface SocketPayload {
    data class Text(
        val preview: String,
        val truncated: Boolean = false,
    ) : SocketPayload

    data class Binary(
        val length: Long,
        val truncated: Boolean = false,
    ) : SocketPayload {
        var preview: String? = null
            private set

        var previewEncoding: BinaryPreviewEncoding? = null
            private set

        fun withPreview(
            preview: String,
            encoding: BinaryPreviewEncoding,
        ): Binary =
            copy().also {
                it.preview = preview
                it.previewEncoding = encoding
            }
    }
}

data class SocketMessageMetadata(
    val frameType: SocketFrameType = SocketFrameType.TEXT,
    val textFormat: SocketTextFormat = SocketTextFormat.TEXT,
)

data class SocketMessage(
    val connectionId: String,
    val direction: SocketDirection,
    val timestampEpochMs: Long,
    val payload: SocketPayload,
    val contentType: String? = null,
) {
    var metadata: SocketMessageMetadata =
        SocketMessageMetadata(
            frameType = if (payload is SocketPayload.Binary) SocketFrameType.BINARY else SocketFrameType.TEXT,
        )
        private set

    fun withMetadata(metadata: SocketMessageMetadata): SocketMessage = copy().also { it.metadata = metadata }
}

data class SocketLifecycleEvent(
    val connectionId: String,
    val type: SocketLifecycleType,
    val timestampEpochMs: Long,
    val code: Int? = null,
    val reason: String? = null,
    val error: String? = null,
)

data class SocketConnection(
    val id: String,
    val url: String,
    val openedAtEpochMs: Long,
    val closedAtEpochMs: Long? = null,
    val state: SocketConnectionState = SocketConnectionState.OPEN,
    val error: String? = null,
    val reconnectAttempt: Int = 0,
    val messages: List<SocketMessage> = emptyList(),
    val protocol: SocketProtocol = SocketProtocol.WEBSOCKET,
) {
    var lifecycleEvents: List<SocketLifecycleEvent> = emptyList()
        private set

    fun withLifecycleEvents(events: List<SocketLifecycleEvent>): SocketConnection =
        copy().also {
            it.lifecycleEvents =
                events.toList()
        }

    val sentCount: Int get() = messages.count { it.direction == SocketDirection.SENT }
    val receivedCount: Int get() = messages.count { it.direction == SocketDirection.RECEIVED }
}

data class SocketMessageQuery(
    val connectionIds: Set<String> = emptySet(),
    val directions: Set<SocketDirection> = emptySet(),
    val frameTypes: Set<SocketFrameType> = emptySet(),
    val fromEpochMs: Long? = null,
    val toEpochMs: Long? = null,
    val query: String? = null,
    val hasError: Boolean? = null,
)

interface SocketStore {
    fun open(connection: SocketConnection)

    fun append(message: SocketMessage)

    fun appendLifecycle(event: SocketLifecycleEvent) = Unit

    // Mirrors the SocketLifecycleEvent fields callers already construct; splitting into an
    // overload or a parameter object would be a breaking change to this public store API.
    @Suppress("LongParameterList")
    fun transition(
        connectionId: String,
        state: SocketConnectionState,
        timestampEpochMs: Long,
        code: Int? = null,
        reason: String? = null,
        error: String? = null,
    ) {
        if (state == SocketConnectionState.CLOSED || state == SocketConnectionState.FAILED) {
            close(connectionId, state, timestampEpochMs, error)
        }
    }

    fun close(
        connectionId: String,
        state: SocketConnectionState,
        timestampEpochMs: Long,
        error: String? = null,
    )

    fun connection(id: String): SocketConnection?

    fun connections(): List<SocketConnection>

    fun messages(connectionId: String? = null): List<SocketMessage>

    fun messages(query: SocketMessageQuery): List<SocketMessage> =
        messages(null).filter { message ->
            (query.connectionIds.isEmpty() || message.connectionId in query.connectionIds) &&
                (query.directions.isEmpty() || message.direction in query.directions) &&
                (query.frameTypes.isEmpty() || message.metadata.frameType in query.frameTypes) &&
                (query.fromEpochMs == null || message.timestampEpochMs >= query.fromEpochMs) &&
                (query.toEpochMs == null || message.timestampEpochMs <= query.toEpochMs) &&
                (query.query == null || message.searchableText().contains(query.query, ignoreCase = true))
        }

    fun lifecycleEvents(connectionId: String? = null): List<SocketLifecycleEvent> =
        connectionId?.let { connection(it)?.lifecycleEvents }.orEmpty()
}

/**
 * Bounded, thread-safe in-memory store for socket lifecycle and redacted frame previews.
 *
 * One small function per SocketStore operation plus the byte/connection-capacity helpers this
 * class alone owns; splitting further would fragment one cohesive bounded-storage boundary.
 */
@Suppress("TooManyFunctions")
class InMemorySocketStore(
    private val maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
    private val maxMessagesPerConnection: Int = DEFAULT_MAX_MESSAGES_PER_CONNECTION,
) : SocketStore {
    private val lock = Any()
    private val connections = linkedMapOf<String, SocketConnection>()
    private var byteCapacity = DEFAULT_MAX_BYTES
    private var storedBytes = 0L

    init {
        require(maxConnections > 0) { "maxConnections must be positive" }
        require(maxMessagesPerConnection > 0) { "maxMessagesPerConnection must be positive" }
    }

    fun withByteCapacity(maxBytes: Long): InMemorySocketStore {
        require(maxBytes > 0) { "maxBytes must be positive" }
        synchronized(lock) {
            byteCapacity = maxBytes
            pruneToByteCapacity()
        }
        return this
    }

    fun clear() =
        synchronized(lock) {
            connections.clear()
            storedBytes = 0
        }

    override fun open(connection: SocketConnection) =
        synchronized(lock) {
            val existing = connections[connection.id]
            val updated =
                connection
                    .copy(
                        messages =
                            (
                                existing
                                    ?.messages
                                    .orEmpty() + connection.messages
                            ).takeLast(maxMessagesPerConnection),
                    ).withLifecycleEvents(existing?.lifecycleEvents.orEmpty() + connection.lifecycleEvents)
            storedBytes -= existing?.estimatedSizeBytes() ?: 0
            connections[connection.id] = updated
            storedBytes += updated.estimatedSizeBytes()
            while (connections.size > maxConnections) {
                val oldest = connections.entries.minBy { it.value.openedAtEpochMs }
                storedBytes -= oldest.value.estimatedSizeBytes()
                connections.remove(oldest.key)
            }
            pruneToByteCapacity()
        }

    override fun append(message: SocketMessage) =
        synchronized(lock) {
            val connection = connections[message.connectionId] ?: return@synchronized
            val updated =
                connection
                    .copy(messages = (connection.messages + message).takeLast(maxMessagesPerConnection))
                    .withLifecycleEvents(connection.lifecycleEvents)
            connections[message.connectionId] = updated
            storedBytes += updated.estimatedSizeBytes() - connection.estimatedSizeBytes()
            pruneToByteCapacity()
        }

    override fun appendLifecycle(event: SocketLifecycleEvent) =
        synchronized(lock) {
            val connection = connections[event.connectionId] ?: return@synchronized
            val updated =
                connection
                    .copy()
                    .withLifecycleEvents(
                        (connection.lifecycleEvents + event).takeLast(maxMessagesPerConnection),
                    )
            connections[event.connectionId] = updated
            storedBytes += updated.estimatedSizeBytes() - connection.estimatedSizeBytes()
            pruneToByteCapacity()
        }

    override fun transition(
        connectionId: String,
        state: SocketConnectionState,
        timestampEpochMs: Long,
        code: Int?,
        reason: String?,
        error: String?,
    ) = synchronized(lock) {
        val connection = connections[connectionId] ?: return@synchronized
        val updated =
            connection
                .copy(
                    state = state,
                    closedAtEpochMs =
                        timestampEpochMs.takeIf {
                            state == SocketConnectionState.CLOSED || state == SocketConnectionState.FAILED
                        },
                    error = error ?: connection.error,
                ).withLifecycleEvents(connection.lifecycleEvents)
        connections[connectionId] = updated
        storedBytes += updated.estimatedSizeBytes() - connection.estimatedSizeBytes()
        pruneToByteCapacity()
    }

    override fun close(
        connectionId: String,
        state: SocketConnectionState,
        timestampEpochMs: Long,
        error: String?,
    ) = transition(connectionId, state, timestampEpochMs, error = error)

    override fun connection(id: String): SocketConnection? = synchronized(lock) { connections[id] }

    override fun connections(): List<SocketConnection> =
        synchronized(lock) {
            connections.values.sortedByDescending { it.openedAtEpochMs }
        }

    override fun messages(connectionId: String?): List<SocketMessage> =
        synchronized(lock) {
            (
                if (connectionId ==
                    null
                ) {
                    connections.values.flatMap(SocketConnection::messages)
                } else {
                    connections[connectionId]?.messages.orEmpty()
                }
            ).sortedByDescending(SocketMessage::timestampEpochMs)
        }

    override fun messages(query: SocketMessageQuery): List<SocketMessage> =
        synchronized(lock) {
            connections.values
                .asSequence()
                .filter { connection ->
                    query.connectionIds.isEmpty() || connection.id in query.connectionIds
                }.flatMap { connection ->
                    connection.messages
                        .asSequence()
                        .filter { message ->
                            query.directions.isEmpty() || message.direction in query.directions
                        }.filter { message ->
                            query.frameTypes.isEmpty() || message.metadata.frameType in query.frameTypes
                        }.filter { message ->
                            query.fromEpochMs == null || message.timestampEpochMs >= query.fromEpochMs
                        }.filter { message ->
                            query.toEpochMs == null || message.timestampEpochMs <= query.toEpochMs
                        }.filter { message ->
                            query.query == null || message.searchableText().contains(query.query, ignoreCase = true)
                        }.filter { query.hasError == null || (connection.error != null) == query.hasError }
                }.sortedByDescending(SocketMessage::timestampEpochMs)
                .toList()
        }

    override fun lifecycleEvents(connectionId: String?): List<SocketLifecycleEvent> =
        synchronized(lock) {
            (
                connectionId?.let { connections[it]?.lifecycleEvents }
                    ?: connections.values.flatMap(SocketConnection::lifecycleEvents)
            ).sortedBy(SocketLifecycleEvent::timestampEpochMs)
        }

    private fun pruneToByteCapacity() {
        while (storedBytes > byteCapacity && connections.isNotEmpty()) {
            val victim =
                connections.values
                    .flatMap { connection ->
                        connection.messages
                            .firstOrNull()
                            ?.let { message ->
                                BufferedSocketItem(
                                    connection.id,
                                    0,
                                    message.timestampEpochMs,
                                    message.estimatedSizeBytes(),
                                    isMessage = true,
                                )
                            }.let(::listOfNotNull) +
                            connection.lifecycleEvents
                                .firstOrNull()
                                ?.let { event ->
                                    BufferedSocketItem(
                                        connection.id,
                                        0,
                                        event.timestampEpochMs,
                                        event.estimatedSizeBytes(),
                                        isMessage = false,
                                    )
                                }.let(::listOfNotNull)
                    }.minWithOrNull(compareBy(BufferedSocketItem::timestampEpochMs, BufferedSocketItem::index))
            if (victim == null) {
                val oldest = connections.entries.minBy { it.value.openedAtEpochMs }
                storedBytes -= oldest.value.estimatedSizeBytes()
                connections.remove(oldest.key)
                continue
            }
            val connection = connections.getValue(victim.connectionId)
            connections[victim.connectionId] =
                if (victim.isMessage) {
                    connection
                        .copy(messages = connection.messages.filterIndexed { index, _ -> index != victim.index })
                        .withLifecycleEvents(connection.lifecycleEvents)
                } else {
                    connection
                        .copy()
                        .withLifecycleEvents(
                            connection.lifecycleEvents.filterIndexed { index, _ -> index != victim.index },
                        )
                }
            storedBytes -= victim.bytes
        }
    }

    private data class BufferedSocketItem(
        val connectionId: String,
        val index: Int,
        val timestampEpochMs: Long,
        val bytes: Long,
        val isMessage: Boolean,
    )

    companion object {
        const val DEFAULT_MAX_CONNECTIONS = 200
        const val DEFAULT_MAX_MESSAGES_PER_CONNECTION = 2_000
        const val DEFAULT_MAX_BYTES: Long = 8L * 1024L * 1024L
    }
}

// Rough fixed overhead per stored record (object/field bookkeeping) on top of variable-length
// string/collection payloads, used only to budget the in-memory byte cap.
private const val CONNECTION_SIZE_OVERHEAD_BYTES = 128L
private const val MESSAGE_SIZE_OVERHEAD_BYTES = 96L
private const val LIFECYCLE_EVENT_SIZE_OVERHEAD_BYTES = 96L

// [protocol] is a fixed-size enum reference, not a variable-length string/collection, so it is
// already covered by CONNECTION_SIZE_OVERHEAD_BYTES and needs no term of its own here.
private fun SocketConnection.estimatedSizeBytes(): Long =
    CONNECTION_SIZE_OVERHEAD_BYTES +
        id.byteSize() +
        url.byteSize() +
        error.orEmpty().byteSize() +
        messages.sumOf(SocketMessage::estimatedSizeBytes) +
        lifecycleEvents.sumOf(SocketLifecycleEvent::estimatedSizeBytes)

private fun SocketMessage.estimatedSizeBytes(): Long =
    MESSAGE_SIZE_OVERHEAD_BYTES +
        connectionId.byteSize() +
        contentType.orEmpty().byteSize() +
        when (val value = payload) {
            is SocketPayload.Text -> value.preview.byteSize()
            is SocketPayload.Binary -> value.preview.orEmpty().byteSize()
        }

private fun SocketLifecycleEvent.estimatedSizeBytes(): Long =
    LIFECYCLE_EVENT_SIZE_OVERHEAD_BYTES +
        connectionId.byteSize() +
        reason.orEmpty().byteSize() +
        error.orEmpty().byteSize()

private fun String.byteSize(): Int = encodeToByteArray().size

/**
 * Manual socket capture API. Every method is fail-open so host socket behavior remains isolated.
 * When [enabled] is false, every method returns immediately without redacting or touching [store].
 *
 * One small function per socket lifecycle/frame event type plus the session-binding helpers;
 * splitting further would fragment one cohesive fail-open recording boundary.
 */
@Suppress("TooManyFunctions")
class SocketRecorder(
    private val redaction: RedactionEngine,
    private val store: SocketStore,
    private val enabled: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var binaryPreviewPolicy: BinaryPreviewPolicy = BinaryPreviewPolicy.NONE
    private var sessionIdProvider: (() -> String?)? = null
    private var capturedSessionId: String? = null
    private var sessionBound = false
    private var capturedSessionAvailable = false
    private var operationLock: Any = Any()
    private var capturedProtocol: SocketProtocol = SocketProtocol.WEBSOCKET
    private var protocolGate: ((SocketProtocol) -> Boolean)? = null

    /** The protocol this recorder stamps onto every connection it opens. See [withProtocol]. */
    val protocol: SocketProtocol get() = capturedProtocol

    constructor(
        redaction: RedactionEngine,
        store: SocketStore,
        enabled: Boolean,
        clock: () -> Long,
        binaryPreviewPolicy: BinaryPreviewPolicy,
    ) : this(redaction, store, enabled, clock) {
        this.binaryPreviewPolicy = binaryPreviewPolicy
    }

    fun withSessionIdProvider(provider: () -> String?): SocketRecorder = apply { sessionIdProvider = provider }

    fun withOperationLock(lock: Any): SocketRecorder = apply { operationLock = lock }

    /** Apply-style, matching [withSessionIdProvider]/[withOperationLock]. Fail-open: see [canRecord]. */
    fun withProtocolGate(gate: (SocketProtocol) -> Boolean): SocketRecorder = apply { protocolGate = gate }

    /**
     * Returns a configured COPY stamped with [protocol] -- built the same way [bindToCurrentSession]
     * builds one -- so the facade can hand the same base recorder instance to both a WebSocket and an
     * MQTT adapter without either mutating the other's protocol.
     */
    fun withProtocol(protocol: SocketProtocol): SocketRecorder =
        SocketRecorder(redaction, store, enabled, clock).also {
            it.binaryPreviewPolicy = binaryPreviewPolicy
            it.sessionIdProvider = sessionIdProvider
            it.capturedSessionId = capturedSessionId
            it.capturedSessionAvailable = capturedSessionAvailable
            it.sessionBound = sessionBound
            it.operationLock = operationLock
            it.protocolGate = protocolGate
            it.capturedProtocol = protocol
        }

    /** Freezes the current app-run for delayed listener callbacks. */
    fun bindToCurrentSession(): SocketRecorder =
        SocketRecorder(redaction, store, enabled, clock).also {
            it.binaryPreviewPolicy = binaryPreviewPolicy
            it.sessionIdProvider = sessionIdProvider
            val provider = sessionIdProvider
            val captured = runCatching { provider?.invoke() }
            it.capturedSessionId = captured.getOrNull()
            it.capturedSessionAvailable = captured.isSuccess && it.capturedSessionId != null
            // A standalone recorder has no app-run concept and must retain its historical behavior.
            // A configured provider that is temporarily unavailable (for example before init) is
            // bound but inactive, so it cannot create an unattributed connection.
            it.sessionBound = provider != null
            it.operationLock = operationLock
            it.protocolGate = protocolGate
            it.capturedProtocol = capturedProtocol
        }

    private fun canRecord(): Boolean =
        enabled &&
            (
                !sessionBound ||
                    (
                        capturedSessionAvailable &&
                            runCatching { sessionIdProvider?.invoke() }.getOrNull() == capturedSessionId
                    )
            ) &&
            runCatching { protocolGate?.invoke(capturedProtocol) ?: true }.getOrDefault(true)

    private inline fun guarded(block: () -> Unit) {
        synchronized(operationLock) {
            if (!canRecord()) return
            runCatching(block)
        }
    }

    fun onCreated(
        connectionId: String,
        url: String,
        reconnectAttempt: Int = 0,
    ) {
        guarded {
            val timestamp = clock()
            store.open(
                SocketConnection(
                    connectionId,
                    redaction.redactText(url),
                    timestamp,
                    state = SocketConnectionState.CREATED,
                    reconnectAttempt = reconnectAttempt,
                    protocol = protocol,
                ),
            )
            store.appendLifecycle(SocketLifecycleEvent(connectionId, SocketLifecycleType.CREATED, timestamp))
        }
    }

    fun onOpen(
        connectionId: String,
        url: String,
        reconnectAttempt: Int = 0,
    ) {
        guarded {
            if (store.connection(connectionId) == null) onCreated(connectionId, url, reconnectAttempt)
            val timestamp = clock()
            store.transition(connectionId, SocketConnectionState.OPEN, timestamp)
            store.appendLifecycle(SocketLifecycleEvent(connectionId, SocketLifecycleType.OPENED, timestamp))
        }
    }

    fun onMessage(
        connectionId: String,
        direction: SocketDirection,
        text: String,
        contentType: String? = null,
    ) {
        guarded {
            store.append(
                SocketMessage(
                    connectionId,
                    direction,
                    clock(),
                    SocketPayload.Text(
                        redaction.redactText(text, MAX_TEXT_PREVIEW_CHARS),
                        text.length > MAX_TEXT_PREVIEW_CHARS,
                    ),
                    contentType,
                ).withMetadata(
                    SocketMessageMetadata(
                        frameType = SocketFrameType.TEXT,
                        textFormat = text.detectTextFormat(contentType),
                    ),
                ),
            )
        }
    }

    fun onBinaryMessage(
        connectionId: String,
        direction: SocketDirection,
        length: Long,
        contentType: String? = null,
    ) {
        guarded {
            store.append(
                SocketMessage(
                    connectionId,
                    direction,
                    clock(),
                    SocketPayload.Binary(length, length > MAX_BINARY_PREVIEW_BYTES),
                    contentType,
                ).withMetadata(SocketMessageMetadata(frameType = SocketFrameType.BINARY)),
            )
        }
    }

    fun onBinaryMessage(
        connectionId: String,
        direction: SocketDirection,
        bytes: ByteArray,
        contentType: String? = null,
    ) {
        guarded {
            store.append(
                SocketMessage(
                    connectionId,
                    direction,
                    clock(),
                    bytes.binaryPayload(binaryPreviewPolicy),
                    contentType,
                ).withMetadata(SocketMessageMetadata(frameType = SocketFrameType.BINARY)),
            )
        }
    }

    fun onPing(
        connectionId: String,
        direction: SocketDirection,
        bytes: ByteArray = ByteArray(0),
    ) = onControlFrame(connectionId, direction, bytes, SocketFrameType.PING)

    fun onPong(
        connectionId: String,
        direction: SocketDirection,
        bytes: ByteArray = ByteArray(0),
    ) = onControlFrame(connectionId, direction, bytes, SocketFrameType.PONG)

    fun onClosing(
        connectionId: String,
        code: Int? = null,
        reason: String? = null,
    ) {
        guarded {
            val timestamp = clock()
            val safeReason = reason?.let(redaction::redactText)
            store.transition(connectionId, SocketConnectionState.CLOSING, timestamp, code, safeReason)
            store.appendLifecycle(
                SocketLifecycleEvent(connectionId, SocketLifecycleType.CLOSING, timestamp, code, safeReason),
            )
        }
    }

    fun onClosed(
        connectionId: String,
        failed: Boolean = false,
        error: String? = null,
    ) {
        guarded {
            val timestamp = clock()
            val safeError = error?.let(redaction::redactText)
            val state = if (failed) SocketConnectionState.FAILED else SocketConnectionState.CLOSED
            store.close(
                connectionId,
                state,
                timestamp,
                safeError,
            )
            store.appendLifecycle(
                SocketLifecycleEvent(
                    connectionId,
                    if (failed) SocketLifecycleType.FAILED else SocketLifecycleType.CLOSED,
                    timestamp,
                    error = safeError,
                ),
            )
        }
    }

    fun onFailure(
        connectionId: String,
        error: Throwable,
    ) = onClosed(connectionId, failed = true, error = error.message ?: error.javaClass.simpleName)

    private fun onControlFrame(
        connectionId: String,
        direction: SocketDirection,
        bytes: ByteArray,
        type: SocketFrameType,
    ) {
        guarded {
            store.append(
                SocketMessage(
                    connectionId,
                    direction,
                    clock(),
                    bytes.binaryPayload(binaryPreviewPolicy),
                    "application/octet-stream",
                ).withMetadata(SocketMessageMetadata(frameType = type)),
            )
        }
    }

    companion object {
        const val MAX_TEXT_PREVIEW_CHARS = 64 * 1024
        const val MAX_BINARY_PREVIEW_BYTES = 2L * 1024L * 1024L
        const val MAX_BINARY_FRAME_PREVIEW_BYTES = 4 * 1024
    }
}

private fun SocketMessage.searchableText(): String =
    when (val value = payload) {
        is SocketPayload.Text -> value.preview
        is SocketPayload.Binary -> value.preview.orEmpty()
    }

private fun String.detectTextFormat(contentType: String?): SocketTextFormat =
    if (contentType.declaresJson() || trim().looksLikeJson()) {
        SocketTextFormat.JSON
    } else {
        SocketTextFormat.TEXT
    }

private fun String?.declaresJson(): Boolean = this?.contains("json", ignoreCase = true) == true

private fun String.looksLikeJson(): Boolean = (startsWith('{') && endsWith('}')) || (startsWith('[') && endsWith(']'))

private const val BYTE_MASK = 0xff

private fun ByteArray.binaryPayload(policy: BinaryPreviewPolicy): SocketPayload.Binary {
    val previewBytes = take(SocketRecorder.MAX_BINARY_FRAME_PREVIEW_BYTES).toByteArray()
    val payload = SocketPayload.Binary(size.toLong(), size > previewBytes.size)
    return when (policy) {
        BinaryPreviewPolicy.NONE -> payload
        BinaryPreviewPolicy.HEX ->
            payload.withPreview(
                previewBytes.joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) },
                BinaryPreviewEncoding.HEX,
            )
        BinaryPreviewPolicy.BASE64 ->
            payload.withPreview(Base64.getEncoder().encodeToString(previewBytes), BinaryPreviewEncoding.BASE64)
    }
}
