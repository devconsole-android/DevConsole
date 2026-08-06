package io.devconsole.storage.room

import io.devconsole.storage.api.StoredEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class EventMapperTest {
    @Test
    fun `maps all event schema fields without losing redacted payload references`() {
        val source =
            StoredEvent(
                id = "event-1",
                sessionId = "session-1",
                sequence = 7,
                pluginId = "network",
                type = "network.transaction",
                wallTimeMs = 11,
                monoTimeNs = 12,
                severity = 3,
                summary = "<redacted>",
                correlationId = "trace-1",
                tagsJson = "{\"route\":\"/orders\"}",
                payloadJson = "{\"body\":\"<redacted>\"}",
                attachmentId = "attachment-1",
            )

        assertEquals(source, source.toEntity().toStoredEvent())
    }
}
