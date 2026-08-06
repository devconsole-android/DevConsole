/**
 * @author Shakib
 * @since 20/07/26
 */
package io.devconsole.storage.room

import io.devconsole.storage.api.AttachmentWriteRequest
import io.devconsole.storage.api.AttachmentWriteResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

private class FakeAttachmentDao : AttachmentDao {
    val attachments = linkedMapOf<String, AttachmentEntity>()

    // Models the pending_deletion column: a row here has been tombstoned but not yet hard-deleted.
    private val pending = mutableSetOf<String>()

    override fun insert(attachment: AttachmentEntity) {
        attachments[attachment.id] = attachment
    }

    override fun oldestUnbookmarked(): List<AttachmentEntity> =
        attachments.values
            .filter { !it.isBookmarked && it.id !in pending }
            .sortedBy { it.createdWallTimeMs }

    override fun totalStoredBytes(): Long = attachments.values.sumOf { it.storedLength }

    override fun contains(id: String): Int = if (attachments.containsKey(id)) 1 else 0

    override fun attachment(id: String): AttachmentEntity? = attachments[id]?.takeIf { it.id !in pending }

    override fun deleteById(id: String) {
        attachments.remove(id)
        pending.remove(id)
    }

    override fun markPendingDeletion(id: String): Int = if (attachments.containsKey(id) && pending.add(id)) 1 else 0

    override fun clearPendingDeletion(id: String): Int = if (pending.remove(id)) 1 else 0

    override fun pendingDeletion(): List<AttachmentEntity> =
        attachments.values.filter { it.id in pending }.sortedBy { it.createdWallTimeMs }

    override fun pendingDeletionForSession(sessionId: String): List<AttachmentEntity> =
        attachments.values
            .filter { it.id in pending && it.sessionId == sessionId }
            .sortedBy { it.createdWallTimeMs }

    override fun markSessionPendingDeletion(sessionId: String) {
        attachments.values.filter { it.sessionId == sessionId }.forEach { pending.add(it.id) }
    }

    override fun oldestUnbookmarkedForSession(sessionId: String): List<AttachmentEntity> =
        attachments.values
            .filter { it.sessionId == sessionId && !it.isBookmarked && it.id !in pending }
            .sortedBy { it.createdWallTimeMs }

    override fun deleteSession(sessionId: String) {
        attachments.values.filter { it.sessionId == sessionId }.forEach {
            attachments.remove(it.id)
            pending.remove(it.id)
        }
    }
}

