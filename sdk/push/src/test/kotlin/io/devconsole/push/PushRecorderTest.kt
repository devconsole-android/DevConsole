package io.devconsole.push
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRecorderTest {
    @Test
    fun `redacts push data before recording`() {
        val recorder = PushRecorder(RedactionEngine(RedactionPolicy.default()))
        val event = recorder.record(PushInput("fcm", mapOf("access_token" to "raw")))
        assertEquals("<redacted>", event.data.getValue("access_token"))
    }

    @Test
    fun `disabled recorder never redacts or stores an event`() {
        val store = InMemoryPushStore()
        val recorder = PushRecorder(RedactionEngine(RedactionPolicy.default()), store, enabled = false)

        recorder.record(PushInput("fcm", mapOf("access_token" to "raw")))

        assertTrue(store.events().isEmpty())
    }
}
