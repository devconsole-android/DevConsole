package io.devconsole.storage.room

import io.devconsole.storage.api.RedactionApplicability
import io.devconsole.storage.api.StoredAttachment
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class AttachmentMapperTest {
    @Test
    fun `round trips attachment metadata including redaction and ownership`() {
        val attachment =
            StoredAttachment(
                id = UUID.randomUUID().toString(),
                eventId = UUID.randomUUID().toString(),
                sessionId = UUID.randomUUID().toString(),
                mimeType = "application/json",
                originalLength = 10,
                storedLength = 8,
                truncated = true,
                sha256 = "a".repeat(64),
                isRedacted = true,
                relativePath = "session/attachments/file.bin",
                redactionApplicability = RedactionApplicability.APPLIED,
            )

        assertEquals(attachment, attachment.toEntity(100).toStoredAttachment())
    }

    @Test
    fun `a not-applicable attachment can be honestly unredacted and still round trips`() {
        val attachment =
            StoredAttachment(
                id = UUID.randomUUID().toString(),
                eventId = UUID.randomUUID().toString(),
                sessionId = UUID.randomUUID().toString(),
                mimeType = "image/png",
                originalLength = 4096,
                storedLength = 4096,
                truncated = false,
                sha256 = "b".repeat(64),
                isRedacted = false,
                relativePath = "session/attachments/screenshot.bin",
                redactionApplicability = RedactionApplicability.NOT_APPLICABLE,
            )

        assertEquals(attachment, attachment.toEntity(100).toStoredAttachment())
    }
}
