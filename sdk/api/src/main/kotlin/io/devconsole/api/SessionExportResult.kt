/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole.api

import java.io.File

/**
 * Outcome of [io.devconsole.DevConsole.exportSession].
 */
sealed interface SessionExportResult {
    data class Exported(
        val file: File,
    ) : SessionExportResult

    data class Failed(
        val reason: String,
    ) : SessionExportResult

    data object Disabled : SessionExportResult
}
