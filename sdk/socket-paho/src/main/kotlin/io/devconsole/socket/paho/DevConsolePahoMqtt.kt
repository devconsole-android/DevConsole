/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.socket.paho

import io.devconsole.socket.MqttFrameMetadata
import io.devconsole.socket.SocketDirection
import io.devconsole.socket.SocketProtocol
import io.devconsole.socket.SocketRecorder
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttMessage

/** Paho adapter that records MQTT callbacks without altering the client's own control flow. */
class DevConsolePahoMqttCallback
    @JvmOverloads
    constructor(
        recorder: SocketRecorder,
        private val connectionId: String,
        private val delegate: MqttCallback? = null,
    ) : MqttCallbackExtended {
        private val recorder = recorder.withProtocol(SocketProtocol.MQTT).bindToCurrentSession()

        override fun connectComplete(
            reconnect: Boolean,
            serverURI: String,
        ) {
            recorder.onOpen(connectionId, serverURI, reconnectAttempt = if (reconnect) 1 else 0)
            (delegate as? MqttCallbackExtended)?.connectComplete(reconnect, serverURI)
        }

        override fun connectionLost(cause: Throwable?) {
            if (cause != null) {
                recorder.onFailure(connectionId, cause)
            } else {
                recorder.onClosed(connectionId)
            }
            delegate?.connectionLost(cause)
        }

        override fun messageArrived(
            topic: String,
            message: MqttMessage,
        ) {
            // Recording must never stop the host's own message handling from firing below --
            // a bad content-type format or decode failure is swallowed here, not surfaced.
            runCatching {
                val contentType = MqttFrameMetadata.format(topic, message.qos, message.isRetained)
                val text = message.payload.decodeUtf8TextOrNull()
                if (text != null) {
                    recorder.onMessage(connectionId, SocketDirection.RECEIVED, text, contentType)
                } else {
                    recorder.onBinaryMessage(connectionId, SocketDirection.RECEIVED, message.payload, contentType)
                }
            }
            delegate?.messageArrived(topic, message)
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) {
            // Sends are recorded by DevConsoleRecordingMqttPublisher, on the delegate's own
            // success signal -- this callback only forwards to the host.
            delegate?.deliveryComplete(token)
        }
    }

/** Records host-initiated publishes and disconnects without changing the delegate client's result. */
class DevConsoleRecordingMqttPublisher private constructor(
    private val delegate: IMqttAsyncClient,
    private val recorder: SocketRecorder,
    val connectionId: String,
) {
    fun publish(
        topic: String,
        message: MqttMessage,
    ): IMqttDeliveryToken =
        delegate.publish(topic, message).also { token ->
            if (token != null) recordSent(topic, message.payload, message.qos, message.isRetained)
        }

    fun publish(
        topic: String,
        payload: ByteArray,
        qos: Int,
        retained: Boolean,
    ): IMqttDeliveryToken =
        delegate.publish(topic, payload, qos, retained).also { token ->
            if (token != null) recordSent(topic, payload, qos, retained)
        }

    fun disconnect(): IMqttToken =
        delegate.disconnect().also {
            recorder.onClosing(connectionId)
            recorder.onClosed(connectionId)
        }

    private fun recordSent(
        topic: String,
        payload: ByteArray,
        qos: Int,
        retained: Boolean,
    ) {
        runCatching {
            val contentType = MqttFrameMetadata.format(topic, qos, retained)
            val text = payload.decodeUtf8TextOrNull()
            if (text != null) {
                recorder.onMessage(connectionId, SocketDirection.SENT, text, contentType)
            } else {
                recorder.onBinaryMessage(connectionId, SocketDirection.SENT, payload, contentType)
            }
        }
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun wrap(
            delegate: IMqttAsyncClient,
            recorder: SocketRecorder,
            connectionIdProvider: (IMqttAsyncClient) -> String = { "${it.serverURI}-${System.identityHashCode(it)}" },
        ): DevConsoleRecordingMqttPublisher {
            val connectionId = connectionIdProvider(delegate)
            val boundRecorder = recorder.withProtocol(SocketProtocol.MQTT).bindToCurrentSession()
            boundRecorder.onCreated(connectionId, delegate.serverURI)
            return DevConsoleRecordingMqttPublisher(delegate, boundRecorder, connectionId)
        }
    }
}

/** One-call install/wire-up for a Paho MQTT client: real capture here, a no-op in `sdk:socket-paho-noop`. */
object DevConsolePahoMqtt {
    /**
     * Paho holds exactly one [MqttCallback] per client and [install] claims that slot — calling
     * `client.setCallback(...)` afterward silently unhooks all MQTT capture. Pass the app's own
     * callback as [delegate] instead; every event is forwarded to it after recording.
     */
    @JvmStatic
    @JvmOverloads
    fun install(
        client: IMqttAsyncClient,
        recorder: SocketRecorder,
        delegate: MqttCallback? = null,
    ): DevConsoleRecordingMqttPublisher {
        val publisher = DevConsoleRecordingMqttPublisher.wrap(client, recorder)
        client.setCallback(DevConsolePahoMqttCallback(recorder, publisher.connectionId, delegate))
        return publisher
    }
}

/**
 * Decodes as UTF-8 and rejects the result if it contains U+FFFD (the replacement character) --
 * the standard signal that the bytes were not valid UTF-8 to begin with, so a binary MQTT payload
 * is stored as binary rather than as garbled text.
 */
private fun ByteArray.decodeUtf8TextOrNull(): String? {
    val text = toString(Charsets.UTF_8)
    return if (text.contains('\uFFFD')) null else text
}
