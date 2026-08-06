/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.storage.api

/** What a flagged evidence item was captured from. */
enum class EvidenceKind { NETWORK, TIMELINE, SOCKET, PUSH, SCREENSHOT, CRASH }

/** QA-assigned impact of one evidence report, independent of any tracker's own severity scale. */
enum class EvidenceSeverity { BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL }

/**
 * One flagged QA item, durable across a browser refresh and an app restart.
 *
 * [snapshotJson] is the whole point of this type: it is materialized once, at flag time, from the
 * same already-redacted sources the detail endpoints use. It is never re-derived from whatever a
 * client happens to still be holding, which is the defect this store exists to fix -- a flagged
 * network transaction used to degrade to a bare label the moment the dashboard's list moved on.
 * Absent fields inside it are omitted, not defaulted; nothing here is fabricated to fill a gap.
 */
data class StoredEvidenceItem(
    val id: String,
    val sessionId: String,
    val kind: EvidenceKind,
    val subjectId: String,
    val label: String,
    val flaggedAtMs: Long,
    val snapshotJson: String,
    val attachmentId: String? = null,
)

/**
 * The typed bug-report draft attached to one session's evidence tray. A session has at most one;
 * saving replaces the prior draft rather than appending to a history.
 */
data class StoredEvidenceReport(
    val sessionId: String,
    val severity: EvidenceSeverity = EvidenceSeverity.MAJOR,
    val summary: String? = null,
    val expected: String? = null,
    val actual: String? = null,
    val updatedAtMs: Long = 0L,
)

sealed interface EvidenceWriteResult {
    data class Success(
        val item: StoredEvidenceItem,
    ) : EvidenceWriteResult

    /** The same (session, kind, subject) triple is already flagged; flagging is idempotent per subject. */
    data object AlreadyFlagged : EvidenceWriteResult

    /** The session already holds the maximum number of flagged items a usable bug report can carry. */
    data object QuotaExceeded : EvidenceWriteResult

    data object Unavailable : EvidenceWriteResult
}

/**
 * Durable, per-session QA evidence tray: the flagged items a bug report is built from, plus its
 * typed draft. This is the server-side precedent [io.devconsole.timeline.TimelineAnnotations]
 * already set for durable, per-subject annotations -- evidence is a second, richer instance of the
 * same idea, not a new pattern.
 *
 * Implementations enforce the caps a usable bug report needs: a bounded snapshot size (truncated
 * with an explicit marker rather than silently dropped), a bounded item count per session, and
 * bounded text field lengths on the report draft. Callers at the route boundary should validate the
 * same limits up front so a rejection is reported precisely, but the store enforces them
 * unconditionally -- it never trusts a caller to have done so.
 */
interface EvidenceStore {
    companion object {
        /**
         * Max chars for [StoredEvidenceItem.label] (and the subject id validated at the route
         * boundary). The single shared definition every layer -- device SDK, server route, and
         * storage implementation -- references rather than re-declaring independently.
         */
        const val MAX_LABEL_LENGTH: Int = 512

        /** Max chars for each of [StoredEvidenceReport]'s free-text fields (summary/expected/actual). */
        const val MAX_TEXT_LENGTH: Int = 4096

        /** Max flagged items retained per session before [EvidenceWriteResult.QuotaExceeded]. */
        const val MAX_ITEMS_PER_SESSION: Int = 200
    }

    /** Flags [item]; returns [EvidenceWriteResult.AlreadyFlagged] if its subject is already flagged. */
    suspend fun flag(item: StoredEvidenceItem): EvidenceWriteResult

    /** Removes one flagged item by its (session, kind, subject) identity. A no-op if it is not flagged. */
    suspend fun unflag(
        sessionId: String,
        kind: EvidenceKind,
        subjectId: String,
    )

    /** All items flagged for [sessionId], oldest first. */
    suspend fun items(sessionId: String): List<StoredEvidenceItem>

    /** Removes every flagged item for [sessionId]. The report draft is untouched. */
    suspend fun clear(sessionId: String)

    /** The report draft for [sessionId], or a fresh default one if nothing has been saved yet. */
    suspend fun report(sessionId: String): StoredEvidenceReport

    /** Replaces the report draft for [report]'s session. */
    suspend fun saveReport(report: StoredEvidenceReport)

    /** Removes every evidence item and the report draft for [sessionId]. */
    suspend fun deleteSession(sessionId: String)
}
