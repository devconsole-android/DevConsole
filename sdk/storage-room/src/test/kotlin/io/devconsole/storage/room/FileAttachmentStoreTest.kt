package io.devconsole.storage.room

import io.devconsole.storage.api.AttachmentWriteRequest
import io.devconsole.storage.api.AttachmentWriteResult
import io.devconsole.storage.api.RedactionApplicability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class FileAttachmentStoreTest {
    @Test
    fun `writes redacted attachment atomically under its session directory`() =
        runBlocking {
            withTemporaryDirectory { root ->
                val sessionId = UUID.randomUUID().toString()
                val result =
                    FileAttachmentStore(root).write(
                        AttachmentWriteRequest(
                            sessionId = sessionId,
                            eventId = UUID.randomUUID().toString(),
                            mimeType = "text/plain",
                            bytes = "safe body".encodeToByteArray(),
                            isRedacted = true,
                        ),
                    )

                val attachment = (result as AttachmentWriteResult.Success).attachment
                assertTrue(File(root, attachment.relativePath).isFile)
                assertEquals(9L, attachment.storedLength)
                assertFalse(attachment.truncated)
                assertFalse(File(root, attachment.relativePath).name.startsWith("."))
            }
        }

    @Test
    fun `rejects unredacted bytes before writing to disk`() =
        runBlocking {
            withTemporaryDirectory { root ->
                val result =
                    FileAttachmentStore(root).write(
                        AttachmentWriteRequest(
                            sessionId = UUID.randomUUID().toString(),
                            eventId = UUID.randomUUID().toString(),
                            mimeType = "text/plain",
                            bytes = "Bearer secret".encodeToByteArray(),
                            isRedacted = false,
                        ),
                    )

                assertEquals(AttachmentWriteResult.RejectedUnredactedContent, result)
                assertTrue(root.listFiles().isNullOrEmpty())
            }
        }

    @Test
    fun `rejects unredacted bytes even under an explicit APPLIED applicability`() =
        runBlocking {
            withTemporaryDirectory { root ->
                val result =
                    FileAttachmentStore(root).write(
                        AttachmentWriteRequest(
                            sessionId = UUID.randomUUID().toString(),
                            eventId = UUID.randomUUID().toString(),
                            mimeType = "text/plain",
                            bytes = "Bearer secret".encodeToByteArray(),
                            isRedacted = false,
                            redactionApplicability = RedactionApplicability.APPLIED,
                        ),
                    )

                assertEquals(AttachmentWriteResult.RejectedUnredactedContent, result)
            }
        }

    @Test
    fun `accepts and later reads back honestly unredacted content marked NOT_APPLICABLE`() =
        runBlocking {
            withTemporaryDirectory { root ->
                val store = FileAttachmentStore(root)
                val bytes = "raw-screenshot-pixels".encodeToByteArray()
                val result =
                    store.write(
                        AttachmentWriteRequest(
                            sessionId = UUID.randomUUID().toString(),
                            eventId = UUID.randomUUID().toString(),
                            mimeType = "image/png",
                            bytes = bytes,
                            isRedacted = false,
                            redactionApplicability = RedactionApplicability.NOT_APPLICABLE,
                        ),
                    )

                val attachment = (result as AttachmentWriteResult.Success).attachment
                assertFalse(attachment.isRedacted)
                assertEquals(RedactionApplicability.NOT_APPLICABLE, attachment.redactionApplicability)
                assertTrue(store.read(attachment)!!.contentEquals(bytes))
            }
        }

    @Test
    fun `rejects NOT_APPLICABLE for a MIME type redaction can actually reach`() =
        runBlocking {
            withTemporaryDirectory { root ->
                val result =
                    FileAttachmentStore(root).write(
                        AttachmentWriteRequest(
                            sessionId = UUID.randomUUID().toString(),
                            eventId = UUID.randomUUID().toString(),
                            mimeType = "text/plain",
                            bytes = "Bearer secret".encodeToByteArray(),
                            isRedacted = false,
                            redactionApplicability = RedactionApplicability.NOT_APPLICABLE,
                        ),
                    )

                assertEquals(AttachmentWriteResult.RejectedUnredactedContent, result)
                assertTrue(root.listFiles().isNullOrEmpty())
            }
        }

    @Test
    fun `rejects NOT_APPLICABLE for JSON-flavored and parameterized MIME types alike`() =
        runBlocking {
            withTemporaryDirectory { root ->
                val store = FileAttachmentStore(root)
                val jsonRequest =
                    AttachmentWriteRequest(
                        sessionId = UUID.randomUUID().toString(),
                        eventId = UUID.randomUUID().toString(),
                        mimeType = "application/json",
                        bytes = "{\"token\":\"secret\"}".encodeToByteArray(),
                        isRedacted = false,
                        redactionApplicability = RedactionApplicability.NOT_APPLICABLE,
                    )
                val vendorJsonRequest =
                    jsonRequest.copy(
                        eventId = UUID.randomUUID().toString(),
                        mimeType = "application/vnd.devconsole+json",
                    )
                val parameterizedTextRequest =
                    jsonRequest.copy(
                        eventId = UUID.randomUUID().toString(),
                        mimeType = "text/plain; charset=utf-8",
                    )

                assertEquals(AttachmentWriteResult.RejectedUnredactedContent, store.write(jsonRequest))
                assertEquals(AttachmentWriteResult.RejectedUnredactedContent, store.write(vendorJsonRequest))
                assertEquals(AttachmentWriteResult.RejectedUnredactedContent, store.write(parameterizedTextRequest))
            }
        }

    @Test
    fun `records truncation when attachment exceeds configured cap`() =
        runBlocking {
            withTemporaryDirectory { root ->
                val result =
                    FileAttachmentStore(root, maxAttachmentBytes = 3).write(
                        AttachmentWriteRequest(
                            sessionId = UUID.randomUUID().toString(),
                            eventId = UUID.randomUUID().toString(),
                            mimeType = "application/octet-stream",
                            bytes = byteArrayOf(1, 2, 3, 4),
                            isRedacted = true,
                        ),
                    )

                val attachment = (result as AttachmentWriteResult.Success).attachment
                assertEquals(4L, attachment.originalLength)
                assertEquals(3L, attachment.storedLength)
                assertTrue(attachment.truncated)
            }
        }

    @Test
    fun `retains source length and truncation after capture-time redaction changes byte length`() =
        runBlocking {
            withTemporaryDirectory { root ->
                val result =
                    FileAttachmentStore(root).write(
                        AttachmentWriteRequest(
                            sessionId = UUID.randomUUID().toString(),
                            eventId = UUID.randomUUID().toString(),
                            mimeType = "application/octet-stream",
                            bytes = "<redacted>".encodeToByteArray(),
                            isRedacted = true,
                        ).withSourceMetadata(originalLength = 3, truncated = true),
                    )

                val attachment = (result as AttachmentWriteResult.Success).attachment
                assertEquals(3L, attachment.originalLength)
                assertEquals(10L, attachment.storedLength)
                assertTrue(attachment.truncated)
            }
        }

    @Test
    fun `deletes every attachment in a session for session-only retention`() =
        runBlocking {
            withTemporaryDirectory { root ->
                val sessionId = UUID.randomUUID().toString()
                val store = FileAttachmentStore(root)
                val result =
                    store.write(
                        AttachmentWriteRequest(
                            sessionId = sessionId,
                            eventId = UUID.randomUUID().toString(),
                            mimeType = "text/plain",
                            bytes = "safe".encodeToByteArray(),
                            isRedacted = true,
                        ),
                    ) as AttachmentWriteResult.Success

                store.deleteSession(sessionId)

                assertFalse(File(root, result.attachment.relativePath).exists())
                assertFalse(File(root, sessionId).exists())
            }
        }

    @Test
    fun `reads only intact attachment bytes contained by the store root`() =
        runBlocking {
            withTemporaryDirectory { root ->
                val store = FileAttachmentStore(root)
                val result =
                    store.write(
                        AttachmentWriteRequest(
                            sessionId = UUID.randomUUID().toString(),
                            eventId = UUID.randomUUID().toString(),
                            mimeType = "application/octet-stream",
                            bytes = byteArrayOf(1, 2, 3),
                            isRedacted = true,
                        ),
                    ) as AttachmentWriteResult.Success

                assertTrue(store.read(result.attachment)!!.contentEquals(byteArrayOf(1, 2, 3)))

                File(root, result.attachment.relativePath).writeBytes(byteArrayOf(9, 9, 9))
                assertEquals(null, store.read(result.attachment))
                assertEquals(null, store.read(result.attachment.copy(relativePath = "../outside.bin")))
            }
        }

    private inline fun withTemporaryDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("devconsole-attachment-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
