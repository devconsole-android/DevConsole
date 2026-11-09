/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole.server.ktor

import io.devconsole.socket.SocketSendRequest
import io.ktor.http.Parameters

/**
 * Reads the send route's form (`text`, plus MQTT `topic`/`qos`/`retained`); null when `text` is
 * missing or `qos`/`retained` is malformed. Range checks stay with `SocketSenderRegistry`.
 */
internal fun Parameters.toSocketSendRequest(): SocketSendRequest? {
    val text = this["text"]?.takeIf(String::isNotEmpty)
    val qos = this["qos"]?.takeIf(String::isNotBlank)?.let { it.toIntOrNull() ?: INVALID_QOS } ?: 0
    val retained = this["retained"]?.takeIf(String::isNotBlank)?.toBooleanStrictOrNull()
    val retainedValid = retained != null || this["retained"].isNullOrBlank()
    return if (text == null || qos == INVALID_QOS || !retainedValid) {
        null
    } else {
        SocketSendRequest(text, this["topic"]?.takeIf(String::isNotBlank), qos, retained ?: false)
    }
}

private const val INVALID_QOS = -1
