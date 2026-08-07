/**
 * @author Shakib
 * @since 05/08/26
 */
package io.devconsole.socket.okhttp

import io.devconsole.socket.SocketRecorder
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Protected-build adapter: keeps the full-side listener's public shape (see
 * `OkHttpAdapterFullNoopParityTest`) but never inspects socket data or touches
 * [connectionIdProvider]. It still forwards every callback to a supplied [delegate], so a host that
 * composes its own listener through this adapter keeps receiving callbacks identically in release.
 */
class DevConsoleOkHttpWebSocketListener
    @JvmOverloads
    constructor(
        @Suppress("UNUSED_PARAMETER")
        recorder: SocketRecorder,
        @Suppress("UNUSED_PARAMETER")
        connectionIdProvider: (WebSocket) -> String = { "" },
        private val delegate: WebSocketListener? = null,
    ) : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            delegate?.onOpen(webSocket, response)
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            delegate?.onMessage(webSocket, text)
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString,
        ) {
            delegate?.onMessage(webSocket, bytes)
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            delegate?.onClosing(webSocket, code, reason)
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            delegate?.onClosed(webSocket, code, reason)
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            delegate?.onFailure(webSocket, t, response)
        }
    }

/**
 * Protected-build counterpart to the full-side `DevConsoleRecordingWebSocket`: same public shape
 * (constructor + `wrap`), so a host that constructs one in a debug build keeps compiling once the
 * release variant substitutes this no-op module. Delegates every call straight through to the
 * wrapped [WebSocket] and never touches the recorder -- nothing is inspected or recorded.
 */
class DevConsoleRecordingWebSocket private constructor(
    private val delegate: WebSocket,
    @Suppress("UNUSED_PARAMETER") recorder: SocketRecorder,
    @Suppress("UNUSED_PARAMETER") connectionId: String,
) : WebSocket {
    override fun request() = delegate.request()

    override fun queueSize(): Long = delegate.queueSize()

    override fun send(text: String): Boolean = delegate.send(text)

    override fun send(bytes: ByteString): Boolean = delegate.send(bytes)

    override fun close(
        code: Int,
        reason: String?,
    ): Boolean = delegate.close(code, reason)

    override fun cancel() = delegate.cancel()

    companion object {
        fun wrap(
            delegate: WebSocket,
            recorder: SocketRecorder,
            @Suppress("UNUSED_PARAMETER")
            connectionIdProvider: (WebSocket) -> String = { "" },
        ): WebSocket = DevConsoleRecordingWebSocket(delegate, recorder, "")
    }
}
