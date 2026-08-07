/**
 * @author Shakib
 * @since 25/07/26
 */
package io.devconsole

import android.content.Context
import io.devconsole.security.RedactionEngine
import io.devconsole.server.api.PreferencesEntryData
import io.devconsole.server.api.PreferencesFileData
import io.devconsole.server.api.PreferencesInspector
import java.io.File

internal class AndroidPreferencesInspector(
    private val context: Context,
    private val redaction: RedactionEngine,
) : PreferencesInspector {
    override fun files(): List<PreferencesFileData> =
        preferenceFileNames().map { name ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            PreferencesFileData(
                name = name,
                entries =
                    prefs.all.entries
                        .map { (key, value) -> toEntry(key, value) }
                        .sortedBy { it.key },
            )
        }

    override fun put(
        file: String,
        key: String,
        value: String,
        type: String,
    ): Boolean =
        runCatching {
            if (file !in preferenceFileNames()) return false
            val editor = context.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
            when (type) {
                TYPE_BOOLEAN -> editor.putBoolean(key, value.toBooleanStrict())
                TYPE_INT -> editor.putInt(key, value.toInt())
                TYPE_LONG -> editor.putLong(key, value.toLong())
                TYPE_FLOAT -> editor.putFloat(key, value.toFloat())
                TYPE_STRING -> editor.putString(key, value)
                // String sets are read-only: the ", "-joined display form can't be reversed without
                // corrupting elements that themselves contain ", " (or a single empty-string member).
                else -> return false
            }
            editor.commit()
        }.getOrDefault(false)

    override fun remove(
        file: String,
        key: String,
    ): Boolean =
        runCatching {
            if (file !in preferenceFileNames()) return false
            context
                .getSharedPreferences(file, Context.MODE_PRIVATE)
                .edit()
                .remove(key)
                .commit()
        }.getOrDefault(false)

    /** Only files inside the app's own `shared_prefs` dir are ever listed or written. */
    private fun preferenceFileNames(): List<String> {
        val dir = File(context.applicationInfo.dataDir, SHARED_PREFS_DIR)
        return dir
            .listFiles { candidate -> candidate.isFile && candidate.name.endsWith(XML_SUFFIX) }
            ?.map { it.name.removeSuffix(XML_SUFFIX) }
            ?.sorted()
            .orEmpty()
    }

    private fun toEntry(
        key: String,
        value: Any?,
    ): PreferencesEntryData {
        val type =
            when (value) {
                is Boolean -> TYPE_BOOLEAN
                is Int -> TYPE_INT
                is Long -> TYPE_LONG
                is Float -> TYPE_FLOAT
                is String -> TYPE_STRING
                is Set<*> -> TYPE_STRING_SET
                else -> TYPE_UNKNOWN
            }
        val raw = if (value is Set<*>) value.joinToString(", ") else value.toString()
        val redactable = value is String || value is Set<*>
        val display = if (redactable) redaction.redactFields(mapOf(key to raw)).getValue(key) else raw
        return PreferencesEntryData(key, display, type, redacted = redactable && display != raw)
    }

    private companion object {
        const val SHARED_PREFS_DIR = "shared_prefs"
        const val XML_SUFFIX = ".xml"
        const val TYPE_BOOLEAN = "BOOLEAN"
        const val TYPE_INT = "INT"
        const val TYPE_LONG = "LONG"
        const val TYPE_FLOAT = "FLOAT"
        const val TYPE_STRING = "STRING"
        const val TYPE_STRING_SET = "STRING_SET"
        const val TYPE_UNKNOWN = "UNKNOWN"
    }
}
