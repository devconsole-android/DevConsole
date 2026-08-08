/**
 * @author Shakib
 * @since 08/08/26
 */
package io.devconsole.network.okhttp

import io.devconsole.network.NetworkRequestInput
import io.devconsole.network.NetworkResponseInput
import io.devconsole.network.NetworkTimingPhases
import io.devconsole.network.NetworkTransactionRecorder
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pending transaction state for a response whose body [DevConsoleOkHttpInterceptor] tees instead of
 * peeking eagerly: an unknown-length, non-binary, non-SSE body. The interceptor snapshots everything
 * it already knows at `intercept()` time -- the fully-built request input and a response envelope
 * template (status, headers, protocol, `fromCache`) -- and this class fills in the two things only
 * the host's own consumption of the body can reveal: the body bytes and the completion moment.
 *
 * Every teed response is recorded under one stable [transactionId], from up to two record calls:
 *
 * - A **provisional** metadata-only `"streaming"` record, scheduled by
 *   [recordProvisionallyWhileBodyStaysOpen] and emitted only if the body is still open after
 *   [PROVISIONAL_RECORD_DELAY_MS]. INVARIANT: every teed response is recorded -- a long-lived
 *   stream (NDJSON, long-poll, an SSE feed whose server omitted its content type) shows up in
 *   DevConsole while it is still open instead of only at its end, and a body the host neither
 *   reads to EOF nor closes still leaves this record instead of vanishing entirely. A finite body
 *   the host consumes promptly completes inside the grace period, so the common case still
 *   records exactly once.
 * - The **final** record at whichever comes first of body EOF or body close, carrying the captured
 *   bytes. Because both records share [transactionId], the final record *replaces* the provisional
 *   one in the store ([io.devconsole.network.NetworkTransactionStore.record] contract) -- one HTTP
 *   call never shows as two transactions.
 *
 * The final record is emitted exactly once -- [recorded] is an atomic guard because a `close()` on
 * one thread can race the read that hits EOF on another -- and both record paths enqueue under
 * [lock] so the provisional record can never be enqueued after (and thus clobber) the final one.
 * A close *before* EOF means the host abandoned the body mid-stream: the recorded body is only
 * what was delivered up to that point, and is marked `bodyOmittedReason = "partial"` so a
 * fragment is never presented as the complete response. All work is wrapped in `runCatching`, so
 * nothing here can ever throw into the host's reads.
 *
 * [phaseTimestamps] is the *live* per-call timing state handed out by
 * [DevConsoleOkHttpEventListenerFactory.phaseTimestampsFor], not a snapshot: by the time the body
 * completes, the factory has long evicted its map entry (the interceptor's `finally` runs when
 * `intercept()` returns, and `callEnd` fires on body exhaustion), but the object itself still holds
 * every recorded mark -- including `RESPONSE_BODY_END`, which OkHttp writes before the EOF read
 * returns -- so snapshotting it here yields complete phases.
 */
