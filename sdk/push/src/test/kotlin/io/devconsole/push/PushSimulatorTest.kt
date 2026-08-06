package io.devconsole.push

import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushSimulatorTest {
    @Test
    fun `simulation invokes only local callback and records a simulated outcome`() {
        var callbackReceived = false
        val store = InMemoryPushStore()
        val simulator =
            PushSimulator(
                callback =
                    PushSimulationCallback { input ->
                        callbackReceived = input.simulated
                        PushLifecycle.DISPLAYED
                    },
                recorder = PushRecorder(RedactionEngine(RedactionPolicy.default()), store),
            )

        val event = simulator.simulate(PushInput(provider = "local", data = mapOf("id" to "42")))

        assertTrue(callbackReceived)
        assertTrue(event.simulated)
        assertEquals(PushLifecycle.DISPLAYED, event.lifecycle)
        assertEquals(event, store.events().single())
    }
}
