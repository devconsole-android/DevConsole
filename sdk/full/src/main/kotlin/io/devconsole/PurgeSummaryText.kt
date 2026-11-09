/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole

import io.devconsole.api.PurgeResult

/**
 * Human-readable summary of what [DevConsole.purgeCapturedData] removed, naming only what was actually
 * removed, e.g. "Removed 12 requests, 2 connections and 1 old run" or "Removed 8 old runs". A purge
 * that found nothing says so instead of listing four zeros.
 */
internal fun purgeSummaryText(result: PurgeResult): String {
    val parts =
        listOf(
            result.networkTransactions to "request",
            result.socketConnections to "connection",
            result.pushEvents to "push event",
            result.retainedRunsDeleted to "old run",
        ).filter { (count, _) -> count > 0 }
            .map { (count, noun) -> countPhrase(count, noun) }
    return when (parts.size) {
        0 -> "Nothing to purge"
        1 -> "Removed ${parts.single()}"
        else -> "Removed ${parts.dropLast(1).joinToString(", ")} and ${parts.last()}"
    }
}

/** "1 request" / "12 requests": the count plus its singular or plural noun. */
private fun countPhrase(
    count: Int,
    singular: String,
): String = "$count $singular" + if (count == 1) "" else "s"
