/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.storage.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.devconsole.storage.api.EvidenceKind
import io.devconsole.storage.api.SessionRetentionPolicy
import io.devconsole.storage.api.StoredEvidenceItem
import io.devconsole.storage.api.StoredEvidenceReport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * [SessionRetentionPruner]'s full session delete runs inside a real `runInTransaction` block, which
 * a bare, non-Room-built [DevConsoleDatabase] subclass cannot execute -- that is why
 * [SessionRetentionPrunerTest]'s plain JVM fakes stay off this path entirely. This test exercises it
 * against a real, in-memory Room database instead, so an evidence item or report left pointing at a
 * session Room actually deleted would be caught here.
 */
@RunWith(AndroidJUnit4::class)
class EvidenceCascadeDeletionInstrumentedTest {
    private lateinit var database: DevConsoleDatabase
    private lateinit var root: File

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, DevConsoleDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        root = File(context.cacheDir, "evidence-cascade-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun closeDatabase() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun pruningAnExpiredCompletedSessionDeletesItsEvidenceItemsAndReport() =
        runBlocking {
            val sessionId = UUID.randomUUID().toString()
            database.sessionDao().insertIfAbsent(
                SessionEntity(sessionId, "COMPLETED", 0, 0, 0L, null, null, null, null, null, null, null, 0, 0),
            )
            val evidenceStore = RoomEvidenceStore(database)
            evidenceStore.flag(
                StoredEvidenceItem(
                    id = "evidence-1",
                    sessionId = sessionId,
                    kind = EvidenceKind.TIMELINE,
                    subjectId = "event-1",
                    label = "flagged for review",
                    flaggedAtMs = 0,
                    snapshotJson = "{}",
                ),
            )
            evidenceStore.saveReport(StoredEvidenceReport(sessionId = sessionId, summary = "notes"))

            val files = FileAttachmentStore(root)
            val policy = SessionRetentionPolicy(maxSessions = 10, maxAgeMs = 1, maxBytes = Long.MAX_VALUE / 2)
            SessionRetentionPruner(database, files).prune(policy, activeSessionId = null, nowMs = 10_000)

            // The session itself is gone, and no evidence row survives pointing at it -- an orphaned
            // evidence item or report referencing a pruned session is exactly the bug this wiring
            // exists to prevent.
            assertNull(database.sessionDao().session(sessionId))
            assertTrue(evidenceStore.items(sessionId).isEmpty())
            assertEquals(StoredEvidenceReport(sessionId = sessionId), evidenceStore.report(sessionId))
        }
}
