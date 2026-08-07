package io.devconsole.socket.okhttp

import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.socket.InMemorySocketStore
import io.devconsole.socket.SocketConnectionState
import io.devconsole.socket.SocketDirection
import io.devconsole.socket.SocketLifecycleType
import io.devconsole.socket.SocketMessage
import io.devconsole.socket.SocketPayload
import io.devconsole.socket.SocketRecorder
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevConsoleOkHttpWebSocketListenerTest {
    @Test
    fun `records redacted text callback for an open socket`() {
        val store = InMemorySocketStore()
        val listener =
            DevConsoleOkHttpWebSocketListener(
                SocketRecorder(RedactionEngine(RedactionPolicy.default()), store),
                connectionIdProvider = { "socket-1" },
            )
        val socket = FakeWebSocket()
        listener.recordOpen(socket)

        listener.onMessage(socket, "Bearer socket-secret")

        val preview =
            (
                store
                    .connection("socket-1")!!
                    .messages
                    .single()
                    .payload as SocketPayload.Text
            ).preview
        assertFalse(preview.contains("socket-secret"))
    }

    @Test
    fun `recording socket preserves delegate results and records accepted sends and closing`() {
        val store = InMemorySocketStore()
        val recorder = SocketRecorder(RedactionEngine(RedactionPolicy.default()), store)
        val delegate = FakeWebSocket()
        val socket = DevConsoleRecordingWebSocket.wrap(delegate, recorder) { "socket-1" }

        assertTrue(socket.send("hello"))
        assertTrue(socket.send(ByteString.of(1, 2, 3)))
        assertTrue(socket.close(1000, "done"))

        assertEquals(2, delegate.sendCount)
        assertEquals(
            listOf(SocketDirection.SENT, SocketDirection.SENT),
            store.messages("socket-1").map(SocketMessage::direction),
        )
        assertEquals(SocketLifecycleType.CLOSING, store.lifecycleEvents("socket-1").last().type)
    }

    @Test
    fun `wrap after the listener already opened the socket keeps OPEN state and preserves openedAtEpochMs`() {
        val store = InMemorySocketStore()
        var time = 100L
        val recorder = SocketRecorder(RedactionEngine(RedactionPolicy.default()), store, clock = { time })
        val listener = DevConsoleOkHttpWebSocketListener(recorder, connectionIdProvider = { "socket-1" })
        val socket = FakeWebSocket()

        listener.recordOpen(socket)
        val openedAt = store.connection("socket-1")!!.openedAtEpochMs
        assertEquals(SocketConnectionState.OPEN, store.connection("socket-1")!!.state)

        // The documented dual-wiring: a host also wraps the already-open socket.
        time = 200L
        DevConsoleRecordingWebSocket.wrap(socket, recorder) { "socket-1" }

        val connection = store.connection("socket-1")!!
        assertEquals(SocketConnectionState.OPEN, connection.state)
        assertEquals(openedAt, connection.openedAtEpochMs)
        assertEquals(
            1,
            store.lifecycleEvents("socket-1").count { it.type == SocketLifecycleType.CREATED },
        )
    }

    @Test
    fun `delegate receives every callback after it has been recorded`() {
        val store = InMemorySocketStore()
        val recorder = SocketRecorder(RedactionEngine(RedactionPolicy.default()), store)
        val hostDelegate = RecordingWebSocketListener()
        val listener =
            DevConsoleOkHttpWebSocketListener(
                recorder,
                connectionIdProvider = { "socket-1" },
                delegate = hostDelegate,
            )
        val socket = FakeWebSocket()

        listener.onOpen(socket, fakeResponse())
        listener.onMessage(socket, "hello")
        listener.onClosing(socket, 1000, "bye")
        listener.onClosed(socket, 1000, "bye")

        assertEquals(
            listOf("onOpen", "onMessage:hello", "onClosing", "onClosed"),
            hostDelegate.events,
        )
        assertEquals(SocketConnectionState.CLOSED, store.connection("socket-1")!!.state)
    }

    @Test
    fun `cancel records a non-error CANCELLED lifecycle instead of FAILED`() {
        val store = InMemorySocketStore()
        val recorder = SocketRecorder(RedactionEngine(RedactionPolicy.default()), store)
        val socket = DevConsoleRecordingWebSocket.wrap(FakeWebSocket(), recorder) { "socket-1" }

        socket.cancel()

        val connection = store.connection("socket-1")!!
        assertEquals(SocketConnectionState.CLOSED, connection.state)
        assertEquals(SocketLifecycleType.CANCELLED, store.lifecycleEvents("socket-1").last().type)
    }

    private fun fakeResponse(): Response =
        Response
            .Builder()
            .request(Request.Builder().url("wss://api.test/socket").build())
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()

    private class RecordingWebSocketListener : WebSocketListener() {
        val events = mutableListOf<String>()

        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            events += "onOpen"
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            events += "onMessage:$text"
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            events += "onClosing"
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            events += "onClosed"
        }
    }

    private class FakeWebSocket : WebSocket {
        var sendCount = 0

        override fun request(): Request = Request.Builder().url("wss://api.test/socket").build()

        override fun queueSize(): Long = 0

        override fun send(text: String): Boolean {
            sendCount += 1
            return true
        }

        override fun send(bytes: ByteString): Boolean {
            sendCount += 1
            return true
        }

        override fun close(
            code: Int,
            reason: String?,
        ): Boolean = true

        override fun cancel() = Unit
    }
}
