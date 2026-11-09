/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole

import io.devconsole.network.NetworkCursorCodec
import io.devconsole.push.PushEvent
import io.devconsole.socket.SocketConnection
import io.devconsole.socket.SocketConnectionState
import io.devconsole.socket.SocketDirection
import io.devconsole.socket.SocketLifecycleEvent
import io.devconsole.socket.SocketLifecycleType
import io.devconsole.socket.SocketMessage
import io.devconsole.socket.SocketPayload
import io.devconsole.socket.SocketProtocol
import io.devconsole.storage.api.StoredEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetainedSessionCapturesTest {
    private val wsConnection = SocketConnection(id = "ws-1", url = "wss://chat.test/live", openedAtEpochMs = 100L)
    private val mqttConnection =
        SocketConnection(id = "mq-1", url = "tcp://broker.test", openedAtEpochMs = 500L, protocol = SocketProtocol.MQTT)

    @Test
    fun `rebuilds connections with frames, final state and protocol, newest opened first`() {
        val events =
            listOf(
                lifecycle(SocketLifecycleEvent("ws-1", SocketLifecycleType.OPENED, 100L), wsConnection),
                frame(SocketMessage("ws-1", SocketDirection.SENT, 150L, SocketPayload.Text("hi", truncated = false))),
                frame(
                    SocketMessage("ws-1", SocketDirection.RECEIVED, 160L, SocketPayload.Text("yo", truncated = false)),
                ),
                lifecycle(
                    SocketLifecycleEvent("ws-1", SocketLifecycleType.FAILED, 200L, error = "reset"),
                    wsConnection,
                ),
                lifecycle(SocketLifecycleEvent("mq-1", SocketLifecycleType.OPENED, 500L), mqttConnection),
            )

        val connections = rebuildSocketConnections(events)

        assertEquals(listOf("mq-1", "ws-1"), connections.map(SocketConnection::id))
        val ws = connections.last()
        assertEquals("wss://chat.test/live", ws.url)
        assertEquals(SocketConnectionState.CLOSED, ws.state)
        assertEquals(200L, ws.closedAtEpochMs)
        assertEquals("reset", ws.error)
        assertEquals(1, ws.sentCount)
        assertEquals(1, ws.receivedCount)
        assertEquals(SocketProtocol.WEBSOCKET, ws.protocol)
        val mqtt = connections.first()
        assertEquals(SocketConnectionState.OPEN, mqtt.state)
        assertEquals(SocketProtocol.MQTT, mqtt.protocol)
    }

    @Test
    fun `push events come back newest first and other plugins are ignored`() =
        runTest {
            val events =
                listOf(
                    storedEvent("push", CapturePayloadCodec.push(push("older", 10L))),
                    storedEvent("push", CapturePayloadCodec.push(push("newer", 20L))),
                    storedEvent("logs", """{"log":"noise"}"""),
                )
            val captures = RetainedSessionCaptures({ events }, NetworkCursorCodec(ByteArray(16)))

            assertEquals(listOf("newer", "older"), captures.pushEvents("run-1").map(PushEvent::messageId))
            assertTrue(captures.sockets("run-1").isEmpty())
        }

    private fun push(
        messageId: String,
        receivedAt: Long,
    ) = PushEvent(provider = "fcm", data = mapOf("k" to "v"), messageId = messageId, receivedAtEpochMs = receivedAt)

    private fun lifecycle(
        event: SocketLifecycleEvent,
        connection: SocketConnection,
    ) = storedEvent("websocket", CapturePayloadCodec.socketLifecycle(event, connection))

    private fun frame(message: SocketMessage) = storedEvent("websocket", CapturePayloadCodec.socketMessage(message))

    private var sequence = 0L

    private fun storedEvent(
        pluginId: String,
        payloadJson: String?,
    ): StoredEvent {
        sequence++
        return StoredEvent(
            id = "e-$sequence",
            sessionId = "run-1",
            sequence = sequence,
            pluginId = pluginId,
            type = "timeline",
            wallTimeMs = sequence,
            monoTimeNs = sequence,
            severity = 0,
            summary = "event $sequence",
            correlationId = null,
            tagsJson = "{}",
            payloadJson = payloadJson,
            attachmentId = null,
        )
    }
}
