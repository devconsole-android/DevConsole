/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.storage.room

import io.devconsole.storage.api.EvidenceKind
import io.devconsole.storage.api.EvidenceSeverity
import io.devconsole.storage.api.EvidenceStore
import io.devconsole.storage.api.EvidenceStore.Companion.MAX_ITEMS_PER_SESSION
import io.devconsole.storage.api.EvidenceStore.Companion.MAX_LABEL_LENGTH
import io.devconsole.storage.api.EvidenceStore.Companion.MAX_TEXT_LENGTH
import io.devconsole.storage.api.EvidenceWriteResult
import io.devconsole.storage.api.StoredEvidenceItem
import io.devconsole.storage.api.StoredEvidenceReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Room-backed evidence tray: the durable home for flagged QA items and their bug-report draft.
 *
 * [StoredEvidenceItem.snapshotJson] is materialized once by the caller at flag time and stored
 * verbatim (subject to the truncation cap below) -- this store never re-derives it, which is the
 * defect the whole evidence tray design exists to fix.
 *
 * One small function per EvidenceStore operation plus the recovery-configuration helper; splitting
 * further would fragment one cohesive evidence-tray boundary across multiple classes.
 */
class RoomEvidenceStore private constructor(
    private var itemDao: () -> EvidenceItemDao,
    private var reportDao: () -> EvidenceReportDao,
) : EvidenceStore {
    @Volatile
    private var recover: ((Throwable) -> Unit)? = null

    /**
     * Serializes the check-then-insert in [flag] so a concurrent duplicate flag on *this instance*
     * -- a crash auto-flag racing a manual flag, or two evidence-tray writes from the same embedded
     * server -- cannot observe a stale [EvidenceItemDao.existsCount] or [EvidenceItemDao.countForSession]
     * between the check and the write. That race used to let a concurrent duplicate slip past both
     * checks and hit the unique index at [EvidenceItemDao.insert] instead, surfacing as
     * [EvidenceWriteResult.Unavailable] (see [isUniqueConstraintViolation]) rather than
     * [EvidenceWriteResult.AlreadyFlagged], and could let the [MAX_ITEMS_PER_SESSION] quota be
     * exceeded by more than one writer at a time.
     */
    private val flagMutex = Mutex()

    constructor(database: DevConsoleDatabase) : this({ database.evidenceItemDao() }, { database.evidenceReportDao() })

    internal constructor(itemDao: EvidenceItemDao, reportDao: EvidenceReportDao) : this({ itemDao }, { reportDao })

    fun withRecovery(
        databaseProvider: () -> DevConsoleDatabase,
        recover: (Throwable) -> Unit,
    ): RoomEvidenceStore =
        apply {
            itemDao = { databaseProvider().evidenceItemDao() }
            reportDao = { databaseProvider().evidenceReportDao() }
            this.recover = recover
        }

    override suspend fun flag(item: StoredEvidenceItem): EvidenceWriteResult {
        require(item.label.length <= MAX_LABEL_LENGTH) { "label exceeds $MAX_LABEL_LENGTH characters" }
        val bounded = item.copy(snapshotJson = item.snapshotJson.truncatedForEvidenceSnapshot())
        return withContext(Dispatchers.IO) {
            flagMutex.withLock {
                executeWithItemRecovery(EvidenceWriteResult.Unavailable) { activeDao ->
                    insertIfAbsent(activeDao, bounded)
                }
            }
        }
    }

    override suspend fun unflag(
        sessionId: String,
        kind: EvidenceKind,
        subjectId: String,
    ) {
        withContext(Dispatchers.IO) {
            executeWithItemRecovery(Unit) { it.delete(sessionId, kind.name, subjectId) }
        }
    }

    override suspend fun items(sessionId: String): List<StoredEvidenceItem> =
        withContext(Dispatchers.IO) {
            executeWithItemRecovery(emptyList()) { it.items(sessionId).map(EvidenceItemEntity::toStoredEvidenceItem) }
        }

    override suspend fun clear(sessionId: String) {
        withContext(Dispatchers.IO) {
            executeWithItemRecovery(Unit) { it.deleteSession(sessionId) }
        }
    }

    override suspend fun report(sessionId: String): StoredEvidenceReport =
        withContext(Dispatchers.IO) {
            executeWithReportRecovery(StoredEvidenceReport(sessionId = sessionId)) { activeDao ->
                activeDao.report(sessionId)?.toStoredEvidenceReport() ?: StoredEvidenceReport(sessionId = sessionId)
            }
        }

    override suspend fun saveReport(report: StoredEvidenceReport) {
        require((report.summary?.length ?: 0) <= MAX_TEXT_LENGTH) { "summary exceeds $MAX_TEXT_LENGTH characters" }
        require((report.expected?.length ?: 0) <= MAX_TEXT_LENGTH) { "expected exceeds $MAX_TEXT_LENGTH characters" }
        require((report.actual?.length ?: 0) <= MAX_TEXT_LENGTH) { "actual exceeds $MAX_TEXT_LENGTH characters" }
        withContext(Dispatchers.IO) {
            executeWithReportRecovery(Unit) { it.upsert(report.toEntity()) }
        }
    }

    override suspend fun deleteSession(sessionId: String) {
        withContext(Dispatchers.IO) {
            executeWithItemRecovery(Unit) { it.deleteSession(sessionId) }
            executeWithReportRecovery(Unit) { it.deleteSession(sessionId) }
        }
    }

    private suspend fun <T> executeWithItemRecovery(
        unavailable: T,
        operation: (EvidenceItemDao) -> T,
    ): T = executeWithSqliteRecovery(unavailable, itemDao, recover, operation)

    private suspend fun <T> executeWithReportRecovery(
        unavailable: T,
        operation: (EvidenceReportDao) -> T,
    ): T = executeWithSqliteRecovery(unavailable, reportDao, recover, operation)
}

