package io.devconsole.push.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseRemoteMessageAdapterNoopTest {
    @Test
    fun `does not reflect on or serialize the supplied remote message`() {
        val input = FirebaseRemoteMessageAdapter().toPushInput(HostileRemoteMessage())

        assertEquals("fcm", input.provider)
        assertEquals("disabled-build", input.source)
        assertTrue(input.data.isEmpty())
        assertTrue(input.rawMetadata.isEmpty())
    }

    private class HostileRemoteMessage {
        fun getData(): Map<String, String> = error("message data must not be inspected")

        fun getMessageId(): String = error("message id must not be inspected")

        fun getSentTime(): Long = error("message time must not be inspected")
    }
}
