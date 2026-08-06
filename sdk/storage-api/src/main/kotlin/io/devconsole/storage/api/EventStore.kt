package io.devconsole.storage.api

/**
 * A redacted event ready for durable storage. This contract deliberately has no Android or Room
 * types, so consumers never need to know which persistence mechanism is active.
 */
data class StoredEvent(
    val id: String,
    val sessionId: String,
    val sequence: Long,
    val pluginId: String,
    val type: String,
    val wallTimeMs: Long,
    val monoTimeNs: Long,
    val severity: Int,
    val summary: String,
    val correlationId: String? = null,
    val tagsJson: String = "{}",
    val payloadJson: String? = null,
    val attachmentId: String? = null,
    val schemaVersion: Int = 1,
)

sealed interface EventStoreWriteResult {
    data class Success(
        val insertedCount: Int,
    ) : EventStoreWriteResult

    data object Unavailable : EventStoreWriteResult
}

/** A single-source, durable event data source. Inputs must already be redacted. */
interface EventStore {
    suspend fun insert(events: List<StoredEvent>): EventStoreWriteResult

    suspend fun eventsForSession(sessionId: String): List<StoredEvent>

    /**
     * Returns the newest bounded rows for one app run in chronological order, optionally
     * restricted to [pluginIds] (empty, the default, means no filter -- every plugin). The default
     * implementation filters in Kotlin after [eventsForSession] loads the whole session; a store
     * backed by a real index (see `RoomEventStore`) should override this to push the predicate into
     * its query instead, so a small [limit] still surfaces a rare matching row rather than being
     * crowded out by more recent rows of an unfiltered plugin.
     */
    suspend fun recentEventsForSession(
        sessionId: String,
        limit: Int,
        pluginIds: Set<String> = emptySet(),
    ): List<StoredEvent> {
        require(limit > 0) { "limit must be positive" }
        val events = eventsForSession(sessionId)
        return (if (pluginIds.isEmpty()) events else events.filter { it.pluginId in pluginIds }).takeLast(limit)
    }

    /**
     * Cross-session counterpart of [recentEventsForSession]: the newest bounded rows across every
     * retained session, restricted to [pluginIds]. [pluginIds] is required and non-empty -- unlike
     * the session-scoped read, there is no cheap, generic way for this interface to enumerate
     * "every session" on its own, so an unfiltered cross-session scan is intentionally not exposed
     * here (it would reproduce the exact crowding-out problem this method exists to avoid).
     *
     * Finding a crash from a run that has already ended -- its process is gone and a *different*
     * session is now current -- is the reason this exists: nothing durable ties "which session" to
     * "the crash a developer is looking for" except the plugin id.
     *
     * A store without a real cross-session index (most test doubles) may leave this at its default,
     * which returns an empty list; `RoomEventStore` is the one production override, backed by a
     * single indexed `plugin_id IN (...)` query.
     */
    suspend fun recentEventsForPlugins(
        pluginIds: Set<String>,
        limit: Int,
    ): List<StoredEvent> {
        require(limit > 0) { "limit must be positive" }
        require(pluginIds.isNotEmpty()) { "pluginIds must not be empty" }
        return emptyList()
    }

    suspend fun deleteSession(sessionId: String)

    suspend fun eventCount(): Long
}

/**
 * Whether redaction was a meaningful step for one attachment's content.
 *
 * Redaction is a text-content operation: it scans structured or semi-structured payloads for known
 * secret shapes and masks them. A screenshot's bytes are raw pixels -- there is no text to scan, so
 * "redacted" is not a question that has a truthful "yes" answer for one. Forcing `isRedacted = true`
 * on such content, as [AttachmentStore.write] otherwise requires, would make the data model lie
 * about what was actually done to it.
 */
enum class RedactionApplicability {
    /** Text content that went through the RedactionEngine. [AttachmentWriteRequest.isRedacted] must be true. */
    APPLIED,

    /**
     * Binary content redaction cannot be applied to, e.g. screenshots. Unredacted by construction.
     *
     * This is a claim about the content's *kind*, not a caller-controlled bypass: implementations of
     * [AttachmentStore.write] are expected to reject [AttachmentWriteRequest.mimeType]s that redaction
     * can actually reach (text/JSON/XML) even when the caller marks them `NOT_APPLICABLE`, so this
     * value cannot be used to smuggle unredacted text past redaction.
     */
    NOT_APPLICABLE,
}

