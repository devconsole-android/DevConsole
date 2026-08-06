/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.socket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttFrameMetadataTest {
    @Test
    fun `round trips a topic containing every delimiter character`() {
        val topic = "devconsole/demo;test=value%foo"
        val contentType = MqttFrameMetadata.format(topic, qos = 2, retained = true)

        assertTrue(MqttFrameMetadata.isMqtt(contentType))
        assertEquals(topic, MqttFrameMetadata.topic(contentType))
        assertEquals(2, MqttFrameMetadata.qos(contentType))
        assertEquals(true, MqttFrameMetadata.retained(contentType))
    }

    @Test
    fun `defaults qos to zero and retained to false`() {
        val contentType = MqttFrameMetadata.format("devconsole/demo")

        assertEquals(0, MqttFrameMetadata.qos(contentType))
        assertEquals(false, MqttFrameMetadata.retained(contentType))
    }

    @Test
    fun `null input never throws and reports not-mqtt`() {
        assertFalse(MqttFrameMetadata.isMqtt(null))
        assertNull(MqttFrameMetadata.topic(null))
        assertNull(MqttFrameMetadata.qos(null))
        assertNull(MqttFrameMetadata.retained(null))
    }

    @Test
    fun `garbage or non-mqtt content type returns null for every parser`() {
        assertFalse(MqttFrameMetadata.isMqtt("application/json"))
        assertNull(MqttFrameMetadata.topic("application/json; topic=x"))
        assertNull(MqttFrameMetadata.qos("not a content type at all"))
        assertNull(MqttFrameMetadata.retained(""))

        // A prefix collision must not be mistaken for the real content type.
        assertFalse(MqttFrameMetadata.isMqtt("application/mqttx"))
    }

    @Test
    fun `qos out of range or non-numeric returns null`() {
        assertNull(MqttFrameMetadata.qos("application/mqtt; topic=x; qos=3; retained=false"))
        assertNull(MqttFrameMetadata.qos("application/mqtt; topic=x; qos=abc; retained=false"))
        assertNull(MqttFrameMetadata.qos("application/mqtt; topic=x; retained=false"))
    }

    @Test
    fun `retained missing or malformed returns null`() {
        assertNull(MqttFrameMetadata.retained("application/mqtt; topic=x; qos=1"))
        assertNull(MqttFrameMetadata.retained("application/mqtt; topic=x; qos=1; retained=maybe"))
    }
}
