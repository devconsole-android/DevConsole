package io.devconsole.socket.okhttp

import io.devconsole.socket.SocketDirection
import io.devconsole.socket.SocketProtocol
import io.devconsole.socket.SocketRecorder
import io.devconsole.socket.SocketSendResult
import io.devconsole.socket.SocketSender
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** OkHttp refuses a frame for exactly one reason, and the dashboard should show OkHttp's wording. */
private const val SEND_QUEUE_FULL_REASON = "Send queue full or socket closing"

/**
 * OkHttp adapter that records callbacks without altering socket control flow.
 *
 * [delegate], if supplied, receives every callback right after it is recorded -- the same
 * compose-by-delegate shape as [io.devconsole.socket.paho.DevConsolePahoMqtt.install]'s
 * `MqttCallback` parameter -- so a host can wire this listener in without subclassing it.
 */
class DevConsoleOkHttpWebSocketListener
    @JvmOverloads
    constructor(
        recorder: SocketRecorder,
        private val connectionIdProvider: (WebSocket) -> String = { socket ->
            "${socket.request().url}-${System.identityHashCode(socket)}"
        },
        private val delegate: WebSocketListener? = null,
    ) : WebSocketListener() {
        private val recorder = recorder.bindToCurrentSession()

        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            recordOpen(webSocket)
            delegate?.onOpen(webSocket, response)
        }

        /** Internal: only [onOpen] and this module's own tests need to invoke this directly. */
        internal fun recordOpen(webSocket: WebSocket) {
            val connectionId = connectionIdProvider(webSocket)
            recorder.onOpen(connectionId, webSocket.request().url.toString())
            registerSenderIfAbsent(connectionId, webSocket)
        }

        /**
         * Skipped when a sender is already registered, because [DevConsoleRecordingWebSocket.wrap]
         * registers its own. The wrapper's sender records through the wrapper's `send`, so letting
         * the listener replace it would record every host frame twice.
         */
        private fun registerSenderIfAbsent(
            connectionId: String,
            webSocket: WebSocket,
        ) {
            if (recorder.senders.canSend(connectionId)) return
            recorder.senders.register(
                connectionId,
                SocketProtocol.WEBSOCKET,
                SocketSender { request -> sendDirect(webSocket, connectionId, request.text) },
            )
        }

        private fun sendDirect(
            webSocket: WebSocket,
            connectionId: String,
            text: String,
        ): SocketSendResult {
            if (!webSocket.send(text)) return SocketSendResult.Rejected(SEND_QUEUE_FULL_REASON)
            recorder.onMessage(connectionId, SocketDirection.SENT, text, "text/plain")
            return SocketSendResult.Sent
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            recorder.onMessage(connectionIdProvider(webSocket), SocketDirection.RECEIVED, text, "text/plain")
            delegate?.onMessage(webSocket, text)
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString,
        ) {
            recorder.onBinaryMessage(
                connectionIdProvider(webSocket),
                SocketDirection.RECEIVED,
                bytes.toByteArray(),
                "application/octet-stream",
            )
            delegate?.onMessage(webSocket, bytes)
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            recorder.onClosing(connectionIdProvider(webSocket), code, reason)
            delegate?.onClosing(webSocket, code, reason)
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            val connectionId = connectionIdProvider(webSocket)
            recorder.onClosed(connectionId)
            recorder.senders.unregister(connectionId)
            delegate?.onClosed(webSocket, code, reason)
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            val connectionId = connectionIdProvider(webSocket)
            recorder.onFailure(connectionId, t)
            recorder.senders.unregister(connectionId)
            delegate?.onFailure(webSocket, t, response)
        }
    }

/** Records host-initiated sends and closing without changing the delegate socket's result. */
class DevConsoleRecordingWebSocket private constructor(
    private val delegate: WebSocket,
    private val recorder: SocketRecorder,
    private val connectionId: String,
) : WebSocket {
    /**
     * Registered here, not by the listener, so a registry send goes through this wrapper's own
     * `send` and is recorded exactly once. Replacing the listener's sender is the point: the
     * documented dual-wiring would otherwise record the same frame twice.
     */
    init {
        recorder.senders.register(
            connectionId,
            SocketProtocol.WEBSOCKET,
            SocketSender { request -> sendRegistered(request.text) },
        )
    }

    override fun request() = delegate.request()

    override fun queueSize(): Long = delegate.queueSize()

    override fun send(text: String): Boolean =
        delegate.send(text).also { accepted ->
            if (accepted) recorder.onMessage(connectionId, SocketDirection.SENT, text, "text/plain")
        }

    override fun send(bytes: ByteString): Boolean =
        delegate.send(bytes).also { accepted ->
            if (accepted) {
                recorder.onBinaryMessage(
                    connectionId,
                    SocketDirection.SENT,
                    bytes.toByteArray(),
                    "application/octet-stream",
                )
            }
        }

    override fun close(
        code: Int,
        reason: String?,
    ): Boolean =
        delegate.close(code, reason).also { accepted ->
            if (accepted) recorder.onClosing(connectionId, code, reason)
            recorder.senders.unregister(connectionId)
        }

    override fun cancel() {
        delegate.cancel()
        recorder.senders.unregister(connectionId)
        // Not a failure: an intentional cancel by the host, so it must not read as ERROR severity.
        recorder.onCancelled(connectionId, "Cancelled by host")
    }

    private fun sendRegistered(text: String): SocketSendResult =
        if (send(text)) SocketSendResult.Sent else SocketSendResult.Rejected(SEND_QUEUE_FULL_REASON)

    companion object {
        fun wrap(
            delegate: WebSocket,
            recorder: SocketRecorder,
            connectionIdProvider: (WebSocket) -> String = { socket ->
                "${socket.request().url}-${System.identityHashCode(socket)}"
            },
        ): WebSocket {
            val connectionId = connectionIdProvider(delegate)
            val boundRecorder = recorder.bindToCurrentSession()
            boundRecorder.onCreated(connectionId, delegate.request().url.toString())
            return DevConsoleRecordingWebSocket(delegate, boundRecorder, connectionId)
        }
    }
}
