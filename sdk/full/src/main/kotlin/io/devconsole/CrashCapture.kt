package io.devconsole

import android.os.Handler
import android.os.Looper
import io.devconsole.api.CrashPolicy
import io.devconsole.security.RedactionEngine
import io.devconsole.storage.api.EventStore
import io.devconsole.storage.api.EvidenceKind
import io.devconsole.storage.api.EvidenceStore
import io.devconsole.storage.api.SessionStore
import io.devconsole.storage.api.StoredEvent
import io.devconsole.storage.api.StoredEvidenceItem
import io.devconsole.timeline.TimelineAppender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal enum class CrashKind { UNCAUGHT, ANR }

/**
 * Records uncaught exceptions and main-thread stalls onto the timeline.
 *
 * Persistence here is deliberately **synchronous**. Every other capture path hands work to a
 * background queue, but a process that has just thrown an uncaught exception is about to die, and
 * an asynchronous flush would routinely lose the one event the developer actually needs.
 *
 * [PERSIST_TIMEOUT_MS] only reliably bounds *waiting* for the event store's write lock: that lock is
 * acquired via a suspending `Mutex`, which is a cancellable suspension point, so a timeout while
 * another write is in progress returns promptly. It does **not** bound a write that has already
 * started -- the underlying Room transaction runs as a genuinely blocking call on `Dispatchers.IO`,
 * which does not check for cancellation, so `withTimeoutOrNull` cannot interrupt it once it is under
 * way; a database wedged mid-transaction can still stall past [PERSIST_TIMEOUT_MS]. The evidence
 * auto-flag (see [autoFlagCrash]) runs *inside* [persistNow]'s own window, after the crash record's
 * own insert, rather than getting a fresh [PERSIST_TIMEOUT_MS] window of its own -- it is a
 * convenience read against a record that already exists, not something worth making the host's own
 * crash reporter wait on a second full timeout for. [markCrashed] then runs afterward in its own
 * `finally` block with a second, independent [PERSIST_TIMEOUT_MS] window, so [persistNow] and
 * [markCrashed] together still add up to only roughly 4 seconds of normal-case latency -- **two**
 * round trips, not three -- before the previously installed handler -- and, downstream of it, the
 * host's own crash reporter -- ever runs. That is a description of the normal-case latency, not a
 * hard ceiling against a genuinely wedged transaction.
 */
