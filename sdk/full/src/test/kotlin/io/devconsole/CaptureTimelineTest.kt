package io.devconsole

import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkRequestInput
import io.devconsole.network.NetworkRequestMetadata
import io.devconsole.network.NetworkResponseInput
import io.devconsole.network.NetworkTransaction
import io.devconsole.push.InMemoryPushStore
import io.devconsole.push.PushEvent
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.socket.InMemorySocketStore
import io.devconsole.socket.SocketConnection
import io.devconsole.socket.SocketDirection
import io.devconsole.socket.SocketRecorder
import io.devconsole.storage.api.StoredEvent
import io.devconsole.timeline.TimelineAppender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Proves the fix for "nothing shows on the timeline when a sample runs": a capture written to any
 * inspector store is mirrored onto the shared timeline, and the delegate store still receives it.
 */
class CaptureTimelineTest {
    private val collected = mutableListOf<StoredEvent>()
    private val appender =
        object : TimelineAppender {
            override fun append(event: StoredEvent) {
                collected.add(event)
            }
        }
    private val sequence = AtomicLong(0)
    private val bridge = CaptureTimelineBridge("session-1", { appender }, { null }, sequence::incrementAndGet)

    @Test
    fun `a recorded network transaction lands on the timeline and the delegate store`() {
        val store =
            TeeingNetworkTransactionStore(
                InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16))),
                bridge,
            )
        val capture =
            NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default()))
                .capture(
                    NetworkRequestInput(
                        "GET",
                        "https://api.test/orders",
                        correlationId = "order-42",
                    ).withMetadata(NetworkRequestMetadata(tags = mapOf("mocked" to "true"))),
                    NetworkResponseInput(200),
                )

        store.record(NetworkTransaction("tx-1", startedAtEpochMs = 0, completedAtEpochMs = 5, capture = capture))

        val event = collected.single()
        assertEquals("network", event.pluginId)
        assertEquals("network.transaction", event.type)
        assertTrue(event.summary, event.summary.contains("api.test/orders"))
        assertEquals("order-42", event.correlationId)
        assertTrue(event.tagsJson, event.tagsJson.contains("\"mocked\":\"true\""))
        assertEquals("tx-1", store.find("tx-1")?.id)
    }

    @Test
    fun `a recorded push lands on the timeline and the delegate store`() {
        val store = TeeingPushStore(InMemoryPushStore(), bridge)

        store.append(PushEvent(provider = "local", data = emptyMap(), messageId = "m1"))

        val event = collected.single()
        assertEquals("push", event.pluginId)
        assertTrue(event.summary, event.summary.contains("m1"))
        assertEquals(1, store.events().size)
    }

    @Test
    fun `an opened socket connection lands on the timeline and the delegate store`() {
        val store = TeeingSocketStore(InMemorySocketStore(), bridge)

        store.open(SocketConnection(id = "c1", url = "wss://echo.test/raw", openedAtEpochMs = 0))

        val event = collected.single()
        assertEquals("websocket", event.pluginId)
        assertTrue(event.summary, event.summary.contains("echo.test"))
        assertEquals("c1", store.connection("c1")?.id)
    }

    @Test
    fun `socket recorder mirrors each lifecycle and frame once`() {
        val store = TeeingSocketStore(InMemorySocketStore(), bridge)
        val recorder = SocketRecorder(RedactionEngine(RedactionPolicy.default()), store, clock = { 10L })

        recorder.onCreated("c1", "wss://echo.test/raw")
        recorder.onOpen("c1", "wss://echo.test/raw")
        recorder.onPing("c1", SocketDirection.SENT)
        recorder.onMessage("c1", SocketDirection.RECEIVED, "{\"ok\":true}", "application/json")
        recorder.onClosing("c1", 1000, "done")
        recorder.onClosed("c1")

        assertEquals(
            listOf(
                "socket.created",
                "socket.opened",
                "socket.frame.ping.sent",
                "socket.frame.text.received",
                "socket.closing",
                "socket.closed",
            ),
            collected.map(StoredEvent::type),
        )
        assertEquals(4, store.lifecycleEvents("c1").size)
        assertEquals(2, store.messages("c1").size)
    }

    @Test
    fun `the shared sequence increases across capture kinds so the timeline orders by insertion`() {
        val network =
            TeeingNetworkTransactionStore(
                InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16))),
                bridge,
            )
        val push = TeeingPushStore(InMemoryPushStore(), bridge)
        val capture =
            NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default()))
                .capture(NetworkRequestInput("GET", "https://api.test/a"), null)

        network.record(NetworkTransaction("tx-1", 0, 1, capture))
        push.append(PushEvent(provider = "local", data = emptyMap(), messageId = "m1"))

        assertEquals(listOf(1L, 2L), collected.map { it.sequence })
    }
}