internal class DeferredResponseBodyCapture(
    private val recorder: NetworkTransactionRecorder,
    private val requestInput: NetworkRequestInput,
    private val responseTemplate: NetworkResponseInput,
    private val startedAtEpochMs: Long,
    private val phaseTimestamps: CallPhaseTimestamps?,
    private val maxCapturedBytes: Long,
) {
    private val transactionId: String = UUID.randomUUID().toString()
    private val recorded = AtomicBoolean(false)
    private val lock = Any()
    private val captured = Buffer()
    private var totalBytesDelivered = 0L

    @Volatile
    private var provisionalRecordTask: ScheduledFuture<*>? = null

    /**
     * Copies the [byteCount] bytes just appended to [deliveredInto] at [offset] into the bounded
     * capture buffer. Once [maxCapturedBytes] have been captured, later bytes are counted (so the
     * recorded `bodyLength` stays the true delivered total) but no longer copied. Copy failures are
     * swallowed: the host's own bytes were already delivered before this runs, and a broken capture
     * must never surface through the host's read.
     */
    fun onBytesDelivered(
        deliveredInto: Buffer,
        offset: Long,
        byteCount: Long,
    ) {
        runCatching {
            synchronized(lock) {
                if (recorded.get()) return@runCatching
                totalBytesDelivered += byteCount
                val remainingCapacity = maxCapturedBytes - captured.size
                if (remainingCapacity > 0L) {
                    deliveredInto.copyTo(captured, offset, minOf(byteCount, remainingCapacity))
                }
            }
        }
    }

    fun onBodyExhausted() = recordFinalTransactionOnce(reachedEndOfBody = true)

    fun onBodyClosed() = recordFinalTransactionOnce(reachedEndOfBody = false)

    /**
     * Schedules the provisional `"streaming"` record described in the class kdoc. Called by the
     * interceptor right before it hands the teed response to the host; scheduling failures are
     * swallowed because provisional visibility is strictly best-effort on top of the final record.
     */
    fun recordProvisionallyWhileBodyStaysOpen() {
        runCatching {
            provisionalRecordTask =
                watchdogScheduler.schedule(
                    ::recordProvisionalStreamingTransaction,
                    PROVISIONAL_RECORD_DELAY_MS,
                    TimeUnit.MILLISECONDS,
                )
        }
    }

    private fun recordProvisionalStreamingTransaction() {
        runCatching {
            synchronized(lock) {
                if (recorded.get()) return@runCatching
                val responseInput =
                    responseTemplate.withMetadata(
                        responseTemplate.metadata.copy(
                            bodyOmittedReason = "streaming",
                            timings = phaseTimestamps?.snapshot() ?: NetworkTimingPhases(),
                        ),
                    )
                // completedAtEpochMs is null on purpose: the body is still open, so the
                // transaction has no duration yet. The final record fills it in.
                recorder.record(requestInput, responseInput, startedAtEpochMs, null, transactionId)
            }
        }
    }

    private fun recordFinalTransactionOnce(reachedEndOfBody: Boolean) {
        if (!recorded.compareAndSet(false, true)) return
        runCatching { provisionalRecordTask?.cancel(false) }
        runCatching {
            val completedAtEpochMs = System.currentTimeMillis()
            // The whole read-and-enqueue runs under [lock]: the flag flipped above makes a
            // concurrently running provisional task either enqueue first (and be replaced) or see
            // the flag and skip -- it can never enqueue after this final record.
            synchronized(lock) {
                val bodyBytes = captured.readByteArray()
                val capturedWasCapped = totalBytesDelivered > bodyBytes.size
                val responseInput =
                    responseTemplate
                        .copy(body = bodyBytes)
                        .withMetadata(
                            responseTemplate.metadata.copy(
                                bodyLength = totalBytesDelivered,
                                bodyOmittedReason =
                                    when {
                                        !reachedEndOfBody -> "partial"
                                        capturedWasCapped -> "truncated"
                                        else -> null
                                    },
                                timings = phaseTimestamps?.snapshot() ?: NetworkTimingPhases(),
                            ),
                        )
                recorder.record(requestInput, responseInput, startedAtEpochMs, completedAtEpochMs, transactionId)
            }
        }
    }

    private companion object {
        /**
         * Grace period before a still-open teed body is provisionally recorded. Long enough that
         * an ordinary finite chunked/gzipped API response -- which the host typically drains in
         * well under this -- records exactly once, short enough that a stream held open for
         * minutes still becomes visible in DevConsole near-immediately.
         */
        const val PROVISIONAL_RECORD_DELAY_MS = 500L

        /**
         * Lazy for the same reason as [NetworkTransactionRecorder]'s default executor: merely
         * loading this class must never start a thread; only actually teeing a body may.
         */
        val watchdogScheduler: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "devconsole-okhttp-stream-watchdog").apply { isDaemon = true }
            }
        }
    }
}

/**
 * Tee between the host's reads and a [DeferredResponseBodyCapture]: every byte the host pulls is
 * first delivered untouched into the host's own sink by the delegate, then copied (bounded) as a
 * side effect. EOF and close each notify the capture so it can record at whichever happens first.
 *
 * A delegate read that throws propagates unchanged and notifies nothing -- the host's subsequent
 * `close()` (every well-behaved consumer closes a failed body) is what records the transaction with
 * whatever bytes had been delivered by then. [close] notifies in a `finally` so even a close failure
 * still records.
 */
internal class TeeCapturingSource(
    delegate: Source,
    private val capture: DeferredResponseBodyCapture,
) : ForwardingSource(delegate) {
    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        val bytesRead = super.read(sink, byteCount)
        if (bytesRead == -1L) {
            capture.onBodyExhausted()
        } else {
            capture.onBytesDelivered(sink, sink.size - bytesRead, bytesRead)
        }
        return bytesRead
    }

    override fun close() {
        try {
            super.close()
        } finally {
            capture.onBodyClosed()
        }
    }
}
