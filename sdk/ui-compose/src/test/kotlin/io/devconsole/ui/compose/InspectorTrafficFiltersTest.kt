/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class InspectorTrafficFiltersTest {
    private fun transaction(
        id: String,
        host: String,
    ) = InspectorTransactionUi(
        id = id,
        method = "GET",
        host = host,
        path = "/orders",
        statusCode = 200,
        durationMs = null,
    )

    @Test
    fun `hosts are ranked by capture count`() {
        val transactions =
            listOf(
                transaction("1", "api.example.test"),
                transaction("2", "api.example.test"),
                transaction("3", "cdn.example.test"),
                transaction("4", "img.example.test"),
                transaction("5", "img.example.test"),
            )

        assertEquals(
            listOf("api.example.test", "img.example.test", "cdn.example.test"),
            topTrafficHosts(transactions),
        )
    }

    @Test
    fun `the host list is capped and ties stay alphabetical`() {
        val transactions = (1..10).map { index -> transaction("tx-$index", "host-$index.example.test") }

        val hosts = topTrafficHosts(transactions)

        assertEquals(8, hosts.size)
        assertEquals(
            listOf(
                "host-1.example.test",
                "host-10.example.test",
                "host-2.example.test",
                "host-3.example.test",
                "host-4.example.test",
                "host-5.example.test",
                "host-6.example.test",
                "host-7.example.test",
            ),
            hosts,
        )
    }
}