@Suppress("LongParameterList") // Every collaborator is injected so the capture path stays unit-testable.
internal class CrashCapture(
    private val sessionId: () -> String,
    private val redaction: RedactionEngine,
    private val appender: () -> TimelineAppender?,
    private val store: () -> EventStore?,
    private val sessionStore: () -> SessionStore?,
    private val nextSequence: () -> Long = AtomicLong(0)::incrementAndGet,
    /** Read synchronously at record time and serialized into the payload as `"breadcrumbs"`. */
    private val breadcrumbs: () -> BreadcrumbRingBuffer? = { null },
    /** [CrashPolicy.maxStackChars] governs both kinds so an ANR dump is never re-truncated shorter. */
    private val policy: () -> CrashPolicy = { CrashPolicy() },
    /**
     * D4's auto-flag: every crash and ANR is flagged into the evidence tray server-side at insert
     * time, kind CRASH -- the one thing nobody should have to remember to click. Read inside
     * [persistNow]'s own timeout window, after the crash record's own insert, and failure-tolerant
     * on its own (see [autoFlagCrash]), so a missing/unavailable/over-quota evidence store can never
     * cost the crash record itself -- and never extends the synchronous crash-path budget either.
     */
    private val evidenceStore: () -> EvidenceStore? = { null },
) {
    constructor(
        sessionId: String,
        redaction: RedactionEngine,
        appender: () -> TimelineAppender?,
        store: () -> EventStore?,
        nextSequence: () -> Long = AtomicLong(0)::incrementAndGet,
    ) : this({ sessionId }, redaction, appender, store, { null }, nextSequence)

    private val installed = AtomicBoolean(false)

    @Volatile private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /**
     * Chains to the handler already in place rather than replacing it. Swallowing the host's own
     * crash reporter would be a far worse bug than anything this captures.
     */
    fun install() {
        if (!installed.compareAndSet(false, true)) return
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                runCatching {
                    record(
                        CrashKind.UNCAUGHT,
                        thread.name,
                        throwable.stackTraceToString(),
                        describe(throwable),
                    )
                }
            } finally {
                runCatching { markCrashed() }
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Called from [AnrWatchdog]'s own daemon thread. Wrapped in [runCatching] exactly like the
     * uncaught-exception path in [install] -- a failure while *capturing* an ANR must never itself
     * become an uncaught exception on the watchdog thread, which (once [install] has run) would take
     * the whole host process down over a capture-path bug instead of just skipping this ANR record.
     */
    fun recordAnr(
        threadName: String,
        stackTrace: String,
    ) {
        runCatching { record(CrashKind.ANR, threadName, stackTrace, "Main thread unresponsive") }
    }

    private fun record(
        kind: CrashKind,
        threadName: String,
        stackTrace: String,
        summary: String,
    ) {
        val event =
            StoredEvent(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId(),
                sequence = nextSequence(),
                pluginId = PLUGIN_ID,
                type = kind.name.lowercase(),
                wallTimeMs = System.currentTimeMillis(),
                monoTimeNs = System.nanoTime(),
                severity = SEVERITY_ERROR,
                summary = summary.take(SUMMARY_CHARS),
                tagsJson = """{"kind":"${kind.name}","thread":"${threadName.escapeJson()}"}""",
                payloadJson = payloadJson(stackTrace),
            )
        // A throwing appender must never escape record(): on the ANR watchdog thread there is no
        // caller-side try/catch the way install()'s uncaught-exception handler has one, so an
        // unguarded throw here would itself become an uncaught exception and -- once install() has
        // run -- take the whole host process down over a failed *capture* of a stall.
        runCatching { appender()?.append(event) }
        persistNow(event, kind, threadName)
    }

    /**
     * Flags [event] into the evidence tray, kind CRASH, keyed by the crash event's own id so a
     * later manual re-flag through `POST /api/v1/evidence` (same subject id) is idempotent against
     * this one. Snapshot shape mirrors the server route's own `StoredEvent.crashSnapshotJson()` byte
     * for byte, since both are read by the same dashboard/export consumers.
     *
     * Deliberately best-effort: no result is inspected, and any failure (unavailable store, quota
     * exceeded, a wedged Room transaction) is swallowed. Called from inside [persistNow]'s own
     * [PERSIST_TIMEOUT_MS] window rather than a fresh one of its own, after the crash record's own
     * insert has already been attempted -- losing the flag is a worse UX, never a worse crash report,
     * and never a reason to make the host's crash reporter wait on a second full timeout.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun autoFlagCrash(
        event: StoredEvent,
        kind: CrashKind,
        threadName: String,
    ) {
        val store = evidenceStore() ?: return
        val item =
            StoredEvidenceItem(
                id = UUID.randomUUID().toString(),
                sessionId = event.sessionId,
                kind = EvidenceKind.CRASH,
                subjectId = event.id,
                label = event.summary,
                flaggedAtMs = System.currentTimeMillis(),
                snapshotJson = crashSnapshotJson(kind, threadName, event.summary, event.payloadJson),
                attachmentId = null,
            )
        try {
            store.flag(item)
        } catch (cancelled: CancellationException) {
            // persistNow's shared withTimeoutOrNull window cancels this the same way it cancels the
            // insert above; rethrowing (rather than runCatching's broad Throwable catch, which would
            // swallow it) keeps that boundary the single source of truth for the deadline.
            throw cancelled
        } catch (_: Exception) {
            // Best-effort: unavailable store, quota exceeded, a wedged Room transaction -- never worth
            // costing the crash record above, which has already been attempted by the time this runs.
        }
    }

    private fun crashSnapshotJson(
        kind: CrashKind,
        threadName: String,
        summary: String,
        payloadJson: String?,
    ): String =
        buildString {
            append("{\"kind\":\"").append(kind.name).append('"')
            append(",\"thread\":\"").append(threadName.escapeJson()).append('"')
            append(",\"summary\":\"").append(summary.escapeJson()).append('"')
            payloadJson?.let { append(",\"payload\":").append(it) }
            append('}')
        }

    private fun payloadJson(stackTrace: String): String {
        val maxStackChars = policy().maxStackChars.coerceAtLeast(1)
        val redacted = redaction.redactText(stackTrace.take(maxStackChars), maxStackChars)
        return buildString {
            append("{\"stackTrace\":\"").append(redacted.escapeJson()).append('"')
            append(",\"breadcrumbs\":").append(breadcrumbsJson())
            append('}')
        }
    }

    /**
     * Breadcrumbs are already-redacted timeline summaries (see [Breadcrumb]) -- no payload bodies,
     * so nothing here needs to pass through [redaction] again.
     */
    private fun breadcrumbsJson(): String =
        breadcrumbs()
            ?.snapshot()
            .orEmpty()
            .joinToString(prefix = "[", postfix = "]") { crumb ->
                buildString {
                    append("{\"ts\":").append(crumb.wallTimeMs)
                    append(",\"plugin\":\"").append(crumb.pluginId.escapeJson()).append('"')
                    append(",\"type\":\"").append(crumb.type.escapeJson()).append('"')
                    append(",\"severity\":").append(crumb.severity)
                    append(",\"summary\":\"").append(crumb.summary.escapeJson()).append('"')
                    append('}')
                }
            }

    /**
     * Persists [event] and, best-effort, auto-flags it into the evidence tray (see [autoFlagCrash])
     * -- both inside this one [PERSIST_TIMEOUT_MS] window, not one each. Finding 1's fix: the
     * auto-flag used to open a second, independent `withTimeoutOrNull(PERSIST_TIMEOUT_MS)` of its
     * own, growing the synchronous crash-path budget from ~4s to ~6s for a convenience flag. Sharing
     * this window means a slow or wedged event store leaves the auto-flag little or no time rather
     * than costing the host's crash reporter an extra [PERSIST_TIMEOUT_MS] on top.
     *
     * Deliberately only one `runCatching`, around the whole block, rather than one per suspend call:
     * `runCatching` catches `CancellationException` too, and a nested one around the insert call would
     * swallow the timeout's own cancellation, letting [autoFlagCrash] start *after* the shared window
     * has already elapsed -- exactly the extra latency this fix exists to remove.
     */
    private fun persistNow(
        event: StoredEvent,
        kind: CrashKind,
        threadName: String,
    ) {
        runCatching {
            runBlocking {
                withTimeoutOrNull(PERSIST_TIMEOUT_MS) {
                    store()?.insert(listOf(event))
                    autoFlagCrash(event, kind, threadName)
                }
            }
        }
    }

    private fun markCrashed() {
        val activeSessionId = sessionId()
        val sessions = sessionStore() ?: return
        runCatching {
            runBlocking {
                withTimeoutOrNull(
                    PERSIST_TIMEOUT_MS,
                ) { sessions.crash(activeSessionId, System.currentTimeMillis()) }
            }
        }
    }

    private fun describe(throwable: Throwable): String {
        val detail = throwable.message.orEmpty()
        return "${throwable.javaClass.simpleName}: $detail"
    }

    private companion object {
        const val PLUGIN_ID = "crash"
        const val SEVERITY_ERROR = 4
        const val SUMMARY_CHARS = 200
        const val PERSIST_TIMEOUT_MS = 2_000L
    }
}

/**
 * Detects main-thread stalls the way the platform does: post a token to the main looper and see
 * whether it comes back. If it does not within [thresholdMs], the main thread is blocked -- but a
 * main-thread-only stack shows where the block surfaced, not what caused it, since the thread
 * actually holding a contended lock is usually a different one. So the report is a bounded dump of
 * every thread ([Thread.getAllStackTraces]), main thread first, then the rest sorted by name for
 * deterministic output, capped by [maxThreadsInDump], [maxFramesPerThread], and [maxStackChars] with
 * every truncation marked explicitly (see [ThreadDumpFormatter]).
 *
 * [thresholdMs], [maxThreadsInDump], [maxFramesPerThread], and [maxStackChars] are `@Volatile var`s
 * updated via [updatePolicy] rather than fixed constructor values: the watchdog is constructed once,
 * eagerly, before a host's [io.devconsole.api.CrashPolicy] is known, and a later re-initialize with a
 * different policy must take effect on the next poll without tearing down a running thread.
 *
 * A daemon thread, so it can never keep the process alive.
 */
internal class AnrWatchdog(
    @Volatile private var thresholdMs: Long = DEFAULT_THRESHOLD_MS,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    @Volatile private var maxThreadsInDump: Int = DEFAULT_MAX_THREADS_IN_DUMP,
    @Volatile private var maxFramesPerThread: Int = DEFAULT_MAX_FRAMES_PER_THREAD,
    @Volatile private var maxStackChars: Int = DEFAULT_MAX_STACK_CHARS,
    private val onAnr: (threadName: String, stackTrace: String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    @Volatile private var worker: Thread? = null

    fun updatePolicy(
        thresholdMs: Long,
        maxThreadsInDump: Int,
        maxFramesPerThread: Int,
        maxStackChars: Int,
    ) {
        this.thresholdMs = thresholdMs
        this.maxThreadsInDump = maxThreadsInDump
        this.maxFramesPerThread = maxFramesPerThread
        this.maxStackChars = maxStackChars
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker =
            Thread({ loop() }, "devconsole-anr-watchdog").apply {
                isDaemon = true
                start()
            }
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        worker = null
    }

    private fun loop() {
        var alreadyReported = false
        while (running.get()) {
            val ticked = AtomicBoolean(false)
            mainHandler.post { ticked.set(true) }
            val deadline = System.currentTimeMillis() + thresholdMs
            while (!ticked.get() && System.currentTimeMillis() < deadline) {
                if (!sleepQuietly(pollIntervalMs)) return
            }
            if (!ticked.get()) {
                // Report the stall once, not every poll, or a long block floods the timeline.
                if (!alreadyReported) {
                    val mainThread = Looper.getMainLooper().thread
                    val samples = Thread.getAllStackTraces().toOrderedStackSamples(mainThread)
                    val dump = ThreadDumpFormatter.format(samples, maxThreadsInDump, maxFramesPerThread, maxStackChars)
                    // A throwing onAnr must never escape loop(): this thread has no other caller-side
                    // try/catch, and an uncaught exception here would kill the whole host process once
                    // CrashCapture.install() has run -- exactly the outcome an ANR watchdog exists to
                    // report, not cause.
                    runCatching { onAnr(mainThread.name, dump) }
                    alreadyReported = true
                }
            } else {
                alreadyReported = false
            }
            if (!sleepQuietly(pollIntervalMs)) return
        }
    }

    private fun sleepQuietly(millis: Long): Boolean =
        try {
            Thread.sleep(millis)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private companion object {
        const val DEFAULT_THRESHOLD_MS = 5_000L
        const val DEFAULT_POLL_INTERVAL_MS = 500L
        const val DEFAULT_MAX_THREADS_IN_DUMP = 64
        const val DEFAULT_MAX_FRAMES_PER_THREAD = 64
        const val DEFAULT_MAX_STACK_CHARS = 32 * 1024
    }
}
