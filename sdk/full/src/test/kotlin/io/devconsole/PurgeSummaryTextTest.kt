/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole

import io.devconsole.api.PurgeResult
import org.junit.Assert.assertEquals
import org.junit.Test

class PurgeSummaryTextTest {
    @Test
    fun `plural counts read naturally`() {
        val result =
            PurgeResult(
                networkTransactions = 12,
                socketConnections = 2,
                pushEvents = 0,
                retainedRunsDeleted = 1,
            )

        assertEquals("Removed 12 requests, 2 connections and 1 old run", purgeSummaryText(result))
    }

    @Test
    fun `singular counts use singular nouns`() {
        val result =
            PurgeResult(
                networkTransactions = 1,
                socketConnections = 1,
                pushEvents = 1,
                retainedRunsDeleted = 1,
            )

        assertEquals("Removed 1 request, 1 connection, 1 push event and 1 old run", purgeSummaryText(result))
    }

    @Test
    fun `a single non-zero count is named alone`() {
        val result =
            PurgeResult(networkTransactions = 0, socketConnections = 0, pushEvents = 0, retainedRunsDeleted = 8)

        assertEquals("Removed 8 old runs", purgeSummaryText(result))
    }

    @Test
    fun `an empty purge says nothing was purged`() {
        assertEquals("Nothing to purge", purgeSummaryText(PurgeResult.NOTHING))
    }
}
