/**
 * @author Shakib
 * @since 25/07/26
 */
@file:Suppress("ReturnCount") // Guard-clause early returns are the clearest form for these path/type checks.

package io.devconsole

import android.content.Context
import io.devconsole.security.RedactionEngine
import io.devconsole.server.api.FileEntryData
import io.devconsole.server.api.FileInspector
import io.devconsole.server.api.FileListingData
import io.devconsole.server.api.FilePreviewData
import java.io.File

// One small function per FileInspector operation (list/preview/delete/create/replace/rename/
// readBytes) plus resolveShareableFile and the private resolve helpers; splitting further would
// fragment one cohesive containment-checked file-access boundary across multiple classes.
@Suppress("TooManyFunctions")
internal class AndroidFileInspector(
    private val context: Context,
    private val redaction: RedactionEngine,
    private val maxPreviewBytes: Int = DEFAULT_MAX_PREVIEW_BYTES,
) : FileInspector {
    /**
     * The only directories ever exposed; all resolve under the app sandbox. Recomputed per access
     * because external storage can mount after this inspector is constructed — a snapshot would
     * hide `external-files` for the rest of the process.
     */
    private val rootDirs: Map<String, File>
        get() =
            buildMap {
                put("files", context.filesDir)
                put("cache", context.cacheDir)
                context.getExternalFilesDir(null)?.let { put("external-files", it) }
                put("no-backup", context.noBackupFilesDir)
            }.mapValues { (_, dir) -> dir.canonicalFile }

    override fun roots(): List<String> = rootDirs.keys.sorted()

    override fun list(
        root: String,
        relativePath: String,
    ): FileListingData? {
        val target = resolve(root, relativePath) ?: return null
        if (!target.isDirectory) return null
        val rootDir = rootDirs.getValue(root)
        val entries =
            target
                .listFiles()
                ?.map { it.toEntry(rootDir) }
                ?.sortedWith(compareByDescending<FileEntryData> { it.isDirectory }.thenBy { it.name.lowercase() })
                .orEmpty()
        return FileListingData(root = root, relativePath = target.relativeTo(rootDir).path, entries = entries)
    }

    override fun preview(
        root: String,
        relativePath: String,
    ): FilePreviewData {
        val target = resolve(root, relativePath) ?: return FilePreviewData.Unavailable("Path is not accessible")
        if (!target.isFile) return FilePreviewData.Unavailable("Not a regular file")
        // Read at most maxPreviewBytes so an oversized (multi-GB) app file can never OOM the host,
        // then use an O(1) length() stat to report truncation rather than reading the whole file.
        val head =
            runCatching { target.readHead(maxPreviewBytes) }
                .getOrElse { return FilePreviewData.Unavailable("Unreadable") }
        val totalBytes = target.length()
        if (head.looksBinary()) return FilePreviewData.Binary(totalBytes)
        val text = redaction.redactText(head.decodeToString(), maxPreviewBytes)
        return FilePreviewData.Text(content = text, truncated = totalBytes > head.size)
    }

    override fun delete(
        root: String,
        relativePath: String,
    ): Boolean {
        val target = resolve(root, relativePath) ?: return false
        // Only regular files are deletable; never a whole directory tree from a debug tool.
        if (!target.isFile) return false
        return runCatching { target.delete() }.getOrDefault(false)
    }

    override fun create(
        root: String,
        relativePath: String,
        content: String,
    ): Boolean {
        val target = resolveForWrite(root, relativePath) ?: return false
        // createNewFile() is atomic: it fails rather than truncating when something is already
        // there, so a concurrent creator can never race this into silently overwriting a file.
        return runCatching {
            if (!target.createNewFile()) return@runCatching false
            // If the write fails after the empty file exists, remove it -- otherwise every retry
            // would hit the just-created zero-byte file and report a conflict forever.
            runCatching { target.writeText(content) }
                .onFailure { runCatching { target.delete() } }
                .isSuccess
        }.getOrDefault(false)
    }

    override fun replace(
        root: String,
        relativePath: String,
        content: String,
    ): Boolean {
        val target = resolve(root, relativePath) ?: return false
        if (!target.isFile) return false
        return runCatching { target.writeText(content) }.isSuccess
    }

    override fun rename(
        root: String,
        relativePath: String,
        newRelativePath: String,
    ): Boolean {
        val source = resolve(root, relativePath) ?: return false
        if (!source.isFile) return false
        val destination = resolveForWrite(root, newRelativePath) ?: return false
        // Never clobber an existing destination -- the caller must delete it first if that is
        // really what they want.
        if (destination.exists()) return false
        return runCatching { source.renameTo(destination) }.getOrDefault(false)
    }

    override fun readBytes(
        root: String,
        relativePath: String,
    ): ByteArray? {
        val target = resolve(root, relativePath) ?: return null
        if (!target.isFile) return null
        // Bounded read instead of stat-then-read: a file that grows between the stat and the read
        // could otherwise blow past the cap. Reads in chunks so a small file never costs a
        // MAX_READ_BYTES allocation, and stops one chunk past the limit to detect oversize.
        val bytes = runCatching { target.readCapped(FileInspector.MAX_READ_BYTES) }.getOrNull() ?: return null
        return bytes.takeIf { it.size <= FileInspector.MAX_READ_BYTES }
    }

    /**
     * Resolves a regular file for local sharing (Android's `ACTION_SEND` via `FileProvider`).
     * Not part of [FileInspector] -- that interface stays free of `java.io.File` so it can be
     * shared with non-Android surfaces -- but this class is Android-only anyway, and the Compose
     * Files screen (`sdk:ui-compose`) needs a real path to hand `FileProvider.getUriForFile`, not
     * just bytes. Containment-checked identically to every other operation here via [resolve].
     */
    fun resolveShareableFile(
        root: String,
        relativePath: String,
    ): File? = resolve(root, relativePath)?.takeIf { it.isFile }

    /**
     * Resolves [relativePath] under [root] and confirms the canonical result is still inside that
     * root, defeating `..`, absolute paths, and symlink escapes. Returns null when out of bounds.
     */
    private fun resolve(
        root: String,
        relativePath: String,
    ): File? {
        val rootDir = rootDirs[root] ?: return null
        val candidate = runCatching { File(rootDir, relativePath).canonicalFile }.getOrNull() ?: return null
        val rootPath = rootDir.path
        val candidatePath = candidate.path
        val withinRoot = candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
        return candidate.takeIf { withinRoot && it.exists() }
    }

    /**
     * Resolves a target for a file that may not exist yet (create/rename destination). [resolve]
     * cannot be reused here because it requires the candidate to already exist; instead this
     * canonicalizes the *parent* directory -- which must already exist -- and validates that it is
     * still inside [root], defeating the same `..`/absolute-path/symlink escapes on a not-yet-created
     * leaf. The leaf name itself is taken verbatim from the canonicalized parent's child, so a `..`
     * segment in the final path component cannot smuggle the target outside the validated parent.
     */
    private fun resolveForWrite(
        root: String,
        relativePath: String,
    ): File? {
        val rootDir = rootDirs[root] ?: return null
        if (relativePath.isBlank() || relativePath.endsWith("/") || relativePath.endsWith(File.separator)) return null
        val rawTarget = File(rootDir, relativePath)
        val name = rawTarget.name
        if (name.isBlank() || name == "." || name == "..") return null
        val parentDir = rawTarget.parentFile ?: return null
        val canonicalParent = runCatching { parentDir.canonicalFile }.getOrNull() ?: return null
        val rootPath = rootDir.path
        val parentPath = canonicalParent.path
        val withinRoot = parentPath == rootPath || parentPath.startsWith(rootPath + File.separator)
        if (!withinRoot || !canonicalParent.isDirectory) return null
        return File(canonicalParent, name)
    }

    /** Reads at most [limit] bytes from the file without ever allocating more than that. */
    private fun File.readHead(limit: Int): ByteArray =
        inputStream().buffered().use { stream ->
            val buffer = ByteArray(limit)
            var total = 0
            while (total < limit) {
                val read = stream.read(buffer, total, limit - total)
                if (read < 0) break
                total += read
            }
            if (total == limit) buffer else buffer.copyOf(total)
        }

    /**
     * Reads the whole file in chunks, stopping as soon as it exceeds [cap]. The result grows with
     * the file rather than preallocating [cap] bytes; a result larger than [cap] signals oversize.
     */
    private fun File.readCapped(cap: Long): ByteArray =
        inputStream().buffered().use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(READ_CHUNK_BYTES)
            while (out.size() <= cap) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }

    private fun File.toEntry(rootDir: File): FileEntryData =
        FileEntryData(
            name = name,
            relativePath = relativeTo(rootDir).path,
            isDirectory = isDirectory,
            sizeBytes = if (isFile) length() else 0L,
            lastModifiedEpochMs = lastModified(),
        )

    private fun ByteArray.looksBinary(): Boolean {
        val sample = copyOf(minOf(size, BINARY_SNIFF_BYTES))
        if (sample.any { it.toInt() == 0 }) return true
        val control = sample.count { it.isNonTextControl() }
        return sample.isNotEmpty() && control.toDouble() / sample.size > BINARY_CONTROL_RATIO
    }

    /** A control byte that is not one of the common text whitespace characters (tab/newline/return). */
    private fun Byte.isNonTextControl(): Boolean =
        this in 0 until MIN_PRINTABLE_BYTE && this != TAB_BYTE && this != NEWLINE_BYTE && this != RETURN_BYTE

    private companion object {
        const val DEFAULT_MAX_PREVIEW_BYTES = 64 * 1024
        const val READ_CHUNK_BYTES = 64 * 1024
        const val BINARY_SNIFF_BYTES = 4 * 1024
        const val BINARY_CONTROL_RATIO = 0.30
        const val MIN_PRINTABLE_BYTE: Byte = 0x20
        const val TAB_BYTE: Byte = '\t'.code.toByte()
        const val NEWLINE_BYTE: Byte = '\n'.code.toByte()
        const val RETURN_BYTE: Byte = '\r'.code.toByte()
    }
}
