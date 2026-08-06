/**
 * @author Shakib
 * @since 24/07/26
 */
package io.devconsole

import io.devconsole.api.DevConsoleConfig
import io.devconsole.api.EditingCapabilities
import io.devconsole.composer.ComposerExecutor
import io.devconsole.composer.ComposerResponse
import io.devconsole.composer.ComposerTransport
import io.devconsole.composer.ResolvedComposerRequest
import io.devconsole.mocks.MockAction
import io.devconsole.mocks.MockEngine
import io.devconsole.mocks.MockRule
import io.devconsole.network.ExportSelection
import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkRequestInput
import io.devconsole.network.NetworkRequestMetadata
import io.devconsole.network.NetworkResponseInput
import io.devconsole.network.NetworkResponseMetadata
import io.devconsole.network.NetworkTimingPhases
import io.devconsole.network.NetworkTransaction
import io.devconsole.push.InMemoryPushStore
import io.devconsole.push.PushEvent
import io.devconsole.push.PushLifecycle
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.server.api.DatabaseExecResult
import io.devconsole.server.api.DatabaseInspector
import io.devconsole.server.api.DatabaseListingData
import io.devconsole.server.api.DatabaseQueryData
import io.devconsole.server.api.DatabaseTableData
import io.devconsole.server.api.FileEntryData
import io.devconsole.server.api.FileInspector
import io.devconsole.server.api.FileListingData
import io.devconsole.server.api.FilePreviewData
import io.devconsole.server.api.PreferencesEntryData
import io.devconsole.server.api.PreferencesFileData
import io.devconsole.server.api.PreferencesInspector
import io.devconsole.socket.InMemorySocketStore
import io.devconsole.socket.SocketConnection
import io.devconsole.socket.SocketConnectionState
import io.devconsole.socket.SocketDirection
import io.devconsole.socket.SocketMessage
import io.devconsole.socket.SocketMessageMetadata
import io.devconsole.socket.SocketPayload
import io.devconsole.state.FeatureFlag
import io.devconsole.state.SessionFeatureFlags
import io.devconsole.state.StateRegistry
import io.devconsole.state.StateSnapshot
import io.devconsole.state.StateValue
import io.devconsole.state.stateProvider
import io.devconsole.storage.api.EventStore
import io.devconsole.storage.api.EventStoreWriteResult
import io.devconsole.storage.api.EvidenceKind
import io.devconsole.storage.api.EvidenceStore
import io.devconsole.storage.api.EvidenceWriteResult
import io.devconsole.storage.api.RetainedCaptureQuery
import io.devconsole.storage.api.StoredEvent
import io.devconsole.storage.api.StoredEvidenceItem
import io.devconsole.storage.api.StoredEvidenceReport
import io.devconsole.timeline.CursorCodec
import io.devconsole.timeline.InMemoryTimeline
import io.devconsole.ui.compose.InspectorBrowserUi
import io.devconsole.ui.compose.InspectorCommandResult
import io.devconsole.ui.compose.InspectorComposerRequest
import io.devconsole.ui.compose.InspectorHealthUi
import io.devconsole.ui.compose.InspectorRetentionUi
import io.devconsole.ui.compose.InspectorSessionUi
import io.devconsole.ui.compose.InspectorSqlResultUi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("LargeClass", "LongParameterList") // Exhaustive adapter coverage; configWith mirrors the capability flags.
class FullInspectorDataSourceTest {
    private val captureFactory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default()))

    private fun networkStore(): InMemoryNetworkTransactionStore =
        InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))

    private fun recordTransaction(
        store: InMemoryNetworkTransactionStore,
        id: String,
        startedAtEpochMs: Long,
        completedAtEpochMs: Long?,
    ) {
        val capture =
            captureFactory.capture(
                NetworkRequestInput(
                    method = "GET",
                    url = "https://api.example.test/orders",
                    headers = mapOf("X-Request-Id" to id),
                ),
                NetworkResponseInput(
                    statusCode = 200,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = "{\"ok\":true}".encodeToByteArray(),
                    contentType = "application/json",
                ),
            )
        store.record(NetworkTransaction(id, startedAtEpochMs, completedAtEpochMs, capture))
    }

    private class RecordingComposerTransport(
        private val response: ComposerResponse,
    ) : ComposerTransport {
        var lastRequest: ResolvedComposerRequest? = null

        override fun execute(request: ResolvedComposerRequest): ComposerResponse {
            lastRequest = request
            return response
        }
    }

    private fun configWith(
        requestExecution: Boolean = false,
        mocks: Boolean = false,
        featureFlags: Boolean = false,
        preferences: Boolean = false,
        files: Boolean = false,
        database: Boolean = false,
        composerEnabled: Boolean = false,
        composerAllowedHosts: Set<String> = emptySet(),
    ): DevConsoleConfig =
        DevConsoleConfig(composerEnabled = composerEnabled, composerAllowedHosts = composerAllowedHosts)
            .withEditingCapabilities(
                EditingCapabilities
                    .builder()
                    .requestExecution(requestExecution)
                    .mocks(mocks)
                    .featureFlags(featureFlags)
                    .preferences(preferences)
                    .files(files)
                    .database(database)
                    .build(),
            )

    private class FakeFileInspector(
        private val rootsData: List<String> = listOf("files"),
        private val listing: FileListingData? = null,
        private val previewData: FilePreviewData = FilePreviewData.Unavailable("none"),
    ) : FileInspector {
        var deleteCallCount = 0
            private set
        var deleteReturns = true
        var createReturns = true
        var replaceReturns = true
        var renameReturns = true
        var readBytesReturns: ByteArray? = null
        var lastReadBytesCall: List<String>? = null
            private set

        override fun roots(): List<String> = rootsData

        override fun list(
            root: String,
            relativePath: String,
        ): FileListingData? = listing

        override fun preview(
            root: String,
            relativePath: String,
        ): FilePreviewData = previewData

        override fun delete(
            root: String,
            relativePath: String,
        ): Boolean {
            deleteCallCount++
            return deleteReturns
        }

        override fun create(
            root: String,
            relativePath: String,
            content: String,
        ): Boolean = createReturns

        override fun replace(
            root: String,
            relativePath: String,
            content: String,
        ): Boolean = replaceReturns

        override fun rename(
            root: String,
            relativePath: String,
            newRelativePath: String,
        ): Boolean = renameReturns

        override fun readBytes(
            root: String,
            relativePath: String,
        ): ByteArray? {
            lastReadBytesCall = listOf(root, relativePath)
            return readBytesReturns
        }
    }

    private class FakePreferencesInspector(
        private val filesData: List<PreferencesFileData> = emptyList(),
    ) : PreferencesInspector {
        var putCallCount = 0
            private set
        var removeCallCount = 0
            private set
        var lastPut: List<String>? = null
            private set
        var putReturns = true
        var removeReturns = true

        override fun files(): List<PreferencesFileData> = filesData

        override fun put(
            file: String,
            key: String,
            value: String,
            type: String,
        ): Boolean {
            putCallCount++
            lastPut = listOf(file, key, value, type)
            return putReturns
        }

        override fun remove(
            file: String,
            key: String,
        ): Boolean {
            removeCallCount++
            return removeReturns
        }
    }

    private class FakeDatabaseInspector(
        private val databasesData: List<String> = listOf("demo.db"),
        private val listing: DatabaseListingData? = null,
        private val queryData: DatabaseQueryData? = null,
        private val executeResult: DatabaseExecResult = DatabaseExecResult.Failed("not configured"),
    ) : DatabaseInspector {
        var executeCallCount = 0
            private set
        var lastWriteEnabled: Boolean? = null
            private set
        var lastSql: String? = null
            private set

        override fun databases(): List<String> = databasesData

        override fun tables(database: String): DatabaseListingData? = listing

        override fun query(
            database: String,
            table: String,
        ): DatabaseQueryData? = queryData

        override fun execute(
            database: String,
            sql: String,
            writeEnabled: Boolean,
        ): DatabaseExecResult {
            executeCallCount++
            lastWriteEnabled = writeEnabled
            lastSql = sql
            return executeResult
        }
    }

    private class FakeInspectorExporter(
        private val harResult: ExportOutcome = ExportOutcome.Written("/data/x.har", 10),
        private val postmanResult: ExportOutcome = ExportOutcome.Written("/data/x.postman_collection.json", 10),
        private val sessionZipResult: ExportOutcome = ExportOutcome.Written("/data/x.zip", 10),
    ) : InspectorExporter {
        var exportHarCallCount = 0
            private set
        var exportPostmanCallCount = 0
            private set
        var exportSessionZipCallCount = 0
            private set
        var lastHarSelection: ExportSelection? = null
            private set
        var lastPostmanSelection: ExportSelection? = null
            private set

        override fun exportHar(selection: ExportSelection): ExportOutcome {
            exportHarCallCount++
            lastHarSelection = selection
            return harResult
        }

        override fun exportPostman(selection: ExportSelection): ExportOutcome {
            exportPostmanCallCount++
            lastPostmanSelection = selection
            return postmanResult
        }

        override fun exportSessionZip(): ExportOutcome {
            exportSessionZipCallCount++
            return sessionZipResult
        }
    }

    /**
     * Session-scoped, in-memory stand-in for a real `EvidenceStore` -- enforces the same identity
     * and quota rules `RoomEvidenceStore` does, without Room.
     */
    private class FakeEvidenceStore(
        private val quota: Int = Int.MAX_VALUE,
    ) : EvidenceStore {
        private val store = mutableListOf<StoredEvidenceItem>()
        var flagAttempts = 0
            private set

        @Suppress("ReturnCount") // One early-exit per rejection reason (already flagged, over quota).
        override suspend fun flag(item: StoredEvidenceItem): EvidenceWriteResult {
            flagAttempts++
            val alreadyFlagged =
                store.any { it.sessionId == item.sessionId && it.kind == item.kind && it.subjectId == item.subjectId }
            if (alreadyFlagged) return EvidenceWriteResult.AlreadyFlagged
            if (store.count { it.sessionId == item.sessionId } >= quota) return EvidenceWriteResult.QuotaExceeded
            store += item
            return EvidenceWriteResult.Success(item)
        }

        override suspend fun unflag(
            sessionId: String,
            kind: EvidenceKind,
            subjectId: String,
        ) {
            store.removeAll { it.sessionId == sessionId && it.kind == kind && it.subjectId == subjectId }
        }

        override suspend fun items(sessionId: String): List<StoredEvidenceItem> =
            store.filter { it.sessionId == sessionId }

        override suspend fun clear(sessionId: String) {
            store.removeAll { it.sessionId == sessionId }
        }

        override suspend fun report(sessionId: String): StoredEvidenceReport =
            StoredEvidenceReport(sessionId = sessionId)

        override suspend fun saveReport(report: StoredEvidenceReport) = Unit

        override suspend fun deleteSession(sessionId: String) {
            store.removeAll { it.sessionId == sessionId }
        }
    }

    /** Minimal, session-filtered `EventStore` stand-in for [RetainedCaptureQuery] in the crash-flag tests. */
    private class FakeEventStore(
        private val events: List<StoredEvent>,
    ) : EventStore {
        override suspend fun insert(events: List<StoredEvent>): EventStoreWriteResult =
            EventStoreWriteResult.Success(events.size)

        override suspend fun eventsForSession(sessionId: String): List<StoredEvent> =
            events.filter { it.sessionId == sessionId }

        override suspend fun deleteSession(sessionId: String) = Unit

        override suspend fun eventCount(): Long = events.size.toLong()
    }

    private fun sampleCrashEvent() =
        StoredEvent(
            id = "crash-1",
            sessionId = "session-1",
            sequence = 1,
            pluginId = "crash",
            type = "anr",
            wallTimeMs = 20,
            monoTimeNs = 20,
            severity = 4,
            summary = "Main thread unresponsive",
            tagsJson = """{"kind":"ANR","thread":"main"}""",
            payloadJson = """{"stackTrace":"at Foo.bar","breadcrumbs":[]}""",
        )

    // ============================================================================================
    // Evidence tray -- FullInspectorDataSource delegates flag/unflag/query to the durable
    // EvidenceStore instead of returning local Compose state. No Robolectric in this test file (see
    // its class-level constraint elsewhere in this suite), so shape assertions below are plain
    // String.contains checks on snapshotJson rather than org.json.
    // ============================================================================================

    @Test
    fun `flagTransaction stores a NETWORK item in the EvidenceStore with the server's own detail JSON shape`() =
        runTest {
            val store = networkStore()
            recordTransaction(store, "tx-1", startedAtEpochMs = 0, completedAtEpochMs = 5)
            val evidence = FakeEvidenceStore()
            val source =
                FullInspectorDataSource(
                    store,
                    MockEngine(emptyList()),
                    configSupplier = { null },
                    evidenceStore = evidence,
                    evidenceSessionId = { "session-1" },
                )

            val result = source.flagTransaction("tx-1")

            assertTrue(result is InspectorCommandResult.Success)
            val item = evidence.items("session-1").single()
            assertEquals(EvidenceKind.NETWORK, item.kind)
            assertEquals("tx-1", item.subjectId)
            assertEquals("session-1", item.sessionId)
            assertEquals("GET api.example.test/orders", item.label)
            // Mirrors DevConsoleKtorModule's NetworkTransaction.summaryJson() + .detailJson(), concatenated.
            assertTrue(item.snapshotJson.contains("\"id\":\"tx-1\""))
            assertTrue(item.snapshotJson.contains("\"startedAtEpochMs\":0"))
            assertTrue(item.snapshotJson.contains("\"completedAtEpochMs\":5"))
            assertTrue(item.snapshotJson.contains("\"durationMs\":5"))
            assertTrue(item.snapshotJson.contains("\"method\":\"GET\""))
            assertTrue(item.snapshotJson.contains("\"host\":\"api.example.test\""))
            assertTrue(item.snapshotJson.contains("\"path\":\"/orders\""))
            assertTrue(item.snapshotJson.contains("\"status\":200"))
            assertTrue(item.snapshotJson.contains("\"contentType\":\"application/json\""))
            assertTrue(item.snapshotJson.contains("\"correlationId\":null"))
            assertTrue(item.snapshotJson.contains("\"request\":{\"url\":\"https://api.example.test/orders\""))
            assertTrue(item.snapshotJson.contains("\"headers\":[{\"name\":\"X-Request-Id\",\"value\":\"tx-1\"}]"))
            assertTrue(item.snapshotJson.contains("\"response\":{\"headers\":"))
            assertTrue(item.snapshotJson.contains("\"timings\":{\"dnsMs\":null"))
        }

    @Test
    fun `flaggedTransactionIds reflects the item just flagged`() =
        runTest {
            val store = networkStore()
            recordTransaction(store, "tx-1", startedAtEpochMs = 0, completedAtEpochMs = 5)
            val evidence = FakeEvidenceStore()
            val source =
                FullInspectorDataSource(
                    store,
                    MockEngine(emptyList()),
                    configSupplier = { null },
                    evidenceStore = evidence,
                    evidenceSessionId = { "session-1" },
                )

            source.flagTransaction("tx-1")

            assertEquals(setOf("tx-1"), source.flaggedTransactionIds())
        }

    @Test
    fun `flaggedTransactionIds reflects an item flagged directly on the store by something else`() =
        runTest {
            // Simulates the dashboard flagging the same transaction through its own route: this data
            // source never wrote it, only the store did, and the read must still see it -- the exact
            // round trip that made local Compose state the wrong home for evidence flags.
            val evidence = FakeEvidenceStore()
            evidence.flag(
                StoredEvidenceItem(
                    id = "evidence-1",
                    sessionId = "session-1",
                    kind = EvidenceKind.NETWORK,
                    subjectId = "tx-remote",
                    label = "GET api.example.test/orders",
                    flaggedAtMs = 1_000,
                    snapshotJson = "{}",
                ),
            )
            val source =
                FullInspectorDataSource(
                    networkStore(),
                    MockEngine(emptyList()),
                    configSupplier = { null },
                    evidenceStore = evidence,
                    evidenceSessionId = { "session-1" },
                )

            assertEquals(setOf("tx-remote"), source.flaggedTransactionIds())
        }

    @Test
    fun `unflagTransaction removes the item`() =
        runTest {
            val store = networkStore()
            recordTransaction(store, "tx-1", startedAtEpochMs = 0, completedAtEpochMs = 5)
            val evidence = FakeEvidenceStore()
            val source =
                FullInspectorDataSource(
                    store,
                    MockEngine(emptyList()),
                    configSupplier = { null },
                    evidenceStore = evidence,
                    evidenceSessionId = { "session-1" },
                )
            source.flagTransaction("tx-1")

            val result = source.unflagTransaction("tx-1")

            assertTrue(result is InspectorCommandResult.Success)
            assertTrue(source.flaggedTransactionIds().isEmpty())
        }

    @Test
    fun `flagTransaction surfaces AlreadyFlagged with a human-readable message instead of throwing or no-opping`() =
        runTest {
            val store = networkStore()
            recordTransaction(store, "tx-1", startedAtEpochMs = 0, completedAtEpochMs = 5)
            val evidence = FakeEvidenceStore()
            val source =
                FullInspectorDataSource(
                    store,
                    MockEngine(emptyList()),
                    configSupplier = { null },
                    evidenceStore = evidence,
                    evidenceSessionId = { "session-1" },
                )
            source.flagTransaction("tx-1")

            val result = source.flagTransaction("tx-1")

            assertTrue(result is InspectorCommandResult.Invalid)
            assertTrue((result as InspectorCommandResult.Invalid).message.contains("already", ignoreCase = true))
            assertEquals(1, evidence.items("session-1").size)
        }

    @Test
    fun `flagTransaction surfaces QuotaExceeded with a human-readable message`() =
        runTest {
            val store = networkStore()
            recordTransaction(store, "tx-1", startedAtEpochMs = 0, completedAtEpochMs = 5)
            val evidence = FakeEvidenceStore(quota = 0)
            val source =
                FullInspectorDataSource(
                    store,
                    MockEngine(emptyList()),
                    configSupplier = { null },
                    evidenceStore = evidence,
                    evidenceSessionId = { "session-1" },
                )

            val result = source.flagTransaction("tx-1")

            assertTrue(result is InspectorCommandResult.Invalid)
            assertTrue((result as InspectorCommandResult.Invalid).message.contains("full", ignoreCase = true))
            assertTrue(evidence.items("session-1").isEmpty())
        }

    @Test
    fun `flagTransaction and unflagTransaction are Unavailable when no durable EvidenceStore is wired up`() =
        runTest {
            val store = networkStore()
            recordTransaction(store, "tx-1", startedAtEpochMs = 0, completedAtEpochMs = 5)
            val source = FullInspectorDataSource(store, MockEngine(emptyList()), configSupplier = { null })

            assertEquals(InspectorCommandResult.Unavailable, source.flagTransaction("tx-1"))
            assertEquals(InspectorCommandResult.Unavailable, source.unflagTransaction("tx-1"))
            assertTrue(source.flaggedTransactionIds().isEmpty())
        }

    @Test
    fun `flagTransaction rejects an over-long label cleanly instead of throwing into the UI`() =
        runTest {
            val store = networkStore()
            val longPathCapture =
                captureFactory.capture(
                    NetworkRequestInput(method = "GET", url = "https://api.example.test/" + "a".repeat(600)),
                    NetworkResponseInput(statusCode = 200),
                )
            store.record(NetworkTransaction("tx-long", 0, 5, longPathCapture))
            val evidence = FakeEvidenceStore()
            val source =
                FullInspectorDataSource(
                    store,
                    MockEngine(emptyList()),
                    configSupplier = { null },
                    evidenceStore = evidence,
                    evidenceSessionId = { "session-1" },
                )

            val result = source.flagTransaction("tx-long")

            assertTrue(result is InspectorCommandResult.Invalid)
            // The over-long label is caught before RoomEvidenceStore's own require() ever runs --
            // the store is never even consulted, so its IllegalArgumentException can never reach here.
            assertEquals(0, evidence.flagAttempts)
        }

    @Test
    fun `flagCrash stores a CRASH item in the EvidenceStore with the same snapshot shape CrashCapture auto-flags`() =
        runTest {
            val eventStore = FakeEventStore(listOf(sampleCrashEvent()))
            val evidence = FakeEvidenceStore()
            val source =
                FullInspectorDataSource(
                    networkStore(),
                    MockEngine(emptyList()),
                    configSupplier = { null },
                    retainedCaptures = RetainedCaptureQuery({ eventStore }, { "session-1" }),
                    evidenceStore = evidence,
                    evidenceSessionId = { "session-1" },
                )

            val result = source.flagCrash("crash-1", sessionId = null)

            assertTrue(result is InspectorCommandResult.Success)
            val item = evidence.items("session-1").single()
            assertEquals(EvidenceKind.CRASH, item.kind)
            assertEquals("crash-1", item.subjectId)
            assertEquals("session-1", item.sessionId)
            assertEquals("Main thread unresponsive", item.label)
            // Mirrors CrashCapture.crashSnapshotJson() / DevConsoleKtorModule's StoredEvent.crashSnapshotJson().
            assertTrue(item.snapshotJson.contains("\"kind\":\"ANR\""))
            assertTrue(item.snapshotJson.contains("\"thread\":\"main\""))
            assertTrue(item.snapshotJson.contains("\"summary\":\"Main thread unresponsive\""))
            assertTrue(item.snapshotJson.contains("\"payload\":{\"stackTrace\":\"at Foo.bar\""))
        }

    @Test
    fun `flaggedCrashIds reflects an item flagged directly on the store by something else`() =
        runTest {
            val evidence = FakeEvidenceStore()
            evidence.flag(
                StoredEvidenceItem(
                    id = "evidence-1",
                    sessionId = "session-1",
                    kind = EvidenceKind.CRASH,
                    subjectId = "crash-remote",
                    label = "Main thread unresponsive",
                    flaggedAtMs = 1_000,
                    snapshotJson = "{}",
                ),
            )
            val source =
                FullInspectorDataSource(
                    networkStore(),
                    MockEngine(emptyList()),
                    configSupplier = { null },
                    evidenceStore = evidence,
                    evidenceSessionId = { "session-1" },
                )

            assertEquals(setOf("crash-remote"), source.flaggedCrashIds(sessionId = null))
        }

    @Test
    fun `unflagCrash removes the item`() =
        runTest {
            val eventStore = FakeEventStore(listOf(sampleCrashEvent()))
            val evidence = FakeEvidenceStore()
            val source =
                FullInspectorDataSource(
                    networkStore(),
                    MockEngine(emptyList()),
                    configSupplier = { null },
                    retainedCaptures = RetainedCaptureQuery({ eventStore }, { "session-1" }),
                    evidenceStore = evidence,
                    evidenceSessionId = { "session-1" },
                )
            source.flagCrash("crash-1", sessionId = null)

            val result = source.unflagCrash("crash-1", sessionId = null)

            assertTrue(result is InspectorCommandResult.Success)
            assertTrue(source.flaggedCrashIds(sessionId = null).isEmpty())
        }

    @Test
    fun `flagCrash is Invalid when the crash is no longer available`() =
        runTest {
            val evidence = FakeEvidenceStore()
            val source =
                FullInspectorDataSource(
                    networkStore(),
                    MockEngine(emptyList()),
                    configSupplier = { null },
                    retainedCaptures = RetainedCaptureQuery({ FakeEventStore(emptyList()) }, { "session-1" }),
                    evidenceStore = evidence,
                    evidenceSessionId = { "session-1" },
                )

            val result = source.flagCrash("unknown-crash", sessionId = null)

            assertTrue(result is InspectorCommandResult.Invalid)
            assertTrue(evidence.items("session-1").isEmpty())
        }

    @Test
    fun `snapshot maps transactions, mock rules, and capabilities from the underlying engines`() {
        val store = networkStore()
        recordTransaction(store, "tx-1", startedAtEpochMs = 0, completedAtEpochMs = 5)
        recordTransaction(store, "tx-2", startedAtEpochMs = 10, completedAtEpochMs = 15)
        val mockEngine =
            MockEngine(
                listOf(
                    MockRule(
                        id = "rule-1",
                        priority = 0,
                        method = "GET",
                        path = "/orders",
                        action = MockAction.StaticResponse(200, "{}"),
                    ),
                ),
            )
        val config = configWith(requestExecution = true, mocks = true)
        val source = FullInspectorDataSource(store, mockEngine, configSupplier = { config })

        val snapshot = source.snapshot()

        assertTrue(snapshot.available)
        assertEquals(2, snapshot.transactions.size)
        val first = snapshot.transactions.first { it.id == "tx-1" }
        assertEquals("GET", first.method)
        assertEquals("api.example.test", first.host)
        assertEquals("/orders", first.path)
        assertEquals(200, first.statusCode)
        assertEquals(5L, first.durationMs)
        assertEquals("tx-1", first.requestHeaders["X-Request-Id"])
        assertEquals("application/json", first.responseHeaders["Content-Type"])
        assertTrue(first.responsePreview.orEmpty().contains("ok"))
        assertNull(first.error)
        assertEquals(0L, first.startedAtEpochMs)
        val second = snapshot.transactions.first { it.id == "tx-2" }
        assertEquals(10L, second.startedAtEpochMs)
        // Neither recordTransaction() capture below sets NetworkResponseMetadata.timings, so a
        // normal (no-phase-data) transaction must fall back to no timing phases rather than
        // fabricating zeros -- see the dedicated timing-phases test for the populated case.
        assertFalse(first.timingPhases.hasAnyPhase)

        assertEquals(1, snapshot.mockRules.size)
        assertEquals("rule-1", snapshot.mockRules.single().id)
        assertEquals("GET", snapshot.mockRules.single().method)
        assertEquals("/orders", snapshot.mockRules.single().pathPattern)
        assertTrue(
            snapshot.mockRules
                .single()
                .actionLabel
                .isNotBlank(),
        )

        assertTrue(snapshot.mocksEnabled)
        assertTrue(snapshot.capabilities.requestExecution)
        assertTrue(snapshot.capabilities.mocks)
    }

    @Test
    fun `snapshot maps captured per-phase network timings, leaving uncaptured phases null`() {
        val store = networkStore()
        val capture =
            captureFactory.capture(
                NetworkRequestInput(method = "GET", url = "https://api.example.test/orders"),
                NetworkResponseInput(statusCode = 200)
                    // A pooled connection: no DNS/connect/TLS phase, only send/wait/receive --
                    // the null phases must stay null, never fabricated as zero-length bars.
                    .withMetadata(
                        NetworkResponseMetadata(
                            timings = NetworkTimingPhases(sendMs = 2, waitMs = 118, receiveMs = 9),
                        ),
                    ),
            )
        store.record(NetworkTransaction("tx-timed", 0, 130, capture))
        val source = FullInspectorDataSource(store, MockEngine(emptyList()), configSupplier = { null })

        val phases =
            source
                .snapshot()
                .transactions
                .single()
                .timingPhases

        assertTrue(phases.hasAnyPhase)
        assertNull(phases.dnsMs)
        assertNull(phases.connectMs)
        assertNull(phases.tlsMs)
        assertEquals(2L, phases.sendMs)
        assertEquals(118L, phases.waitMs)
        assertEquals(9L, phases.receiveMs)
    }

    @Test
    fun `snapshot marks a transaction mocked from its capture tags, and leaves an untagged one alone`() {
        val store = networkStore()
        val mockedCapture =
            captureFactory.capture(
                NetworkRequestInput(method = "GET", url = "https://api.example.test/orders")
                    .withMetadata(NetworkRequestMetadata(tags = mapOf("mocked" to "true", "mockRuleId" to "rule-1"))),
                NetworkResponseInput(statusCode = 200),
            )
        store.record(NetworkTransaction("tx-mocked", 0, 5, mockedCapture))
        recordTransaction(store, "tx-real", startedAtEpochMs = 10, completedAtEpochMs = 15)
        val source = FullInspectorDataSource(store, MockEngine(emptyList()), configSupplier = { null })

        val transactions = source.snapshot().transactions

        val mocked = transactions.single { it.id == "tx-mocked" }
        assertTrue(mocked.isMocked)
        assertEquals("rule-1", mocked.mockRuleId)
        val real = transactions.single { it.id == "tx-real" }
        assertTrue(!real.isMocked)
        assertNull(real.mockRuleId)
    }

    @Test
    fun `mockRules surfaces headers, delay, and hit stats from the engine`() {
        val mockEngine =
            MockEngine(
                listOf(
                    MockRule(
                        id = "delayed",
                        priority = 0,
                        path = "/orders",
                        action = MockAction.Delay(250, MockAction.StaticResponse(200, "{}", mapOf("X-Trace" to "abc"))),
                    ),
                ),
            )
        mockEngine.decide(io.devconsole.mocks.MockRequest("GET", "https", "api.test", "/orders"))
        val source = FullInspectorDataSource(networkStore(), mockEngine, configSupplier = { null })

        val rule = source.mockRules().single()

        assertEquals(250L, rule.delayMs)
        assertEquals("abc", rule.headers["X-Trace"])
        assertEquals(1L, rule.hitCount)
        assertTrue(rule.lastHitEpochMs != null)
    }

    @Test
    fun `upsertMockRule wraps the response in a delay only when delayMs is positive`() {
        val mockEngine = MockEngine(emptyList())
        val config = configWith(mocks = true)
        val source = FullInspectorDataSource(networkStore(), mockEngine, configSupplier = { config })

        source.upsertMockRule(
            io.devconsole.ui.compose
                .InspectorMockRuleUi(id = "plain", statusCode = 200, body = "{}", delayMs = 0),
        )
        source.upsertMockRule(
            io.devconsole.ui.compose.InspectorMockRuleUi(
                id = "delayed",
                statusCode = 200,
                body = "{}",
                delayMs = 500,
                headers = mapOf("X-Trace" to "abc"),
            ),
        )

        val plain = mockEngine.rules().single { it.id == "plain" }.action
        val delayed = mockEngine.rules().single { it.id == "delayed" }.action
        assertTrue(plain is MockAction.StaticResponse)
        assertTrue(delayed is MockAction.Delay)
        assertEquals(500L, (delayed as MockAction.Delay).durationMs)
        assertEquals("abc", (delayed.next as MockAction.StaticResponse).headers["X-Trace"])
    }

    @Test
    fun `upsertMockRule rejects a delay outside the engine's supported range`() {
        val mockEngine = MockEngine(emptyList())
        val config = configWith(mocks = true)
        val source = FullInspectorDataSource(networkStore(), mockEngine, configSupplier = { config })

        val badRule =
            io.devconsole.ui.compose.InspectorMockRuleUi(
                id = "bad-delay",
                statusCode = 200,
                body = "{}",
                delayMs = 99_999,
            )
        val result = source.upsertMockRule(badRule)

        assertTrue(result is InspectorCommandResult.Invalid)
        assertTrue(mockEngine.rules().isEmpty())
    }

    @Test
    fun `execute is disabled when requestExecution capability is off`() {
        val mockEngine = MockEngine(emptyList())
        val config =
            configWith(
                requestExecution = false,
                composerEnabled = true,
                composerAllowedHosts = setOf("api.example.test"),
            )
        val transport = RecordingComposerTransport(ComposerResponse(statusCode = 200))
        val source =
            FullInspectorDataSource(
                networkStore(),
                mockEngine,
                composerExecutor = ComposerExecutor(transport),
                configSupplier = { config },
            )

        val result =
            source.execute(InspectorComposerRequest(method = "GET", url = "https://api.example.test/orders"))

        assertEquals(InspectorCommandResult.Disabled("requestExecution"), result)
        assertNull(transport.lastRequest)
    }

    @Test
    fun `execute rejects a host outside the allow-list even when requestExecution is on`() {
        val mockEngine = MockEngine(emptyList())
        val config =
            configWith(
                requestExecution = true,
                composerEnabled = true,
                composerAllowedHosts = setOf("allowed.example.test"),
            )
        val transport = RecordingComposerTransport(ComposerResponse(statusCode = 200))
        val source =
            FullInspectorDataSource(
                networkStore(),
                mockEngine,
                composerExecutor = ComposerExecutor(transport),
                configSupplier = { config },
            )

        val result =
            source.execute(InspectorComposerRequest(method = "GET", url = "https://not-allowed.example.test/orders"))

        assertTrue(result is InspectorCommandResult.Invalid)
        assertNull(transport.lastRequest)
    }

    @Test
    fun `execute rejects when composer is disabled even though requestExecution is on`() {
        val mockEngine = MockEngine(emptyList())
        val config =
            configWith(
                requestExecution = true,
                composerEnabled = false,
                composerAllowedHosts = setOf("allowed.example.test"),
            )
        val transport = RecordingComposerTransport(ComposerResponse(statusCode = 200))
        val source =
            FullInspectorDataSource(
                networkStore(),
                mockEngine,
                composerExecutor = ComposerExecutor(transport),
                configSupplier = { config },
            )

        val result =
            source.execute(InspectorComposerRequest(method = "GET", url = "https://allowed.example.test/orders"))

        assertTrue(result is InspectorCommandResult.Invalid)
        assertNull(transport.lastRequest)
    }

    @Test
    fun `execute dispatches through the composer executor for an allow-listed host`() {
        val mockEngine = MockEngine(emptyList())
        val config =
            configWith(
                requestExecution = true,
                composerEnabled = true,
                composerAllowedHosts = setOf("allowed.example.test"),
            )
        val transport =
            RecordingComposerTransport(
                ComposerResponse(
                    statusCode = 201,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = "{\"id\":1}",
                ),
            )
        val source =
            FullInspectorDataSource(
                networkStore(),
                mockEngine,
                composerExecutor = ComposerExecutor(transport),
                configSupplier = { config },
            )

        val result =
            source.execute(
                InspectorComposerRequest(
                    method = "POST",
                    url = "https://allowed.example.test/orders",
                    headers = mapOf("X-Trace" to "1"),
                    body = "{}",
                ),
            )

        val success = result as InspectorCommandResult.Success
        assertEquals(201, success.statusCode)
        assertEquals("{\"id\":1}", success.body)
        assertEquals("https://allowed.example.test/orders", transport.lastRequest?.url)
        assertEquals("1", transport.lastRequest?.headers?.get("X-Trace"))
    }

    @Test
    fun `setMocksEnabled is disabled and leaves the engine untouched when mocks capability is off`() {
        val mockEngine = MockEngine(emptyList(), enabled = true)
        val config = configWith(mocks = false)
        val source = FullInspectorDataSource(networkStore(), mockEngine, configSupplier = { config })

        val result = source.setMocksEnabled(false)

        assertEquals(InspectorCommandResult.Disabled("mocks"), result)
        assertTrue(mockEngine.isEnabled())
    }

    @Test
    fun `setMocksEnabled flips the engine when mocks capability is on`() {
        val mockEngine = MockEngine(emptyList(), enabled = false)
        val config = configWith(mocks = true)
        val source = FullInspectorDataSource(networkStore(), mockEngine, configSupplier = { config })

        val result = source.setMocksEnabled(true)

        assertTrue(result is InspectorCommandResult.Success)
        assertTrue(mockEngine.isEnabled())
    }

    @Test
    fun `snapshot maps socket connections and frames from the socket store`() {
        val socketStore = InMemorySocketStore()
        socketStore.open(
            SocketConnection(
                id = "socket-1",
                url = "wss://api.example.test/stream",
                openedAtEpochMs = 100,
                state = SocketConnectionState.OPEN,
            ),
        )
        socketStore.append(
            SocketMessage(
                connectionId = "socket-1",
                direction = SocketDirection.SENT,
                timestampEpochMs = 110,
                payload = SocketPayload.Text("hello"),
            ),
        )
        socketStore.append(
            SocketMessage(
                connectionId = "socket-1",
                direction = SocketDirection.RECEIVED,
                timestampEpochMs = 120,
                payload = SocketPayload.Binary(length = 4),
            ).withMetadata(SocketMessageMetadata(frameType = io.devconsole.socket.SocketFrameType.BINARY)),
        )
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { null },
                socketStore = socketStore,
            )

        val snapshot = source.snapshot()

        assertEquals(1, snapshot.sockets.size)
        val socket = snapshot.sockets.single()
        assertEquals("socket-1", socket.id)
        assertEquals("wss://api.example.test/stream", socket.url)
        assertEquals("OPEN", socket.state)
        assertEquals(1, socket.sentCount)
        assertEquals(1, socket.receivedCount)
        assertEquals(2, socket.frames.size)
        val textFrame = socket.frames.single { it.direction == "SENT" }
        assertEquals("hello", textFrame.preview)
        assertEquals("hello".encodeToByteArray().size.toLong(), textFrame.byteLength)
        assertFalse(textFrame.truncated)
        val binaryFrame = socket.frames.single { it.direction == "RECEIVED" && it.frameType == "BINARY" }
        assertEquals(4L, binaryFrame.byteLength)
        assertFalse(binaryFrame.truncated)
    }

    @Test
    fun `snapshot maps a truncated binary frame's true full length, not just its preview`() {
        val socketStore = InMemorySocketStore()
        socketStore.open(
            SocketConnection(
                id = "socket-1",
                url = "wss://api.example.test/stream",
                openedAtEpochMs = 100,
                state = SocketConnectionState.OPEN,
            ),
        )
        // A large binary payload: the preview is truncated, but the captured `length` is the real,
        // full frame size -- this is exactly the "~20 ch" bug the byteLength field exists to fix.
        socketStore.append(
            SocketMessage(
                connectionId = "socket-1",
                direction = SocketDirection.RECEIVED,
                timestampEpochMs = 110,
                payload = SocketPayload.Binary(length = 2_000_000, truncated = true),
            ).withMetadata(SocketMessageMetadata(frameType = io.devconsole.socket.SocketFrameType.BINARY)),
        )
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { null },
                socketStore = socketStore,
            )

        val frame =
            source
                .snapshot()
                .sockets
                .single()
                .frames
                .single()

        assertEquals(2_000_000L, frame.byteLength)
        assertTrue(frame.truncated)
    }

    @Test
    fun `snapshot maps a truncated text frame's byteLength as the preview's own size`() {
        val socketStore = InMemorySocketStore()
        socketStore.open(
            SocketConnection(
                id = "socket-1",
                url = "wss://api.example.test/stream",
                openedAtEpochMs = 100,
                state = SocketConnectionState.OPEN,
            ),
        )
        socketStore.append(
            SocketMessage(
                connectionId = "socket-1",
                direction = SocketDirection.SENT,
                timestampEpochMs = 110,
                payload = SocketPayload.Text("partial preview", truncated = true),
            ),
        )
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { null },
                socketStore = socketStore,
            )

        val frame =
            source
                .snapshot()
                .sockets
                .single()
                .frames
                .single()

        assertEquals("partial preview".encodeToByteArray().size.toLong(), frame.byteLength)
        assertTrue(frame.truncated)
    }

    @Test
    fun `snapshot maps push events from the push store`() {
        val pushStore = InMemoryPushStore()
        pushStore.append(
            PushEvent(
                provider = "fcm",
                data = mapOf("order_id" to "42"),
                messageId = "message-1",
                receivedAtEpochMs = 200,
                lifecycle = PushLifecycle.DISPLAYED,
                simulated = true,
            ),
        )
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { null },
                pushStore = pushStore,
            )

        val snapshot = source.snapshot()

        assertEquals(1, snapshot.pushEvents.size)
        val push = snapshot.pushEvents.single()
        assertEquals("fcm", push.provider)
        assertEquals("message-1", push.messageId)
        assertEquals("DISPLAYED", push.lifecycle)
        assertTrue(push.simulated)
        assertEquals(200L, push.receivedAtEpochMs)
        assertEquals("42", push.dataPreview["order_id"])
    }

    @Test
    fun `snapshot maps logs and crashes from the timeline`() {
        val timeline = InMemoryTimeline(emptyList(), CursorCodec("timeline-cursor-key".encodeToByteArray()))
        timeline.append(
            StoredEvent(
                id = "log-1",
                sessionId = "session-1",
                sequence = 1,
                pluginId = "logs",
                type = "log",
                wallTimeMs = 10,
                monoTimeNs = 10,
                severity = 3,
                summary = "Checkout failed",
                tagsJson = """{"tag":"Checkout","level":"ERROR"}""",
                payloadJson = """{"message":"Checkout failed"}""",
            ),
        )
        timeline.append(
            StoredEvent(
                id = "crash-1",
                sessionId = "session-1",
                sequence = 2,
                pluginId = "crash",
                type = "anr",
                wallTimeMs = 20,
                monoTimeNs = 20,
                severity = 4,
                summary = "Main thread unresponsive",
                tagsJson = """{"kind":"ANR","thread":"main"}""",
                payloadJson = """{"stackTrace":"at Foo.bar"}""",
            ),
        )
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { null },
                timelineSupplier = { timeline },
            )

        val snapshot = source.snapshot()

        assertEquals(2, snapshot.logs.size)
        val log = snapshot.logs.first { it.source == "Checkout" }
        assertEquals("ERROR", log.kind)
        assertEquals("Checkout failed", log.summary)
        assertEquals(10L, log.timestampEpochMs)
        val crash = snapshot.logs.first { it.kind == "anr" }
        assertEquals("main", crash.source)
        assertEquals("Main thread unresponsive", crash.summary)
        assertTrue(crash.detail.orEmpty().contains("Foo.bar"))
    }

    @Test
    fun `snapshot maps crashes with kind, thread, dump and breadcrumbs onto the Crashes surface`() {
        val timeline = InMemoryTimeline(emptyList(), CursorCodec("timeline-cursor-key".encodeToByteArray()))
        timeline.append(
            StoredEvent(
                id = "crash-1",
                sessionId = "session-1",
                sequence = 1,
                pluginId = "crash",
                type = "anr",
                wallTimeMs = 20,
                monoTimeNs = 20,
                severity = 4,
                summary = "Main thread unresponsive",
                tagsJson = """{"kind":"ANR","thread":"main"}""",
                payloadJson =
                    """{"stackTrace":"\"main\"\n\tat Foo.bar","breadcrumbs":[""" +
                        """{"ts":10,"plugin":"network","type":"request","severity":2,"summary":"GET /orders"}]}""",
            ),
        )
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { null },
                timelineSupplier = { timeline },
            )

        val snapshot = source.snapshot()

        assertEquals(1, snapshot.crashes.size)
        val crash = snapshot.crashes.single()
        assertEquals("ANR", crash.kind)
        assertEquals("main", crash.thread)
        assertEquals("Main thread unresponsive", crash.summary)
        assertEquals(20L, crash.timestampEpochMs)
        assertTrue(crash.stackTrace.contains("Foo.bar"))
        assertEquals(1, crash.breadcrumbs.size)
        val breadcrumb = crash.breadcrumbs.single()
        assertEquals(10L, breadcrumb.timestampEpochMs)
        assertEquals("network", breadcrumb.plugin)
        assertEquals("request", breadcrumb.type)
        assertEquals(2, breadcrumb.severity)
        assertEquals("GET /orders", breadcrumb.summary)
        // A "logs"-pluginId event never leaks into the crash-only surface.
        assertTrue(snapshot.logs.any { it.source == "main" })
    }

    @Test
    fun `snapshot maps an uncaught crash with no breadcrumbs to an empty list, not a failure`() {
        val timeline = InMemoryTimeline(emptyList(), CursorCodec("timeline-cursor-key".encodeToByteArray()))
        timeline.append(
            StoredEvent(
                id = "crash-2",
                sessionId = "session-1",
                sequence = 1,
                pluginId = "crash",
                type = "uncaught",
                wallTimeMs = 30,
                monoTimeNs = 30,
                severity = 4,
                summary = "boom",
                tagsJson = """{"kind":"UNCAUGHT","thread":"main"}""",
                payloadJson = """{"stackTrace":"at Foo.bar","breadcrumbs":[]}""",
            ),
        )
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { null },
                timelineSupplier = { timeline },
            )

        val crash = source.snapshot().crashes.single()

        assertEquals("UNCAUGHT", crash.kind)
        assertTrue(crash.breadcrumbs.isEmpty())
    }

    @Test
    fun `snapshot yields empty crashes when no timeline is supplied`() {
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { null },
                timelineSupplier = { null },
            )

        assertTrue(source.snapshot().crashes.isEmpty())
    }

    @Test
    fun `snapshot yields empty logs when no timeline is supplied`() {
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { null },
                timelineSupplier = { null },
            )

        val snapshot = source.snapshot()

        assertTrue(snapshot.logs.isEmpty())
    }

    @Test
    fun `log detail preserves literal backslashes adjacent to letters`() {
        val timeline = InMemoryTimeline(emptyList(), CursorCodec("timeline-cursor-key".encodeToByteArray()))
        timeline.append(
            StoredEvent(
                id = "crash-2",
                sessionId = "session-1",
                sequence = 1,
                pluginId = "crash",
                type = "uncaught",
                wallTimeMs = 10,
                monoTimeNs = 10,
                severity = 4,
                summary = "boom",
                tagsJson = """{"kind":"UNCAUGHT","thread":"main"}""",
                // Engine-escaped form of the literal path `at C:\new\temp Foo.kt`.
                payloadJson = """{"stackTrace":"at C:\\new\\temp Foo.kt"}""",
            ),
        )
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { null },
                timelineSupplier = { timeline },
            )

        val detail =
            source
                .snapshot()
                .logs
                .single()
                .detail
                .orEmpty()

        assertEquals("at C:\\new\\temp Foo.kt", detail)
        assertFalse(detail.contains('\n'))
        assertFalse(detail.contains('\t'))
    }

    @Test
    fun `duplicate-content log lines still map to distinct ids for stable list keys`() {
        val timeline = InMemoryTimeline(emptyList(), CursorCodec("timeline-cursor-key".encodeToByteArray()))
        repeat(2) { index ->
            timeline.append(
                StoredEvent(
                    id = "log-$index",
                    sessionId = "session-1",
                    sequence = index.toLong(),
                    pluginId = "logs",
                    type = "log",
                    wallTimeMs = 500,
                    monoTimeNs = index.toLong(),
                    severity = 3,
                    summary = "loop",
                    tagsJson = """{"tag":"Tick","level":"DEBUG"}""",
                    payloadJson = """{"message":"loop"}""",
                ),
            )
        }
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { null },
                timelineSupplier = { timeline },
            )

        val logs = source.snapshot().logs

        assertEquals(2, logs.size)
        assertEquals(2, logs.map { it.id }.toSet().size)
    }

    @Test
    fun `snapshot maps feature flags including value, override state, allowed values, and mutability`() {
        val flags =
            SessionFeatureFlags(
                listOf(
                    FeatureFlag(
                        key = "dark_mode",
                        defaultValue = "false",
                        allowedValues = setOf("true", "false"),
                        type = io.devconsole.state.FeatureFlagType.BOOLEAN,
                        description = "Enables dark mode",
                        mutable = true,
                    ),
                    FeatureFlag(
                        key = "locked_flag",
                        defaultValue = "on",
                        allowedValues = setOf("on", "off"),
                        mutable = false,
                    ),
                ),
            )
        flags.override("dark_mode", "true")
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { null },
                featureFlagsSupplier = { flags },
            )

        val snapshot = source.snapshot()

        assertEquals(2, snapshot.featureFlags.size)
        val darkMode = snapshot.featureFlags.first { it.key == "dark_mode" }
        assertEquals("true", darkMode.value)
        assertEquals("false", darkMode.defaultValue)
        assertEquals(setOf("true", "false"), darkMode.allowedValues.toSet())
        assertEquals("BOOLEAN", darkMode.type)
        assertTrue(darkMode.mutable)
        assertEquals("Enables dark mode", darkMode.description)
        assertTrue(darkMode.isOverridden)

        val locked = snapshot.featureFlags.first { it.key == "locked_flag" }
        assertEquals("on", locked.value)
        assertFalse(locked.mutable)
        assertFalse(locked.isOverridden)
    }

    @Test
    fun `snapshot yields empty feature flags when no provider is supplied`() {
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { null },
            )

        assertTrue(source.snapshot().featureFlags.isEmpty())
    }

    @Test
    fun `snapshot maps state providers, redacting values marked redacted by the engine`() {
        val registry = StateRegistry()
        registry.register(
            stateProvider("preferences") {
                StateSnapshot(
                    values =
                        mapOf(
                            "theme" to StateValue.StringValue("dark"),
                            "auth_token" to StateValue.Redacted,
                            "retries" to StateValue.NumberValue(3),
                            "enabled" to StateValue.BooleanValue(true),
                        ),
                )
            },
        )
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { null },
                stateRegistry = registry,
            )

        val snapshot = source.snapshot()

        assertEquals(1, snapshot.stateProviders.size)
        val provider = snapshot.stateProviders.single()
        assertEquals("preferences", provider.id)
        val theme = provider.entries.first { it.key == "theme" }
        assertEquals("dark", theme.value)
        assertFalse(theme.redacted)
        val token = provider.entries.first { it.key == "auth_token" }
        assertTrue(token.redacted)
    }

    @Test
    fun `snapshot yields empty state providers when no registry is supplied`() {
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { null },
            )

        assertTrue(source.snapshot().stateProviders.isEmpty())
    }

    @Test
    fun `setFeatureFlag is disabled and leaves the provider untouched when featureFlags capability is off`() {
        val flags = SessionFeatureFlags(listOf(FeatureFlag("dark_mode", false)))
        val config = configWith(featureFlags = false)
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { config },
                featureFlagsSupplier = { flags },
            )

        val result = source.setFeatureFlag("dark_mode", "true")

        assertEquals(InspectorCommandResult.Disabled("featureFlags"), result)
        assertEquals("false", flags.value("dark_mode"))
    }

    @Test
    fun `setFeatureFlag overrides the provider when featureFlags capability is on`() {
        val flags = SessionFeatureFlags(listOf(FeatureFlag("dark_mode", false)))
        val config = configWith(featureFlags = true)
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { config },
                featureFlagsSupplier = { flags },
            )

        val result = source.setFeatureFlag("dark_mode", "true")

        assertTrue(result is InspectorCommandResult.Success)
        assertEquals("true", flags.value("dark_mode"))
    }

    @Test
    fun `setFeatureFlag returns Invalid for a value outside the allowed set`() {
        val flags = SessionFeatureFlags(listOf(FeatureFlag("dark_mode", false)))
        val config = configWith(featureFlags = true)
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { config },
                featureFlagsSupplier = { flags },
            )

        val result = source.setFeatureFlag("dark_mode", "purple")

        assertTrue(result is InspectorCommandResult.Invalid)
        assertEquals("false", flags.value("dark_mode"))
    }

    @Test
    fun `setFeatureFlag is unavailable when no provider is supplied`() {
        val config = configWith(featureFlags = true)
        val source = FullInspectorDataSource(networkStore(), MockEngine(emptyList()), configSupplier = { config })

        val result = source.setFeatureFlag("dark_mode", "true")

        assertEquals(InspectorCommandResult.Unavailable, result)
    }

    @Test
    fun `snapshot maps preference files and entries from the inspector`() {
        val inspector =
            FakePreferencesInspector(
                listOf(
                    PreferencesFileData(
                        name = "user_prefs",
                        entries =
                            listOf(
                                PreferencesEntryData("theme", "dark", "STRING"),
                                PreferencesEntryData("onboarded", "true", "BOOLEAN"),
                            ),
                    ),
                ),
            )
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { configWith() },
                preferencesInspector = inspector,
            )

        val files = source.snapshot().preferenceFiles

        assertEquals(1, files.size)
        assertEquals("user_prefs", files.single().name)
        assertEquals(2, files.single().entries.size)
        assertEquals(
            "STRING",
            files
                .single()
                .entries
                .first { it.key == "theme" }
                .type,
        )
    }

    @Test
    fun `setPreference is disabled and never touches the inspector when preferences capability is off`() {
        val inspector = FakePreferencesInspector()
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { configWith(preferences = false) },
                preferencesInspector = inspector,
            )

        val result = source.setPreference("user_prefs", "theme", "light", "STRING")

        assertEquals(InspectorCommandResult.Disabled("preferences"), result)
        assertEquals(0, inspector.putCallCount)
    }

    @Test
    fun `setPreference writes through and reports success when the capability is on`() {
        val inspector = FakePreferencesInspector()
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { configWith(preferences = true) },
                preferencesInspector = inspector,
            )

        val result = source.setPreference("user_prefs", "theme", "light", "STRING")

        assertTrue(result is InspectorCommandResult.Success)
        assertEquals(1, inspector.putCallCount)
        assertEquals(listOf("user_prefs", "theme", "light", "STRING"), inspector.lastPut)
    }

    @Test
    fun `setPreference reports Invalid when the inspector rejects the write`() {
        val inspector = FakePreferencesInspector().apply { putReturns = false }
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { configWith(preferences = true) },
                preferencesInspector = inspector,
            )

        val result = source.setPreference("user_prefs", "theme", "notabool", "BOOLEAN")

        assertTrue(result is InspectorCommandResult.Invalid)
    }

    @Test
    fun `removePreference is disabled and never touches the inspector when the capability is off`() {
        val inspector = FakePreferencesInspector()
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { configWith(preferences = false) },
                preferencesInspector = inspector,
            )

        val result = source.removePreference("user_prefs", "theme")

        assertEquals(InspectorCommandResult.Disabled("preferences"), result)
        assertEquals(0, inspector.removeCallCount)
    }

    @Test
    fun `removePreference writes through when the capability is on`() {
        val inspector = FakePreferencesInspector()
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { configWith(preferences = true) },
                preferencesInspector = inspector,
            )

        val result = source.removePreference("user_prefs", "theme")

        assertTrue(result is InspectorCommandResult.Success)
        assertEquals(1, inspector.removeCallCount)
    }

    @Test
    fun `snapshot exposes file roots and listing and preview are ungated`() {
        val listing =
            FileListingData(
                root = "files",
                relativePath = "logs",
                entries = listOf(FileEntryData("today.log", "logs/today.log", false, 12, 0)),
            )
        val inspector =
            FakeFileInspector(
                rootsData = listOf("files", "cache"),
                listing = listing,
                previewData = FilePreviewData.Text("hello", truncated = false),
            )
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { configWith(files = false) },
                fileInspector = inspector,
            )

        assertEquals(listOf("files", "cache"), source.snapshot().fileRoots)
        assertEquals("logs", source.listFiles("files", "logs")?.relativePath)
        val preview = source.previewFile("files", "logs/today.log")
        assertTrue(preview is io.devconsole.ui.compose.InspectorFilePreviewUi.Text)
    }

    @Test
    fun `deleteFile is disabled and never touches the inspector when the files capability is off`() {
        val inspector = FakeFileInspector()
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { configWith(files = false) },
                fileInspector = inspector,
            )

        val result = source.deleteFile("files", "logs/today.log")

        assertEquals(InspectorCommandResult.Disabled("files"), result)
        assertEquals(0, inspector.deleteCallCount)
    }

    @Test
    fun `deleteFile writes through when the files capability is on`() {
        val inspector = FakeFileInspector()
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { configWith(files = true) },
                fileInspector = inspector,
            )

        val result = source.deleteFile("files", "logs/today.log")

        assertTrue(result is InspectorCommandResult.Success)
        assertEquals(1, inspector.deleteCallCount)
    }

    @Test
    fun `deleteFile reports Invalid when the inspector refuses`() {
        val inspector = FakeFileInspector().apply { deleteReturns = false }
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { configWith(files = true) },
                fileInspector = inspector,
            )

        val result = source.deleteFile("files", "../escape")

        assertTrue(result is InspectorCommandResult.Invalid)
    }

    @Test
    fun `shareableFilePath is null and never touches the resolver when the files capability is off`() {
        var resolverCallCount = 0
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { configWith(files = false) },
                shareableFileResolver = { _, _ ->
                    resolverCallCount++
                    java.io.File("/tmp/whatever")
                },
            )

        val result = source.shareableFilePath("files", "logs/today.log")

        assertNull(result)
        assertEquals(0, resolverCallCount)
    }

    @Test
    fun `shareableFilePath resolves the absolute path when the files capability is on`() {
        val resolved = java.io.File("/tmp/devconsole-share-test/today.log")
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { configWith(files = true) },
                shareableFileResolver = { root, relativePath ->
                    if (root == "files" && relativePath == "logs/today.log") resolved else null
                },
            )

        val result = source.shareableFilePath("files", "logs/today.log")

        assertEquals(resolved.absolutePath, result)
    }

    @Test
    fun `shareableFilePath is null when the resolver refuses the path even with the capability on`() {
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { configWith(files = true) },
                shareableFileResolver = { _, _ -> null },
            )

        val result = source.shareableFilePath("files", "../escape")

        assertNull(result)
    }

    @Test
    fun `snapshot maps databases from the database inspector`() {
        val inspector = FakeDatabaseInspector(databasesData = listOf("demo.db", "cache.db"))
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { configWith() },
                databaseInspector = inspector,
            )

        assertEquals(listOf("demo.db", "cache.db"), source.snapshot().databases)
    }

    @Test
    fun `listTables and queryTable are ungated by capability`() {
        val listing = DatabaseListingData("demo.db", listOf(DatabaseTableData("users", 1)))
        val queryData = DatabaseQueryData(columns = listOf("id"), rows = listOf(listOf("1")), truncated = false)
        val inspector = FakeDatabaseInspector(listing = listing, queryData = queryData)
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { configWith(database = false) },
                databaseInspector = inspector,
            )

        val tables = requireNotNull(source.listTables("demo.db"))
        assertEquals("users", tables.tables.single().name)
        val rows = requireNotNull(source.queryTable("demo.db", "users"))
        assertEquals(listOf("1"), rows.rows.single())
    }

    @Test
    fun `listTables carries the database's real on-disk size through to the UI listing`() {
        val listing = DatabaseListingData("demo.db", listOf(DatabaseTableData("users", 1)), sizeBytes = 4_300_000)
        val inspector = FakeDatabaseInspector(listing = listing)
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { configWith(database = false) },
                databaseInspector = inspector,
            )

        val tables = requireNotNull(source.listTables("demo.db"))

        assertEquals(4_300_000L, tables.sizeBytes)
    }

    @Test
    fun `executeSql passes writeEnabled false when the database capability is off`() {
        val inspector = FakeDatabaseInspector(executeResult = DatabaseExecResult.WriteBlocked)
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { configWith(database = false) },
                databaseInspector = inspector,
            )

        source.executeSql("demo.db", "DELETE FROM users")

        assertEquals(1, inspector.executeCallCount)
        assertEquals(false, inspector.lastWriteEnabled)
        assertEquals("DELETE FROM users", inspector.lastSql)
    }

    @Test
    fun `executeSql passes writeEnabled true when the database capability is on`() {
        val inspector = FakeDatabaseInspector(executeResult = DatabaseExecResult.Write(1))
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { configWith(database = true) },
                databaseInspector = inspector,
            )

        source.executeSql("demo.db", "DELETE FROM users")

        assertEquals(1, inspector.executeCallCount)
        assertEquals(true, inspector.lastWriteEnabled)
    }

    @Test
    fun `executeSql maps WriteBlocked from the engine to InspectorSqlResultUi WriteBlocked`() {
        val inspector = FakeDatabaseInspector(executeResult = DatabaseExecResult.WriteBlocked)
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { configWith(database = false) },
                databaseInspector = inspector,
            )

        val result = source.executeSql("demo.db", "DELETE FROM users")

        assertEquals(InspectorSqlResultUi.WriteBlocked, result)
    }

    @Test
    fun `snapshot maps sessions, health, and browser from their suppliers`() {
        val session = InspectorSessionUi(id = "session-1", startedAtEpochMs = 10, label = "DevConsole server started")
        val health =
            InspectorHealthUi(
                state = "Started",
                initializationCount = 1,
                publishedEventCount = 5,
                droppedEventCount = 0,
            )
        val browser = InspectorBrowserUi(binding = "LOOPBACK", endpoint = "127.0.0.1:8080")
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { null },
                sessionsSupplier = { listOf(session) },
                healthSupplier = { health },
                browserSupplier = { browser },
            )

        val snapshot = source.snapshot()

        assertEquals(listOf(session), snapshot.sessions)
        assertEquals(health, snapshot.health)
        assertEquals(browser, snapshot.browser)
    }

    @Test
    fun `snapshot yields no sessions, health, or browser when no suppliers are configured`() {
        val source = FullInspectorDataSource(networkStore(), MockEngine(emptyList()), configSupplier = { null })

        val snapshot = source.snapshot()

        assertTrue(snapshot.sessions.isEmpty())
        assertNull(snapshot.health)
        assertNull(snapshot.browser)
        assertNull(snapshot.retention)
    }

    @Test
    fun `snapshot maps retention from its supplier`() {
        val retention = InspectorRetentionUi(maxSessions = 10, maxAgeMs = 604_800_000L, maxBytes = 104_857_600L)
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { null },
                retentionSupplier = { retention },
            )

        val snapshot = source.snapshot()

        assertEquals(retention, snapshot.retention)
    }

    @Test
    fun `revokePrincipal returns Success and calls through when the handler reports a revocation`() {
        var revokedId: String? = null
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { null },
                revokePrincipalHandler = { id ->
                    revokedId = id
                    true
                },
            )

        val result = source.revokePrincipal("browser-1")

        assertEquals("browser-1", revokedId)
        assertTrue(result is InspectorCommandResult.Success)
    }

    @Test
    fun `revokePrincipal returns Invalid when the handler reports no matching principal`() {
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { null },
                revokePrincipalHandler = { false },
            )

        val result = source.revokePrincipal("unknown")

        assertTrue(result is InspectorCommandResult.Invalid)
    }

    @Test
    fun `exportHar and exportPostman are ungated by capability and map Written to Success`() {
        val exporter = FakeInspectorExporter()
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { configWith() },
                exporter = exporter,
            )

        val harResult = source.exportHar()
        val postmanResult = source.exportPostman()
        val sessionZipResult = source.exportSessionZip()

        assertEquals(1, exporter.exportHarCallCount)
        assertEquals(1, exporter.exportPostmanCallCount)
        assertEquals(1, exporter.exportSessionZipCallCount)
        assertEquals(ExportSelection.All, exporter.lastHarSelection)
        assertEquals(ExportSelection.All, exporter.lastPostmanSelection)
        assertTrue(harResult is InspectorCommandResult.Success)
        assertTrue((harResult as InspectorCommandResult.Success).summary.contains("/data/x.har"))
        assertEquals("/data/x.har", harResult.sharePath)
        assertTrue(postmanResult is InspectorCommandResult.Success)
        assertTrue(sessionZipResult is InspectorCommandResult.Success)
    }

    @Test
    fun `exportHar and exportPostman resolve an explicit transaction selection into ExportSelection Ids`() {
        val exporter = FakeInspectorExporter()
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { configWith() },
                exporter = exporter,
            )

        source.exportHar(setOf("tx-1", "tx-2"))
        source.exportPostman(setOf("tx-1"))

        assertEquals(ExportSelection.Ids(setOf("tx-1", "tx-2")), exporter.lastHarSelection)
        assertEquals(ExportSelection.Ids(setOf("tx-1")), exporter.lastPostmanSelection)
    }

    @Test
    fun `exportHar and exportPostman map Failed to InspectorCommandResult Failed`() {
        val exporter =
            FakeInspectorExporter(
                harResult = ExportOutcome.Failed("disk full"),
                postmanResult = ExportOutcome.Failed("disk full"),
            )
        val source =
            FullInspectorDataSource(
                networkStore(),
                MockEngine(emptyList()),
                configSupplier = { configWith() },
                exporter = exporter,
            )

        val harResult = source.exportHar()
        val postmanResult = source.exportPostman()

        assertEquals(InspectorCommandResult.Failed("disk full"), harResult)
        assertEquals(InspectorCommandResult.Failed("disk full"), postmanResult)
    }

    @Test
    fun `exportHar and exportPostman are Unavailable when no exporter is configured`() {
        val source = FullInspectorDataSource(networkStore(), MockEngine(emptyList()), configSupplier = { configWith() })

        assertEquals(InspectorCommandResult.Unavailable, source.exportHar())
        assertEquals(InspectorCommandResult.Unavailable, source.exportPostman())
        assertEquals(InspectorCommandResult.Unavailable, source.exportSessionZip())
    }
}
