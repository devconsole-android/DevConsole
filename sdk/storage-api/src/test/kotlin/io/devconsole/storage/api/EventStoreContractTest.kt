package io.devconsole.storage.api

import org.junit.Assert.assertEquals
import org.junit.Test

class EventStoreContractTest {
    @Test
    fun `stored event defaults to an empty tag object and current schema`() {
        val event =
            StoredEvent(
                id = "event-1",
                sessionId = "session-1",
                sequence = 1,
                pluginId = "system",
                type = "system.event",
                wallTimeMs = 1,
                monoTimeNs = 1,
                severity = 1,
                summary = "ready",
            )

        assertEquals("{}", event.tagsJson)
        assertEquals(1, event.schemaVersion)
    }
}
