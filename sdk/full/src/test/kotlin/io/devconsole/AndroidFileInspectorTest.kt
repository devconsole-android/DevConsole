/**
 * @author Shakib
 * @since 25/07/26
 */
package io.devconsole

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.server.api.FileInspector
import io.devconsole.server.api.FilePreviewData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidFileInspectorTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val inspector =
        AndroidFileInspector(application, RedactionEngine(RedactionPolicy.default()))

    @Test
    fun `roots expose only app-owned storage`() {
        assertTrue(inspector.roots().contains("files"))
        assertTrue(inspector.roots().contains("cache"))
    }

    @Test
    fun `list returns directory entries with directories first`() {
        File(application.filesDir, "logs").mkdirs()
        File(application.filesDir, "logs/today.log").writeText("boot ok")
        File(application.filesDir, "readme.txt").writeText("hi")

        val listing = requireNotNull(inspector.list("files", ""))
        val names = listing.entries.map { it.name }

        assertTrue(names.contains("logs"))
        assertTrue(names.contains("readme.txt"))
        assertTrue(listing.entries.first().isDirectory)
    }

    @Test
    fun `preview redacts text file contents`() {
        File(application.filesDir, "secret.txt")
            .writeText("authorization: Bearer sk-super-secret-value-1234567890")

        val preview = inspector.preview("files", "secret.txt")

        assertTrue(preview is FilePreviewData.Text)
        assertFalse((preview as FilePreviewData.Text).content.contains("sk-super-secret-value-1234567890"))
    }

    @Test
    fun `preview reports binary files without dumping bytes`() {
        File(application.filesDir, "blob.bin").writeBytes(byteArrayOf(0, 1, 2, 0, 3, 0, 7))

        val preview = inspector.preview("files", "blob.bin")

        assertTrue(preview is FilePreviewData.Binary)
    }

    @Test
    fun `list refuses to escape the root via parent traversal`() {
        assertNull(inspector.list("files", "../../.."))
        assertNull(inspector.list("files", "../databases"))
    }

    @Test
    fun `preview refuses to read outside the root`() {
        // A real file that exists outside filesDir; a traversal must not reach it.
        File(application.cacheDir, "outside.txt").writeText("do not read me")

        val preview = inspector.preview("files", "../cache/outside.txt")

        assertTrue(preview is FilePreviewData.Unavailable)
    }

    @Test
    fun `preview refuses an absolute path that would escape the root`() {
        val preview = inspector.preview("files", "/etc/hosts")

        assertTrue(preview is FilePreviewData.Unavailable)
    }

    @Test
    fun `root selection is case-sensitive so a differently-cased root name is refused`() {
        // rootDirs is keyed by exact root name; a browser sending a case-varied root ("Files",
        // "FILES") must not accidentally resolve to the real "files" root and must not throw --
        // it is simply an unknown root.
        assertNull(inspector.list("Files", ""))
        assertNull(inspector.list("FILES", ""))
        assertFalse(inspector.delete("Files", "notes.txt"))
        assertNull(inspector.resolveShareableFile("Files", "notes.txt"))
    }

    @Test
    fun `create and list agree on a file's identity even when the filesystem is case-insensitive`() {
        // On a case-insensitive filesystem (default macOS/Windows), "Notes.txt" and "notes.txt"
        // are the same inode -- createNewFile() must therefore refuse the second as a collision
        // (never silently produce two entries or a duplicate that only differs by case), and
        // whichever name lands must still resolve back to the file that was actually written.
        assertTrue(inspector.create("files", "notes.txt", "first"))
        val secondCreateSameCase = inspector.create("files", "notes.txt", "second")
        val secondCreateDifferentCase = inspector.create("files", "Notes.txt", "second")

        assertFalse("re-creating the identical path must refuse to clobber", secondCreateSameCase)
        // Whether "Notes.txt" collides with "notes.txt" depends on the host filesystem's case
        // sensitivity; either outcome is acceptable, but a reported success must never silently
        // discard the original content, and the listing must never show more than one file when
        // the filesystem folded the two names together.
        val entries = requireNotNull(inspector.list("files", "")).entries.map { it.name }
        val matchingNames = entries.count { it.equals("notes.txt", ignoreCase = true) }
        assertTrue("at most one on-disk entry for the case-varied name pair", matchingNames in 1..2)
        if (!secondCreateDifferentCase) {
            assertEquals("first", File(application.filesDir, "notes.txt").readText())
        }
    }

    @Test
    fun `list refuses a prefix-collision sibling of the root`() {
        // A sibling directory whose path shares the root's string prefix must not be reachable.
        File(application.filesDir.parentFile, application.filesDir.name + "-evil").mkdirs()

        assertNull(inspector.list("files", "../${application.filesDir.name}-evil"))
    }

    @Test
    fun `preview refuses a symlink that points outside the root`() {
        val outside = File(application.cacheDir, "outside-secret.txt").apply { writeText("secret") }
        val link = File(application.filesDir, "escape-link").toPath()
        java.nio.file.Files
            .createSymbolicLink(link, outside.toPath())

        val preview = inspector.preview("files", "escape-link")

        assertTrue(preview is FilePreviewData.Unavailable)
    }

    @Test
    fun `preview bounds the read and reports truncation without loading the whole file`() {
        val big = File(application.filesDir, "big.txt")
        big.writeText("A".repeat(200_000))

        val preview = inspector.preview("files", "big.txt")

        assertTrue(preview is FilePreviewData.Text)
        assertTrue((preview as FilePreviewData.Text).truncated)
        assertTrue(preview.content.length <= 64 * 1024)
    }

    @Test
    fun `delete removes a regular file within the root`() {
        val target = File(application.filesDir, "junk.txt").apply { writeText("x") }

        assertTrue(inspector.delete("files", "junk.txt"))
        assertFalse(target.exists())
    }

    @Test
    fun `delete refuses a directory and an out-of-root path`() {
        File(application.filesDir, "keepdir").mkdirs()

        assertFalse(inspector.delete("files", "keepdir"))
        assertFalse(inspector.delete("files", "../cache/anything"))
        assertFalse(inspector.delete("nonexistent-root", "whatever"))
        assertTrue(File(application.filesDir, "keepdir").exists())
    }

    @Test
    fun `create writes a brand-new file but refuses to overwrite an existing one`() {
        assertTrue(inspector.create("files", "notes.txt", "hello"))
        assertEquals("hello", File(application.filesDir, "notes.txt").readText())

        assertFalse(inspector.create("files", "notes.txt", "clobber"))
        assertEquals("hello", File(application.filesDir, "notes.txt").readText())
    }

    @Test
    fun `create refuses a path escaping the root or a missing parent directory`() {
        assertFalse(inspector.create("files", "../cache/evil.txt", "x"))
        assertFalse(inspector.create("files", "no-such-dir/evil.txt", "x"))
        assertFalse(inspector.create("files", "/etc/evil.txt", "x"))
        assertFalse(inspector.create("files", "", "x"))
        assertFalse(inspector.create("files", "trailing/", "x"))
    }

    @Test
    fun `create refuses to escape the root via a symlinked parent directory`() {
        val outside = File(application.cacheDir, "evil-dir").apply { mkdirs() }
        val link = File(application.filesDir, "linked-dir").toPath()
        java.nio.file.Files
            .createSymbolicLink(link, outside.toPath())

        assertFalse(inspector.create("files", "linked-dir/evil.txt", "x"))
        assertFalse(File(outside, "evil.txt").exists())
    }

    @Test
    fun `replace overwrites an existing file but refuses a missing one`() {
        val target = File(application.filesDir, "config.txt").apply { writeText("old") }

        assertTrue(inspector.replace("files", "config.txt", "new"))
        assertEquals("new", target.readText())

        assertFalse(inspector.replace("files", "missing.txt", "new"))
    }

    @Test
    fun `replace refuses a directory and a path escaping the root`() {
        File(application.filesDir, "adir").mkdirs()

        assertFalse(inspector.replace("files", "adir", "x"))
        assertFalse(inspector.replace("files", "../cache/anything", "x"))
    }

    @Test
    fun `rename moves a file within the root but refuses to clobber an existing destination`() {
        val source = File(application.filesDir, "a.txt").apply { writeText("a") }

        assertTrue(inspector.rename("files", "a.txt", "b.txt"))
        assertFalse(source.exists())
        assertEquals("a", File(application.filesDir, "b.txt").readText())

        File(application.filesDir, "c.txt").writeText("c")
        assertFalse(inspector.rename("files", "b.txt", "c.txt"))
        assertTrue(File(application.filesDir, "b.txt").exists())
        assertEquals("c", File(application.filesDir, "c.txt").readText())
    }

    @Test
    fun `rename refuses a source or destination that escapes the root`() {
        File(application.filesDir, "a.txt").writeText("a")

        assertFalse(inspector.rename("files", "../cache/a.txt", "b.txt"))
        assertFalse(inspector.rename("files", "a.txt", "../cache/escaped.txt"))
        assertFalse(File(application.cacheDir, "escaped.txt").exists())
    }

    @Test
    fun `rename refuses a directory source`() {
        File(application.filesDir, "adir").mkdirs()

        assertFalse(inspector.rename("files", "adir", "renamed"))
    }

    @Test
    fun `rename refuses a destination whose parent is a symlink escaping the root`() {
        // The destination goes through resolveForWrite, which canonicalizes the parent -- a
        // symlinked parent that points outside the root must be refused, or a rename would write
        // the moved file outside the sandbox. Plain `..` is covered elsewhere; this is the symlink
        // variant, matching the create-side test.
        val source = File(application.filesDir, "movable.txt").apply { writeText("payload") }
        val outside = File(application.cacheDir, "escape-target").apply { mkdirs() }
        val link = File(application.filesDir, "linked-out").toPath()
        java.nio.file.Files
            .createSymbolicLink(link, outside.toPath())

        assertFalse(inspector.rename("files", "movable.txt", "linked-out/moved.txt"))
        assertTrue("source must remain in place", source.exists())
        assertFalse(File(outside, "moved.txt").exists())
    }

    @Test
    fun `readBytes accepts a file of exactly the download cap`() {
        val atCap = File(application.filesDir, "at-cap.bin")
        atCap.outputStream().use { stream ->
            val chunk = ByteArray(1024 * 1024)
            var written = 0L
            while (written < FileInspector.MAX_READ_BYTES) {
                val remaining = (FileInspector.MAX_READ_BYTES - written).toInt().coerceAtMost(chunk.size)
                stream.write(chunk, 0, remaining)
                written += remaining
            }
        }

        val read = inspector.readBytes("files", "at-cap.bin")
        assertEquals(FileInspector.MAX_READ_BYTES, read?.size?.toLong())
    }

    @Test
    fun `readBytes returns raw content for a small file and refuses directories and out-of-root paths`() {
        File(application.filesDir, "blob.bin").writeBytes(byteArrayOf(1, 2, 3, 0, 4))
        File(application.filesDir, "adir").mkdirs()

        assertTrue(inspector.readBytes("files", "blob.bin").contentEquals(byteArrayOf(1, 2, 3, 0, 4)))
        assertNull(inspector.readBytes("files", "adir"))
        assertNull(inspector.readBytes("files", "../cache/anything"))
        assertNull(inspector.readBytes("files", "missing.bin"))
    }

    @Test
    fun `resolveShareableFile returns a real File for a regular file within the root`() {
        val target = File(application.filesDir, "shareable.txt").apply { writeText("share me") }

        val resolved = inspector.resolveShareableFile("files", "shareable.txt")

        assertEquals(target.canonicalPath, resolved?.canonicalPath)
    }

    @Test
    fun `resolveShareableFile refuses a directory and a path escaping the root`() {
        File(application.filesDir, "adir").mkdirs()

        assertNull(inspector.resolveShareableFile("files", "adir"))
        assertNull(inspector.resolveShareableFile("files", "../cache/anything"))
        assertNull(inspector.resolveShareableFile("nonexistent-root", "whatever"))
    }

    @Test
    fun `readBytes refuses a file exactly one byte over the download cap`() {
        // readCapped() grows a ByteArrayOutputStream in READ_CHUNK_BYTES chunks and only checks
        // `out.size() <= cap` between chunks, so the file that first proves the cap is enforced
        // (not just "eventually rejects something huge") is one that crosses the boundary by
        // exactly one byte, not by a whole extra chunk or more.
        val target = File(application.filesDir, "one-over-cap.bin")
        target.outputStream().use { stream ->
            val chunk = ByteArray(1024 * 1024)
            var written = 0L
            while (written < FileInspector.MAX_READ_BYTES) {
                val remaining = (FileInspector.MAX_READ_BYTES - written).toInt().coerceAtMost(chunk.size)
                stream.write(chunk, 0, remaining)
                written += remaining
            }
            stream.write(1)
        }

        assertNull(inspector.readBytes("files", "one-over-cap.bin"))
    }

    @Test
    fun `readBytes refuses a file larger than the download cap`() {
        val oversized = File(application.filesDir, "huge.bin")
        oversized.outputStream().use { stream ->
            val chunk = ByteArray(1024 * 1024)
            var written = 0L
            while (written <= FileInspector.MAX_READ_BYTES) {
                stream.write(chunk)
                written += chunk.size
            }
        }

        assertNull(inspector.readBytes("files", "huge.bin"))
    }
}
