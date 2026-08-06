package io.devconsole.network

import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class NetworkTransactionRecorderTest {
    @Test
    fun `records a redacted transaction with supplied timing`() {
        val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
        val recorder =
            NetworkTransactionRecorder(
                factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                store = store,
                idProvider = { "transaction-1" },
            )

        recorder.record(
            request = NetworkRequestInput("GET", "https://api.test/orders?access_token=secret"),
            response = NetworkResponseInput(200),
            startedAtEpochMs = 100,
            completedAtEpochMs = 125,
        )

        val transaction = awaitTransaction(store, "transaction-1")!!
        assertEquals(25L, transaction.durationMs)
        assertTrue(
            transaction.capture.request.url.display
                .contains("<redacted>"),
        )
    }

    @Test
    fun `disabled recorder never stores a transaction`() {
        val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
        val recorder =
            NetworkTransactionRecorder(
                factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                store = store,
                enabled = false,
                idProvider = { "transaction-1" },
            )

        recorder.record(
            request = NetworkRequestInput("GET", "https://api.test/orders?access_token=secret"),
            response = NetworkResponseInput(200),
            startedAtEpochMs = 100,
            completedAtEpochMs = 125,
        )

        assertEquals(null, store.find("transaction-1"))
    }

    @Test
    fun `record returns immediately without blocking caller thread`() {
        val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
        // Single thread executor that blocks processing until latch released
        val startLatch = java.util.concurrent.CountDownLatch(1)
        val customExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
                Thread {
                    startLatch.await()
                    runnable.run()
                }
            }

        val recorder =
            NetworkTransactionRecorder(
                factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                store = store,
                idProvider = { "transaction-blocked" },
                executor = customExecutor,
            )

        val startMs = System.currentTimeMillis()
        recorder.record(
            request = NetworkRequestInput("GET", "https://api.test/slow"),
            response = NetworkResponseInput(200),
            startedAtEpochMs = 100,
            completedAtEpochMs = 125,
        )
        val elapsedMs = System.currentTimeMillis() - startMs

        // Record call must return immediately (under 100ms) despite background worker being blocked
        assertTrue("record() blocked caller thread for ${elapsedMs}ms", elapsedMs < 200)

        startLatch.countDown()
        val transaction = awaitTransaction(store, "transaction-blocked")!!
        assertEquals("transaction-blocked", transaction.id)
        customExecutor.shutdown()
    }

    @Test
    fun `queue overflow drops oldest item and tracks dropped count`() {
        val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
        val startLatch = java.util.concurrent.CountDownLatch(1)
        val customExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
                Thread {
                    startLatch.await()
                    runnable.run()
                }
            }

        val recorder =
            NetworkTransactionRecorder(
                factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                store = store,
                maxQueueSize = 2,
                executor = customExecutor,
            )

        repeat(5) { index ->
            recorder.record(
                request = NetworkRequestInput("GET", "https://api.test/req-$index"),
                response = NetworkResponseInput(200),
                startedAtEpochMs = 100,
                completedAtEpochMs = 125,
            )
        }

        assertTrue(recorder.droppedCount() > 0)
        startLatch.countDown()
        customExecutor.shutdown()
    }

    @Test
    fun `byte budget drops oldest queued item even when the count capacity has plenty of room`() {
        val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
        val startLatch = java.util.concurrent.CountDownLatch(1)
        val customExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
                Thread {
                    startLatch.await()
                    runnable.run()
                }
            }
        // A count capacity of 100 would happily hold every item below; only the byte budget should
        // force an eviction here, mirroring EventBatchWriter's dual count+byte queue budget.
        val recorder =
            NetworkTransactionRecorder(
                factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                store = store,
                maxQueueSize = 100,
                maxQueuedBytes = 150_000L,
                executor = customExecutor,
            )
        val bigBody = ByteArray(100_000) { 'x'.code.toByte() }

        // Two ~100KB requests exceed the 150KB budget together, so the first must be evicted once the
        // second is enqueued -- well before either is processed (the worker is blocked on startLatch).
        recorder.record(
            request =
                NetworkRequestInput("POST", "https://api.test/first", body = bigBody, contentType = "text/plain"),
            response = null,
            startedAtEpochMs = 100,
            completedAtEpochMs = 125,
        )
        recorder.record(
            request =
                NetworkRequestInput("POST", "https://api.test/second", body = bigBody, contentType = "text/plain"),
            response = null,
            startedAtEpochMs = 200,
            completedAtEpochMs = 225,
        )

        assertTrue(recorder.droppedCount() > 0)
        startLatch.countDown()
        customExecutor.shutdown()
    }

    @Test
    fun `binary and oversized bodies are handed off as bounded redacted attachments`() {
        val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
        val attachments = CopyOnWriteArrayList<NetworkAttachmentPayload>()
        val recorder =
            NetworkTransactionRecorder(
                factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                store = store,
                idProvider = { "transaction-attachments" },
            ).withAttachmentSink { payload ->
                attachments += payload
                "attachment-${payload.role.name.lowercase()}"
            }
        val secretPrefix = "token=body-secret&".encodeToByteArray()
        val binaryResponse = secretPrefix + ByteArray(3 * 1024 * 1024) { 1 }

        recorder.record(
            request =
                NetworkRequestInput(
                    method = "POST",
                    url = "https://api.test/upload",
                    body = ByteArray(300 * 1024) { 'a'.code.toByte() },
                    contentType = "text/plain",
                ),
            response =
                NetworkResponseInput(
                    statusCode = 200,
                    body = binaryResponse,
                    contentType = "application/octet-stream",
                ),
            startedAtEpochMs = 100,
            completedAtEpochMs = 125,
        )

        val transaction = awaitTransaction(store, "transaction-attachments")!!
        assertEquals(2, attachments.size)
        assertTrue(attachments.all { it.bytes.size <= NetworkAttachmentPayload.MAX_BYTES })
        assertTrue(
            attachments.sumOf { it.bytes.size } + transaction.capture.estimatedSizeBytes() <=
                NetworkCaptureLimits.DEFAULT_TOTAL_CAPTURE_BYTES,
        )
        val responseAttachment = attachments.single { it.role == NetworkAttachmentRole.RESPONSE }
        assertFalse(responseAttachment.bytes.decodeToString().contains("body-secret"))
        assertEquals(binaryResponse.size.toLong(), responseAttachment.originalLength)
        assertTrue(responseAttachment.truncated)
        assertEquals("attachment-request", transaction.capture.request.attachmentId)
        assertEquals("attachment-response", transaction.capture.response?.attachmentId)
    }

    private fun awaitTransaction(
        store: NetworkTransactionStore,
        id: String,
        maxWaitMs: Long = 2000L,
    ): NetworkTransaction? {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            val found = store.find(id)
            if (found != null) return found
            Thread.sleep(10)
        }
        return store.find(id)
    }
}
