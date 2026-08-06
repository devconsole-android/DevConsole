/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.socket.paho

import io.devconsole.socket.SocketRecorder
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttMessage

/**
 * Protected-build adapter: overrides every callback the full-side listener declares with a no-op
 * so the two modules' public shapes stay identical (see `OkHttpAdapterFullNoopParityTest`, which
 * now also covers this pair), but never inspects the client or the message -- functionally these
 * are exactly what an empty [MqttCallbackExtended] implementation already is.
 */
class DevConsolePahoMqttCallback
    @JvmOverloads
    constructor(
        @Suppress("UNUSED_PARAMETER") recorder: SocketRecorder,
        @Suppress("UNUSED_PARAMETER") private val connectionId: String,
        private val delegate: MqttCallback? = null,
    ) : MqttCallbackExtended {
        override fun connectComplete(
            reconnect: Boolean,
            serverURI: String,
        ) {
            (delegate as? MqttCallbackExtended)?.connectComplete(reconnect, serverURI)
        }

        override fun connectionLost(cause: Throwable?) {
            delegate?.connectionLost(cause)
        }

        override fun messageArrived(
            topic: String,
            message: MqttMessage,
        ) {
            delegate?.messageArrived(topic, message)
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) {
            delegate?.deliveryComplete(token)
        }
    }

/**
 * Protected-build counterpart to the full-side `DevConsoleRecordingMqttPublisher`: same public
 * shape (constructor + `wrap`), so a host that constructs one in a debug build keeps compiling
 * once the release variant substitutes this no-op module. Delegates every call straight through to
 * the wrapped [IMqttAsyncClient] and never touches the recorder -- nothing is inspected or recorded.
 */
class DevConsoleRecordingMqttPublisher private constructor(
    private val delegate: IMqttAsyncClient,
    @Suppress("UNUSED_PARAMETER") private val recorder: SocketRecorder,
    val connectionId: String,
) {
    fun publish(
        topic: String,
        message: MqttMessage,
    ): IMqttDeliveryToken = delegate.publish(topic, message)

    fun publish(
        topic: String,
        payload: ByteArray,
        qos: Int,
        retained: Boolean,
    ): IMqttDeliveryToken = delegate.publish(topic, payload, qos, retained)

    fun disconnect(): IMqttToken = delegate.disconnect()

    companion object {
        @JvmStatic
        @JvmOverloads
        fun wrap(
            delegate: IMqttAsyncClient,
            recorder: SocketRecorder,
            @Suppress("UNUSED_PARAMETER")
            connectionIdProvider: (IMqttAsyncClient) -> String = { "${it.serverURI}-${System.identityHashCode(it)}" },
        ): DevConsoleRecordingMqttPublisher = DevConsoleRecordingMqttPublisher(delegate, recorder, "")
    }
}

/** Protected-build counterpart to the full-side `DevConsolePahoMqtt`: wires the callback but records nothing. */
object DevConsolePahoMqtt {
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
