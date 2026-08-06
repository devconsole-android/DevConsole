package io.devconsole.socket

import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocketRecorderTest {
    @Test
    fun `redacts textual messages before storing a bounded preview`() {
        val store = InMemorySocketStore()
        val recorder = SocketRecorder(RedactionEngine(RedactionPolicy.default()), store, clock = { 100L })
        recorder.onOpen("connection", "wss://api.test/socket")

        recorder.onMessage("connection", SocketDirection.RECEIVED, "Bearer socket-secret", "application/json")

        val preview =
            (
                store
                    .connection("connection")!!
                    .messages
                    .single()
                    .payload as SocketPayload.Text
            ).preview
        assertFalse(preview.contains("socket-secret"))
    }

    @Test
    fun `disabled recorder never opens a connection or stores a message`() {
        val store = InMemorySocketStore()
        val recorder =
            SocketRecorder(RedactionEngine(RedactionPolicy.default()), store, enabled = false, clock = { 100L })

        recorder.onOpen("connection", "wss://api.test/socket")
        recorder.onMessage("connection", SocketDirection.RECEIVED, "Bearer socket-secret", "application/json")

        assertNull(store.connection("connection"))
    }

    @Test
    fun `records the complete lifecycle plus ping and pong frames`() {
        var time = 0L
        val store = InMemorySocketStore()
        val recorder = SocketRecorder(RedactionEngine(RedactionPolicy.default()), store, clock = { ++time })

        recorder.onCreated("connection", "wss://api.test/socket")
        recorder.onOpen("connection", "wss://api.test/socket")
        recorder.onPing("connection", SocketDirection.SENT, byteArrayOf(1, 2))
        recorder.onPong("connection", SocketDirection.RECEIVED, byteArrayOf(3, 4))
        recorder.onClosing("connection", 1000, "normal")
        recorder.onClosed("connection")

        assertEquals(
            listOf(
                SocketLifecycleType.CREATED,
                SocketLifecycleType.OPENED,
                SocketLifecycleType.CLOSING,
                SocketLifecycleType.CLOSED,
            ),
            store.lifecycleEvents("connection").map(SocketLifecycleEvent::type),
        )
        assertEquals(
            listOf(SocketFrameType.PING, SocketFrameType.PONG),
            store.messages("connection").sortedBy(SocketMessage::timestampEpochMs).map { it.metadata.frameType },
        )
        assertEquals(SocketConnectionState.CLOSED, store.connection("connection")!!.state)
    }

    @Test
    fun `binary preview is metadata only by default and bounded hex when explicitly enabled`() {
        val metadataOnly = InMemorySocketStore()
        val preview = InMemorySocketStore()
        val bytes = byteArrayOf(0, 15, -1, 16)
        SocketRecorder(RedactionEngine(RedactionPolicy.default()), metadataOnly).apply {
            onOpen("connection", "wss://api.test/socket")
            onBinaryMessage("connection", SocketDirection.RECEIVED, bytes)
        }
        SocketRecorder(
            RedactionEngine(RedactionPolicy.default()),
            preview,
            true,
            System::currentTimeMillis,
            BinaryPreviewPolicy.HEX,
        ).apply {
            onOpen("connection", "wss://api.test/socket")
            onBinaryMessage("connection", SocketDirection.RECEIVED, bytes)
        }

        val metadataPayload = metadataOnly.messages("connection").single().payload as SocketPayload.Binary
        val previewPayload = preview.messages("connection").single().payload as SocketPayload.Binary
        assertNull(metadataPayload.preview)
        assertEquals("000fff10", previewPayload.preview)
        assertEquals(BinaryPreviewEncoding.HEX, previewPayload.previewEncoding)
        assertTrue(!previewPayload.truncated)
    }

    @Test
    fun `withProtocol returns a configured copy without mutating the original`() {
        val store = InMemorySocketStore()
        val base = SocketRecorder(RedactionEngine(RedactionPolicy.default()), store, clock = { 100L })
        val mqtt = base.withProtocol(SocketProtocol.MQTT)

        mqtt.onOpen("mqtt-conn", "tcp://broker.test:1883")
        base.onOpen("ws-conn", "wss://api.test/socket")

        assertEquals(SocketProtocol.MQTT, store.connection("mqtt-conn")!!.protocol)
        assertEquals(SocketProtocol.WEBSOCKET, store.connection("ws-conn")!!.protocol)
        assertEquals(SocketProtocol.WEBSOCKET, base.protocol)
        assertEquals(SocketProtocol.MQTT, mqtt.protocol)
    }

    @Test
    fun `protocol gate drops the disallowed protocol and keeps the other`() {
        val store = InMemorySocketStore()
        val base =
            SocketRecorder(RedactionEngine(RedactionPolicy.default()), store, clock = { 100L })
                .withProtocolGate { it != SocketProtocol.MQTT }
        val mqtt = base.withProtocol(SocketProtocol.MQTT)

        mqtt.onOpen("mqtt-conn", "tcp://broker.test:1883")
        base.onOpen("ws-conn", "wss://api.test/socket")

        assertNull(store.connection("mqtt-conn"))
        assertEquals(SocketProtocol.WEBSOCKET, store.connection("ws-conn")!!.protocol)
    }

    @Test
    fun `a throwing protocol gate still records -- fail-open`() {
        val store = InMemorySocketStore()
        val recorder =
            SocketRecorder(RedactionEngine(RedactionPolicy.default()), store, clock = { 100L })
                .withProtocolGate { throw IllegalStateException("boom") }

        recorder.onOpen("connection", "wss://api.test/socket")

        assertEquals(SocketConnectionState.OPEN, store.connection("connection")!!.state)
    }
}