/**
 * The check-then-insert at the heart of [RoomEvidenceStore.flag], run under that store's own flag
 * mutex so it is atomic against every other call to `flag` on the same instance.
 * [EvidenceItemDao.insert]'s unique index (`index_evidence_items_subject`) is still the backstop for
 * any writer *not* covered by that mutex; a constraint violation reaching here is mapped to
 * [EvidenceWriteResult.AlreadyFlagged] -- the correct, idempotent outcome for a duplicate subject --
 * rather than being caught by [executeWithSqliteRecovery]'s broader, corruption-oriented handling one
 * frame up, which would otherwise report the wrong error: [EvidenceWriteResult.Unavailable].
 */
@Suppress("TooGenericExceptionCaught", "ReturnCount")
private fun insertIfAbsent(
    activeDao: EvidenceItemDao,
    bounded: StoredEvidenceItem,
): EvidenceWriteResult {
    if (activeDao.existsCount(bounded.sessionId, bounded.kind.name, bounded.subjectId) > 0) {
        return EvidenceWriteResult.AlreadyFlagged
    }
    if (activeDao.countForSession(bounded.sessionId) >= MAX_ITEMS_PER_SESSION) {
        return EvidenceWriteResult.QuotaExceeded
    }
    return try {
        activeDao.insert(bounded.toEntity())
        EvidenceWriteResult.Success(bounded)
    } catch (failure: Exception) {
        if (failure.isUniqueConstraintViolation()) EvidenceWriteResult.AlreadyFlagged else throw failure
    }
}

/**
 * Detects the unique-index violation `index_evidence_items_subject` raises for a duplicate
 * (session, kind, subject) triple -- Room surfaces this as `android.database.sqlite.SQLiteConstraintException`,
 * a subtype this module does not depend on directly, so detection follows the same class-name/message
 * convention [isSqliteCorruption] already uses rather than adding that dependency for one check.
 */
internal fun Throwable.isUniqueConstraintViolation(): Boolean =
    generateSequence(this) { it.cause }
        .any { failure ->
            failure.javaClass.simpleName.contains("ConstraintException") ||
                failure.message.orEmpty().contains("UNIQUE constraint failed", ignoreCase = true)
        }

/**
 * Caps [StoredEvidenceItem.snapshotJson] at 256 KiB so a flagged response body can never become an
 * unbounded row. Oversized input is replaced with a small JSON envelope carrying an explicit
 * `"truncated": true` marker and a bounded preview of the original content -- never silently
 * dropped, and never allowed to overrun the cap even after the preview is JSON-escaped.
 */
internal fun String.truncatedForEvidenceSnapshot(): String {
    val originalBytes = utf8ByteCount()
    if (originalBytes <= MAX_SNAPSHOT_JSON_BYTES) return this
    // Escaping (and the envelope itself) can only grow text, so the preview starts at a quarter of
    // the cap and is halved until the assembled envelope actually fits -- generous headroom rather
    // than an exact-fit computation.
    var previewLength = length / INITIAL_PREVIEW_DIVISOR
    var candidate = evidenceSnapshotEnvelope(take(previewLength), originalBytes)
    while (candidate.utf8ByteCount() > MAX_SNAPSHOT_JSON_BYTES && previewLength > 0) {
        previewLength /= PREVIEW_SHRINK_DIVISOR
        candidate = evidenceSnapshotEnvelope(take(previewLength), originalBytes)
    }
    return candidate
}

private fun evidenceSnapshotEnvelope(
    preview: String,
    originalLength: Int,
): String = "{\"truncated\":true,\"originalLength\":$originalLength,\"snapshot\":\"${preview.jsonEscaped()}\"}"

private fun String.jsonEscaped(): String =
    buildString(length) {
        for (character in this@jsonEscaped) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (character.code < MIN_PRINTABLE_CHAR_CODE) {
                        append("\\u%04x".format(character.code))
                    } else {
                        append(character)
                    }
            }
        }
    }

private fun String.utf8ByteCount(): Int = toByteArray(Charsets.UTF_8).size

private const val MAX_SNAPSHOT_JSON_BYTES = 256 * 1024
private const val MIN_PRINTABLE_CHAR_CODE = 0x20
private const val INITIAL_PREVIEW_DIVISOR = 4
private const val PREVIEW_SHRINK_DIVISOR = 2

internal fun StoredEvidenceItem.toEntity(): EvidenceItemEntity =
    EvidenceItemEntity(
        id,
        sessionId,
        kind.name,
        subjectId,
        label,
        flaggedAtMs,
        snapshotJson,
        attachmentId,
    )

internal fun EvidenceItemEntity.toStoredEvidenceItem(): StoredEvidenceItem =
    StoredEvidenceItem(
        id = id,
        sessionId = sessionId,
        kind = EvidenceKind.valueOf(kind),
        subjectId = subjectId,
        label = label,
        flaggedAtMs = flaggedAtMs,
        snapshotJson = snapshotJson,
        attachmentId = attachmentId,
    )

internal fun StoredEvidenceReport.toEntity(): EvidenceReportEntity =
    EvidenceReportEntity(
        sessionId,
        severity.name,
        summary,
        expected,
        actual,
        updatedAtMs,
    )

internal fun EvidenceReportEntity.toStoredEvidenceReport(): StoredEvidenceReport =
    StoredEvidenceReport(
        sessionId = sessionId,
        severity = EvidenceSeverity.valueOf(severity),
        summary = summary,
        expected = expected,
        actual = actual,
        updatedAtMs = updatedAtMs,
    )
