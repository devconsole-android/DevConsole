@file:Suppress("ReturnCount") // Guard-clause early returns are the clearest form for these file/path checks.

package io.devconsole.storage.room

import io.devconsole.storage.api.AttachmentStore
import io.devconsole.storage.api.AttachmentWriteRequest
import io.devconsole.storage.api.AttachmentWriteResult
import io.devconsole.storage.api.RedactionApplicability
import io.devconsole.storage.api.StoredAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Persists attachments under the caller-supplied no-backup `devconsole` directory. The caller must
 * pass only redacted data; unredacted input is refused before it can reach disk.
 *
 * One small function per AttachmentStore operation (write/prepare/materialize/delete/read) plus
 * the private path-validation/atomic-move helpers; splitting further would fragment one cohesive
 * containment-checked file-access boundary across multiple classes.
 */
@Suppress("TooManyFunctions")
class FileAttachmentStore(
    private val rootDirectory: File,
    private val maxAttachmentBytes: Int = DEFAULT_MAX_ATTACHMENT_BYTES,
) : AttachmentStore {
    init {
        require(maxAttachmentBytes > 0) { "maxAttachmentBytes must be positive" }
    }

    override suspend fun write(request: AttachmentWriteRequest): AttachmentWriteResult {
        if (request.redactsWithoutRedaction()) return AttachmentWriteResult.RejectedUnredactedContent
        val prepared = prepare(request) ?: return AttachmentWriteResult.Unavailable
        return when (materialize(prepared)) {
            FileMaterialization.Written -> AttachmentWriteResult.Success(prepared.attachment)
            FileMaterialization.Failed -> AttachmentWriteResult.Unavailable
        }
    }

    /** Prepares deterministic metadata and bounded bytes without creating a file. */
    internal suspend fun prepare(request: AttachmentWriteRequest): PreparedAttachment? {
        if (request.redactsWithoutRedaction()) return null
        return withContext(Dispatchers.IO) {
            try {
                val sessionId = request.sessionId.requireUuid("sessionId")
                request.eventId.requireUuid("eventId")
                val attachmentId = UUID.randomUUID()
                val originalLength = request.originalLength
                val storedBytes = request.bytes.copyOf(minOf(request.bytes.size, maxAttachmentBytes))
                val hash = storedBytes.sha256()
                val filename = "${hash.take(HASH_PREFIX_LENGTH)}-$attachmentId.bin"
                PreparedAttachment(
                    StoredAttachment(
                        id = attachmentId.toString(),
                        eventId = request.eventId,
                        sessionId = request.sessionId,
                        mimeType = request.mimeType,
                        originalLength = originalLength,
                        storedLength = storedBytes.size.toLong(),
                        truncated = request.sourceTruncated || request.bytes.size > storedBytes.size,
                        sha256 = hash,
                        isRedacted = request.isRedacted,
                        relativePath = "$sessionId/${ATTACHMENTS_DIRECTORY}/$filename",
                        redactionApplicability = request.redactionApplicability,
                    ),
                    storedBytes,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Materializes exactly the file represented by [prepared]; its Room row must already exist. */
    internal suspend fun materialize(prepared: PreparedAttachment): FileMaterialization =
        withContext(Dispatchers.IO) {
            try {
                val target =
                    validatedAttachmentTarget(prepared.attachment) ?: return@withContext FileMaterialization.Failed
                val directory = target.parentFile ?: return@withContext FileMaterialization.Failed
                if (!directory.mkdirs() && !directory.isDirectory) return@withContext FileMaterialization.Failed
                val temporary = File(directory, ".${target.name}.tmp")
                try {
                    temporary.outputStream().use { it.write(prepared.bytes) }
                    temporary.moveAtomicallyTo(target)
                } finally {
                    if (temporary.exists()) temporary.delete()
                }
                FileMaterialization.Written
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                FileMaterialization.Failed
            }
        }

    override suspend fun deleteSession(sessionId: String) {
        deleteSessionFiles(sessionId)
    }

    /**
     * Returns an explicit outcome so callers never discard Room metadata after an on-disk failure.
     * Missing files are safe to reconcile because no bytes remain allocated on disk.
     */
    internal suspend fun deleteSessionFiles(sessionId: String): FileDeletion =
        withContext(Dispatchers.IO) {
            try {
                val root = rootDirectory.canonicalFile
                // Pre-session databases may contain arbitrary legacy event session IDs. They could
                // never have owned FileAttachmentStore content (writes require UUIDs), so treat
                // them as already clean instead of blocking metadata retention forever.
                val safeSessionId = sessionId.toUuidOrNull() ?: return@withContext FileDeletion.Missing
                deleteTarget(root, File(root, safeSessionId.toString()).canonicalFile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                FileDeletion.Failed
            }
        }

    internal suspend fun delete(attachment: StoredAttachment): FileDeletion =
        withContext(Dispatchers.IO) {
            try {
                val target = validatedAttachmentTarget(attachment) ?: return@withContext FileDeletion.Failed
                if (!target.exists()) return@withContext FileDeletion.Missing
                if (!target.isFile) return@withContext FileDeletion.Failed
                if (target.delete() && !target.exists()) FileDeletion.Deleted else FileDeletion.Failed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                FileDeletion.Failed
            }
        }

    internal suspend fun read(attachment: StoredAttachment): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                // A NOT_APPLICABLE attachment (e.g. a screenshot) is honestly unredacted, so
                // isRedacted alone can no longer gate whether stored bytes are safe to serve back.
                val safeToServe =
                    attachment.isRedacted || attachment.redactionApplicability == RedactionApplicability.NOT_APPLICABLE
                if (!safeToServe || attachment.storedLength !in 0..maxAttachmentBytes.toLong()) {
                    return@withContext null
                }
                val target = validatedAttachmentTarget(attachment) ?: return@withContext null
                if (!target.isFile || target.length() != attachment.storedLength) {
                    return@withContext null
                }
                val bytes = target.readBytes()
                bytes.takeIf {
                    it.size.toLong() == attachment.storedLength &&
                        it.sha256() == attachment.sha256
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

    private fun String.requireUuid(name: String): UUID =
        try {
            UUID.fromString(this)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("$name must be a UUID", error)
        }

    private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

    private fun validatedAttachmentTarget(attachment: StoredAttachment): File? {
        val root = rootDirectory.canonicalFile
        val sessionId = attachment.sessionId.toUuidOrNull() ?: return null
        val attachmentId = attachment.id.toUuidOrNull() ?: return null
        val directory = File(File(root, sessionId.toString()), ATTACHMENTS_DIRECTORY).canonicalFile
        val target = File(root, attachment.relativePath).canonicalFile
        val expectedName = "${attachment.sha256.take(HASH_PREFIX_LENGTH)}-$attachmentId.bin"
        return target.takeIf {
            directory.isInside(root) &&
                it.parentFile?.canonicalFile == directory &&
                it.name == expectedName
        }
    }

    private fun deleteTarget(
        root: File,
        target: File,
    ): FileDeletion {
        if (!target.isInside(root)) return FileDeletion.Failed
        if (!target.exists()) return FileDeletion.Missing
        return if (target.deleteRecursively() && !target.exists()) FileDeletion.Deleted else FileDeletion.Failed
    }

    private fun ByteArray.sha256(): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(this)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    /**
     * `java.nio.file` is API 26+, so this uses [File.renameTo], which maps to `rename(2)` on
     * Android and atomically replaces the destination within a filesystem. The copy fallback covers
     * the cross-filesystem case, where no atomic move is possible either way.
     */
    private fun File.moveAtomicallyTo(destination: File) {
        if (renameTo(destination)) return
        inputStream().use { source ->
            destination.outputStream().use(source::copyTo)
        }
        delete()
    }

    /** Containment check without `java.nio.file.Path.startsWith`. Both paths must be canonical. */
    private fun File.isInside(root: File): Boolean = path == root.path || path.startsWith(root.path + File.separator)

    companion object {
        const val DEFAULT_MAX_ATTACHMENT_BYTES: Int = 2 * 1024 * 1024
        private const val ATTACHMENTS_DIRECTORY = "attachments"
        private const val HASH_PREFIX_LENGTH = 12
    }
}

/**
 * True when unredacted bytes would reach disk for content redaction could have applied to.
 *
 * Two cases: text content marked [RedactionApplicability.APPLIED] that was not actually redacted --
 * the case [AttachmentStore.write] has always refused -- and, as of this check,
 * [RedactionApplicability.NOT_APPLICABLE] claimed for a MIME type redaction *can* reach (text/JSON/XML).
 * `NOT_APPLICABLE` exists only so genuinely binary content (screenshots) can skip an assertion that
 * cannot be true for pixels; it is not a caller-controlled bypass for content redaction could have
 * scanned. A [RedactionApplicability.NOT_APPLICABLE] request for e.g. `image/png` never trips this.
 */
private fun AttachmentWriteRequest.redactsWithoutRedaction(): Boolean =
    (redactionApplicability == RedactionApplicability.APPLIED && !isRedacted) ||
        (redactionApplicability == RedactionApplicability.NOT_APPLICABLE && mimeType.isRedactableMimeType())

/**
 * Whether [RedactionEngine]-style text-content redaction is meaningfully applicable to [this] MIME
 * type. Deliberately conservative (matches broadly) since the cost of a false positive here is a
 * caller having to redact-then-mark-[RedactionApplicability.APPLIED] instead of claiming
 * [RedactionApplicability.NOT_APPLICABLE]; the cost of a false negative is unredacted text on disk.
 * Parameters (e.g. `text/plain; charset=utf-8`) are stripped before matching.
 */
internal fun String.isRedactableMimeType(): Boolean {
    val type = substringBefore(';').trim().lowercase()
    return type.startsWith("text/") ||
        type == "application/json" ||
        type == "application/xml" ||
        type.endsWith("+json") ||
        type.endsWith("+xml")
}

internal enum class FileDeletion { Deleted, Missing, Failed }

internal enum class FileMaterialization { Written, Failed }

internal data class PreparedAttachment(
    val attachment: StoredAttachment,
    val bytes: ByteArray,
)
