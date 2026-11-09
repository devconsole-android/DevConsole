/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole

import io.devconsole.composer.ComposerCollection
import io.devconsole.composer.ComposerCollectionStore
import io.devconsole.composer.ComposerRequest
import io.devconsole.composer.InMemoryComposerCollectionStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * File-backed [ComposerCollectionStore] that keeps an in-memory delegate as the source of truth,
 * lazily deserializes from disk on first access, and persists state atomically after every save or remove.
 */
internal class FileComposerCollectionStore(
    private val file: File,
    private val delegate: InMemoryComposerCollectionStore = InMemoryComposerCollectionStore(),
) : ComposerCollectionStore {
    private val lock = Any()
    private var loaded = false

    override fun save(
        name: String,
        request: ComposerRequest,
    ): ComposerCollection =
        synchronized(lock) {
            ensureLoaded()
            val collection = delegate.save(name, request)
            persistLocked()
            collection
        }

    override fun collections(): List<ComposerCollection> =
        synchronized(lock) {
            ensureLoaded()
            delegate.collections()
        }

    override fun remove(id: String): Boolean =
        synchronized(lock) {
            ensureLoaded()
            val removed = delegate.remove(id)
            if (removed) {
                persistLocked()
            }
            removed
        }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        loadFromDisk()
    }

    private fun loadFromDisk() {
        if (!file.exists()) return
        val restored =
            runCatching {
                val root = JSONObject(file.readText(StandardCharsets.UTF_8))
                // A newer-version file is set aside rather than overwritten on the next save.
                check(root.optInt(KEY_VERSION, -1) == CURRENT_VERSION) { "Unsupported collections file version" }
                val array = root.getJSONArray(KEY_COLLECTIONS)
                List(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    item.getString("name") to item.getJSONObject("request").toComposerRequest()
                }
            }.getOrNull()
        if (restored == null) {
            quarantineCorruptFile()
        } else {
            restored.forEach { (name, request) -> delegate.save(name, request) }
        }
    }

    private fun quarantineCorruptFile() {
        runCatching {
            val corrupt = file.resolveSibling(file.name + CORRUPT_EXTENSION)
            if (file.renameTo(corrupt)) return@runCatching
            file.inputStream().use { input ->
                corrupt.outputStream().use(input::copyTo)
            }
            file.delete()
        }
    }

    private fun persistLocked() {
        runCatching {
            file.parentFile?.mkdirs()
            val root = JSONObject()
            root.put(KEY_VERSION, CURRENT_VERSION)
            val array = JSONArray()
            for (collection in delegate.collections()) {
                array.put(collection.toJson())
            }
            root.put(KEY_COLLECTIONS, array)
            val tmp = file.resolveSibling(file.name + TMP_EXTENSION)
            writeStringToFile(tmp, root.toString())
            moveAtomically(tmp, file)
        }
    }

    private fun writeStringToFile(
        target: File,
        content: String,
    ) {
        OutputStreamWriter(FileOutputStream(target), StandardCharsets.UTF_8).use { writer ->
            writer.write(content)
        }
    }

    private fun moveAtomically(
        source: File,
        destination: File,
    ) {
        if (source.renameTo(destination)) return
        source.inputStream().use { input ->
            destination.outputStream().use(input::copyTo)
        }
        source.delete()
    }

    private companion object {
        private const val CURRENT_VERSION = 1
        private const val KEY_VERSION = "v"
        private const val KEY_COLLECTIONS = "collections"
        private const val TMP_EXTENSION = ".tmp"
        private const val CORRUPT_EXTENSION = ".corrupt"
    }
}
