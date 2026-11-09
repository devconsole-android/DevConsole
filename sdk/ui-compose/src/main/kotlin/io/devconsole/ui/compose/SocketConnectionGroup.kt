/**
 * @author Shakib
 * @since 09/11/26
 */
package io.devconsole.ui.compose

internal data class SocketConnectionGroup(
    val socket: InspectorSocketUi,
    val frames: List<InspectorSocketFrameUi>,
)

internal fun groupSocketConnections(
    sockets: List<InspectorSocketUi>,
    query: String,
): List<SocketConnectionGroup> {
    val search = query.trim()
    return sockets
        .mapNotNull { socket ->
            val urlMatches = search.isNotEmpty() && socket.url.contains(search, ignoreCase = true)
            val frames =
                socket.frames.sortedByDescending { it.timestampEpochMs }.filter { frame ->
                    search.isEmpty() || urlMatches || frame.preview?.contains(search, ignoreCase = true) == true
                }
            if (search.isNotEmpty() && !urlMatches && frames.isEmpty()) null else SocketConnectionGroup(socket, frames)
        }.sortedByDescending { it.socket.openedAtEpochMs }
}

internal fun socketConnectionStateLabel(
    state: String,
    error: String?,
): String =
    if (error != null) {
        "Failed"
    } else {
        when (state) {
            "OPEN" -> "Connected"
            "CREATED" -> "Connecting"
            "CLOSING" -> "Closing"
            "CLOSED" -> "Closed"
            "FAILED" -> "Failed"
            else -> state.replaceFirstChar { it.titlecase() }
        }
    }
