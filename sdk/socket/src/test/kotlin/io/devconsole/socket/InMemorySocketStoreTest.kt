package io.devconsole.socket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemorySocketStoreTest {
    @Test
    fun `byte capacity evicts oldest frames even below count capacity`() {
        val store =
            InMemorySocketStore(maxConnections = 2, maxMessagesPerConnection = 10)
                .withByteCapacity(1_500)
        store.open(SocketConnection("one", "wss://api.test/socket", 1))
        repeat(3) { index ->
            store.append(
                SocketMessage(
                    "one",
                    SocketDirection.RECEIVED,
                    index.toLong(),
                    SocketPayload.Text("$index-${"x".repeat(700)}"),
                ),
            )
        }

        val remaining = store.messages("one")
        assertTrue(remaining.size < 3)
        assertEquals(2L, remaining.maxOf { it.timestampEpochMs })
    }

    @Test
    fun `stores a bounded connection message preview`() {
        val store = InMemorySocketStore(maxConnections = 2, maxMessagesPerConnection = 2)
        store.open(SocketConnection("one", "wss://api.test/socket", 1))
        store.append(SocketMessage("one", SocketDirection.SENT, 2, SocketPayload.Text("hello")))

        assertEquals(1, store.connection("one")!!.messages.size)
        assertEquals(
            "hello",
            (
                store
                    .connection("one")!!
                    .messages
                    .single()
                    .payload as SocketPayload.Text
            ).preview,
        )
    }

    @Test
    fun `filters frames by connection direction type text error and time`() {
        val store = InMemorySocketStore()
        store.open(SocketConnection("failed", "wss://api.test/failed", 1))
        store.append(
            SocketMessage("failed", SocketDirection.RECEIVED, 10, SocketPayload.Text("{\"kind\":\"match\"}"))
                .withMetadata(
                    SocketMessageMetadata(frameType = SocketFrameType.TEXT, textFormat = SocketTextFormat.JSON),
                ),
        )
        store.append(
            SocketMessage("failed", SocketDirection.SENT, 20, SocketPayload.Text("ignore"))
                .withMetadata(SocketMessageMetadata(frameType = SocketFrameType.TEXT)),
        )
        store.transition("failed", SocketConnectionState.FAILED, 30, error = "boom")
        store.open(SocketConnection("healthy", "wss://api.test/healthy", 1))
        store.append(SocketMessage("healthy", SocketDirection.RECEIVED, 10, SocketPayload.Text("match")))

        val matching =
            store.messages(
                SocketMessageQuery(
                    connectionIds = setOf("failed"),
                    directions = setOf(SocketDirection.RECEIVED),
                    frameTypes = setOf(SocketFrameType.TEXT),
                    fromEpochMs = 5,
                    toEpochMs = 15,
                    query = "kind",
                    hasError = true,
                ),
            )

        assertEquals(1, matching.size)
        assertEquals("failed", matching.single().connectionId)
        assertTrue((matching.single().payload as SocketPayload.Text).preview.contains("match"))
    }

    @Test
    fun `protocol survives an open, transition, close round trip`() {
        val store = InMemorySocketStore()
        store.open(SocketConnection("mqtt-conn", "tcp://broker.test:1883", 1, protocol = SocketProtocol.MQTT))
        assertEquals(SocketProtocol.MQTT, store.connection("mqtt-conn")!!.protocol)

        store.transition("mqtt-conn", SocketConnectionState.CLOSING, 2)
        assertEquals(SocketProtocol.MQTT, store.connection("mqtt-conn")!!.protocol)

        store.close("mqtt-conn", SocketConnectionState.CLOSED, 3)
        assertEquals(SocketProtocol.MQTT, store.connection("mqtt-conn")!!.protocol)

        // Re-opening the same connection id (as happens when a listener recreates it) must not
        // silently reset the protocol back to the default.
        store.open(SocketConnection("mqtt-conn", "tcp://broker.test:1883", 1, protocol = SocketProtocol.MQTT))
        assertEquals(SocketProtocol.MQTT, store.connection("mqtt-conn")!!.protocol)
    }
}
