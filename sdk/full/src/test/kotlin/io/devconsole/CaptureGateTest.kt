/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole

import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkRequestInput
import io.devconsole.network.NetworkResponseInput
import io.devconsole.network.NetworkTransaction
import io.devconsole.push.InMemoryPushStore
import io.devconsole.push.PushEvent
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.socket.InMemorySocketStore
import io.devconsole.socket.SocketConnection
import io.devconsole.socket.SocketDirection
import io.devconsole.socket.SocketMessage
import io.devconsole.socket.SocketPayload
import io.devconsole.storage.api.StoredEvent
import io.devconsole.timeline.TimelineAppender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class CaptureGateTest {
    private val collected = mutableListOf<StoredEvent>()
    private val appender =
        object : TimelineAppender {
            override fun append(event: StoredEvent) {
                collected.add(event)
            }
        }
    private val sequence = AtomicLong(0)
    private val bridge = CaptureTimelineBridge("session-1", { appender }, { null }, sequence::incrementAndGet)
    private val factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.recommended()))

    @Suppress("LongMethod") // One end-to-end scenario; splitting it would hide the ordering it checks.
    @Test
    fun `paused gate drops new network transaction ids but allows updates to already recorded ids`() {
        val gate = CaptureGate()
        val store =
            TeeingNetworkTransactionStore(
                delegate = InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16))),
                bridge = bridge,
                captureGate = gate,
            )
        val initialCapture = factory.capture(NetworkRequestInput("GET", "https://api.test/inflight"), null)
        store.record(
            NetworkTransaction(
                "tx-inflight",
                startedAtEpochMs = 10L,
                completedAtEpochMs = null,
                capture = initialCapture,
            ),
        )
        assertEquals(1, collected.size)

        assertTrue(gate.setPaused(true))
        assertFalse(gate.setPaused(true))
        assertTrue(gate.isPaused())

        val blockedCapture =
            factory.capture(NetworkRequestInput("GET", "https://api.test/new"), NetworkResponseInput(200))
        store.record(
            NetworkTransaction(
                "tx-new",
                startedAtEpochMs = 20L,
                completedAtEpochMs = 25L,
                capture = blockedCapture,
            ),
        )
        assertNull(store.find("tx-new"))
        assertEquals(1, collected.size)

        val completedCapture =
            factory.capture(NetworkRequestInput("GET", "https://api.test/inflight"), NetworkResponseInput(200))
        store.record(
            NetworkTransaction(
                "tx-inflight",
                startedAtEpochMs = 10L,
                completedAtEpochMs = 30L,
                capture = completedCapture,
            ),
        )
        val updated = store.find("tx-inflight")
        assertNotNull(updated)
        assertEquals(200, updated?.capture?.response?.statusCode)
        assertEquals(2, collected.size)

        assertTrue(gate.setPaused(false))
        assertFalse(gate.isPaused())

        store.record(
            NetworkTransaction(
                "tx-after-resume",
                startedAtEpochMs = 40L,
                completedAtEpochMs = 45L,
                capture = blockedCapture,
            ),
        )
        assertNotNull(store.find("tx-after-resume"))
        assertEquals(3, collected.size)
    }

    @Test
    fun `paused gate drops new socket connections and push events`() {
        val gate = CaptureGate(initialPaused = true)
        val socketStore = TeeingSocketStore(InMemorySocketStore(), bridge, captureGate = gate)
        val pushStore = TeeingPushStore(InMemoryPushStore(), bridge, captureGate = gate)

        socketStore.open(SocketConnection(id = "c1", url = "wss://api.test/ws", openedAtEpochMs = 10L))
        socketStore.append(
            SocketMessage(
                connectionId = "c1",
                direction = SocketDirection.SENT,
                timestampEpochMs = 11L,
                payload = SocketPayload.Text("hello"),
            ),
        )
        pushStore.append(PushEvent(provider = "fcm", data = emptyMap(), messageId = "p1"))

        assertNull(socketStore.connection("c1"))
        assertTrue(pushStore.events().isEmpty())
        assertTrue(collected.isEmpty())
    }

    @Test
    fun `session marker recorder emits capture paused and capture resumed system events`() {
        val markers = SessionMarkerRecorder(bridge)

        markers.captureStateChanged(paused = true)
        markers.captureStateChanged(paused = false)

        assertEquals(listOf("system.capture.paused", "system.capture.resumed"), collected.map(StoredEvent::type))
        assertEquals(listOf("Capture paused", "Capture resumed"), collected.map(StoredEvent::summary))
    }
}
