/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.socket.paho

import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.socket.InMemorySocketStore
import io.devconsole.socket.MqttFrameMetadata
import io.devconsole.socket.SocketDirection
import io.devconsole.socket.SocketPayload
import io.devconsole.socket.SocketProtocol
import io.devconsole.socket.SocketRecorder
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttMessageListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevConsolePahoMqttTest {
    @Test
    fun `received message with a secret is stored redacted, tagged mqtt, and the topic round-trips`() {
        val store = InMemorySocketStore()
        val recorder = SocketRecorder(RedactionEngine(RedactionPolicy.default()), store)
        val client = FakeMqttAsyncClient()
        val delivered = mutableListOf<String>()
        val hostDelegate =
            object : MqttCallback {
                override fun connectionLost(cause: Throwable?) = Unit

                override fun messageArrived(
                    topic: String,
                    message: MqttMessage,
                ) {
                    delivered += topic
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            }

        val publisher = DevConsolePahoMqtt.install(client, recorder, hostDelegate)
        val callback = requireNotNull(client.callback)

        callback.messageArrived(
            "devconsole/demo/hello",
            MqttMessage("Bearer socket-secret".toByteArray()).apply { qos = 1 },
        )

        val connection = requireNotNull(store.connection(publisher.connectionId))
        assertEquals(SocketProtocol.MQTT, connection.protocol)

        val stored = connection.messages.single()
        val preview = (stored.payload as SocketPayload.Text).preview
        assertFalse(preview.contains("socket-secret"))
        assertEquals("devconsole/demo/hello", MqttFrameMetadata.topic(stored.contentType))
        assertEquals(1, MqttFrameMetadata.qos(stored.contentType))

        // the host's own callback must still see the untouched message
        assertEquals(listOf("devconsole/demo/hello"), delivered)
    }

    @Test
    fun `publish stores exactly one sent message and returns the delegate token`() {
        val store = InMemorySocketStore()
        val recorder = SocketRecorder(RedactionEngine(RedactionPolicy.default()), store)
        val client = FakeMqttAsyncClient()
        val publisher = DevConsolePahoMqtt.install(client, recorder)

        val token = publisher.publish("devconsole/demo/hello", "hi".toByteArray(), 1, false)

        assertEquals(client.lastToken, token)
        val messages = store.messages(publisher.connectionId)
        assertEquals(1, messages.size)
        assertEquals(SocketDirection.SENT, messages.single().direction)
    }

    @Test
    fun `host callbacks fire for connect complete, connection lost and delivery complete`() {
        val store = InMemorySocketStore()
        val recorder = SocketRecorder(RedactionEngine(RedactionPolicy.default()), store)
        val client = FakeMqttAsyncClient()
        var connectCompleteCalls = 0
        var connectionLostCalls = 0
        var deliveryCompleteCalls = 0
        val hostDelegate =
            object : MqttCallbackExtended {
                override fun connectComplete(
                    reconnect: Boolean,
                    serverURI: String,
                ) {
                    connectCompleteCalls += 1
                }

                override fun connectionLost(cause: Throwable?) {
                    connectionLostCalls += 1
                }

                override fun messageArrived(
                    topic: String,
                    message: MqttMessage,
                ) = Unit

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    deliveryCompleteCalls += 1
                }
            }

        val publisher = DevConsolePahoMqtt.install(client, recorder, hostDelegate)
        val callback = requireNotNull(client.callback)

        callback.connectComplete(false, client.serverURI)
        callback.connectionLost(IllegalStateException("connection dropped"))
        callback.deliveryComplete(null)

        assertEquals(1, connectCompleteCalls)
        assertEquals(1, connectionLostCalls)
        assertEquals(1, deliveryCompleteCalls)

        val connection = requireNotNull(store.connection(publisher.connectionId))
        assertNotNull(connection)
        assertTrue(connection.messages.isEmpty())
    }

    private class FakeMqttAsyncClient : IMqttAsyncClient {
        var callback: MqttCallbackExtended? = null
        var lastToken: IMqttDeliveryToken? = null

        override fun setCallback(callback: MqttCallback) {
            this.callback = callback as MqttCallbackExtended
        }

        override fun getServerURI(): String = "tcp://broker.test:1883"

        override fun getClientId(): String = "fake-client"

        override fun isConnected(): Boolean = true

        override fun connect(): IMqttToken = FakeToken()

        override fun connect(options: MqttConnectOptions): IMqttToken = FakeToken()

        override fun connect(
            userContext: Any?,
            callback: IMqttActionListener?,
        ): IMqttToken = FakeToken()

        override fun connect(
            options: MqttConnectOptions,
            userContext: Any?,
            callback: IMqttActionListener?,
        ): IMqttToken = FakeToken()

        override fun disconnect(): IMqttToken = FakeToken()

        override fun disconnect(quiesceTimeout: Long): IMqttToken = FakeToken()

        override fun disconnect(
            userContext: Any?,
            callback: IMqttActionListener?,
        ): IMqttToken = FakeToken()

        override fun disconnect(
            quiesceTimeout: Long,
            userContext: Any?,
            callback: IMqttActionListener?,
        ): IMqttToken = FakeToken()

        override fun disconnectForcibly() = Unit

        override fun disconnectForcibly(disconnectTimeout: Long) = Unit

        override fun disconnectForcibly(
            quiesceTimeout: Long,
            disconnectTimeout: Long,
        ) = Unit

        override fun publish(
            topic: String,
            payload: ByteArray,
            qos: Int,
            retained: Boolean,
        ): IMqttDeliveryToken = FakeDeliveryToken().also { lastToken = it }

        override fun publish(
            topic: String,
            payload: ByteArray,
            qos: Int,
            retained: Boolean,
            userContext: Any?,
            callback: IMqttActionListener?,
        ): IMqttDeliveryToken = FakeDeliveryToken().also { lastToken = it }

        override fun publish(
            topic: String,
            message: MqttMessage,
        ): IMqttDeliveryToken = FakeDeliveryToken().also { lastToken = it }

        override fun publish(
            topic: String,
            message: MqttMessage,
            userContext: Any?,
            callback: IMqttActionListener?,
        ): IMqttDeliveryToken = FakeDeliveryToken().also { lastToken = it }

        override fun subscribe(
            topicFilter: String,
            qos: Int,
        ): IMqttToken = FakeToken()

        override fun subscribe(
            topicFilter: String,
            qos: Int,
            userContext: Any?,
            callback: IMqttActionListener?,
        ): IMqttToken = FakeToken()

        override fun subscribe(
            topicFilters: Array<String>,
            qos: IntArray,
        ): IMqttToken = FakeToken()

        override fun subscribe(
            topicFilters: Array<String>,
            qos: IntArray,
            userContext: Any?,
            callback: IMqttActionListener?,
        ): IMqttToken = FakeToken()

        override fun subscribe(
            topicFilter: String,
            qos: Int,
            userContext: Any?,
            callback: IMqttActionListener?,
            messageListener: IMqttMessageListener,
        ): IMqttToken = FakeToken()

        override fun subscribe(
            topicFilter: String,
            qos: Int,
            messageListener: IMqttMessageListener,
        ): IMqttToken = FakeToken()

        override fun subscribe(
            topicFilters: Array<String>,
            qos: IntArray,
            messageListeners: Array<IMqttMessageListener>,
        ): IMqttToken = FakeToken()

        override fun subscribe(
            topicFilters: Array<String>,
            qos: IntArray,
            userContext: Any?,
            callback: IMqttActionListener?,
            messageListeners: Array<IMqttMessageListener>,
        ): IMqttToken = FakeToken()

        override fun unsubscribe(topicFilter: String): IMqttToken = FakeToken()

        override fun unsubscribe(topicFilters: Array<String>): IMqttToken = FakeToken()

        override fun unsubscribe(
            topicFilter: String,
            userContext: Any?,
            callback: IMqttActionListener?,
        ): IMqttToken = FakeToken()

        override fun unsubscribe(
            topicFilters: Array<String>,
            userContext: Any?,
            callback: IMqttActionListener?,
        ): IMqttToken = FakeToken()

        override fun removeMessage(token: IMqttDeliveryToken?): Boolean = false

        override fun getPendingDeliveryTokens(): Array<IMqttDeliveryToken> = emptyArray()

        override fun setManualAcks(manualAcks: Boolean) = Unit

        override fun reconnect() = Unit

        override fun messageArrivedComplete(
            messageId: Int,
            qos: Int,
        ) = Unit

        override fun setBufferOpts(bufferOpts: DisconnectedBufferOptions) = Unit

        override fun getBufferedMessageCount(): Int = 0

        override fun getBufferedMessage(bufferIndex: Int): MqttMessage = MqttMessage()

        override fun deleteBufferedMessage(bufferIndex: Int) = Unit

        override fun getInFlightMessageCount(): Int = 0

        override fun close() = Unit
    }

    private class FakeToken : IMqttToken {
        override fun waitForCompletion() = Unit

        override fun waitForCompletion(timeout: Long) = Unit

        override fun isComplete(): Boolean = true

        override fun getException() = null

        override fun setActionCallback(listener: IMqttActionListener?) = Unit

        override fun getActionCallback(): IMqttActionListener? = null

        override fun getClient(): IMqttAsyncClient? = null

        override fun getTopics(): Array<String> = emptyArray()

        override fun setUserContext(userContext: Any?) = Unit

        override fun getUserContext(): Any? = null

        override fun getMessageId(): Int = 0

        override fun getGrantedQos(): IntArray = IntArray(0)

        override fun getSessionPresent(): Boolean = false

        override fun getResponse() = null
    }

    private class FakeDeliveryToken : IMqttDeliveryToken {
        override fun getMessage(): MqttMessage = MqttMessage()

        override fun waitForCompletion() = Unit

        override fun waitForCompletion(timeout: Long) = Unit

        override fun isComplete(): Boolean = true

        override fun getException() = null

        override fun setActionCallback(listener: IMqttActionListener?) = Unit

        override fun getActionCallback(): IMqttActionListener? = null

        override fun getClient(): IMqttAsyncClient? = null

        override fun getTopics(): Array<String> = emptyArray()

        override fun setUserContext(userContext: Any?) = Unit

        override fun getUserContext(): Any? = null

        override fun getMessageId(): Int = 0

        override fun getGrantedQos(): IntArray = IntArray(0)

        override fun getSessionPresent(): Boolean = false

        override fun getResponse() = null
    }
}
