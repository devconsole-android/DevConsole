/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.socket.paho

import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.socket.InMemorySocketStore
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
import org.junit.Assert.assertTrue
import org.junit.Test

class DevConsolePahoMqttNoopTest {
    @Test
    fun `wrap and install never touch the client's identity, connect, or subscribe surface`() {
        val store = InMemorySocketStore()
        val recorder = SocketRecorder(RedactionEngine(RedactionPolicy.default()), store)
        val client = FakeMqttAsyncClient()
        var identityReads = 0

        val publisher =
            DevConsoleRecordingMqttPublisher.wrap(client, recorder) {
                identityReads += 1
                "must-not-be-used"
            }

        assertEquals(0, identityReads)
        assertEquals("", publisher.connectionId)
        assertTrue(store.connections().isEmpty())
    }

    @Test
    fun `install wires the callback through and publish still delegates without recording`() {
        val store = InMemorySocketStore()
        val recorder = SocketRecorder(RedactionEngine(RedactionPolicy.default()), store)
        val client = FakeMqttAsyncClient()
        val delivered = mutableListOf<String>()
        var connectCompleteCalls = 0
        val hostDelegate =
            object : MqttCallbackExtended {
                override fun connectComplete(
                    reconnect: Boolean,
                    serverURI: String,
                ) {
                    connectCompleteCalls += 1
                }

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

        val token = publisher.publish("devconsole/demo/hello", "hi".toByteArray(), 1, false)
        callback.messageArrived("devconsole/demo/hello", MqttMessage("Bearer canary-secret".toByteArray()))
        callback.connectComplete(false, "tcp://broker.test:1883")
        callback.connectionLost(IllegalStateException("dropped"))
        callback.deliveryComplete(null)

        assertEquals(client.lastToken, token)
        assertEquals(1, client.publishCount)
        assertEquals(listOf("devconsole/demo/hello"), delivered)
        assertEquals(1, connectCompleteCalls)
        assertTrue(store.connections().isEmpty())
    }

    /** Every method other than [setCallback] and [publish] errors if touched by `wrap`/`install`. */
    private class FakeMqttAsyncClient : IMqttAsyncClient {
        var callback: MqttCallbackExtended? = null
        var lastToken: IMqttDeliveryToken? = null
        var publishCount = 0

        override fun setCallback(callback: MqttCallback) {
            this.callback = callback as MqttCallbackExtended
        }

        override fun publish(
            topic: String,
            payload: ByteArray,
            qos: Int,
            retained: Boolean,
        ): IMqttDeliveryToken {
            publishCount += 1
            return FakeDeliveryToken().also { lastToken = it }
        }

        override fun getServerURI(): String = error("serverURI must not be read")

        override fun getClientId(): String = error("clientId must not be read")

        override fun isConnected(): Boolean = error("isConnected must not be read")

        override fun connect(): IMqttToken = error("connect must not be called")

        override fun connect(options: MqttConnectOptions): IMqttToken = error("connect must not be called")

        override fun connect(
            userContext: Any?,
            callback: IMqttActionListener?,
        ): IMqttToken = error("connect must not be called")

        override fun connect(
            options: MqttConnectOptions,
            userContext: Any?,
            callback: IMqttActionListener?,
        ): IMqttToken = error("connect must not be called")

        override fun disconnect(): IMqttToken = error("disconnect must not be called")

        override fun disconnect(quiesceTimeout: Long): IMqttToken = error("disconnect must not be called")

        override fun disconnect(
            userContext: Any?,
            callback: IMqttActionListener?,
        ): IMqttToken = error("disconnect must not be called")

        override fun disconnect(
            quiesceTimeout: Long,
            userContext: Any?,
            callback: IMqttActionListener?,
        ): IMqttToken = error("disconnect must not be called")

        override fun disconnectForcibly(): Unit = error("disconnectForcibly must not be called")

        override fun disconnectForcibly(disconnectTimeout: Long): Unit = error("disconnectForcibly must not be called")

        override fun disconnectForcibly(
            quiesceTimeout: Long,
            disconnectTimeout: Long,
        ): Unit = error("disconnectForcibly must not be called")

        override fun publish(
            topic: String,
            payload: ByteArray,
            qos: Int,
            retained: Boolean,
            userContext: Any?,
            callback: IMqttActionListener?,
        ): IMqttDeliveryToken = error("publish with userContext must not be called")

        override fun publish(
            topic: String,
            message: MqttMessage,
        ): IMqttDeliveryToken = error("publish(MqttMessage) must not be called")

        override fun publish(
            topic: String,
            message: MqttMessage,
            userContext: Any?,
            callback: IMqttActionListener?,
        ): IMqttDeliveryToken = error("publish with userContext must not be called")

        override fun subscribe(
            topicFilter: String,
            qos: Int,
        ): IMqttToken = error("subscribe must not be called")

        override fun subscribe(
            topicFilter: String,
            qos: Int,
            userContext: Any?,
            callback: IMqttActionListener?,
        ): IMqttToken = error("subscribe must not be called")

        override fun subscribe(
            topicFilters: Array<String>,
            qos: IntArray,
        ): IMqttToken = error("subscribe must not be called")

        override fun subscribe(
            topicFilters: Array<String>,
            qos: IntArray,
            userContext: Any?,
            callback: IMqttActionListener?,
        ): IMqttToken = error("subscribe must not be called")

        override fun subscribe(
            topicFilter: String,
            qos: Int,
            userContext: Any?,
            callback: IMqttActionListener?,
            messageListener: IMqttMessageListener,
        ): IMqttToken = error("subscribe must not be called")

        override fun subscribe(
            topicFilter: String,
            qos: Int,
            messageListener: IMqttMessageListener,
        ): IMqttToken = error("subscribe must not be called")

        override fun subscribe(
            topicFilters: Array<String>,
            qos: IntArray,
            messageListeners: Array<IMqttMessageListener>,
        ): IMqttToken = error("subscribe must not be called")

        override fun subscribe(
            topicFilters: Array<String>,
            qos: IntArray,
            userContext: Any?,
            callback: IMqttActionListener?,
            messageListeners: Array<IMqttMessageListener>,
        ): IMqttToken = error("subscribe must not be called")

        override fun unsubscribe(topicFilter: String): IMqttToken = error("unsubscribe must not be called")

        override fun unsubscribe(topicFilters: Array<String>): IMqttToken = error("unsubscribe must not be called")

        override fun unsubscribe(
            topicFilter: String,
            userContext: Any?,
            callback: IMqttActionListener?,
        ): IMqttToken = error("unsubscribe must not be called")

        override fun unsubscribe(
            topicFilters: Array<String>,
            userContext: Any?,
            callback: IMqttActionListener?,
        ): IMqttToken = error("unsubscribe must not be called")

        override fun removeMessage(token: IMqttDeliveryToken?): Boolean = error("removeMessage must not be called")

        override fun getPendingDeliveryTokens(): Array<IMqttDeliveryToken> =
            error("getPendingDeliveryTokens must not be called")

        override fun setManualAcks(manualAcks: Boolean): Unit = error("setManualAcks must not be called")

        override fun reconnect(): Unit = error("reconnect must not be called")

        override fun messageArrivedComplete(
            messageId: Int,
            qos: Int,
        ): Unit = error("messageArrivedComplete must not be called")

        override fun setBufferOpts(bufferOpts: DisconnectedBufferOptions): Unit =
            error("setBufferOpts must not be called")

        override fun getBufferedMessageCount(): Int = error("getBufferedMessageCount must not be called")

        override fun getBufferedMessage(bufferIndex: Int): MqttMessage = error("getBufferedMessage must not be called")

        override fun deleteBufferedMessage(bufferIndex: Int): Unit = error("deleteBufferedMessage must not be called")

        override fun getInFlightMessageCount(): Int = error("getInFlightMessageCount must not be called")

        override fun close(): Unit = error("close must not be called")
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
