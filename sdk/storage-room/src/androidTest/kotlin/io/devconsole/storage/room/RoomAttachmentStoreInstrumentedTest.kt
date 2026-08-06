package io.devconsole.storage.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.devconsole.storage.api.AttachmentWriteRequest
import io.devconsole.storage.api.AttachmentWriteResult
import io.devconsole.storage.api.RedactionApplicability
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomAttachmentStoreInstrumentedTest {
    private lateinit var database: DevConsoleDatabase
    private lateinit var root: File

    @Before
    fun createStore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, DevConsoleDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        root = File(context.cacheDir, "attachment-store-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun closeStore() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun attachmentRoundTripsThroughRoomAndIntegrityCheckedFileStorage() =
        runBlocking {
            val store = RoomAttachmentStore(database, FileAttachmentStore(root), RoomRetentionCoordinator())
            val bytes = "redacted-device-payload".encodeToByteArray()
            val result =
                store.write(
                    AttachmentWriteRequest(
                        eventId = UUID.randomUUID().toString(),
                        sessionId = UUID.randomUUID().toString(),
                        mimeType = "application/octet-stream",
                        bytes = bytes,
                        isRedacted = true,
                    ),
                )

            assertTrue(result is AttachmentWriteResult.Success)
            val attachment = (result as AttachmentWriteResult.Success).attachment
            assertArrayEquals(bytes, store.read(attachment.id))

            File(root, attachment.relativePath).appendBytes(byteArrayOf(0))
            assertNull(store.read(attachment.id))
        }

    @Test
    fun rejectsUnredactedContentUnderAppliedButAcceptsAndReadsBackNotApplicableContent() =
        runBlocking {
            val store = RoomAttachmentStore(database, FileAttachmentStore(root), RoomRetentionCoordinator())
            val sessionId = UUID.randomUUID().toString()

            val rejected =
                store.write(
                    AttachmentWriteRequest(
                        eventId = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        mimeType = "application/json",
                        bytes = "Bearer secret".encodeToByteArray(),
                        isRedacted = false,
                        redactionApplicability = RedactionApplicability.APPLIED,
                    ),
                )
            assertEquals(AttachmentWriteResult.RejectedUnredactedContent, rejected)

            val screenshotBytes = "raw-screenshot-pixels".encodeToByteArray()
            val accepted =
                store.write(
                    AttachmentWriteRequest(
                        eventId = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        mimeType = "image/png",
                        bytes = screenshotBytes,
                        isRedacted = false,
                        redactionApplicability = RedactionApplicability.NOT_APPLICABLE,
                    ),
                )

            assertTrue(accepted is AttachmentWriteResult.Success)
            val attachment = (accepted as AttachmentWriteResult.Success).attachment
            assertFalse(attachment.isRedacted)
            assertEquals(RedactionApplicability.NOT_APPLICABLE, attachment.redactionApplicability)
            assertArrayEquals(screenshotBytes, store.read(attachment.id))
        }
}
