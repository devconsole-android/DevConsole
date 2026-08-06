package io.devconsole.push.firebase

import org.junit.Assert.assertEquals
import org.junit.Test

class FirebaseRemoteMessageAdapterTest {
    @Test
    fun `converts Firebase-shaped objects without a Firebase compile dependency`() {
        val adapted =
            FirebaseRemoteMessageAdapter().toPushInput(
                FakeRemoteMessage("message-1", mapOf("order" to "42"), 99L),
            )

        assertEquals("fcm", adapted.provider)
        assertEquals("message-1", adapted.messageId)
        assertEquals("42", adapted.data.getValue("order"))
        assertEquals(99L, adapted.sentAtEpochMs)
    }

    private class FakeRemoteMessage(
        private val id: String,
        private val payload: Map<String, String>,
        private val sent: Long,
    ) {
        fun getMessageId(): String = id

        fun getData(): Map<String, String> = payload

        fun getSentTime(): Long = sent
    }
}
