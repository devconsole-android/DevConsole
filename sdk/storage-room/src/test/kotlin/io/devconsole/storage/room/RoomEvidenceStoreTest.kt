/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.storage.room

import io.devconsole.storage.api.EvidenceKind
import io.devconsole.storage.api.EvidenceSeverity
import io.devconsole.storage.api.EvidenceWriteResult
import io.devconsole.storage.api.StoredEvidenceItem
import io.devconsole.storage.api.StoredEvidenceReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomEvidenceStoreTest {
    @Test
    fun `flags, lists, unflags, and clears items for a session`() =
        runBlocking {
            val store = RoomEvidenceStore(FakeEvidenceItemDao(), FakeEvidenceReportDao())
            val item = evidenceItem(subjectId = "network-1")

            val flagged = store.flag(item)

            assertEquals(EvidenceWriteResult.Success(item), flagged)
            assertEquals(listOf(item), store.items(item.sessionId))

            store.unflag(item.sessionId, item.kind, item.subjectId)
            assertTrue(store.items(item.sessionId).isEmpty())

            store.flag(item)
            store.flag(item.copy(id = "evidence-2", subjectId = "network-2"))
            store.clear(item.sessionId)
            assertTrue(store.items(item.sessionId).isEmpty())
        }

    @Test
    fun `re-flagging the same subject returns AlreadyFlagged instead of a duplicate row`() =
        runBlocking {
            val store = RoomEvidenceStore(FakeEvidenceItemDao(), FakeEvidenceReportDao())
            val item = evidenceItem(subjectId = "network-1")
            store.flag(item)

            val second = store.flag(item.copy(id = "evidence-other-id"))

            assertEquals(EvidenceWriteResult.AlreadyFlagged, second)
            assertEquals(1, store.items(item.sessionId).size)
        }

    @Test
    fun `the 201st distinct item for one session is refused with QuotaExceeded`() =
        runBlocking {
            val store = RoomEvidenceStore(FakeEvidenceItemDao(), FakeEvidenceReportDao())
            val sessionId = "session-1"
            repeat(200) { index ->
                val result = store.flag(evidenceItem(id = "evidence-$index", subjectId = "subject-$index"))
                assertTrue(result is EvidenceWriteResult.Success)
            }

            val overQuota = store.flag(evidenceItem(id = "evidence-200", subjectId = "subject-200"))

            assertEquals(EvidenceWriteResult.QuotaExceeded, overQuota)
            assertEquals(200, store.items(sessionId).size)
        }

    // ============================================================================================
    // Finding 2 -- flag()'s check-then-insert must be atomic, and a race that reaches the unique
    // index anyway must surface as AlreadyFlagged, never Unavailable.
    // ============================================================================================

    @Test
    fun `concurrent duplicate flags for the same subject never surface as Unavailable`() =
        runBlocking(Dispatchers.Default) {
            val store = RoomEvidenceStore(FakeEvidenceItemDao(), FakeEvidenceReportDao())
            val item = evidenceItem(subjectId = "network-1")

            val results =
                (0 until 20)
                    .map { index -> async { store.flag(item.copy(id = "evidence-$index")) } }
                    .awaitAll()

            val successes = results.count { it is EvidenceWriteResult.Success }
            assertEquals("exactly one racer should win the insert", 1, successes)
            assertEquals(
                "every other racer must be told the subject is already flagged",
                19,
                results.count { it == EvidenceWriteResult.AlreadyFlagged },
            )
            assertTrue(
                "a concurrent duplicate must never be reported as an unavailable store -- that is the bug",
                results.none { it == EvidenceWriteResult.Unavailable },
            )
            assertEquals(1, store.items(item.sessionId).size)
        }

    @Test
    fun `the 200-item quota holds under concurrent flags for distinct subjects`() =
        runBlocking(Dispatchers.Default) {
            val store = RoomEvidenceStore(FakeEvidenceItemDao(), FakeEvidenceReportDao())
            val sessionId = "session-1"

            val results =
                (0 until 210)
                    .map { index ->
                        val subjectId = "subject-$index"
                        val subject = evidenceItem(id = "evidence-$index", sessionId = sessionId, subjectId = subjectId)
                        async { store.flag(subject) }
                    }.awaitAll()

            assertEquals(200, results.count { it is EvidenceWriteResult.Success })
            assertEquals(10, results.count { it == EvidenceWriteResult.QuotaExceeded })
            assertTrue(results.none { it == EvidenceWriteResult.Unavailable })
            assertEquals("the quota must never be exceeded, even under concurrency", 200, store.items(sessionId).size)
        }

    @Test
    fun `a unique constraint violation reaching insert is mapped to AlreadyFlagged, not Unavailable`() =
        runBlocking {
            // A DAO whose existsCount always claims the subject is free -- reproducing the exact
            // stale-read window flagMutex now closes for a single RoomEvidenceStore instance, so this
            // proves the fallback mapping at the unique index still holds for whatever writer is not
            // covered by that mutex (a second store instance, a future caller).
            val backing = FakeEvidenceItemDao()
            val staleReadDao =
                object : EvidenceItemDao by backing {
                    override fun existsCount(
                        sessionId: String,
                        kind: String,
                        subjectId: String,
                    ): Int = 0
                }
            val store = RoomEvidenceStore(staleReadDao, FakeEvidenceReportDao())
            val item = evidenceItem(subjectId = "network-1")
            store.flag(item)

            val second = store.flag(item.copy(id = "evidence-2"))

            assertEquals(EvidenceWriteResult.AlreadyFlagged, second)
            assertEquals(1, store.items(item.sessionId).size)
        }

    @Test
    fun `a snapshot over the 256 KiB cap is truncated and explicitly marked`() =
        runBlocking {
            val store = RoomEvidenceStore(FakeEvidenceItemDao(), FakeEvidenceReportDao())
            val oversized = "x".repeat(300 * 1024)

            val result = store.flag(evidenceItem(subjectId = "network-1", snapshotJson = oversized))

            val stored = (result as EvidenceWriteResult.Success).item
            assertTrue(stored.snapshotJson.toByteArray(Charsets.UTF_8).size <= 256 * 1024)
            assertTrue(stored.snapshotJson.contains("\"truncated\":true"))
        }

    @Test
    fun `a report draft round trips including severity`() =
        runBlocking {
            val store = RoomEvidenceStore(FakeEvidenceItemDao(), FakeEvidenceReportDao())
            val report =
                StoredEvidenceReport(
                    sessionId = "session-1",
                    severity = EvidenceSeverity.BLOCKER,
                    summary = "crashes on submit",
                    expected = "order confirmation",
                    actual = "app crash",
                    updatedAtMs = 42,
                )

            store.saveReport(report)

            assertEquals(report, store.report("session-1"))
        }

    @Test
    fun `an unsaved session reports a fresh default draft rather than null`() =
        runBlocking {
            val store = RoomEvidenceStore(FakeEvidenceItemDao(), FakeEvidenceReportDao())

            val report = store.report("session-without-a-draft")

            assertEquals(StoredEvidenceReport(sessionId = "session-without-a-draft"), report)
        }

    @Test
    fun `deleting a session removes its items and its report draft`() =
        runBlocking {
            val store = RoomEvidenceStore(FakeEvidenceItemDao(), FakeEvidenceReportDao())
            val item = evidenceItem(subjectId = "network-1")
            store.flag(item)
            store.saveReport(StoredEvidenceReport(sessionId = item.sessionId, summary = "notes"))

            store.deleteSession(item.sessionId)

            assertTrue(store.items(item.sessionId).isEmpty())
            assertEquals(StoredEvidenceReport(sessionId = item.sessionId), store.report(item.sessionId))
        }

    private fun evidenceItem(
        id: String = "evidence-1",
        sessionId: String = "session-1",
        subjectId: String,
        snapshotJson: String = "{}",
    ) = StoredEvidenceItem(
        id = id,
        sessionId = sessionId,
        kind = EvidenceKind.NETWORK,
        subjectId = subjectId,
        label = "GET /orders",
        flaggedAtMs = 1_000,
        snapshotJson = snapshotJson,
    )
}

