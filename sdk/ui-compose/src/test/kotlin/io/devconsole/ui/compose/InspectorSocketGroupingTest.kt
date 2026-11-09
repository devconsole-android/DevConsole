/**
 * @author Shakib
 * @since 09/11/26
 */
package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class InspectorSocketGroupingTest {
    private fun frame(
        time: Long,
        preview: String,
    ) = InspectorSocketFrameUi("RECEIVED", "TEXT", preview, time)

    private fun socket(
        id: String,
        opened: Long,
        vararg frames: InspectorSocketFrameUi,
    ) = InspectorSocketUi(id, "wss://$id.example", "OPEN", 0, 0, opened, frames = frames.toList())

    @Test
    fun connectionsKeepOpenOrderSoLiveMessagesDoNotReshuffleThem() {
        val older = socket("older", 1, frame(10, "first"), frame(30, "last"))
        val empty = socket("empty", 20)
        val newest = socket("newest", 2, frame(40, "latest"))
        val groups = groupSocketConnections(listOf(older, empty, newest), " ")
        assertEquals(listOf("empty", "newest", "older"), groups.map { it.socket.id })
        assertEquals(listOf(30L, 10L), groups[2].frames.map { it.timestampEpochMs })
        assertEquals(emptyList<InspectorSocketFrameUi>(), groups[0].frames)
    }

    @Test
    fun searchMatchesPreviewOrAddressAndRetainsEmptyAddressMatches() {
        val socket = socket("broker", 1, frame(10, "Hello world"), frame(20, "Other"))
        val empty = socket("empty", 2)
        val sockets = listOf(socket, empty)
        assertEquals(listOf("Hello world"), groupSocketConnections(sockets, "HELLO").single().frames.map { it.preview })
        assertEquals(
            listOf("Other", "Hello world"),
            groupSocketConnections(sockets, "BROKER").single().frames.map {
                it.preview
            },
        )
        assertEquals(listOf("empty"), groupSocketConnections(sockets, "EMPTY").map { it.socket.id })
        assertEquals(emptyList<SocketConnectionGroup>(), groupSocketConnections(sockets, "missing"))
    }

    @Test
    fun stateLabelsExplainConnectionState() {
        assertEquals("Connected", socketConnectionStateLabel("OPEN", null))
        assertEquals("Connecting", socketConnectionStateLabel("CREATED", null))
        assertEquals("Closing", socketConnectionStateLabel("CLOSING", null))
        assertEquals("Closed", socketConnectionStateLabel("CLOSED", null))
        assertEquals("Failed", socketConnectionStateLabel("FAILED", null))
        assertEquals("Failed", socketConnectionStateLabel("OPEN", "error"))
        assertEquals("CustomState", socketConnectionStateLabel("customState", null))
    }
}
