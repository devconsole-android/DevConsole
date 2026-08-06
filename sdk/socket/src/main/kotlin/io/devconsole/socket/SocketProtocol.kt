/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.socket

/** Discriminates the wire protocol a [SocketConnection] carries -- plain WebSocket vs MQTT. */
enum class SocketProtocol(
    val wireName: String,
) {
    WEBSOCKET("websocket"),
    MQTT("mqtt"),
    ;

    companion object {
        @JvmStatic
        fun fromWireName(value: String?): SocketProtocol =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: WEBSOCKET
    }
}
