package io.devconsole.network

import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryNetworkTransactionStoreTest {
    @Test
    fun `byte capacity evicts oldest transactions even below count capacity`() {
        val store =
            InMemoryNetworkTransactionStore(
                NetworkCursorCodec("network-cursor-key".encodeToByteArray()),
                maxTransactions = 10,
            ).withByteCapacity(700)
        val factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default()))

        repeat(3) { index ->
            store.record(
                NetworkTransaction(
                    id = "transaction-$index",
                    startedAtEpochMs = index.toLong(),
                    completedAtEpochMs = index.toLong(),
                    capture =
                        factory.capture(
                            NetworkRequestInput(
                                "POST",
                                "https://api.test/$index",
                                body = "x".repeat(500).encodeToByteArray(),
                                contentType = "text/plain",
                            ),
                            null,
                        ),
                ),
            )
        }

        val remaining = store.page(NetworkTransactionQuery(limit = 10)).transactions
        assertEquals(listOf("transaction-2"), remaining.map { it.id })
        assertTrue(remaining.sumOf { it.capture.estimatedSizeBytes() } <= 700)
    }

    @Test
    fun `pages newest matching transactions with an opaque cursor`() {
        val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
        store.record(transaction("first", 100, "GET", "https://api.test/first", 200))
        store.record(transaction("second", 200, "POST", "https://api.test/second", 201))
        store.record(transaction("third", 300, "GET", "https://other.test/third", 200))

        val firstPage = store.page(NetworkTransactionQuery(limit = 1, hosts = setOf("api.test")))
        val secondPage =
            store.page(
                NetworkTransactionQuery(limit = 1, hosts = setOf("api.test"), cursor = firstPage.nextCursor),
            )

        assertEquals(listOf("second"), firstPage.transactions.map(NetworkTransaction::id))
        assertTrue(firstPage.hasMore)
        assertEquals(listOf("first"), secondPage.transactions.map(NetworkTransaction::id))
        assertNull(secondPage.nextCursor)
    }

    @Test
    fun `rejects a tampered cursor instead of restarting the list`() {
        val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
        store.record(transaction("only", 100, "GET", "https://api.test/only", 200))

        val page = store.page(NetworkTransactionQuery(cursor = "tampered.cursor"))

        assertTrue(page.invalidCursor)
        assertTrue(page.transactions.isEmpty())
    }

    @Test
    fun `rejects a validly-signed cursor replayed under a different filter set`() {
        // The cursor's HMAC binds it to the exact filter set it was minted under. Replaying it with
        // a different scope must be rejected rather than silently paging the wrong result set --
        // the same guarantee the timeline keyset cursor makes.
        val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
        store.record(transaction("first", 100, "GET", "https://api.test/first", 200))
        store.record(transaction("second", 200, "POST", "https://api.test/second", 201))
        store.record(transaction("third", 300, "GET", "https://other.test/third", 200))

        val hostScoped = store.page(NetworkTransactionQuery(limit = 1, hosts = setOf("api.test")))
        assertNotNull(hostScoped.nextCursor)

        val replayedUnderDifferentScope =
            store.page(NetworkTransactionQuery(limit = 1, hosts = setOf("other.test"), cursor = hostScoped.nextCursor))

        assertTrue(replayedUnderDifferentScope.invalidCursor)
        assertTrue(replayedUnderDifferentScope.transactions.isEmpty())
    }

    @Test
    fun `statusDistribution buckets transactions by status code prefix and pending`() {
        val store = InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16)))
        store.record(transactionWithStatus(200))
        store.record(transactionWithStatus(201))
        store.record(transactionWithStatus(404))
        store.record(transactionWithStatus(500))
        store.record(transactionWithStatus(null))

        val distribution = store.statusDistribution()

        assertEquals(2, distribution["2xx"])
        assertEquals(1, distribution["4xx"])
        assertEquals(1, distribution["5xx"])
        assertEquals(1, distribution["pending"])
    }

    @Test
    fun `capacity changes prune oldest rows immediately and govern future records`() {
        val store =
            InMemoryNetworkTransactionStore(
                NetworkCursorCodec("network-cursor-key".encodeToByteArray()),
                maxTransactions = 3,
            )
        store.record(transaction("first", 100, "GET", "https://api.test/first", 200))
        store.record(transaction("second", 200, "GET", "https://api.test/second", 200))
        store.record(transaction("third", 300, "GET", "https://api.test/third", 200))

        store.withCapacity(2)
        store.record(transaction("fourth", 400, "GET", "https://api.test/fourth", 200))

        assertEquals(
            listOf("fourth", "third"),
            store.page(NetworkTransactionQuery(limit = 10)).transactions.map(NetworkTransaction::id),
        )
    }

    @Test
    fun `advanced filters cover time path content duration error tags and free text`() {
        val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
        store.record(
            transaction(
                "matching",
                200,
                "POST",
                "https://api.test/v1/orders",
                503,
            ).withCaptureMetadata(
                tags = mapOf("source" to "composer"),
                contentType = "application/json",
                error = "timeout",
            ),
        )
        store.record(transaction("wrong-path", 210, "POST", "https://api.test/v1/profile", 200))
        store.record(transaction("wrong-time", 50, "POST", "https://api.test/v1/orders", 503))

        val page =
            store.page(
                NetworkTransactionQuery(methods = setOf("POST"))
                    .withFilters(
                        NetworkTransactionFilters(
                            fromEpochMs = 100,
                            toEpochMs = 500,
                            paths = setOf("/v1/orders"),
                            contentTypes = setOf("application/json"),
                            minDurationMs = 5,
                            maxDurationMs = 20,
                            hasError = true,
                            tags = mapOf("source" to "composer"),
                            query = "timeout",
                        ),
                    ),
            )

        assertEquals(listOf("matching"), page.transactions.map(NetworkTransaction::id))
    }

    private fun transaction(
        id: String,
        startedAt: Long,
        method: String,
        url: String,
        status: Int,
    ) = NetworkTransaction(
        id = id,
        startedAtEpochMs = startedAt,
        completedAtEpochMs = startedAt + 10,
        capture =
            NetworkCaptureFactory(
                io.devconsole.security.RedactionEngine(
                    io.devconsole.security.RedactionPolicy
                        .default(),
                ),
            ).capture(
                NetworkRequestInput(method, url),
                NetworkResponseInput(status),
            ),
    )

    private fun transactionWithStatus(status: Int?) =
        NetworkTransaction(
            id =
                java.util.UUID
                    .randomUUID()
                    .toString(),
            startedAtEpochMs = 100,
            completedAtEpochMs = if (status == null) null else 110,
            capture =
                NetworkCaptureFactory(
                    io.devconsole.security.RedactionEngine(
                        io.devconsole.security.RedactionPolicy
                            .default(),
                    ),
                ).capture(
                    NetworkRequestInput("GET", "https://api.test/status"),
                    status?.let { NetworkResponseInput(it) },
                ),
        )

    private fun NetworkTransaction.withCaptureMetadata(
        tags: Map<String, String>,
        contentType: String,
        error: String,
    ): NetworkTransaction =
        copy(
            capture =
                NetworkCaptureFactory(
                    io.devconsole.security.RedactionEngine(
                        io.devconsole.security.RedactionPolicy
                            .default(),
                    ),
                ).capture(
                    NetworkRequestInput("POST", capture.request.url.display, contentType = contentType)
                        .withMetadata(NetworkRequestMetadata(tags = tags)),
                    NetworkResponseInput(capture.response!!.statusCode, error = error),
                ),
        )
}