data class AttachmentWriteRequest(
    val sessionId: String,
    val eventId: String,
    val mimeType: String,
    val bytes: ByteArray,
    val isRedacted: Boolean,
    val redactionApplicability: RedactionApplicability = RedactionApplicability.APPLIED,
) {
    var originalLength: Long = bytes.size.toLong()
        private set

    var sourceTruncated: Boolean = false
        private set

    fun withSourceMetadata(
        originalLength: Long,
        truncated: Boolean,
    ): AttachmentWriteRequest =
        copy().also {
            require(originalLength >= 0) { "originalLength must not be negative" }
            it.originalLength = originalLength
            it.sourceTruncated = truncated
        }
}

data class StoredAttachment(
    val id: String,
    val eventId: String,
    val sessionId: String,
    val mimeType: String,
    val originalLength: Long,
    val storedLength: Long,
    val truncated: Boolean,
    val sha256: String,
    val isRedacted: Boolean,
    val relativePath: String,
    val redactionApplicability: RedactionApplicability,
)

sealed interface AttachmentWriteResult {
    data class Success(
        val attachment: StoredAttachment,
    ) : AttachmentWriteResult

    data object RejectedUnredactedContent : AttachmentWriteResult

    data object Unavailable : AttachmentWriteResult
}

/**
 * A file-backed attachment data source.
 *
 * [write] rejects with [AttachmentWriteResult.RejectedUnredactedContent] in two cases: when
 * [AttachmentWriteRequest.redactionApplicability] is [RedactionApplicability.APPLIED] and
 * [AttachmentWriteRequest.isRedacted] is false -- text content that should have gone through
 * redaction but did not -- and when it is [RedactionApplicability.NOT_APPLICABLE] for a
 * [AttachmentWriteRequest.mimeType] redaction can actually reach (text/JSON/XML). Only genuinely
 * binary content (screenshots) is accepted unredacted under `NOT_APPLICABLE`, honestly labeled as
 * such; the flag names an unredactable *kind* of content, not a bypass a caller can invoke for any
 * `mimeType`.
 */
interface AttachmentStore {
    suspend fun write(request: AttachmentWriteRequest): AttachmentWriteResult

    suspend fun deleteSession(sessionId: String)
}

/** Lifecycle state for one persisted app run. */
enum class StoredSessionStatus {
    ACTIVE,
    COMPLETED,
    CRASHED,
    DELETING,
}

/**
 * Durable, already-safe metadata for an app run. Device and build fields are optional so databases
 * created before sessions existed can be backfilled without inventing host information.
 */
data class StoredSession(
    val id: String,
    val status: StoredSessionStatus,
    val startedAtMs: Long,
    val startedAtMonotonicNs: Long = 0,
    val endedAtMs: Long? = null,
    val applicationId: String? = null,
    val appVersionName: String? = null,
    val appVersionCode: Long? = null,
    val buildType: String? = null,
    val deviceModel: String? = null,
    val deviceApiLevel: Int? = null,
    val deviceOsVersion: String? = null,
    val recordCount: Long = 0,
    val estimatedBytes: Long = 0,
)

/** Durable app-run lifecycle and history source. */
interface SessionStore {
    suspend fun start(session: StoredSession)

    suspend fun end(
        sessionId: String,
        endedAtMs: Long,
    )

    suspend fun crash(
        sessionId: String,
        endedAtMs: Long,
    )

    suspend fun sessions(): List<StoredSession>

    suspend fun session(sessionId: String): StoredSession?
}

/** Limits applied to retained app-run sessions by a persistence implementation. */
data class SessionRetentionPolicy(
    val maxSessions: Int,
    val maxAgeMs: Long,
    val maxBytes: Long,
) {
    init {
        require(maxSessions > 0) { "maxSessions must be positive" }
        require(maxAgeMs > 0) { "maxAgeMs must be positive" }
        require(maxBytes > 0) { "maxBytes must be positive" }
    }
}