private class FakeEvidenceItemDao : EvidenceItemDao {
    private val items = linkedMapOf<String, EvidenceItemEntity>()

    override fun existsCount(
        sessionId: String,
        kind: String,
        subjectId: String,
    ): Int = items.values.count { it.sessionId == sessionId && it.kind == kind && it.subjectId == subjectId }

    override fun countForSession(sessionId: String): Int = items.values.count { it.sessionId == sessionId }

    /** Mirrors `index_evidence_items_subject` + `OnConflictStrategy.ABORT`: same shape real Room throws. */
    @Suppress("TooGenericExceptionThrown")
    override fun insert(item: EvidenceItemEntity) {
        val duplicate =
            items.values.any {
                it.sessionId == item.sessionId && it.kind == item.kind && it.subjectId == item.subjectId
            }
        if (duplicate) {
            throw RuntimeException(
                "UNIQUE constraint failed: evidence_items.session_id, evidence_items.kind, " +
                    "evidence_items.subject_id (code 1555 SQLITE_CONSTRAINT_UNIQUE)",
            )
        }
        items[item.id] = item
    }

    override fun items(sessionId: String): List<EvidenceItemEntity> =
        items.values.filter { it.sessionId == sessionId }.sortedBy { it.flaggedAtMs }

    override fun delete(
        sessionId: String,
        kind: String,
        subjectId: String,
    ) {
        items.values
            .filter { it.sessionId == sessionId && it.kind == kind && it.subjectId == subjectId }
            .forEach { items.remove(it.id) }
    }

    override fun deleteSession(sessionId: String) {
        items.values.filter { it.sessionId == sessionId }.forEach { items.remove(it.id) }
    }
}

private class FakeEvidenceReportDao : EvidenceReportDao {
    private val reports = linkedMapOf<String, EvidenceReportEntity>()

    override fun report(sessionId: String): EvidenceReportEntity? = reports[sessionId]

    override fun upsert(report: EvidenceReportEntity) {
        reports[report.sessionId] = report
    }

    override fun deleteSession(sessionId: String) {
        reports.remove(sessionId)
    }
}
