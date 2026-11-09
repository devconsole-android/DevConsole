/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/** Read and write the small escaped crash record left for the next process launch. */
internal object PendingCrashFile {
    fun write(
        file: File,
        summary: String,
        stackTrace: String,
    ) {
        file.parentFile?.mkdirs()
        BufferedWriter(OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8)).use { out ->
            out.write(summary.escapePendingField())
            out.newLine()
            out.write(stackTrace.escapePendingField())
        }
    }

    fun read(file: File): Pair<String, String>? {
        if (!file.isFile) return null
        val fields = file.readText(StandardCharsets.UTF_8).split('\n', limit = 2)
        val trace = fields.getOrNull(1)?.unescapePendingField().orEmpty()
        return fields.firstOrNull()?.unescapePendingField()?.let { summary -> summary to trace }
    }

    private fun String.escapePendingField(): String = replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

    private fun String.unescapePendingField(): String =
        buildString(length) {
            var index = 0
            while (index < this@unescapePendingField.length) {
                val current = this@unescapePendingField[index]
                if (current == '\\' && index + 1 < this@unescapePendingField.length) {
                    when (val escaped = this@unescapePendingField[index + 1]) {
                        'n' -> append('\n')
                        'r' -> append('\r')
                        else -> append(escaped)
                    }
                    index += 2
                } else {
                    append(current)
                    index++
                }
            }
        }
}
