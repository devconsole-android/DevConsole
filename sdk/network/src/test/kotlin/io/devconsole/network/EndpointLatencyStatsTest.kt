/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole.network

import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointLatencyStatsTest {
    private val factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default()))

    @Test
    fun `replaces numeric uuid and long alphanumeric digit segments with id placeholder`() {
        val transactions =
            listOf(
                tx("1", "GET", "https://api.test/v1/orders/42198/items", 100, 120, 200),
                tx(
                    "2",
                    "GET",
                    "https://api.test/v1/orders/550e8400-e29b-41d4-a716-446655440000/items",
                    100,
                    140,
                    200,
                ),
                tx("3", "GET", "https://api.test/v1/orders/abc123def456ghi7/items", 100, 160, 200),
                tx("4", "GET", "https://api.test/v1/orders/abcdefghijklmnop/items", 100, 110, 200),
            )

        val stats = EndpointLatencyStats.from(transactions)

        assertEquals(2, stats.size)
        val templated = stats.first { it.pathTemplate == "/v1/orders/{id}/items" }
        assertEquals(3, templated.count)
        assertEquals(40L, templated.p50Ms)
        assertEquals(60L, templated.p95Ms)
        assertEquals(60L, templated.maxMs)

        val literalAlpha = stats.first { it.pathTemplate == "/v1/orders/abcdefghijklmnop/items" }
        assertEquals(1, literalAlpha.count)
    }

    @Test
    fun `computes nearest-rank percentiles and ignores incomplete transactions`() {
        val completed =
            (1..20).map { index ->
                tx(
                    id = "tx-$index",
                    method = "GET",
                    url = "https://api.test/v1/feed",
                    startedAt = 1000L,
                    completedAt = 1000L + (index * 10L),
                    status = 200,
                )
            }
        val pending =
            tx(
                id = "pending",
                method = "GET",
                url = "https://api.test/v1/feed",
                startedAt = 1000L,
                completedAt = null,
                status = null,
            )

        val stats = EndpointLatencyStats.from(completed + pending)

        assertEquals(1, stats.size)
        val entry = stats.single()
        assertEquals(20, entry.count)
        assertEquals(0, entry.errorCount)
        assertEquals(100L, entry.p50Ms)
        assertEquals(190L, entry.p95Ms)
        assertEquals(200L, entry.p99Ms)
        assertEquals(200L, entry.maxMs)
    }

    @Test
    fun `counts null response response error and status 400 or above as errors`() {
        val transactions =
            listOf(
                tx("ok", "POST", "https://api.test/v1/pay", 10, 20, 200),
                tx("bad-req", "POST", "https://api.test/v1/pay", 10, 30, 400),
                tx("server-err", "POST", "https://api.test/v1/pay", 10, 40, 503),
                tx("io-err", "POST", "https://api.test/v1/pay", 10, 50, 200, error = "reset"),
                tx("no-resp", "POST", "https://api.test/v1/pay", 10, 60, null),
            )

        val entry = EndpointLatencyStats.from(transactions).single()

        assertEquals(5, entry.count)
        assertEquals(4, entry.errorCount)
    }

    @Test
    fun `sorts by p95 descending then count descending and caps at limit`() {
        val transactions =
            listOf(
                tx("a1", "GET", "https://api.test/fast-many", 0, 10, 200),
                tx("a2", "GET", "https://api.test/fast-many", 0, 10, 200),
                tx("b1", "GET", "https://api.test/fast-one", 0, 10, 200),
                tx("c1", "GET", "https://api.test/slow", 0, 90, 200),
            )

        val all = EndpointLatencyStats.from(transactions)
        assertEquals(listOf("/slow", "/fast-many", "/fast-one"), all.map { it.pathTemplate })

        val limited = EndpointLatencyStats.from(transactions, limit = 2)
        assertEquals(listOf("/slow", "/fast-many"), limited.map { it.pathTemplate })
        assertTrue(EndpointLatencyStats.from(transactions, limit = 0).isEmpty())
    }

    @Suppress("LongParameterList") // Positional fixture rows keep each test's table readable.
    private fun tx(
        id: String,
        method: String,
        url: String,
        startedAt: Long,
        completedAt: Long?,
        status: Int?,
        error: String? = null,
    ): NetworkTransaction =
        NetworkTransaction(
            id = id,
            startedAtEpochMs = startedAt,
            completedAtEpochMs = completedAt,
            capture =
                factory.capture(
                    NetworkRequestInput(method, url),
                    status?.let { NetworkResponseInput(it, error = error) },
                ),
        )
}
