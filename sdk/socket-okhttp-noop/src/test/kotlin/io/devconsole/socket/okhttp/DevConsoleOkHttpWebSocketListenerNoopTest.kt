package io.devconsole.socket.okhttp

import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.socket.InMemorySocketStore
import io.devconsole.socket.SocketRecorder
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevConsoleOkHttpWebSocketListenerNoopTest {
    @Test
    fun `callbacks do not inspect the socket or record messages`() {
        val store = InMemorySocketStore()
        var identityReads = 0
        val listener =
            DevConsoleOkHttpWebSocketListener(
                SocketRecorder(RedactionEngine(RedactionPolicy.default()), store),
                connectionIdProvider = {
                    identityReads += 1
                    "must-not-be-used"
                },
            )
        val socket = FakeWebSocket()

        listener.onMessage(socket, "Bearer socket-canary")
        listener.onMessage(socket, ByteString.of(1, 2, 3))
        listener.onClosed(socket, 1000, "done")
        listener.onFailure(socket, IllegalStateException("failure-canary"), null)

        assertEquals(0, identityReads)
        assertTrue(store.connections().isEmpty())
    }

    private class FakeWebSocket : WebSocket {
        override fun request(): Request = error("request must not be inspected")

        override fun queueSize(): Long = error("queue must not be inspected")

        override fun send(text: String): Boolean = error("send must not be called")

        override fun send(bytes: ByteString): Boolean = error("send must not be called")

        override fun close(
            code: Int,
            reason: String?,
        ): Boolean = error("close must not be called")

        override fun cancel(): Unit = error("cancel must not be called")
    }
}
