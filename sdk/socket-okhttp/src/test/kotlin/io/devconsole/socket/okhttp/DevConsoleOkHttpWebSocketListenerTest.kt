package io.devconsole.socket.okhttp

import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.socket.InMemorySocketStore
import io.devconsole.socket.SocketDirection
import io.devconsole.socket.SocketLifecycleType
import io.devconsole.socket.SocketMessage
import io.devconsole.socket.SocketPayload
import io.devconsole.socket.SocketRecorder
import okhttp3.Request
import okhttp3.WebSocket
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
