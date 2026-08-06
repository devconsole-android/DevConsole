package io.devconsole.push

import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushEventStoreTest {
    @Test
    fun `records generic lifecycle payloads with redaction and explicit simulation labeling`() {
        val store = InMemoryPushStore(capacity = 2)
        val recorder = PushRecorder(RedactionEngine(RedactionPolicy.default()), store)

        val event =
            recorder.record(
                PushInput(
                    provider = "fcm",
                    data = mapOf("access_token" to "raw-secret"),
                    messageId = "message-1",
                    source = "notification",
                    sentAtEpochMs = 10,
                    receivedAtEpochMs = 20,
                    notification = PushNotification(title = "New order", body = "Order #42"),
                    rawMetadata = mapOf("authorization" to "Bearer raw-secret"),
                    lifecycle = PushLifecycle.OPENED,
                    simulated = true,
                ),
            )

        assertEquals(PushLifecycle.OPENED, event.lifecycle)
        assertTrue(event.simulated)
        assertEquals("<redacted>", event.data.getValue("access_token"))
        assertEquals("<redacted>", event.rawMetadata.getValue("authorization"))
        assertEquals(listOf(event), store.events())
    }
}
