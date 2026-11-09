/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole.socket

import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SocketSenderRegistryTest {
    @Test
    fun `registered sender receives the request and its result is returned unchanged`() {
        val registry = SocketSenderRegistry()
        val received = mutableListOf<SocketSendRequest>()
        registry.register(
            "ws-1",
            SocketProtocol.WEBSOCKET,
            SocketSender { request ->
                received += request
                SocketSendResult.Sent
            },
        )

        assertTrue(registry.canSend("ws-1"))
        assertEquals(SocketProtocol.WEBSOCKET, registry.protocol("ws-1"))

        val request = SocketSendRequest("hello")
        assertEquals(SocketSendResult.Sent, registry.send("ws-1", request))
        assertEquals(listOf(request), received)
    }

    @Test
    fun `unknown and unregistered connections report NotConnected`() {
        val registry = SocketSenderRegistry()

        assertEquals(SocketSendResult.NotConnected, registry.send("missing", SocketSendRequest("hi")))

        registry.register("ws-1", SocketProtocol.WEBSOCKET, SocketSender { SocketSendResult.Sent })
        registry.unregister("ws-1")

        assertFalse(registry.canSend("ws-1"))
        assertNull(registry.protocol("ws-1"))
        assertEquals(SocketSendResult.NotConnected, registry.send("ws-1", SocketSendRequest("hi")))
    }

    @Test
    fun `clear drops every registration`() {
        val registry = SocketSenderRegistry()
        registry.register("ws-1", SocketProtocol.WEBSOCKET, SocketSender { SocketSendResult.Sent })
        registry.register("mqtt-1", SocketProtocol.MQTT, SocketSender { SocketSendResult.Sent })

        registry.clear()

        assertFalse(registry.canSend("ws-1"))
        assertFalse(registry.canSend("mqtt-1"))
    }

    @Test
    fun `text over the size cap is rejected before it reaches the sender`() {
        var calls = 0
        val registry = SocketSenderRegistry()
        registry.register(
            "ws-1",
            SocketProtocol.WEBSOCKET,
            SocketSender {
                calls += 1
                SocketSendResult.Sent
            },
        )
        val oversized = "x".repeat(SocketSenderRegistry.MAX_TEXT_BYTES + 1)

        val rejected = registry.send("ws-1", SocketSendRequest(oversized))

        assertTrue(rejected is SocketSendResult.Rejected)
        assertEquals(0, calls)
        assertEquals(
            SocketSendResult.Sent,
            registry.send("ws-1", SocketSendRequest("x".repeat(SocketSenderRegistry.MAX_TEXT_BYTES))),
        )
    }

    @Test
    fun `mqtt needs a topic and a qos in range`() {
        var calls = 0
        val registry = SocketSenderRegistry()
        registry.register(
            "mqtt-1",
            SocketProtocol.MQTT,
            SocketSender {
                calls += 1
                SocketSendResult.Sent
            },
        )

        val rejectedRequests =
            listOf(
                SocketSendRequest("hi"),
                SocketSendRequest("hi", topic = "  "),
                SocketSendRequest("hi", topic = "a/b", qos = 3),
                SocketSendRequest("hi", topic = "a/b", qos = -1),
            )

        assertTrue(rejectedRequests.all { registry.send("mqtt-1", it) is SocketSendResult.Rejected })
        assertEquals(0, calls)

        assertEquals(
            SocketSendResult.Sent,
            registry.send("mqtt-1", SocketSendRequest("hi", topic = "a/b", qos = 2)),
        )
    }

    @Test
    fun `websocket ignores the mqtt-only fields`() {
        val registry = SocketSenderRegistry()
        registry.register("ws-1", SocketProtocol.WEBSOCKET, SocketSender { SocketSendResult.Sent })

        assertEquals(SocketSendResult.Sent, registry.send("ws-1", SocketSendRequest("hi", qos = 9)))
    }

    @Test
    fun `a sender that throws becomes a rejection instead of escaping`() {
        val registry = SocketSenderRegistry()
        registry.register("ws-1", SocketProtocol.WEBSOCKET, SocketSender { error("boom") })

        assertEquals(SocketSendResult.Rejected("boom"), registry.send("ws-1", SocketSendRequest("hi")))
    }

    @Test
    fun `every recorder copy shares the same registry instance`() {
        val recorder = SocketRecorder(RedactionEngine(RedactionPolicy.recommended()), InMemorySocketStore())

        assertSame(recorder.senders, recorder.withProtocol(SocketProtocol.MQTT).senders)
        assertSame(recorder.senders, recorder.bindToCurrentSession().senders)
        assertSame(recorder.senders, recorder.withSessionIdProvider { "session" }.senders)
        assertSame(recorder.senders, recorder.withProtocol(SocketProtocol.MQTT).bindToCurrentSession().senders)

        recorder
            .withProtocol(SocketProtocol.MQTT)
            .senders
            .register("mqtt-1", SocketProtocol.MQTT, SocketSender { SocketSendResult.Sent })
        assertTrue(recorder.senders.canSend("mqtt-1"))
    }
}
