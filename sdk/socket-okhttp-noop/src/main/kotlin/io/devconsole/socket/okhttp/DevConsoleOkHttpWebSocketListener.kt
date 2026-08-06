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
 * Protected-build adapter: overrides every callback the full-side listener declares with a no-op
 * so the two modules' public shapes stay identical (see `OkHttpAdapterFullNoopParityTest`), but
 * never inspects socket data or touches [connectionIdProvider] -- functionally these are exactly
 * what [WebSocketListener]'s own empty defaults already do.
 */
class DevConsoleOkHttpWebSocketListener(
    @Suppress("UNUSED_PARAMETER") recorder: SocketRecorder,
    @Suppress("UNUSED_PARAMETER")
    connectionIdProvider: (WebSocket) -> String = { "" },
) : WebSocketListener() {
    override fun onOpen(
        webSocket: WebSocket,
        response: Response,
    ) = Unit

    override fun onMessage(
        webSocket: WebSocket,
        text: String,
    ) = Unit

    override fun onMessage(
        webSocket: WebSocket,
        bytes: ByteString,
    ) = Unit

    override fun onClosing(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) = Unit

    override fun onClosed(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) = Unit

    override fun onFailure(
        webSocket: WebSocket,
        t: Throwable,
        response: Response?,
    ) = Unit
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
