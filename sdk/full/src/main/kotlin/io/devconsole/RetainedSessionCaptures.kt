/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole

import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.push.PushEvent
import io.devconsole.socket.SocketConnection
import io.devconsole.socket.SocketConnectionState
import io.devconsole.socket.SocketLifecycleType
import io.devconsole.socket.SocketMessage
import io.devconsole.socket.SocketProtocol
import io.devconsole.storage.api.StoredEvent

/** Read-only network/socket/push views of a completed run, rebuilt from [CapturePayloadCodec] payloads. */
internal class RetainedSessionCaptures(
    private val events: suspend (sessionId: String) -> List<StoredEvent>,
    cursorCodec: NetworkCursorCodec,
) {
    private val networkStores = RetainedNetworkStores(events, cursorCodec)

    suspend fun networkStore(sessionId: String): InMemoryNetworkTransactionStore = networkStores.storeFor(sessionId)

    suspend fun sockets(sessionId: String): List<SocketConnection> =
        rebuildSocketConnections(events(sessionId).filter { it.pluginId == SOCKET_PLUGIN_ID })

    suspend fun pushEvents(sessionId: String): List<PushEvent> =
        events(sessionId)
            .asSequence()
            .filter { it.pluginId == PUSH_PLUGIN_ID }
            .mapNotNull { RetainedCaptureDecoder.push(it.payloadJson) }
            .sortedByDescending(PushEvent::receivedAtEpochMs)
            .toList()

    /** Drops a cached network view, e.g. after the run's events were pruned or deleted. */
    fun forget(sessionId: String) = networkStores.invalidate(sessionId)

    private companion object {
        const val SOCKET_PLUGIN_ID = "websocket"
        const val PUSH_PLUGIN_ID = "push"
    }
}

/** Newest-opened first, matching the live socket store's own ordering. */
internal fun rebuildSocketConnections(events: List<StoredEvent>): List<SocketConnection> {
    val lifecycles = linkedMapOf<String, MutableList<RetainedSocketLifecycle>>()
    val messages = linkedMapOf<String, MutableList<SocketMessage>>()
    events.forEach { event ->
        val message = RetainedCaptureDecoder.socketMessage(event.payloadJson)
        if (message != null) {
            messages.getOrPut(message.connectionId, ::mutableListOf) += message
        } else {
            RetainedCaptureDecoder.socketLifecycle(event.payloadJson)?.let { lifecycle ->
                lifecycles.getOrPut(lifecycle.event.connectionId, ::mutableListOf) += lifecycle
            }
        }
    }
    return (lifecycles.keys + messages.keys)
        .map { id -> rebuildConnection(id, lifecycles[id].orEmpty(), messages[id].orEmpty()) }
        .sortedByDescending(SocketConnection::openedAtEpochMs)
}

private fun rebuildConnection(
    id: String,
    lifecycles: List<RetainedSocketLifecycle>,
    messages: List<SocketMessage>,
): SocketConnection {
    val ordered = lifecycles.sortedBy { it.event.timestampEpochMs }
    val frames = messages.sortedBy(SocketMessage::timestampEpochMs)
    val last = ordered.lastOrNull()?.event
    val terminal = last?.takeIf { it.type in TERMINAL_LIFECYCLE_TYPES }
    val openedAt =
        ordered.firstOrNull()?.event?.timestampEpochMs
            ?: frames.firstOrNull()?.timestampEpochMs
            ?: 0L
    return SocketConnection(
        id = id,
        url = ordered.firstNotNullOfOrNull(RetainedSocketLifecycle::url).orEmpty(),
        openedAtEpochMs = openedAt,
        closedAtEpochMs = terminal?.timestampEpochMs,
        state = last?.type?.toConnectionState() ?: SocketConnectionState.OPEN,
        error = ordered.lastOrNull { it.event.error != null }?.event?.error,
        messages = frames,
        protocol = ordered.firstOrNull()?.protocol ?: SocketProtocol.WEBSOCKET,
    ).withLifecycleEvents(ordered.map(RetainedSocketLifecycle::event))
}

private val TERMINAL_LIFECYCLE_TYPES =
    setOf(SocketLifecycleType.CLOSED, SocketLifecycleType.FAILED, SocketLifecycleType.CANCELLED)

private fun SocketLifecycleType.toConnectionState(): SocketConnectionState =
    when (this) {
        SocketLifecycleType.CREATED -> SocketConnectionState.CREATED
        SocketLifecycleType.OPENED -> SocketConnectionState.OPEN
        SocketLifecycleType.CLOSING -> SocketConnectionState.CLOSING
        SocketLifecycleType.CLOSED,
        SocketLifecycleType.FAILED,
        SocketLifecycleType.CANCELLED,
        -> SocketConnectionState.CLOSED
    }
