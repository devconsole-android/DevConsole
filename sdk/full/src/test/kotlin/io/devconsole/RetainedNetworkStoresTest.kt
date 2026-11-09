/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole

import io.devconsole.network.BodyPreview
import io.devconsole.network.CapturedRequest
import io.devconsole.network.CapturedResponse
import io.devconsole.network.NetworkCapture
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkTransaction
import io.devconsole.network.NetworkTransactionQuery
import io.devconsole.network.NetworkUrl
import io.devconsole.storage.api.StoredEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetainedNetworkStoresTest {
    private val codecKey = "0123456789abcdef0123456789abcdef".toByteArray()
    private val cursorCodec = NetworkCursorCodec(codecKey)

    private fun sampleTransaction(
        id: String,
        method: String,
        startedAtEpochMs: Long,
    ): NetworkTransaction {
        val req =
            CapturedRequest(
                method = method,
                url = NetworkUrl("https", "test.io", "/res/$id", emptyMap()),
                headers = emptyMap(),
                body = BodyPreview.Absent,
                contentType = null,
                correlationId = null,
                pluginId = "network",
            )
        val resp =
            CapturedResponse(
                statusCode = 200,
                headers = emptyMap(),
                body = BodyPreview.Absent,
                contentType = null,
                protocol = "HTTP/1.1",
                error = null,
            )
        return NetworkTransaction(
            id = id,
            startedAtEpochMs = startedAtEpochMs,
            completedAtEpochMs = startedAtEpochMs + 50L,
            capture = NetworkCapture(req, resp),
        )
    }

    private fun storedEvent(
        id: String,
        pluginId: String,
        payloadJson: String?,
        timestamp: Long,
    ) = StoredEvent(
        id = id,
        sessionId = "session-1",
        sequence = timestamp,
        pluginId = pluginId,
        type = "timeline",
        wallTimeMs = timestamp,
        monoTimeNs = timestamp * 1_000_000L,
        severity = 0,
        summary = "event $id",
        correlationId = null,
        tagsJson = "{}",
        payloadJson = payloadJson,
        attachmentId = null,
    )

    @Test
    fun `loads transactions newest first and skips logs and malformed network payloads`() =
        runTest {
            val tx1 = sampleTransaction("tx-1", "GET", 1_000L)
            val tx2 = sampleTransaction("tx-2", "POST", 2_000L)

            val eventsList =
                listOf(
                    storedEvent("e1", "network", CapturePayloadCodec.network(tx1), 1_000L),
                    storedEvent("e2", "logs", """{"log":"some log"}""", 1_500L),
                    storedEvent("e3", "network", "malformed json {", 1_800L),
                    storedEvent("e4", "network", CapturePayloadCodec.network(tx2), 2_000L),
                )

            val stores =
                RetainedNetworkStores(
                    events = { eventsList },
                    cursorCodec = cursorCodec,
                )

            val store = stores.storeFor("session-1")
            assertNotNull(store)

            val page = store.page(NetworkTransactionQuery())
            assertEquals(2, page.transactions.size)
            // Store ordering is newest-first (descending startedAtEpochMs)
            assertEquals("tx-2", page.transactions[0].id)
            assertEquals("tx-1", page.transactions[1].id)

            // Query filters work (e.g. method filter)
            val postPage = store.page(NetworkTransactionQuery(methods = setOf("POST")))
            assertEquals(1, postPage.transactions.size)
            assertEquals("tx-2", postPage.transactions[0].id)
        }

    @Test
    fun `caches session store and invalidate forces reload`() =
        runTest {
            val callCount = AtomicInteger(0)
            val tx = sampleTransaction("tx-cached", "GET", 10_000L)
            val eventsList =
                listOf(
                    storedEvent("e-c", "network", CapturePayloadCodec.network(tx), 10_000L),
                )

            val stores =
                RetainedNetworkStores(
                    events = {
                        callCount.incrementAndGet()
                        eventsList
                    },
                    cursorCodec = cursorCodec,
                )

            val storeFirst = stores.storeFor("session-cached")
            assertNotNull(storeFirst)
            assertEquals(1, callCount.get())

            // Second call should return cached store without calling events again
            val storeSecond = stores.storeFor("session-cached")
            assertNotNull(storeSecond)
            assertEquals(1, callCount.get())

            // Invalidate forces reload on next access
            stores.invalidate("session-cached")
            val storeThird = stores.storeFor("session-cached")
            assertNotNull(storeThird)
            assertEquals(2, callCount.get())
        }

    @Test
    fun `session with no decodable network events yields an empty store`() =
        runTest {
            val eventsList =
                listOf(
                    storedEvent("e-log", "logs", """{"log":"hello"}""", 5_000L),
                    storedEvent("e-bad-net", "network", "invalid", 6_000L),
                )

            val stores =
                RetainedNetworkStores(
                    events = { eventsList },
                    cursorCodec = cursorCodec,
                )

            val store = stores.storeFor("empty-session")
            assertEquals(0, store.page(NetworkTransactionQuery()).transactions.size)
        }

    @Test
    fun `evicts least recently used session when cache limit is exceeded`() =
        runTest {
            val callCount = AtomicInteger(0)

            fun eventsFor(sessionId: String) =
                listOf(
                    storedEvent(
                        id = "e-$sessionId",
                        pluginId = "network",
                        payloadJson = CapturePayloadCodec.network(sampleTransaction("tx-$sessionId", "GET", 1_000L)),
                        timestamp = 1_000L,
                    ),
                )

            val stores =
                RetainedNetworkStores(
                    events = { sessionId ->
                        callCount.incrementAndGet()
                        eventsFor(sessionId)
                    },
                    cursorCodec = cursorCodec,
                    maxCachedSessions = 2,
                )

            assertNotNull(stores.storeFor("s1"))
            assertEquals(1, callCount.get())

            assertNotNull(stores.storeFor("s2"))
            assertEquals(2, callCount.get())

            // Accessing s1 marks s1 as MRU
            assertNotNull(stores.storeFor("s1"))
            assertEquals(2, callCount.get())

            // Adding s3 evicts s2 (since s1 was accessed more recently)
            assertNotNull(stores.storeFor("s3"))
            assertEquals(3, callCount.get())

            // s1 should still be cached
            assertNotNull(stores.storeFor("s1"))
            assertEquals(3, callCount.get())

            // s2 was evicted, so accessing s2 should reload
            assertNotNull(stores.storeFor("s2"))
            assertEquals(4, callCount.get())
        }
}
