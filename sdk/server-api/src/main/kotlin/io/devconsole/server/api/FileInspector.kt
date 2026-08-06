/**
 * @author Shakib
 * @since 02/08/26
 */
package io.devconsole.server.api

data class FileEntryData(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
)

data class FileListingData(
    val root: String,
    val relativePath: String,
    val entries: List<FileEntryData>,
)

sealed interface FilePreviewData {
    data class Text(
        val content: String,
        val truncated: Boolean,
    ) : FilePreviewData

    data class Binary(
        val sizeBytes: Long,
    ) : FilePreviewData

    data class Unavailable(
        val reason: String,
    ) : FilePreviewData
}

/**
 * Browses an app's own file storage. Every path is resolved against a fixed set of app-owned roots
 * and canonicalized; anything escaping a root (`..`, symlinks, absolute paths) is refused. Text
 * previews are redacted. Only regular files are ever touched -- never a directory tree -- across
 * every operation here, including [create]/[replace]/[rename]. Every mutation (and [readBytes], since
 * it returns raw unredacted bytes) is ungated here -- callers (the Compose adapter in `sdk:full`, and
 * the browser server routes in `sdk:server-ktor`) enforce the `EditingCapabilities.files` opt-in.
 * Declared in `sdk:server-api` -- a plain boundary module with no Android dependency -- so both
 * surfaces can share this one contract without a circular module dependency, the same way
 * `io.devconsole.state.SessionFeatureFlags` reaches `GET/POST /api/v1/flags`.
 */
interface FileInspector {
    fun roots(): List<String>

    fun list(
        root: String,
        relativePath: String,
    ): FileListingData?

    fun preview(
        root: String,
        relativePath: String,
    ): FilePreviewData

    fun delete(
        root: String,
        relativePath: String,
    ): Boolean

    /** Writes a brand-new file with [content]. Refuses (`false`) when a file already exists there. */
    fun create(
        root: String,
        relativePath: String,
        content: String,
    ): Boolean

    /** Overwrites an existing regular file's content. Refuses (`false`) when there is nothing there yet. */
    fun replace(
        root: String,
        relativePath: String,
        content: String,
    ): Boolean

    /**
     * Moves a regular file to [newRelativePath] within the same [root]. Both the source and the
     * destination are containment-checked; refuses (`false`) if the destination already exists so a
     * rename can never silently clobber another file.
     */
    fun rename(
        root: String,
        relativePath: String,
        newRelativePath: String,
    ): Boolean

    /**
     * Raw, unredacted file bytes for download. Refuses (returns `null`) for a missing/inaccessible
     * path, a directory, or a file larger than [MAX_READ_BYTES] so a debug download can never pull a
     * multi-gigabyte file into memory.
     */
    fun readBytes(
        root: String,
        relativePath: String,
    ): ByteArray?

    companion object {
        /** Hard cap for [readBytes]; see its doc comment. */
        const val MAX_READ_BYTES: Long = 10L * 1024 * 1024
    }
}
