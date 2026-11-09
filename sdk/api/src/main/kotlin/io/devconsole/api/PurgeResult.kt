/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole.api

/**
 * Counts of captured records and retained sessions removed by [io.devconsole.DevConsole.purgeCapturedData].
 */
data class PurgeResult(
    val networkTransactions: Int,
    val socketConnections: Int,
    val pushEvents: Int,
    val retainedRunsDeleted: Int,
) {
    companion object {
        val NOTHING = PurgeResult(0, 0, 0, 0)
    }
}
