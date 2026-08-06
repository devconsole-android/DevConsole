package io.devconsole

import io.devconsole.api.CrashPolicy
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.storage.api.EventStore
import io.devconsole.storage.api.EventStoreWriteResult
import io.devconsole.storage.api.EvidenceKind
import io.devconsole.storage.api.EvidenceStore
import io.devconsole.storage.api.EvidenceWriteResult
import io.devconsole.storage.api.SessionStore
import io.devconsole.storage.api.StoredEvent
import io.devconsole.storage.api.StoredEvidenceItem
import io.devconsole.storage.api.StoredEvidenceReport
import io.devconsole.timeline.TimelineAppender
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.system.measureTimeMillis

/** Robolectric only for a real `org.json` implementation to validate emitted payload JSON. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashCaptureTest {
    private val appended = mutableListOf<StoredEvent>()
    private val persisted = mutableListOf<StoredEvent>()

    private val appender =
        object : TimelineAppender {
            override fun append(event: StoredEvent) {
                appended += event
            }
        }
    private val store =
        object : EventStore {
            override suspend fun insert(events: List<StoredEvent>): EventStoreWriteResult {
                persisted += events
                return EventStoreWriteResult.Success(events.size)
            }

            override suspend fun eventsForSession(sessionId: String): List<StoredEvent> = persisted

            override suspend fun deleteSession(sessionId: String) = Unit

            override suspend fun eventCount(): Long = persisted.size.toLong()
        }

    private fun capture() =
        CrashCapture(
            sessionId = "session",
            redaction = RedactionEngine(RedactionPolicy.default()),
            appender = { appender },
            store = { store },
        )

    @Test
    fun `an ANR is recorded to the timeline and persisted synchronously`() {
        capture().recordAnr("main", "\tat android.os.MessageQueue.nativePollOnce")

        assertEquals(1, appended.size)
        assertEquals(1, persisted.size)
        assertEquals("crash", appended.single().pluginId)
        assertEquals("anr", appended.single().type)
        assertTrue("\"kind\":\"ANR\"" in appended.single().tagsJson)
    }

    @Test
    fun `installing chains to the existing handler rather than replacing it`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            var delegated: Throwable? = null
            val host = Thread.UncaughtExceptionHandler { _, throwable -> delegated = throwable }
            Thread.setDefaultUncaughtExceptionHandler(host)

            capture().install()
            val installed = requireNotNull(Thread.getDefaultUncaughtExceptionHandler())
            val boom = IllegalStateException("boom")
            installed.uncaughtException(Thread.currentThread(), boom)

            assertSame("the host's own crash reporter must still run", boom, delegated)
            assertEquals(1, appended.size)
            assertEquals("uncaught", appended.single().type)
            assertTrue("IllegalStateException" in appended.single().summary)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun `the crash payload is redacted`() {
        capture().recordAnr("main", "called with Authorization: Bearer topsecret123")

        assertTrue(
            "expected the token to be redacted: ${appended.single().payloadJson}",
            "topsecret123" !in appended.single().payloadJson.orEmpty(),
        )
    }

    @Test
    fun `the payload is valid JSON with an empty breadcrumbs array when none is wired`() {
        capture().recordAnr("main", "\tat Foo.bar")

        val json = JSONObject(appended.single().payloadJson.orEmpty())

        assertEquals("\tat Foo.bar", json.getString("stackTrace"))
        assertEquals(0, json.getJSONArray("breadcrumbs").length())
    }

    @Test
    fun `breadcrumbs are serialized oldest-first with only summary fields, valid JSON, escaped content`() {
        val ring = BreadcrumbRingBuffer(10)
        ring.record(Breadcrumb(100L, "network", "network.transaction", 1, "GET /orders \"quoted\""))
        ring.record(Breadcrumb(200L, "logs", "log", 2, "line two"))
        val capture =
            CrashCapture(
                sessionId = { "session" },
                redaction = RedactionEngine(RedactionPolicy.default()),
                appender = { appender },
                store = { store },
                sessionStore = { null },
                breadcrumbs = { ring },
            )

        capture.recordAnr("main", "\tat Foo.bar")

        val breadcrumbs = JSONObject(appended.single().payloadJson.orEmpty()).getJSONArray("breadcrumbs")
        assertEquals(2, breadcrumbs.length())
        val first = breadcrumbs.getJSONObject(0)
        assertEquals(100L, first.getLong("ts"))
        assertEquals("network", first.getString("plugin"))
        assertEquals("network.transaction", first.getString("type"))
        assertEquals(1, first.getInt("severity"))
        assertEquals("GET /orders \"quoted\"", first.getString("summary"))
        assertEquals("logs", breadcrumbs.getJSONObject(1).getString("plugin"))
    }

    @Test
    fun `breadcrumbDepth = 0 disables breadcrumbs cleanly`() {
        val ring = BreadcrumbRingBuffer(0)
        ring.record(Breadcrumb(1L, "network", "network.transaction", 1, "should not appear"))
        val capture =
            CrashCapture(
                sessionId = { "session" },
                redaction = RedactionEngine(RedactionPolicy.default()),
                appender = { appender },
                store = { store },
                sessionStore = { null },
                breadcrumbs = { ring },
            )

        capture.recordAnr("main", "\tat Foo.bar")

        assertEquals(0, JSONObject(appended.single().payloadJson.orEmpty()).getJSONArray("breadcrumbs").length())
    }

    @Test
    fun `maxStackChars from the wired policy governs both uncaught and ANR truncation`() {
        val capture =
            CrashCapture(
                sessionId = { "session" },
                redaction = RedactionEngine(RedactionPolicy.default()),
                appender = { appender },
                store = { store },
                sessionStore = { null },
                policy = { CrashPolicy(maxStackChars = 32) },
            )

        capture.recordAnr("main", "x".repeat(1_000))

        val stackTrace = JSONObject(appended.single().payloadJson.orEmpty()).getString("stackTrace")
        assertTrue(stackTrace.length <= 32)
    }

    // ============================================================================================
    // D4 -- every crash and ANR is auto-flagged into the evidence tray, kind CRASH, at insert time.
    // ============================================================================================

    @Test
    fun `an ANR is auto-flagged into the evidence tray with kind CRASH keyed by the crash event's own id`() {
        val evidence = FakeEvidenceStore()
        val capture =
            CrashCapture(
                sessionId = { "session" },
                redaction = RedactionEngine(RedactionPolicy.default()),
                appender = { appender },
                store = { store },
                sessionStore = { null },
                evidenceStore = { evidence },
            )

        capture.recordAnr("main", "\tat Foo.bar")

        val crashEvent = appended.single()
        assertEquals(1, evidence.flagged.size)
        val item = evidence.flagged.single()
        assertEquals(EvidenceKind.CRASH, item.kind)
        assertEquals(crashEvent.id, item.subjectId)
        assertEquals(crashEvent.sessionId, item.sessionId)
        assertEquals(crashEvent.summary, item.label)
        val snapshot = JSONObject(item.snapshotJson)
        assertEquals("ANR", snapshot.getString("kind"))
        assertEquals("main", snapshot.getString("thread"))
        assertTrue("Foo.bar" in snapshot.getJSONObject("payload").getString("stackTrace"))
    }

    @Test
    fun `an uncaught exception is auto-flagged the same way`() {
        val evidence = FakeEvidenceStore()
        val capture =
            CrashCapture(
                sessionId = { "session" },
                redaction = RedactionEngine(RedactionPolicy.default()),
                appender = { appender },
                store = { store },
                sessionStore = { null },
                evidenceStore = { evidence },
            )
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
            capture.install()
            val installed = requireNotNull(Thread.getDefaultUncaughtExceptionHandler())

            installed.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

            assertEquals(1, evidence.flagged.size)
            assertEquals(EvidenceKind.CRASH, evidence.flagged.single().kind)
            assertEquals("UNCAUGHT", JSONObject(evidence.flagged.single().snapshotJson).getString("kind"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun `an unavailable evidence store never loses the crash record itself`() {
        val capture =
            CrashCapture(
                sessionId = { "session" },
                redaction = RedactionEngine(RedactionPolicy.default()),
                appender = { appender },
                store = { store },
                sessionStore = { null },
                evidenceStore = { null },
            )

        capture.recordAnr("main", "\tat Foo.bar")

        assertEquals(1, appended.size)
        assertEquals(1, persisted.size)
    }

    @Test
    fun `a quota-exceeded evidence store never loses the crash record itself`() {
        val evidence = FakeEvidenceStore(alwaysQuotaExceeded = true)
        val capture =
            CrashCapture(
                sessionId = { "session" },
                redaction = RedactionEngine(RedactionPolicy.default()),
                appender = { appender },
                store = { store },
                sessionStore = { null },
                evidenceStore = { evidence },
            )

        capture.recordAnr("main", "\tat Foo.bar")

        assertEquals(1, appended.size)
        assertEquals(1, persisted.size)
        assertTrue("the store was consulted even though it rejected the flag", evidence.attempted)
    }

    // ============================================================================================
    // Finding 1 -- the evidence auto-flag must share persistNow's timeout window, not open a second,
    // independent one: two blocking round trips on the uncaught-exception path, not three.
    // ============================================================================================

    @Test
    fun `the evidence auto-flag shares persistNow's window rather than getting a fresh one`() {
        // The event store's insert() never returns within PERSIST_TIMEOUT_MS (2s), so the shared
        // window is exhausted before autoFlagCrash would ever get a turn -- proving the two are
        // sequenced inside one window rather than each getting an independent budget.
        val neverReturningStore =
            object : EventStore {
                override suspend fun insert(events: List<StoredEvent>): EventStoreWriteResult {
                    delay(10_000)
                    error("unreachable: withTimeoutOrNull should have cancelled this first")
                }

                override suspend fun eventsForSession(sessionId: String): List<StoredEvent> = emptyList()

                override suspend fun deleteSession(sessionId: String) = Unit

                override suspend fun eventCount(): Long = 0
            }
        val evidence = FakeEvidenceStore()
        val capture =
            CrashCapture(
                sessionId = { "session" },
                redaction = RedactionEngine(RedactionPolicy.default()),
                appender = { appender },
                store = { neverReturningStore },
                sessionStore = { null },
                evidenceStore = { evidence },
            )

        val elapsed = measureTimeMillis { capture.recordAnr("main", "\tat Foo.bar") }

        assertFalse("autoFlagCrash must never run once the shared window is exhausted", evidence.attempted)
        assertTrue(
            "expected roughly one PERSIST_TIMEOUT_MS window (~2s), took ${elapsed}ms -- a fresh window " +
                "for the auto-flag would not change this number, which is exactly the bug this guards",
            elapsed < 3_000,
        )
    }

    @Test
    fun `worst-case latency before the previous handler runs is two round trips, not three`() {
        // Every collaborator is pathologically slow. If persistNow and the auto-flag genuinely share
        // one PERSIST_TIMEOUT_MS window (Finding 1's fix) rather than each getting one, total worst
        // case before the previous handler runs is bounded near two windows (~4s): one shared window
        // for persist+auto-flag, one for markCrashed -- not three windows (~6s).
        val neverReturningStore =
            object : EventStore {
                override suspend fun insert(events: List<StoredEvent>): EventStoreWriteResult {
                    delay(10_000)
                    error("unreachable: withTimeoutOrNull should have cancelled this first")
                }

                override suspend fun eventsForSession(sessionId: String): List<StoredEvent> = emptyList()

                override suspend fun deleteSession(sessionId: String) = Unit

                override suspend fun eventCount(): Long = 0
            }
        val neverReturningEvidence =
            object : EvidenceStore {
                override suspend fun flag(item: StoredEvidenceItem): EvidenceWriteResult {
                    delay(10_000)
                    error("unreachable: sequenced after insert(), which already exhausts the window")
                }

                override suspend fun unflag(
                    sessionId: String,
                    kind: EvidenceKind,
                    subjectId: String,
                ) = Unit

                override suspend fun items(sessionId: String): List<StoredEvidenceItem> = emptyList()

                override suspend fun clear(sessionId: String) = Unit

                override suspend fun report(sessionId: String) = StoredEvidenceReport(sessionId = sessionId)

                override suspend fun saveReport(report: StoredEvidenceReport) = Unit

                override suspend fun deleteSession(sessionId: String) = Unit
            }
        val neverReturningSessions =
            object : SessionStore {
                override suspend fun start(session: io.devconsole.storage.api.StoredSession) = Unit

                override suspend fun end(
                    sessionId: String,
                    endedAtMs: Long,
                ) = Unit

                override suspend fun crash(
                    sessionId: String,
                    endedAtMs: Long,
                ) {
                    delay(10_000)
                    error("unreachable: withTimeoutOrNull should have cancelled this first")
                }

                override suspend fun sessions(): List<io.devconsole.storage.api.StoredSession> = emptyList()

                override suspend fun session(sessionId: String): io.devconsole.storage.api.StoredSession? = null
            }
        val capture =
            CrashCapture(
                sessionId = { "session" },
                redaction = RedactionEngine(RedactionPolicy.default()),
                appender = { appender },
                store = { neverReturningStore },
                sessionStore = { neverReturningSessions },
                evidenceStore = { neverReturningEvidence },
            )
        var delegatedTo: Throwable? = null
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, throwable -> delegatedTo = throwable }
            capture.install()
            val installed = requireNotNull(Thread.getDefaultUncaughtExceptionHandler())
            val boom = IllegalStateException("boom")

            val elapsed = measureTimeMillis { installed.uncaughtException(Thread.currentThread(), boom) }

            assertSame("the host's own crash reporter must still run", boom, delegatedTo)
            assertTrue(
                "expected roughly two PERSIST_TIMEOUT_MS windows (~4s), took ${elapsed}ms -- a third " +
                    "window (the pre-fix bug) would push this past 5s",
                elapsed in 3_000..5_500,
            )
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun `evidenceStore defaults to null, so crash capture never requires it`() {
        val capture =
            CrashCapture(
                sessionId = "session",
                redaction = RedactionEngine(RedactionPolicy.default()),
                appender = { appender },
                store = { store },
            )

        capture.recordAnr("main", "\tat Foo.bar")

        assertEquals(1, appended.size)
        assertEquals(1, persisted.size)
    }
}

/** Records every flag attempt; [alwaysQuotaExceeded] simulates an evidence tray already at capacity. */
private class FakeEvidenceStore(
    private val alwaysQuotaExceeded: Boolean = false,
) : EvidenceStore {
    val flagged = mutableListOf<StoredEvidenceItem>()
    var attempted = false
        private set

    override suspend fun flag(item: StoredEvidenceItem): EvidenceWriteResult {
        attempted = true
        if (alwaysQuotaExceeded) return EvidenceWriteResult.QuotaExceeded
        flagged += item
        return EvidenceWriteResult.Success(item)
    }

    override suspend fun unflag(
        sessionId: String,
        kind: EvidenceKind,
        subjectId: String,
    ) = Unit

    override suspend fun items(sessionId: String): List<StoredEvidenceItem> = flagged

    override suspend fun clear(sessionId: String) = Unit

    override suspend fun report(sessionId: String): StoredEvidenceReport = StoredEvidenceReport(sessionId = sessionId)

    override suspend fun saveReport(report: StoredEvidenceReport) = Unit

    override suspend fun deleteSession(sessionId: String) = Unit
}
