/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole.socket

/**
 * One host-initiated frame, addressed to a connection id by [SocketSenderRegistry].
 *
 * [topic], [qos] and [retained] describe an MQTT publish and are ignored by a WebSocket sender. They
 * live on the same type because the dashboard composer already posts one payload to both protocols;
 * a per-protocol request type would only duplicate it and force the server routes to branch earlier.
 */
data class SocketSendRequest(
    val text: String,
    val topic: String? = null,
    val qos: Int = 0,
    val retained: Boolean = false,
)

/**
 * Outcome of [SocketSender.send], modelled as values rather than exceptions so the caller can show
 * why a frame did not go out without any adapter having to decide what to throw.
 */
sealed interface SocketSendResult {
    /** The transport accepted the frame. The adapter records it as SENT itself, exactly once. */
    data object Sent : SocketSendResult

    /** The transport refused the frame for a caller-visible reason (queue full, invalid parameters). */
    data class Rejected(
        val reason: String,
    ) : SocketSendResult

    /** The id is unknown, or the MQTT client behind it is currently disconnected. */
    data object NotConnected : SocketSendResult

    /**
     * The connection's protocol has no send capability in this build. Reserved so that a protocol
     * added later can decline cleanly instead of the dashboard mistaking it for a transport failure.
     */
    data object Unsupported : SocketSendResult
}

/**
 * Writes one frame over a live connection. Implementations hand the frame to the host's own socket
 * or client wrapper, so redaction and SENT recording stay on the adapter side and are never repeated
 * by the registry.
 */
fun interface SocketSender {
    fun send(request: SocketSendRequest): SocketSendResult
}

/**
 * Maps connection ids to the adapter that can write on them.
 *
 * The dashboard only knows ids from the inspector, while socket objects belong to the host app, so
 * sending has to resolve an id -- never a socket reference -- at call time. Adapters register when a
 * connection opens and unregister when it closes, and every access takes one lock because those
 * callbacks arrive on the socket's own thread while the dashboard sends from the Ktor thread. The
 * sender call itself is made outside the lock: it is host code of unknown cost.
 */
class SocketSenderRegistry {
    private val lock = Any()
    private val senders = linkedMapOf<String, Registration>()

    /**
     * Registers (or replaces) the sender for [connectionId]. Replacing is deliberate: a host that
     * wraps an already-open socket with the OkHttp adapter's recording wrapper must hand delivery to
     * the wrapper, otherwise the same frame would be recorded once by the listener's sender and once
     * more by the wrapper's own `send`.
     */
    fun register(
        connectionId: String,
        protocol: SocketProtocol,
        sender: SocketSender,
    ) {
        synchronized(lock) { senders[connectionId] = Registration(protocol, sender) }
    }

    /** Removes [connectionId]'s sender, so a closed connection can no longer be written to by id. */
    fun unregister(connectionId: String) {
        synchronized(lock) { senders.remove(connectionId) }
    }

    fun canSend(connectionId: String): Boolean = synchronized(lock) { connectionId in senders }

    /** The protocol registered for [connectionId], or null when nothing can write to it. */
    fun protocol(connectionId: String): SocketProtocol? = synchronized(lock) { senders[connectionId]?.protocol }

    /**
     * Validates [request] against the registered protocol, then hands it to that adapter. An unknown
     * id is [SocketSendResult.NotConnected]; a sender that throws is reported as a rejection so a
     * buggy adapter can never take the dashboard's request thread down with it.
     */
    fun send(
        connectionId: String,
        request: SocketSendRequest,
    ): SocketSendResult {
        val registration = synchronized(lock) { senders[connectionId] } ?: return SocketSendResult.NotConnected
        val rejection = rejectionReason(registration.protocol, request)
        return if (rejection == null) {
            deliver(registration.sender, request)
        } else {
            SocketSendResult.Rejected(rejection)
        }
    }

    /** Drops every sender, matching the other capture registries, which are cleared when a session ends. */
    fun clear() {
        synchronized(lock) { senders.clear() }
    }

    private fun deliver(
        sender: SocketSender,
        request: SocketSendRequest,
    ): SocketSendResult =
        runCatching { sender.send(request) }.getOrElse { failure ->
            SocketSendResult.Rejected(failure.message?.takeIf(String::isNotBlank) ?: failure.javaClass.simpleName)
        }

    /**
     * An oversized frame is refused rather than truncated: a truncated frame reads as a protocol
     * error to the server, and the inspector already cuts its own text preview at the same size.
     */
    private fun rejectionReason(
        protocol: SocketProtocol,
        request: SocketSendRequest,
    ): String? {
        if (request.text.encodeToByteArray().size > MAX_TEXT_BYTES) {
            return "Frame exceeds the $MAX_TEXT_KIB KiB send limit"
        }
        return if (protocol == SocketProtocol.MQTT) mqttRejectionReason(request) else null
    }

    private fun mqttRejectionReason(request: SocketSendRequest): String? =
        when {
            request.topic.isNullOrBlank() -> "MQTT publish requires a non-blank topic"
            request.qos !in MIN_MQTT_QOS..MAX_MQTT_QOS -> "MQTT qos must be 0, 1 or 2"
            else -> null
        }

    private data class Registration(
        val protocol: SocketProtocol,
        val sender: SocketSender,
    )

    companion object {
        private const val MAX_TEXT_KIB = 64
        private const val BYTES_PER_KIB = 1024
        private const val MIN_MQTT_QOS = 0
        private const val MAX_MQTT_QOS = 2

        /** Upper bound for a single send, in UTF-8 bytes, shared with the inspector's text preview cap. */
        const val MAX_TEXT_BYTES: Int = MAX_TEXT_KIB * BYTES_PER_KIB
    }
}
