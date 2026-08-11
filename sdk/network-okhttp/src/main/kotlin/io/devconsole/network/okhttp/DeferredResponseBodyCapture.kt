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
 * A close *before* EOF means the host abandoned the body mid-stream; [TeeCapturingSource.close]
 * first drains what is left within a bounded budget, so an abandoned body is normally still
 * recorded whole. Only when that drain cannot finish -- deadline, read failure, or the capture cap
 * -- is the record marked `bodyOmittedReason = "partial"` so a fragment is never presented as the
 * complete response, and a body no bytes at all were recovered from is recorded with no body
 * rather than an empty one. All work is wrapped in `runCatching`, so nothing here can ever throw
 * into the host's reads.
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
     * Whether pulling more bytes out of the body could still add anything to the capture: nothing
     * has been recorded yet and the bounded buffer has room left. [TeeCapturingSource.close] asks
     * before -- and between -- its drain reads, so a body the host abandoned is never read past
     * what the capture can actually keep.
     */
    fun wantsMoreBytes(): Boolean = !recorded.get() && synchronized(lock) { captured.size < maxCapturedBytes }

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
                // An abandoned body no bytes were recovered from is recorded as *no* body, not as a
                // zero-length one: an empty preview alongside a 200 reads as "the server sent
                // nothing", which is a different -- and wrong -- claim than "nothing was captured".
                val recoveredNothing = !reachedEndOfBody && bodyBytes.isEmpty()
                val responseInput =
                    responseTemplate
                        .copy(body = bodyBytes.takeUnless { recoveredNothing })
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
 * Capture must not depend on how much of the body the host happens to read. A host that reads only
 * the status code and closes -- or a parser that stops at the end of the JSON value it wanted --
 * would otherwise leave DevConsole showing a blank or truncated body for exactly the responses
 * OkHttp reports no `Content-Length` for (chunked, and every transparently-gzipped response), while
 * the same response *with* a `Content-Length` is captured whole by the interceptor's eager
 * `peekBody`. [close] closes that gap: whatever the host left behind is drained here first, within
 * a bounded budget, so the recorded body is the whole response in every ordinary case.
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
            // Isolated from the close itself: a drain that fails must still leave the host with the
            // close it asked for, and still record whatever was captured before it failed.
            runCatching { drainRemainderWithinBudget() }
            super.close()
        } finally {
            capture.onBodyClosed()
        }
    }

    /**
     * Reads whatever the host left unread through this tee -- discarding the bytes, since only the
     * capture still wants them -- so an abandoned body is recorded whole instead of as the fragment
     * the host happened to consume. Doubly bounded: it stops as soon as the capture has no room
     * left ([DeferredResponseBodyCapture.wantsMoreBytes], i.e. the same 512KB cap the tee already
     * enforces) and it borrows OkHttp's own `Util.skipAll` deadline idiom -- tighten the source's
     * deadline to at most [DRAIN_DEADLINE_MS], restore it afterwards -- so a body that is a live
     * stream rather than a finished response cannot block the host's `close()` for longer than
     * that. Overshooting the deadline surfaces as an `InterruptedIOException` from [read], caught by
     * [close]'s `runCatching`, and the transaction is recorded `"partial"`.
     *
     * This is the same bounded-drain-on-close that OkHttp itself performs to decide whether an
     * abandoned connection is worth reusing (`AbstractSource.discard`, 100ms), so on the common
     * finite-response path these bytes were already going to be read off the socket regardless.
     */
    private fun drainRemainderWithinBudget() {
        if (!capture.wantsMoreBytes()) return
        val timeout = timeout()
        val startNs = System.nanoTime()
        val originalRemainingNs =
            if (timeout.hasDeadline()) timeout.deadlineNanoTime() - startNs else NO_DEADLINE
        timeout.deadlineNanoTime(startNs + minOf(originalRemainingNs, DRAIN_DEADLINE_NS))
        try {
            val discarded = Buffer()
            while (capture.wantsMoreBytes() && read(discarded, DRAIN_SEGMENT_BYTES) != -1L) {
                discarded.clear()
            }
        } finally {
            if (originalRemainingNs == NO_DEADLINE) {
                timeout.clearDeadline()
            } else {
                timeout.deadlineNanoTime(startNs + originalRemainingNs)
            }
        }
    }

    private companion object {
        /**
         * Ceiling on how long a host `close()` may be held while the rest of an abandoned body is
         * drained. Generous for a finite response still in flight on a slow connection, short
         * enough that abandoning a long-lived stream stays effectively instant.
         */
        const val DRAIN_DEADLINE_MS = 300L
        const val DRAIN_DEADLINE_NS = DRAIN_DEADLINE_MS * 1_000_000L
        const val DRAIN_SEGMENT_BYTES = 8L * 1024L

        /** Sentinel for "the source had no deadline of its own to preserve". */
        const val NO_DEADLINE = Long.MAX_VALUE
    }
}