class AttachmentQuotaPrunerTest {
    @Test
    fun `deletes oldest unbookmarked attachments and their files until bytes fit quota`() =
        runBlocking {
            val root = Files.createTempDirectory("devconsole-attachment-quota-test").toFile()
            try {
                val dao = FakeAttachmentDao()
                val files = FileAttachmentStore(root)
                val sessionId = UUID.randomUUID().toString()
                var keptPath = ""
                repeat(3) { index ->
                    val written =
                        files.write(
                            AttachmentWriteRequest(
                                sessionId = sessionId,
                                eventId = UUID.randomUUID().toString(),
                                mimeType = "text/plain",
                                bytes = "0123456789".encodeToByteArray(),
                                isRedacted = true,
                            ),
                        ) as AttachmentWriteResult.Success
                    dao.insert(written.attachment.toEntity(createdWallTimeMs = index.toLong()))
                    if (index == 2) keptPath = written.attachment.relativePath
                }

                val deleted = AttachmentQuotaPruner(dao, files).pruneTo(maxAttachmentBytes = 10)

                assertEquals(2, deleted)
                assertEquals(1, dao.attachments.size)
                assertTrue(File(root, keptPath).isFile)
                assertFalse(File(root, keptPath).name.isEmpty())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `does nothing when stored bytes sit exactly at the byte cap`() =
        runBlocking {
            val root = Files.createTempDirectory("devconsole-attachment-exact-cap-test").toFile()
            try {
                val dao = FakeAttachmentDao()
                val files = FileAttachmentStore(root)
                val sessionId = UUID.randomUUID().toString()
                val written =
                    files.write(
                        AttachmentWriteRequest(
                            sessionId = sessionId,
                            eventId = UUID.randomUUID().toString(),
                            mimeType = "text/plain",
                            bytes = "0123456789".encodeToByteArray(),
                            isRedacted = true,
                        ),
                    ) as AttachmentWriteResult.Success
                dao.insert(written.attachment.toEntity(createdWallTimeMs = 0))
                val exactBytes = dao.totalStoredBytes()

                val deleted = AttachmentQuotaPruner(dao, files).pruneTo(maxAttachmentBytes = exactBytes)

                assertEquals(0, deleted)
                assertEquals(1, dao.attachments.size)
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `a bookmarked attachment survives pruning even though it is the oldest and over quota`() =
        runBlocking {
            val root = Files.createTempDirectory("devconsole-attachment-bookmark-test").toFile()
            try {
                val dao = FakeAttachmentDao()
                val files = FileAttachmentStore(root)
                val sessionId = UUID.randomUUID().toString()
                val bookmarked =
                    files.write(
                        AttachmentWriteRequest(
                            sessionId = sessionId,
                            eventId = UUID.randomUUID().toString(),
                            mimeType = "text/plain",
                            bytes = "0123456789".encodeToByteArray(),
                            isRedacted = true,
                        ),
                    ) as AttachmentWriteResult.Success
                // Oldest by far, but bookmarked -- oldestUnbookmarked() must never surface it as an
                // eviction candidate even though it alone blows the quota. AttachmentEntity is a
                // plain Room Java entity (no copy()), so it is rebuilt field-by-field here.
                val bookmarkedEntity = bookmarked.attachment.toEntity(createdWallTimeMs = 0)
                dao.insert(
                    AttachmentEntity(
                        bookmarkedEntity.id,
                        bookmarkedEntity.eventId,
                        bookmarkedEntity.sessionId,
                        bookmarkedEntity.mimeType,
                        bookmarkedEntity.originalLength,
                        bookmarkedEntity.storedLength,
                        bookmarkedEntity.truncated,
                        bookmarkedEntity.sha256,
                        bookmarkedEntity.isRedacted,
                        bookmarkedEntity.relativePath,
                        bookmarkedEntity.createdWallTimeMs,
                        true,
                        bookmarkedEntity.pendingDeletion,
                        bookmarkedEntity.redactionApplicability,
                    ),
                )
                val newer =
                    files.write(
                        AttachmentWriteRequest(
                            sessionId = sessionId,
                            eventId = UUID.randomUUID().toString(),
                            mimeType = "text/plain",
                            bytes = "0123456789".encodeToByteArray(),
                            isRedacted = true,
                        ),
                    ) as AttachmentWriteResult.Success
                dao.insert(newer.attachment.toEntity(createdWallTimeMs = 100))

                val deleted = AttachmentQuotaPruner(dao, files).pruneTo(maxAttachmentBytes = 0)

                // Only the unbookmarked attachment is ever eligible, so pruning stops there even
                // though the bookmarked one leaves the store over the requested quota.
                assertEquals(1, deleted)
                assertEquals(listOf(bookmarked.attachment.id), dao.attachments.keys.toList())
                assertTrue(dao.attachments.getValue(bookmarked.attachment.id).isBookmarked)
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `multiple attachments over quota are evicted oldest first until bytes fit`() =
        runBlocking {
            val root = Files.createTempDirectory("devconsole-attachment-order-test").toFile()
            try {
                val dao = FakeAttachmentDao()
                val files = FileAttachmentStore(root)
                val sessionId = UUID.randomUUID().toString()
                val ids = mutableListOf<String>()
                repeat(4) { index ->
                    val written =
                        files.write(
                            AttachmentWriteRequest(
                                sessionId = sessionId,
                                eventId = UUID.randomUUID().toString(),
                                mimeType = "text/plain",
                                bytes = "0123456789".encodeToByteArray(),
                                isRedacted = true,
                            ),
                        ) as AttachmentWriteResult.Success
                    // Insert out of chronological order to prove eviction follows createdWallTimeMs,
                    // not insertion order.
                    dao.insert(written.attachment.toEntity(createdWallTimeMs = (3 - index).toLong()))
                    ids += written.attachment.id
                }

                // Quota fits exactly one 10-byte attachment; the three oldest-by-timestamp must go.
                val deleted = AttachmentQuotaPruner(dao, files).pruneTo(maxAttachmentBytes = 10)

                assertEquals(3, deleted)
                assertEquals(1, dao.attachments.size)
                // ids[0] was written first but stamped with the largest createdWallTimeMs (3), so it
                // is the newest by the pruner's clock (createdWallTimeMs, not insertion order) and
                // the one that survives; ids[1..3] (wallTimeMs 2, 1, 0) are the three evicted.
                assertEquals(ids[0], dao.attachments.keys.single())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `expires old unbookmarked attachments even when byte quota has room`() =
        runBlocking {
            val root = Files.createTempDirectory("devconsole-attachment-age-test").toFile()
            try {
                val dao = FakeAttachmentDao()
                val files = FileAttachmentStore(root)
                val sessionId = UUID.randomUUID().toString()
                repeat(2) { index ->
                    val written =
                        files.write(
                            AttachmentWriteRequest(
                                sessionId = sessionId,
                                eventId = UUID.randomUUID().toString(),
                                mimeType = "text/plain",
                                bytes = "safe".encodeToByteArray(),
                                isRedacted = true,
                            ),
                        ) as AttachmentWriteResult.Success
                    dao.insert(written.attachment.toEntity(createdWallTimeMs = index * 100L))
                }

                val deleted =
                    AttachmentQuotaPruner(dao, files).pruneTo(
                        maxAttachmentBytes = 1_000,
                        cutoffEpochMs = 50,
                    )

                assertEquals(1, deleted)
                assertEquals(listOf(100L), dao.attachments.values.map(AttachmentEntity::createdWallTimeMs))
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `reports every distinct session touched by eviction so their usage counters can be corrected`() =
        runBlocking {
            val root = Files.createTempDirectory("devconsole-attachment-session-report-test").toFile()
            try {
                val dao = FakeAttachmentDao()
                val files = FileAttachmentStore(root)
                val sessionA = UUID.randomUUID().toString()
                val sessionB = UUID.randomUUID().toString()
                listOf(sessionA, sessionA, sessionB).forEachIndexed { index, sessionId ->
                    val written =
                        files.write(
                            AttachmentWriteRequest(
                                sessionId = sessionId,
                                eventId = UUID.randomUUID().toString(),
                                mimeType = "text/plain",
                                bytes = "0123456789".encodeToByteArray(),
                                isRedacted = true,
                            ),
                        ) as AttachmentWriteResult.Success
                    dao.insert(written.attachment.toEntity(createdWallTimeMs = index.toLong()))
                }
                val prunedSessions = mutableListOf<String>()

                // No room for any bytes at all, so every attachment across both sessions is evicted.
                val deleted =
                    AttachmentQuotaPruner(dao, files) { prunedSessions += it }.pruneTo(maxAttachmentBytes = 0)

                assertEquals(3, deleted)
                assertEquals(setOf(sessionA, sessionB), prunedSessions.toSet())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `never reports pruning when nothing was deleted`() =
        runBlocking {
            val root = Files.createTempDirectory("devconsole-attachment-no-op-report-test").toFile()
            try {
                val dao = FakeAttachmentDao()
                val files = FileAttachmentStore(root)
                val sessionId = UUID.randomUUID().toString()
                val written =
                    files.write(
                        AttachmentWriteRequest(
                            sessionId = sessionId,
                            eventId = UUID.randomUUID().toString(),
                            mimeType = "text/plain",
                            bytes = "0123456789".encodeToByteArray(),
                            isRedacted = true,
                        ),
                    ) as AttachmentWriteResult.Success
                dao.insert(written.attachment.toEntity(createdWallTimeMs = 0))
                val prunedSessions = mutableListOf<String>()

                AttachmentQuotaPruner(dao, files) { prunedSessions += it }.pruneTo(maxAttachmentBytes = 1_000)

                assertTrue(prunedSessions.isEmpty())
            } finally {
                root.deleteRecursively()
            }
        }
}
