package io.devconsole

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import io.devconsole.api.AccessInfo
import io.devconsole.api.BrowserBinding
import io.devconsole.api.BrowserEndpoint
import io.devconsole.api.BrowserSecurity
import io.devconsole.api.CaptureCategory
import io.devconsole.api.CaptureRuleEngine
import io.devconsole.api.CaptureRuleStore
import io.devconsole.api.CrashPolicy
import io.devconsole.api.DevConsoleConfig
import io.devconsole.api.DevConsoleFacadeProvider
import io.devconsole.api.DevConsoleState
import io.devconsole.api.InitResult
import io.devconsole.api.InspectorOpenResult
import io.devconsole.api.ScreenshotPolicy
import io.devconsole.api.ScreenshotResult
import io.devconsole.api.StartRequest
import io.devconsole.api.StartResult
import io.devconsole.api.StopReason
import io.devconsole.core.DevConsoleRuntime
import io.devconsole.core.EventBatchWriter
import io.devconsole.core.RuntimeGate
import io.devconsole.logs.LogRecorder
import io.devconsole.logs.LogSink
import io.devconsole.mocks.MockEngine
import io.devconsole.mocks.MockEngineRegistry
import io.devconsole.mocks.MockOutcome
import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkAttachmentPayload
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkTransactionRecorder
import io.devconsole.push.InMemoryPushStore
import io.devconsole.push.PushEvent
import io.devconsole.push.PushInput
import io.devconsole.push.PushLifecycle
import io.devconsole.push.PushRecorder
import io.devconsole.push.PushSimulationCallback
import io.devconsole.push.PushSimulator
import io.devconsole.push.PushStore
import io.devconsole.remoteconfig.RemoteConfigProvider
import io.devconsole.remoteconfig.RemoteConfigRegistry
import io.devconsole.remoteconfig.RemoteConfigSnapshot
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.server.api.BrowserPrincipal
import io.devconsole.server.api.LocalNetworkPermissionGate
import io.devconsole.server.api.SdkHealthSnapshot
import io.devconsole.server.api.ServerMetadata
import io.devconsole.server.api.ServerStartResult
import io.devconsole.server.api.SessionAuthority
import io.devconsole.server.api.SessionCodeAuthority
import io.devconsole.server.api.SessionCodeInfo
import io.devconsole.server.ktor.KtorLocalServerEngine
import io.devconsole.socket.InMemorySocketStore
import io.devconsole.socket.SocketProtocol
import io.devconsole.socket.SocketRecorder
import io.devconsole.state.SessionFeatureFlags
import io.devconsole.state.StateProvider
import io.devconsole.state.StateRegistry
import io.devconsole.storage.api.AttachmentWriteRequest
import io.devconsole.storage.api.AttachmentWriteResult
import io.devconsole.storage.api.EventStore
import io.devconsole.storage.api.EvidenceStore
import io.devconsole.storage.api.RedactionApplicability
import io.devconsole.storage.api.RetainedCaptureQuery
import io.devconsole.storage.api.SessionRetentionPolicy
import io.devconsole.storage.api.StoredSession
import io.devconsole.storage.api.StoredSessionStatus
import io.devconsole.storage.room.FileAttachmentStore
import io.devconsole.storage.room.RoomAttachmentStore
import io.devconsole.storage.room.RoomEventStore
import io.devconsole.storage.room.RoomEvidenceStore
import io.devconsole.storage.room.RoomRetentionCoordinator
import io.devconsole.storage.room.RoomSessionStore
import io.devconsole.storage.room.RoomTimelineAnnotations
import io.devconsole.timeline.CursorCodec
import io.devconsole.timeline.InMemoryTimeline
import io.devconsole.timeline.InMemoryTimelineAnnotations
import io.devconsole.timeline.Timeline
import io.devconsole.timeline.TimelineAnnotations
import io.devconsole.timeline.TimelineAppender
import io.devconsole.ui.compose.DevConsoleInspectorBridge
import io.devconsole.ui.compose.InspectorBrowserPrincipalUi
import io.devconsole.ui.compose.InspectorBrowserUi
import io.devconsole.ui.compose.InspectorHealthUi
import io.devconsole.ui.compose.InspectorRetentionUi
import io.devconsole.ui.compose.InspectorSessionUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import io.devconsole.api.BindingMode as PublicBindingMode
import io.devconsole.server.api.BindingMode as ServerBindingMode
import io.devconsole.server.api.SessionPolicy as ServerSessionPolicy
import io.devconsole.server.api.StartRequest as ServerStartRequest

internal class PlatformFacadeProvider : DevConsoleFacadeProvider {
    private val runtime = DevConsoleRuntime(RuntimeGate.Enabled)

    @Volatile private var metadata = ServerMetadata()

    private val redaction = RedactionEngine(RedactionPolicy.default())

    private fun activeSessionId(): String =
        checkNotNull(runtime.currentSessionId()) { "Capture before runtime session" }

    /**
     * Fail-open: an absent config (capture before [initialize]) or a gate that somehow throws both
     * mean "capture", never "drop" -- every capture-category gate in this class routes through here.
     */
    private fun categoryEnabled(category: CaptureCategory): Boolean =
        runCatching { activeConfig?.capturesCategory(category) ?: true }.getOrDefault(true)

    /** One monotonic counter shared by every timeline appender, so the timeline orders correctly. */
    private val timelineSequence = AtomicLong(0)

    /**
     * Ring buffer of recent, already-redacted timeline summaries that [crashCapture] reads
     * synchronously at crash/ANR time. Sized from [io.devconsole.api.CrashPolicy.breadcrumbDepth]
     * once a host config is known; see [io.devconsole.api.DevConsoleConfig.crashPolicy] application in
     * [initialize].
     */
    private val breadcrumbs = BreadcrumbRingBuffer(CrashPolicy().breadcrumbDepth)

    /** Mirrors network/socket/push captures onto the timeline and live stream (see [CaptureTimelineBridge]). */
    private val captureBridge =
        CaptureTimelineBridge(
            sessionId = ::activeSessionId,
            appender = { timelineAppender },
            streamHub = { if (::serverEngine.isInitialized) serverEngine.streamHub else null },
            nextSequence = timelineSequence::incrementAndGet,
            breadcrumbs = breadcrumbs,
            onPublished = runtime::recordPublishedEvent,
        )
    private val sessionMarkerRecorder = SessionMarkerRecorder(captureBridge)
    private var sessionMarkerMonitor: AndroidSessionMarkerMonitor? = null
    private val reportingPersistenceDrop = AtomicBoolean(false)
    private val liveCaptureLock = Any()

    // Wrapped so a capture also lands on the timeline; the server reads through the same wrapper.
    private val networkTransactionStore =
        InMemoryNetworkTransactionStore(NetworkCursorCodec(randomCursorSecret()))
    private val networkStore =
        TeeingNetworkTransactionStore(
            networkTransactionStore,
            captureBridge,
            { runtime.currentSessionId() },
            liveCaptureLock,
        )
    private val inMemorySocketStore = InMemorySocketStore()
    private val socketStore = TeeingSocketStore(inMemorySocketStore, captureBridge, liveCaptureLock)
    private val inMemoryPushStore = InMemoryPushStore()
    private val pushStore = TeeingPushStore(inMemoryPushStore, captureBridge, liveCaptureLock)
    private val stateRegistry = StateRegistry()
    private val remoteConfigRegistry = RemoteConfigRegistry()
    private val mockEngineInstance = MockEngine(emptyList()).withOutcomeSink(::recordMockOutcome)

    /** Handed back by [mockEngine] instead of [mockEngineInstance] while MOCKS is off. Never mutated. */
    private val disabledMockEngine = MockEngine(emptyList(), enabled = false)

    /** Built once and reused by both the Compose adapter and the browser server, per host process. */
    private val preferencesInspectorInstance by lazy { AndroidPreferencesInspector(application, redaction) }
    private val fileInspectorInstance by lazy { AndroidFileInspector(application, redaction) }
    private val databaseInspectorInstance by lazy { AndroidDatabaseInspector(application, redaction) }
    private var mockPersistenceBound = false

    /** Consulted by the network recorder before anything is redacted, stored, or exported. */
    private val captureRuleEngineInstance = CaptureRuleEngine()

    @Volatile private var captureRuleStore: CaptureRuleStore? = null
    private val captureRulePersistenceBound = AtomicBoolean(false)
    private val sessionAuthority = SessionAuthority()

    /** Shares [sessionAuthority]'s session store (see `SessionCodeAuthority` KDoc). */
    private val sessionCodeAuthority = SessionCodeAuthority(sessionAuthority)

    private val networkRecorderInstance =
        NetworkTransactionRecorder(NetworkCaptureFactory(redaction), networkStore)
            .withSessionIdProvider(::activeSessionId)
            .withAttachmentSink(::persistNetworkAttachment)
            .withCaptureGate { method, url ->
                categoryEnabled(CaptureCategory.NETWORK) && captureRuleEngineInstance.allowsCapture(method, url)
            }
    private val socketRecorderInstance =
        SocketRecorder(redaction, socketStore)
            .withSessionIdProvider(::activeSessionId)
            .withOperationLock(liveCaptureLock)
            .withProtocolGate { protocol ->
                when (protocol) {
                    SocketProtocol.WEBSOCKET -> categoryEnabled(CaptureCategory.SOCKET)
                    SocketProtocol.MQTT -> categoryEnabled(CaptureCategory.MQTT)
                }
            }

    /**
     * Gates only the write side (`append`); reads (server routes, [FullInspectorDataSource]) still see
     * whatever was already recorded, matching every other category's belt-and-suspenders gating.
     * [PushRecorder.record] still redacts and returns the normal [PushEvent] shape to the caller --
     * this only skips persistence, so [recordPush] needs no special-cased fallback path.
     */
    private val gatedPushStore =
        object : PushStore by pushStore {
            override fun append(event: PushEvent) {
                if (categoryEnabled(CaptureCategory.PUSH)) pushStore.append(event)
            }
        }
    private val pushRecorderInstance = PushRecorder(redaction, gatedPushStore)

    /**
     * A dashboard "Simulate push" with no host handler still needs a lifecycle to record; DISPLAYED
     * is the sensible default. A host wanting its own handler invoked can supply one later, but the
     * simulator being present at all is what makes the push tab functional rather than a dead 409.
     */
    private val pushSimulator = PushSimulator(PushSimulationCallback { PushLifecycle.DISPLAYED }, pushRecorderInstance)

    /** Set once [persistentTimeline] builds the timeline; the sink no-ops until then. */
    @Volatile private var timelineAppender: TimelineAppender? = null

    /** Retained so crash capture can persist synchronously; the batch writer is too late by then. */
    @Volatile private var eventStore: EventStore? = null

    /** Retained so a host can re-read the address after losing its StartResult. Cleared on stop. */
    @Volatile private var lastStarted: StartResult.Started? = null

    @Volatile private var activeConfig: DevConsoleConfig? = null

    private val crashCapture =
        CrashCapture(
            ::activeSessionId,
            redaction,
            { timelineAppender },
            { eventStore?.takeIf { durableSessionReady.get() } },
            { sessionStore?.takeIf { durableSessionReady.get() } },
            timelineSequence::incrementAndGet,
            breadcrumbs = { breadcrumbs },
            policy = { activeConfig?.crashPolicy ?: CrashPolicy() },
            evidenceStore = { roomEvidenceStore.takeIf { durableSessionReady.get() } },
        )
    private val anrWatchdog = AnrWatchdog(onAnr = crashCapture::recordAnr)

    /** Never holds a strong reference to an Activity; see [ActivityTracker]'s KDoc. */
    private val activityTracker = ActivityTracker()
    private val activityTrackerRegistered = AtomicBoolean(false)
    private var openTriggerController: OpenTriggerController? = null
    private val screenshotCaptureInstance = ScreenshotCapture()
    private val timelineLogSink = TimelineLogSink(captureBridge)
    private val logRecorderInstance =
        LogRecorder(
            redaction,
            LogSink { entry -> if (categoryEnabled(CaptureCategory.LOGS)) timelineLogSink.emit(entry) },
        )

    private fun recordMockOutcome(outcome: MockOutcome) {
        when (outcome) {
            is MockOutcome.Matched ->
                captureBridge.emit(
                    pluginId = "mocks",
                    type = "mock.rule.matched",
                    severity = io.devconsole.api.EventSeverity.INFO,
                    summary = "Mock rule matched: ${outcome.ruleId}",
                    tagsJson = "{\"ruleId\":\"${outcome.ruleId.escapeJson()}\"}",
                )
            is MockOutcome.EvaluationError ->
                captureBridge.emit(
                    pluginId = "mocks",
                    type = "mock.evaluation.failed",
                    severity = io.devconsole.api.EventSeverity.ERROR,
                    summary = "Mock evaluation failed${outcome.ruleId?.let { ": $it" } ?: ""}",
                    tagsJson =
                        outcome.ruleId
                            ?.let { "{\"ruleId\":\"${it.escapeJson()}\"}" }
                            ?: "{}",
                )
            is MockOutcome.Passthrough -> Unit
        }
    }

    /**
     * Called only by the network recorder's background worker. The payload has already been
     * bounded and redacted; Room/file failures return null and never affect the host request.
     */
    private fun persistNetworkAttachment(payload: NetworkAttachmentPayload): String? {
        if (!durableSessionReady.get()) return null
        val store = roomAttachmentStore ?: return null
        return runBlocking {
            when (
                val result =
                    store.write(
                        AttachmentWriteRequest(
                            sessionId = payload.sessionId ?: activeSessionId(),
                            eventId = payload.transactionId,
                            mimeType = payload.contentType,
                            bytes = payload.bytes,
                            isRedacted = true,
                        ).withSourceMetadata(
                            originalLength = payload.originalLength,
                            truncated = payload.truncated,
                        ),
                    )
            ) {
                is AttachmentWriteResult.Success -> {
                    lifecycleScope.launch {
                        sessionStore?.enforceRetention(result.attachment.sessionId)
                        storedSessions = sessionStore?.sessions().orEmpty()
                    }
                    result.attachment.id
                }
                AttachmentWriteResult.RejectedUnredactedContent,
                AttachmentWriteResult.Unavailable,
                -> null
            }
        }
    }

    /** Both deferred until [initialize] because they need the caller-supplied config's flag list. */
    private lateinit var featureFlags: SessionFeatureFlags
    private lateinit var serverEngine: KtorLocalServerEngine

    /** Needed at [start] time to evaluate [LocalNetworkPermissionGate] for LAN binding requests. */
    private lateinit var application: Application

    private var keepAliveGate: KeepAliveGate? = null
    private var keepAliveController: KeepAliveServiceController? = null

    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleMutex = Mutex()
    private val durableSessionReady = AtomicBoolean(false)

    @Volatile private var bootstrapJob: Job? = null

    /** Room history is hydrated after the socket is live and cancelled before teardown completes. */
    @Volatile private var timelineHydrationJob: Job? = null
    private val timelineHydrationGeneration = AtomicLong(0)

    /**
     * Durable timeline storage. Room opens the database file lazily on first query, so building it
     * during [initialize] costs no disk I/O on the calling thread. Left null if construction fails —
     * the console then runs in memory only rather than taking the host down with it.
     */
    @Volatile private var batchWriter: EventBatchWriter? = null

    @Volatile private var batchWriterCapacity: Int? = null

    @Volatile private var roomEventStore: RoomEventStore? = null

    @Volatile private var roomAttachmentStore: RoomAttachmentStore? = null

    /**
     * Exposed via [evidenceStore] for the evidence-tray HTTP routes the server engine wires onto
     * itself, and passed straight into [FullInspectorDataSource] below so the Compose in-app
     * inspector's own flag/unflag reach the identical durable store -- a flag made on this device and
     * one made from the dashboard are the same fact, never two independent copies.
     */
    @Volatile private var roomEvidenceStore: EvidenceStore? = null

    @Volatile private var sessionStore: RoomSessionStore? = null

    @Volatile private var storedSessions: List<StoredSession> = emptyList()

    @Volatile private var timelineAnnotations: TimelineAnnotations = InMemoryTimelineAnnotations()

    /**
     * Built once and reused. `initialize()` runs again whenever a host config supersedes the one
     * auto-initialization installed, and each server engine is rebuilt with the timeline this
     * returns. Rebuilding the timeline too would strand the running server — and every capture,
     * log, and crash appender — on an orphaned instance, which is exactly "nothing shows on the
     * timeline even though the app is active."
     */
    @Volatile private var cachedTimeline: Timeline? = null

    private fun persistentTimeline(
        application: Application,
        storagePolicy: io.devconsole.api.StoragePolicy,
        retentionPolicy: io.devconsole.api.RetentionPolicy,
        eventBufferCapacity: Int,
    ): Timeline {
        roomEventStore?.withPolicy(
            maxEvents = storagePolicy.maxTimelineEvents.toLong(),
            maxAgeMs = storagePolicy.maxAgeMs,
            maxBytes = storagePolicy.maxBytes,
        )
        roomAttachmentStore?.withPolicy(
            maxTotalBytes = storagePolicy.maxBytes,
            maxAgeMs = storagePolicy.maxAgeMs,
        )
        sessionStore?.withPolicy(retentionPolicy.toSessionRetentionPolicy())
        reconfigureBatchWriter(eventBufferCapacity)
        return cachedTimeline
            ?: buildPersistentTimeline(application, storagePolicy, retentionPolicy, eventBufferCapacity).also {
                cachedTimeline =
                    it
            }
    }

    private fun buildPersistentTimeline(
        application: Application,
        storagePolicy: io.devconsole.api.StoragePolicy,
        retentionPolicy: io.devconsole.api.RetentionPolicy,
        eventBufferCapacity: Int,
    ): Timeline =
        runCatching {
            val manager =
                RecoveringDevConsoleDatabase(
                    application = application,
                    databaseName = DATABASE_NAME,
                    onRecovered = sessionMarkerRecorder::storageRecovered,
                )
            val database = manager.current()
            val retentionCoordinator = RoomRetentionCoordinator()
            val attachmentFiles = FileAttachmentStore(File(application.noBackupFilesDir, DEVCONSOLE_DIRECTORY))
            roomAttachmentStore =
                RoomAttachmentStore(
                    database = database,
                    files = attachmentFiles,
                    coordinator = retentionCoordinator,
                ).withRecovery(manager::current, manager::recover)
                    .withPolicy(
                        maxTotalBytes = storagePolicy.maxBytes,
                        maxAgeMs = storagePolicy.maxAgeMs,
                    )
            timelineAnnotations =
                RoomTimelineAnnotations(database)
                    .withRecovery(manager::current, manager::recover)
            captureRuleStore = RoomCaptureRuleStore { manager.current().captureRuleDao() }
            bindCaptureRulePersistence()
            val store =
                RoomEventStore(database, retentionCoordinator)
                    .withRecovery(manager::current, manager::recover)
                    .withPolicy(
                        maxEvents = storagePolicy.maxTimelineEvents.toLong(),
                        maxAgeMs = storagePolicy.maxAgeMs,
                        maxBytes = storagePolicy.maxBytes,
                    ).also {
                        roomEventStore = it
                        eventStore = it
                    }
            sessionStore =
                RoomSessionStore(
                    database = database,
                    attachmentFiles = attachmentFiles,
                    policy = retentionPolicy.toSessionRetentionPolicy(),
                    coordinator = retentionCoordinator,
                ).withRecovery(manager::current, manager::recover)
            roomEvidenceStore =
                RoomEvidenceStore(database)
                    .withRecovery(manager::current, manager::recover)
            disableSessionFirstRetention()
            scheduleSessionBootstrap(application)
            val writer = createBatchWriter(store, eventBufferCapacity).also(EventBatchWriter::stop)
            batchWriter = writer
            batchWriterCapacity = eventBufferCapacity
            PersistentTimeline(
                delegate = InMemoryTimeline(emptyList(), CursorCodec(randomCursorSecret())),
                writer = writer,
                persistenceReady = durableSessionReady.get(),
                pendingCapacity = eventBufferCapacity,
                onPendingDrop = { runtime.recordDroppedEvents(1) },
            ).also { timelineAppender = it }
        }.getOrElse { InMemoryTimeline(emptyList(), CursorCodec(randomCursorSecret())).also { timelineAppender = it } }

    private fun createBatchWriter(
        store: EventStore,
        capacity: Int,
    ): EventBatchWriter =
        EventBatchWriter(
            store = store,
            scope = lifecycleScope,
            capacity = capacity,
            onDrop = { event, _ ->
                runtime.recordDroppedEvents(1)
                if (reportingPersistenceDrop.compareAndSet(false, true)) {
                    try {
                        sessionMarkerMonitor?.dataDropped(event.pluginId, 1)
                    } finally {
                        reportingPersistenceDrop.set(false)
                    }
                }
            },
            onStored = { events ->
                // Retention sees fully committed event/attachment accounting, never queued work.
                sessionStore?.enforceRetention(events.lastOrNull()?.sessionId)
                storedSessions = sessionStore?.sessions().orEmpty()
            },
        )

    /**
     * Restores durable capture exclusions off the main thread -- Room refuses main-thread queries
     * and hosts initialize DevConsole from `Application.onCreate`. Until the restore lands the
     * engine simply excludes nothing, exactly as on a first install.
     */
    private fun bindCaptureRulePersistence() {
        val store = captureRuleStore ?: return
        if (!captureRulePersistenceBound.compareAndSet(false, true)) return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { captureRuleEngineInstance.bindPersistence(store) }
                .onFailure {
                    captureRulePersistenceBound.set(false)
                    logcatInfo("DevConsole", "Persistent capture rules are unavailable: ${it.javaClass.simpleName}")
                }
        }
    }

    /** Enables strict session writes only after the row is demonstrably durable. */
    private fun scheduleSessionBootstrap(application: Application) {
        val expectedSessionId = activeSessionId()
        bootstrapJob =
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    bootstrapDurableSession(application, expectedSessionId)
                } finally {
                    // The server may already be Running while Room catches up; refresh the SDK
                    // inspector so its active-session/uptime data appears without polling.
                    DevConsoleInspectorBridge.notifyServerStateChanged()
                }
            }
    }

    private fun disableSessionFirstRetention() {
        durableSessionReady.set(false)
        roomEventStore?.withSessionFirstRetention(false)
        roomAttachmentStore?.withSessionFirstRetention(false)
    }

    private suspend fun bootstrapDurableSession(
        application: Application,
        expectedSessionId: String,
    ): Boolean =
        withTimeoutOrNull(BOOTSTRAP_TIMEOUT_MS) {
            bootstrapDurableSessionWithinTimeout(application, expectedSessionId)
        } ?: false

    private suspend fun bootstrapDurableSessionWithinTimeout(
        application: Application,
        expectedSessionId: String,
    ): Boolean {
        val store = sessionStore ?: return false

        fun stillCurrent(): Boolean =
            runtime.currentSessionId() == expectedSessionId && runtime.state.value != DevConsoleState.Stopped
        if (!stillCurrent()) return false
        return try {
            if (!closeSessionsLeftByDeadProcesses(store, expectedSessionId) { stillCurrent() }) return false
            if (!stillCurrent()) return false
            store.start(application.storedSession(expectedSessionId))
            if (!stillCurrent()) return false
            storedSessions = store.sessions()
            val active = stillCurrent() && store.session(expectedSessionId)?.status == StoredSessionStatus.ACTIVE
            if (active) {
                roomEventStore?.withSessionFirstRetention()
                roomAttachmentStore?.withSessionFirstRetention()
                durableSessionReady.set(true)
                if (runtime.state.value is DevConsoleState.Running) {
                    (cachedTimeline as? PersistentTimeline)?.setPersistenceReady(true)
                }
            }
            active
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    /**
     * A process death cannot run `stop()`, so every prior `ACTIVE` row has to be closed here, before
     * this process becomes writable -- as `CRASHED` only for a run that actually recorded an uncaught
     * exception. An ordinary kill (swipe-away, system reclaim, Android Studio's stop button) leaves
     * behind a leftover `ACTIVE` row indistinguishable from a crash's, and closing those as `CRASHED`
     * too is what fired the "Previous run crashed" banner after runs that never crashed; see
     * [recordedUncaughtCrash] for the evidence that separates them.
     *
     * [RoomSessionStore] itself dispatches every DAO operation to IO. Returns false when
     * [stillCurrent] stops holding partway through -- a newer bootstrap owns the session now and this
     * one must abandon its work rather than keep writing.
     */
    private suspend fun closeSessionsLeftByDeadProcesses(
        store: RoomSessionStore,
        expectedSessionId: String,
        stillCurrent: () -> Boolean,
    ): Boolean {
        val events = roomEventStore
        store
            .sessions()
            .asSequence()
            .filter { it.status == StoredSessionStatus.ACTIVE && it.id != expectedSessionId }
            .forEach { stale ->
                if (!stillCurrent()) return false
                val endedAtMs = System.currentTimeMillis()
                if (events?.recordedUncaughtCrash(stale.id) == true) {
                    store.crash(stale.id, endedAtMs)
                } else {
                    store.end(stale.id, endedAtMs)
                }
            }
        return true
    }

    private fun reconfigureBatchWriter(capacity: Int) {
        val store = roomEventStore ?: return
        val previous = batchWriter
        if (previous != null && batchWriterCapacity == capacity) return
        val replacement = createBatchWriter(store, capacity)
        if (runtime.state.value is DevConsoleState.Running && durableSessionReady.get()) {
            replacement.start()
        } else {
            replacement.stop()
        }
        batchWriter = replacement
        batchWriterCapacity = capacity
        (cachedTimeline as? PersistentTimeline)?.replaceWriter(replacement)
        if (previous != null) lifecycleScope.launch { previous.flushAndStop() }
    }

    override fun initialize(
        application: Application,
        config: DevConsoleConfig,
    ): InitResult = initialize(application, config, provisional = false)

    private fun installKeepAlive(application: Application) {
        val gate = KeepAliveGate(application)
        keepAliveGate = gate
        keepAliveController = KeepAliveServiceController(gate)
    }

    /**
     * [provisional] marks auto-initialization, whose default config the host's own [initialize]
     * call is allowed to replace rather than collide with.
     */
    @Synchronized
    internal fun initialize(
        application: Application,
        config: DevConsoleConfig,
        provisional: Boolean,
    ): InitResult {
        val validationErrors = config.validationErrors(application.isDebuggable())
        if (validationErrors.isNotEmpty()) return InitResult.InvalidConfiguration(validationErrors)
        val policyConfigured =
            sessionAuthority.configurePolicy(
                ServerSessionPolicy(maxAuthenticatedSessions = config.sessionPolicy.maxAuthenticatedSessions),
            )
        if (!policyConfigured) {
            return InitResult.Conflict("Session policy cannot change while browser sessions are active")
        }
        val result = runtime.initialize(config, provisional)
        if (result != InitResult.Initialized) return result

        val previousConfig = activeConfig
        this.application = application
        installKeepAlive(application)
        activeConfig = config
        // Null (never constructed-then-hidden) when INSPECTION is off -- passed to both the in-app
        // inspector below and the server engine wiring further down.
        val inspectionEnabled = config.capturesCategory(CaptureCategory.INSPECTION)
        val gatedPreferencesInspector = preferencesInspectorInstance.takeIf { inspectionEnabled }
        val gatedFileInspector = fileInspectorInstance.takeIf { inspectionEnabled }
        val gatedDatabaseInspector = databaseInspectorInstance.takeIf { inspectionEnabled }
        installInspectorBridge(application, gatedPreferencesInspector, gatedFileInspector, gatedDatabaseInspector)
        networkTransactionStore.withCapacity(config.storagePolicy.maxNetworkTransactions)
        roomEventStore?.withPolicy(
            maxEvents = config.storagePolicy.maxTimelineEvents.toLong(),
            maxAgeMs = config.storagePolicy.maxAgeMs,
            maxBytes = config.storagePolicy.maxBytes,
        )
        roomAttachmentStore?.withPolicy(
            maxTotalBytes = config.storagePolicy.maxBytes,
            maxAgeMs = config.storagePolicy.maxAgeMs,
        )
        sessionStore?.withPolicy(config.retentionPolicy.toSessionRetentionPolicy())
        breadcrumbs.resize(config.crashPolicy.breadcrumbDepth)
        anrWatchdog.updatePolicy(
            thresholdMs = config.crashPolicy.anrThresholdMs,
            maxThreadsInDump = config.crashPolicy.maxThreadsInDump,
            maxFramesPerThread = config.crashPolicy.maxFramesPerThread,
            maxStackChars = config.crashPolicy.maxStackChars,
        )
        reconfigureBatchWriter(config.eventBufferCapacity)
        if (sessionMarkerMonitor == null) {
            sessionMarkerMonitor = AndroidSessionMarkerMonitor(application, sessionMarkerRecorder)
        }
        registerActivityTrackerOnce(application)
        reconfigureOpenTriggers(application, config)
        metadata =
            application
                .serverMetadata()
                .copy(captureCategories = CaptureCategory.wireNames(config.captureCategories))
        if (!mockPersistenceBound) {
            runCatching {
                val mockRuleStore = AndroidMockRuleStore(application)
                mockEngineInstance.bindPersistence(
                    store = mockRuleStore,
                    currentAppVersion = metadata.appVersionName,
                    installationId = mockRuleStore.installationId(),
                )
            }.onSuccess {
                mockPersistenceBound = true
            }.onFailure {
                logcatInfo("DevConsole", "Persistent mock rules are unavailable: ${it.javaClass.simpleName}")
            }
        }
        // Lets `OkHttpClient.Builder.installDevConsole` wire mock rules without the host naming an
        // engine -- that module sits below this facade and cannot call mockEngine() itself. Published
        // through mockEngine() rather than mockEngineInstance so a host with MOCKS off publishes the
        // permanently disabled engine and mocking stays off everywhere, installer included.
        MockEngineRegistry.publish(mockEngine())
        // Apply the host's policy at the shared capture engine so every recorder redacts by it.
        redaction.updatePolicy(config.redactionPolicy)
        if (config.capturesCategory(CaptureCategory.STATE)) {
            // Cleared first, not merged into: both registries reject a duplicate id, so on a
            // stop() -> initialize(configB) carrying a *replacement* provider under the same id
            // (a fresh FirebaseRemoteConfigAdapter over a new client, say) the registration would
            // be refused and every surface would keep reading through the torn-down original --
            // silently, since safely{}'s result is discarded here. Configured providers are owned
            // by the config, so the new config's set is the whole truth.
            stateRegistry.clear()
            remoteConfigRegistry.clear()
            // A duplicate id *within one config*, or from a host that both configured and
            // late-registered a provider, must not take down initialize(); it is simply ignored.
            config.stateProviders.forEach { provider -> safely { stateRegistry.register(provider) } }
            config.remoteConfigProviders.forEach { provider -> safely { remoteConfigRegistry.register(provider) } }
        }
        featureFlags = SessionFeatureFlags(config.featureFlags)
        buildOrReconfigureServerEngine(
            application,
            config,
            gatedPreferencesInspector,
            gatedFileInspector,
            gatedDatabaseInspector,
        )
        if (previousConfig != null && !previousConfig.runtimeEquivalentTo(config)) {
            sessionMarkerMonitor?.runtimeConfigurationChanged()
        }
        // A host that asked not to be chained into must never see its own uncaught-exception handler
        // replaced -- so this call is skipped entirely, not merely made a no-op after the fact. A
        // disabled CRASHES category is the same story: install-then-drop would still replace the
        // host's handler, so a disabled category skips installation entirely too.
        if (config.crashPolicy.crashCaptureEnabled && config.capturesCategory(CaptureCategory.CRASHES)) {
            crashCapture.install()
        }
        return result
    }

    /**
     * Split out of [initialize] purely to keep that function's cyclomatic complexity under the
     * project's detekt threshold -- every gated inspector below was already computed by the caller.
     */
    @Suppress("LongMethod", "LongParameterList")
    private fun installInspectorBridge(
        application: Application,
        gatedPreferencesInspector: AndroidPreferencesInspector?,
        gatedFileInspector: AndroidFileInspector?,
        gatedDatabaseInspector: AndroidDatabaseInspector?,
    ) {
        io.devconsole.ui.compose.DevConsoleInspectorBridge.install(
            FullInspectorDataSource(
                networkTransactionStore,
                mockEngineInstance,
                captureRuleEngineInstance,
                configSupplier = { activeConfig },
                socketStore = socketStore,
                pushStore = pushStore,
                timelineSupplier = { cachedTimeline },
                featureFlagsSupplier = { if (::featureFlags.isInitialized) featureFlags else null },
                stateRegistry = stateRegistry,
                remoteConfigRegistry = remoteConfigRegistry,
                // The one engine PlatformFacadeProvider keeps current via updatePolicy, rather than
                // a fresh one per snapshot(): constructing a RedactionEngine recompiles the whole
                // textPatterns set, which is why redactionValidationError() guards it at all. This
                // also keeps the in-process path on the exact engine the HTTP path redacts with.
                redactionEngine = { redaction },
                preferencesInspector = gatedPreferencesInspector,
                fileInspector = gatedFileInspector,
                databaseInspector = gatedDatabaseInspector,
                exporter =
                    AndroidInspectorExporter(
                        application,
                        networkTransactionStore,
                        timelineSupplier = { cachedTimeline },
                        sessionIdSupplier = runtime::currentSessionId,
                        metadataSupplier = { metadata },
                        sessionExportSources =
                            SessionExportSources(
                                annotationsSupplier = { timelineAnnotations },
                                attachmentReader = { attachmentId -> roomAttachmentStore?.read(attachmentId) },
                            ),
                    ).apply { exportRedaction = redaction },
                healthSupplier = { runtime.health.value.toInspectorHealthUi() },
                sessionsSupplier = { storedSessions.toInspectorSessions() },
                browserSupplier = {
                    activeConfig?.toInspectorBrowserUi(
                        lastStarted?.endpoint,
                        sessionAuthority.principals(),
                        // liveSessionCodeInfo() before remainingTtlMs(): if it just re-issued, the TTL
                        // read below must reflect the fresh code, not the expired one it replaced.
                        liveSessionCodeInfo(),
                        sessionCodeAuthority.remainingTtlMs(),
                        bindAddressChanged =
                            lastStarted != null && ::serverEngine.isInitialized && serverEngine.bindAddressChanged(),
                    )
                },
                retentionSupplier = { activeConfig?.retentionPolicy?.toInspectorRetentionUi() },
                revokePrincipalHandler = sessionAuthority::revokeIfPresent,
                retainedCaptures = RetainedCaptureQuery({ eventStore }, runtime::currentSessionId),
                shareableFileResolver = fileInspectorInstance::resolveShareableFile,
                evidenceStore = roomEvidenceStore,
                evidenceSessionId = ::currentOrFallbackSessionId,
                serverControlScope = lifecycleScope,
                startServer = {
                    val result = startBrowser(configuredStartRequest())
                    if (result !is StartResult.Started) {
                        logcatInfo("DevConsole", "In-app server start did not start: $result")
                    }
                },
                stopServer = { stop(StopReason.UserRequested) },
                republishKeepAliveNotification = ::republishKeepAliveNotification,
                keepAlivePromptSupplier = ::keepAlivePromptNeeded,
                serverStartPermissionSupplier = ::serverStartPermissionNeeded,
            ),
        )
    }

    /**
     * Re-issues the keep-alive start for the endpoint already bound, which makes the foreground
     * service call `startForeground` again and finally post its notification.
     *
     * Needed because Android suppresses a foreground service's notification when the service starts
     * without `POST_NOTIFICATIONS` and never posts it retroactively once the permission is granted.
     * Until this ran, allowing notifications from the inspector's prompt changed nothing visible:
     * the service was foreground with a notification attached and the shade stayed empty.
     *
     * A no-op when no server is running -- there is nothing to re-notify about, and the keep-alive
     * service must never be started on its own.
     */
    private fun republishKeepAliveNotification() {
        if (!::application.isInitialized) return
        val endpoint = lastStarted?.endpoint ?: return
        keepAliveController?.onServerStarted(application, "http://${endpoint.host}:${endpoint.port}")
    }

    /**
     * The [StartRequest] the inspector's own More-screen Start button issues.
     *
     * The More screen has no request parameters of its own, so before this it always sent a default
     * [StartRequest] -- meaning it bound loopback no matter what the host had configured, and a host
     * that wanted LAN could only get it by calling [startBrowser] itself from its own UI. That is
     * what [io.devconsole.api.BrowserConfig.binding] is for, and it is now the field that decides:
     * an on-device start binds what the host asked for, and uses the port range it asked for too.
     *
     * The default is now [BrowserBinding.AUTO], so an on-device Start reaches the network whenever
     * the device has one to reach and falls back to loopback when it does not. An explicit
     * [BrowserBinding.LAN] still passes through the [rejectUnpermittedLan] gate -- returning
     * [StartResult.PermissionRequired] rather than binding, which the More screen surfaces through
     * its normal state polling, and which is the only way this surface prompts for the permission.
     */
    private fun configuredStartRequest(): StartRequest {
        val browser = activeConfig?.browserConfig ?: return StartRequest()
        return StartRequest(
            bindingMode =
                when (browser.binding) {
                    BrowserBinding.LOOPBACK -> PublicBindingMode.LOOPBACK
                    BrowserBinding.LAN -> PublicBindingMode.LAN
                    BrowserBinding.AUTO -> PublicBindingMode.AUTO
                },
            portRange = browser.portRange,
        )
    }

    /**
     * Split out of [initialize] purely to keep that function's cyclomatic complexity under the
     * project's detekt threshold. Constructs the server engine once; every later [initialize] call
     * reconfigures the same instance instead (a bound server cannot be swapped out from under it).
     */
    @Suppress("LongParameterList")
    private fun buildOrReconfigureServerEngine(
        application: Application,
        config: DevConsoleConfig,
        gatedPreferencesInspector: AndroidPreferencesInspector?,
        gatedFileInspector: AndroidFileInspector?,
        gatedDatabaseInspector: AndroidDatabaseInspector?,
    ) {
        val mocksEditable = config.editingCapabilities.mocks && config.capturesCategory(CaptureCategory.MOCKS)
        val captureRulesEditable =
            config.editingCapabilities.captureRules && config.capturesCategory(CaptureCategory.MOCKS)
        val featureFlagsEditable =
            config.editingCapabilities.featureFlags && config.capturesCategory(CaptureCategory.STATE)
        if (!::serverEngine.isInitialized) {
            serverEngine =
                createServerEngine(
                    application,
                    config,
                    gatedPreferencesInspector,
                    gatedFileInspector,
                    gatedDatabaseInspector,
                    mocksEditable,
                    captureRulesEditable,
                    featureFlagsEditable,
                )
        } else {
            serverEngine.reconfigure(
                featureFlags = featureFlags,
                composerEnabled = config.composerEnabled,
                composerAllowedHosts = config.composerAllowedHosts,
                stateMutationsEnabled = config.stateMutationsEnabled,
                redactionPolicy = config.redactionPolicy,
                mocksEditable = mocksEditable,
                captureRulesEditable = captureRulesEditable,
                featureFlagsEditable = featureFlagsEditable,
                preferencesEditable = config.editingCapabilities.preferences,
                databaseEditable = config.editingCapabilities.database,
                filesEditable = config.editingCapabilities.files,
            )
            serverEngine.withBrowserSecurity(config.browserSecurity)
        }
    }

    /** The one-time construction half of [buildOrReconfigureServerEngine], split out for length alone. */
    @Suppress("LongParameterList")
    private fun createServerEngine(
        application: Application,
        config: DevConsoleConfig,
        gatedPreferencesInspector: AndroidPreferencesInspector?,
        gatedFileInspector: AndroidFileInspector?,
        gatedDatabaseInspector: AndroidDatabaseInspector?,
        mocksEditable: Boolean,
        captureRulesEditable: Boolean,
        featureFlagsEditable: Boolean,
    ): KtorLocalServerEngine =
        KtorLocalServerEngine(
            sessionAuthority = sessionAuthority,
            sessionCodeAuthority = sessionCodeAuthority,
            metadata = { metadata },
            sdkHealth = {
                runtime.health.value.let { health ->
                    SdkHealthSnapshot(
                        initializationCount = health.initializationCount,
                        publishedEventCount = health.publishedEventCount,
                        droppedEventCount = health.droppedEventCount,
                        state = health.state.javaClass.simpleName,
                    )
                }
            },
            networkTransactions = networkStore,
            socketStore = socketStore,
            pushStore = pushStore,
            stateRegistry = stateRegistry,
            // Redacted here, not in the route: the Compose inspector reads the same registry
            // in-process and never crosses the HTTP boundary, so this is the shared boundary both
            // surfaces go through.
            // `activeConfig`, not this call's `config`: createServerEngine runs once, while
            // reconfigure() handles every later initialize() and carries no snapshot supplier of
            // its own -- so capturing the parameter would pin the route to the first config's
            // redaction policy and STATE gate for the process's life, while FullInspectorDataSource
            // (which reads the same live field) moved on. Same field, two surfaces, one answer.
            remoteConfigSnapshots = { redactedRemoteConfigSnapshots(activeConfig) },
            featureFlags = featureFlags,
            featureFlagsEditable = featureFlagsEditable,
            mockEngine = mockEngineInstance,
            mocksEditable = mocksEditable,
            captureRules = captureRuleEngineInstance,
            captureRulesEditable = captureRulesEditable,
            preferencesInspector = gatedPreferencesInspector,
            preferencesEditable = config.editingCapabilities.preferences,
            databaseInspector = gatedDatabaseInspector,
            databaseEditable = config.editingCapabilities.database,
            fileInspector = gatedFileInspector,
            filesEditable = config.editingCapabilities.files,
            timeline =
                persistentTimeline(
                    application,
                    config.storagePolicy,
                    config.retentionPolicy,
                    config.eventBufferCapacity,
                ),
            composerEnabled = config.composerEnabled,
            composerAllowedHosts = config.composerAllowedHosts,
            stateMutationsEnabled = config.stateMutationsEnabled,
            redactionPolicy = config.redactionPolicy,
            pushSimulator = pushSimulator,
            annotations = timelineAnnotations,
            evidenceStore = roomEvidenceStore,
            currentSessionId = ::currentOrFallbackSessionId,
            sessionSnapshotProvider = ::currentSessionSnapshot,
            sessionsProvider = { sessionStore?.sessions().orEmpty() },
            screenshotCapture = ::captureScreenshot,
        ).withRetainedCaptures(RetainedCaptureQuery({ eventStore }, runtime::currentSessionId))
            .withBrowserSecurity(config.browserSecurity)
            .withAttachmentReader { attachmentId -> roomAttachmentStore?.read(attachmentId) }
            .withAttachmentMetadataReader { attachmentId -> roomAttachmentStore?.metadata(attachmentId) }

    override fun state(): StateFlow<DevConsoleState> = runtime.state

    override suspend fun startBrowser(request: StartRequest): StartResult =
        lifecycleMutex.withLock { startLocked(request) }

    private suspend fun startLocked(request: StartRequest): StartResult {
        val validationErrors = request.validationErrors()
        if (validationErrors.isNotEmpty()) return StartResult.InvalidConfiguration(validationErrors)
        // A repeated explicit start is a restart request for the DevConsole-owned server. Stop it
        // through the normal lifecycle path while this mutex is held, so its engine and foreground
        // service are torn down before the unchanged engine bind loop retries 8080 first. No process
        // lookup or force-kill is attempted: a listener owned by another process remains for the
        // engine's normal fallback handling.
        if (runtime.state.value is DevConsoleState.Running) {
            stopLocked(StopReason.UserRequested)
        }
        val lifecycleRejection =
            synchronized(liveCaptureLock) {
                val sessionBeforeStart = runtime.currentSessionId()
                val rejection = runtime.beginServerStart()
                if (rejection == null && sessionBeforeStart != runtime.currentSessionId()) {
                    networkTransactionStore.clear()
                    inMemorySocketStore.clear()
                    socketStore.resetSession()
                    inMemoryPushStore.clear()
                }
                rejection
            }
        if (lifecycleRejection != null) return lifecycleRejection
        // Once the lifecycle transition is reserved, caller cancellation (for example Activity
        // recreation) must not strand the runtime in Starting or leave a bound server unreported.
        return withContext(NonCancellable) {
            // beginServerStart creates a new core-owned ID after an explicit stop. Make its
            // durable ACTIVE row visible before any restarted capture source can emit.
            disableSessionFirstRetention()
            val sessionReady =
                if (sessionStore == null) {
                    // buildPersistentTimeline() deliberately falls back to an in-memory timeline
                    // when Room cannot be constructed. Keep that fallback usable: the browser
                    // server must not become unavailable just because durable history did.
                    logcatInfo("DevConsole", "Durable storage unavailable; starting with in-memory history")
                    true
                } else {
                    // Initialization already starts this bootstrap in the background. Waiting for
                    // it here made the SDK-owned Start button pay the full five-second timeout
                    // when Room was still closing a stale session, even though the server itself
                    // was ready to bind. Keep the server start path latency-bound to the socket;
                    // the bootstrap enables durable writes when it completes.
                    if (!durableSessionReady.get() && bootstrapJob?.isActive != true) {
                        scheduleSessionBootstrap(application)
                    }
                    durableSessionReady.get()
                }
            if (!sessionReady) {
                // A Room/bootstrap failure must not take down the developer server. Captures stay
                // in memory while durable writes remain gated by durableSessionReady=false.
                batchWriter?.stop()
                logcatInfo("DevConsole", "Durable session unavailable; starting with in-memory history")
            }
            val missingLanPermission = missingLanPermission(application)
            rejectUnpermittedLan(runtime, request, missingLanPermission)?.let { return@withContext it }
            val lanPermitted = missingLanPermission == null
            if (activeConfig?.crashPolicy?.anrWatchdogEnabled == true && categoryEnabled(CaptureCategory.CRASHES)) {
                anrWatchdog.start()
            }
            val activeEngine = synchronized(this@PlatformFacadeProvider) { serverEngine }

            val browserConfig = activeConfig?.browserConfig
            val sessionCodeTtlMs =
                browserConfig?.sessionCodeTtlMs ?: SessionCodeAuthority.DEFAULT_SESSION_CODE_TTL_MS

            fun serverRequest(mode: ServerBindingMode) =
                ServerStartRequest(
                    bindingMode = mode,
                    portRange = request.portRange,
                    sessionCodeTtlMs = sessionCodeTtlMs,
                )
            val attempted = AutoBinding.initialMode(request.bindingMode, lanPermitted)
            val attempt = withContext(Dispatchers.IO) { activeEngine.start(serverRequest(attempted)) }
            // Only AUTO retries. The first attempt is left unmapped until the retry is settled so a
            // rescued start never drags the runtime through serverFailed() on its way to serverStarted().
            val rescue =
                request.bindingMode == PublicBindingMode.AUTO &&
                    attempted == ServerBindingMode.LAN &&
                    AutoBinding.rescuableByLoopback(attempt)
            val result =
                if (rescue) {
                    logcatInfo("DevConsole", "LAN is unavailable ($attempt); binding loopback instead")
                    withContext(Dispatchers.IO) { activeEngine.start(serverRequest(ServerBindingMode.LOOPBACK)) }
                } else {
                    attempt
                }
            mapStartResult(result).also {
                if (result is ServerStartResult.Started) scheduleTimelineHydration()
            }
        }
    }

    /**
     * Reads only the active app-run history after the server is bound. The query can still be slow
     * while Room recovers a stale database, but it no longer delays the SDK-owned Start action or
     * the first browser connection. [PersistentTimeline] merges rows captured while this query is
     * in flight, and the generation/session checks prevent a late result from crossing stop/start.
     */
    @Suppress("ReturnCount") // Guard clauses prevent launching work without every required owner.
    private fun scheduleTimelineHydration() {
        val store = roomEventStore ?: return
        val timeline = cachedTimeline as? PersistentTimeline ?: return
        val expectedSessionId = runtime.currentSessionId() ?: return
        val generation = timelineHydrationGeneration.incrementAndGet()
        timelineHydrationJob?.cancel()
        timelineHydrationJob =
            lifecycleScope.launch(Dispatchers.IO) {
                val history =
                    runCatching {
                        store.recentEventsForSession(
                            sessionId = expectedSessionId,
                            limit =
                                activeConfig?.storagePolicy?.maxTimelineEvents
                                    ?: io.devconsole.api.StoragePolicy.DEFAULT_MAX_TIMELINE_EVENTS,
                        )
                    }.onFailure {
                        logcatInfo("DevConsole", "Timeline history is unavailable: ${it.javaClass.simpleName}")
                    }.getOrDefault(emptyList())

                if (!isActive || generation != timelineHydrationGeneration.get()) return@launch
                if (runtime.currentSessionId() != expectedSessionId ||
                    runtime.state.value !is DevConsoleState.Running
                ) {
                    return@launch
                }
                timeline.replaceHydratedForSession(expectedSessionId, history)
                DevConsoleInspectorBridge.notifyServerStateChanged()
            }
    }

    private fun mapStartResult(result: ServerStartResult): StartResult {
        val mapped =
            when (result) {
                is ServerStartResult.Started -> {
                    val credential =
                        if (activeConfig?.browserSecurity == BrowserSecurity.NONE) {
                            AccessInfo(
                                connectUrl = "http://${result.endpoint.host}:${result.endpoint.port}",
                                sessionCode = "",
                                expiresAtEpochMs = Long.MAX_VALUE,
                            )
                        } else {
                            result.sessionCode.let {
                                AccessInfo(
                                    connectUrl = it.browserUrl,
                                    sessionCode = it.code,
                                    expiresAtEpochMs = it.expiresAtEpochMs,
                                )
                            }
                        }
                    val started =
                        StartResult.Started(
                            endpoint =
                                BrowserEndpoint(
                                    host = result.endpoint.host,
                                    port = result.endpoint.port,
                                    bindingMode =
                                        when (result.endpoint.bindingMode) {
                                            ServerBindingMode.LOOPBACK -> PublicBindingMode.LOOPBACK
                                            ServerBindingMode.LAN -> PublicBindingMode.LAN
                                        },
                                ),
                            access = credential,
                        )
                    (cachedTimeline as? PersistentTimeline)?.setPersistenceReady(durableSessionReady.get())
                    lastStarted = started
                    runtime.serverStarted()
                    sessionMarkerMonitor?.start(result.endpoint.bindingMode.name)
                    keepAliveController?.onServerStarted(
                        application,
                        "http://${result.endpoint.host}:${result.endpoint.port}",
                    )
                    logcatInfo(
                        "DevConsole",
                        "Dashboard available at: ${credential.connectUrl.withoutCredentials()} " +
                            "(access link available through the DevConsole API/launcher; " +
                            "binding: ${result.endpoint.bindingMode})",
                    )
                    started
                }

                ServerStartResult.DisabledForBuild -> {
                    runtime.serverFailed("Server is disabled for this build")
                    StartResult.DisabledForBuild
                }

                ServerStartResult.LocalNetworkPermissionRequired -> {
                    runtime.serverRequiresPermission()
                    StartResult.PermissionRequired("android.permission.ACCESS_LOCAL_NETWORK")
                }

                is ServerStartResult.InvalidConfiguration -> {
                    runtime.serverFailed(result.detail)
                    StartResult.Failed(result.detail)
                }

                is ServerStartResult.PortUnavailable -> {
                    runtime.serverFailed("No loopback port available in ${result.attempted}")
                    StartResult.PortUnavailable(result.attempted)
                }

                is ServerStartResult.NoEligibleNetwork -> {
                    runtime.serverFailed(result.detail)
                    StartResult.NoEligibleNetwork(result.detail)
                }

                is ServerStartResult.Failed -> {
                    runtime.serverFailed(result.detail)
                    StartResult.Failed(result.detail)
                }
            }
        // The SDK-owned More screen is snapshot-driven. Wake it as soon as the runtime has
        // transitioned, instead of making a successful start wait for its five-second poll.
        DevConsoleInspectorBridge.notifyServerStateChanged()
        return mapped
    }

    override suspend fun stop(reason: StopReason) = lifecycleMutex.withLock { stopLocked(reason) }

    private suspend fun stopLocked(reason: StopReason) {
        durableSessionReady.set(false)
        (cachedTimeline as? PersistentTimeline)?.setPersistenceReady(false)
        (cachedTimeline as? PersistentTimeline)?.discardPendingPersistence()
        timelineHydrationGeneration.incrementAndGet()
        lastStarted = null
        try {
            stopStep("timeline hydration") {
                timelineHydrationJob?.cancelAndJoin()
                timelineHydrationJob = null
            }
            stopStep("bootstrap") {
                bootstrapJob?.cancelAndJoin()
                bootstrapJob = null
            }
            stopStep("session marker") { sessionMarkerMonitor?.stop(reason.markerLabel()) }
            stopStep("event writer") { withContext(Dispatchers.IO) { batchWriter?.flushAndStop() } }
            stopStep("server engine") {
                if (::serverEngine.isInitialized) withContext(Dispatchers.IO) { serverEngine.stop() }
            }
            stopStep("browser sessions") {
                sessionAuthority.reset()
                sessionCodeAuthority.reset()
            }
            stopStep("feature flags") { if (::featureFlags.isInitialized) featureFlags.reset() }
            stopStep("mock rules") { mockEngineInstance.clearSessionRules() }
            stopStep("ANR watchdog") { anrWatchdog.stop() }
            stopStep("durable session") {
                runtime.currentSessionId()?.let { sessionId ->
                    sessionStore?.end(sessionId, System.currentTimeMillis())
                    storedSessions = sessionStore?.sessions().orEmpty()
                }
            }
        } finally {
            // Notification Stop must never leave the SDK reporting Running just because a best-
            // effort cleanup step failed. The service removes its notification after stopAsync's
            // callback, so finalize the public runtime state and wake the SDK-owned inspector here.
            runtime.stop(reason)
            DevConsoleInspectorBridge.notifyServerStateChanged()
            if (::application.isInitialized) keepAliveController?.onServerStopped(application)
        }
    }

    /** Cleanup is best-effort; cancellation still propagates so structured teardown can finish. */
    @Suppress("TooGenericExceptionCaught") // Every cleanup failure is isolated so later stop steps still run.
    private suspend fun stopStep(
        label: String,
        action: suspend () -> Unit,
    ) {
        try {
            action()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            logcatInfo("DevConsole", "Stop cleanup failed ($label): ${failure.javaClass.simpleName}")
        }
    }

    @Synchronized
    override fun registerStateProvider(provider: StateProvider): Boolean =
        categoryEnabled(CaptureCategory.STATE) && safely { stateRegistry.register(provider) }

    @Synchronized
    override fun registerRemoteConfigProvider(provider: RemoteConfigProvider): Boolean =
        categoryEnabled(CaptureCategory.STATE) && safely { remoteConfigRegistry.register(provider) }

    /**
     * Empty when the STATE category is off, so the dashboard route cannot outflank the host's gate.
     * A null config means initialize() has not landed yet, which reads the same way: nothing to serve.
     */
    private fun redactedRemoteConfigSnapshots(config: DevConsoleConfig?): List<RemoteConfigSnapshot> {
        if (config == null || !config.capturesCategory(CaptureCategory.STATE)) return emptyList()
        val redacting = RedactingRemoteConfig(redaction, config.redactionPolicy)
        return remoteConfigRegistry.snapshots().map { it.copy(entries = redacting.apply(it.entries)) }
    }

    /** Late registration must never throw into the host; a rejected duplicate just returns false. */
    private inline fun safely(block: () -> Unit): Boolean = runCatching(block).isSuccess

    override fun endpoint(): BrowserEndpoint? = lastStarted?.endpoint

    override fun createInspectorIntent(context: Context): Intent? {
        if (runtime.state.value is DevConsoleState.Uninitialized) return null
        return Intent()
            .setClassName(context.packageName, "io.devconsole.ui.compose.DevConsoleActivity")
            .also { intent ->
                // Single-top so a second open() -- a trigger firing during the launch window, a
                // host button double-tapped -- re-delivers to the existing inspector instead of
                // stacking a second instance the user has to back out of.
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (context !is Activity) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
    }

    override fun openInspector(context: Context): InspectorOpenResult {
        val intent = createInspectorIntent(context) ?: return InspectorOpenResult.NotInitialized
        // A host that never starts the browser server still gets ANR detection once it opens the
        // on-device inspector. start() is idempotent, so this composes with the startBrowser path.
        if (activeConfig?.crashPolicy?.anrWatchdogEnabled == true && categoryEnabled(CaptureCategory.CRASHES)) {
            anrWatchdog.start()
        }
        return runCatching { context.startActivity(intent) }
            .fold(
                onSuccess = { InspectorOpenResult.Opened },
                onFailure = {
                    InspectorOpenResult.Failed(it.message ?: "Unable to open DevConsole")
                },
            )
    }

    override fun accessInfo(): AccessInfo? {
        val started = lastStarted ?: return null
        return if (activeConfig?.browserSecurity == BrowserSecurity.NONE) {
            AccessInfo(
                connectUrl = "http://${started.endpoint.host}:${started.endpoint.port}",
                sessionCode = "",
                expiresAtEpochMs = Long.MAX_VALUE,
            )
        } else {
            liveSessionCodeInfo()?.let { info ->
                AccessInfo(
                    connectUrl = info.browserUrl,
                    sessionCode = info.code,
                    expiresAtEpochMs = info.expiresAtEpochMs,
                )
            }
        }
    }

    /**
     * The session code has its own TTL, shorter than the server's lifetime -- if it has expired
     * while the server keeps running, re-issue automatically (reusing the real bind address, not the
     * loopback default) so both the host app's connect-URL card ([accessInfo]) and the SDK's own More
     * screen ([browserSupplier] above) keep advertising something connectable instead of a dead,
     * already-expired code. Null while the server isn't running.
     */
    private fun liveSessionCodeInfo(): SessionCodeInfo? {
        if (lastStarted == null || activeConfig?.browserSecurity != BrowserSecurity.SESSION_CODE) return null
        return sessionCodeAuthority.currentInfo() ?: sessionCodeAuthority.issueCode()
    }

    override fun networkRecorder(): NetworkTransactionRecorder = networkRecorderInstance

    override fun socketRecorder(): SocketRecorder = socketRecorderInstance

    override fun logRecorder(): LogRecorder = logRecorderInstance

    override fun mockEngine(): MockEngine =
        if (categoryEnabled(CaptureCategory.MOCKS)) mockEngineInstance else disabledMockEngine

    override fun recordPush(input: PushInput): PushEvent = pushRecorderInstance.record(input)

    override fun featureFlagValue(key: String): Boolean {
        val flags = if (::featureFlags.isInitialized) featureFlags else return false
        return runCatching { flags.booleanValue(key) }.getOrDefault(false)
    }

    override fun featureFlagStringValue(key: String): String {
        val flags = if (::featureFlags.isInitialized) featureFlags else return ""
        return runCatching { flags.value(key) }.getOrDefault("")
    }

    /** Registration must happen exactly once per Application, regardless of how many times [initialize] runs. */
    private fun registerActivityTrackerOnce(application: Application) {
        if (activityTrackerRegistered.compareAndSet(false, true)) {
            application.registerActivityLifecycleCallbacks(activityTracker)
        }
    }

    /** Created and registered once; every later [initialize] (host superseding provisional) just reconfigures. */
    private fun reconfigureOpenTriggers(
        application: Application,
        config: DevConsoleConfig,
    ) {
        val controller =
            openTriggerController
                ?: OpenTriggerController(application, { activity -> openInspector(activity) }).also {
                    openTriggerController = it
                    it.registerOnce(application)
                }
        controller.reconfigure(config.openTriggers)
    }

    // Guard-clause early returns (disabled, no foreground activity, capture outcome) are the
    // clearest form here -- see RoomAttachmentStore.kt for the same rationale.
    @Suppress("ReturnCount")
    override suspend fun captureScreenshot(): ScreenshotResult {
        val policy = activeConfig?.screenshotPolicy ?: ScreenshotPolicy()
        if (!policy.enabled) return ScreenshotResult.Disabled
        val activity = activityTracker.currentActivity() ?: return ScreenshotResult.NoForegroundActivity
        return when (val captured = screenshotCaptureInstance.capture(activity, policy)) {
            CapturedScreenshot.SecureWindow -> ScreenshotResult.SecureWindow
            is CapturedScreenshot.Failed -> ScreenshotResult.Failed(captured.reason)
            is CapturedScreenshot.Bytes -> persistScreenshot(captured)
        }
    }

    /**
     * Writes through `AttachmentStore` with [RedactionApplicability.NOT_APPLICABLE] -- a screenshot's
     * pixels cannot be redacted -- then mirrors it onto the timeline as a `"screenshot"` event so it
     * inherits retention, export, the evidence tray, and attachment serving with no new plumbing.
     */
    @Suppress("ReturnCount")
    private suspend fun persistScreenshot(captured: CapturedScreenshot.Bytes): ScreenshotResult {
        val sessionId = runtime.currentSessionId() ?: return ScreenshotResult.Failed("DevConsole is not initialized")
        if (!durableSessionReady.get()) return ScreenshotResult.Failed("Durable storage is not ready yet")
        val store = roomAttachmentStore ?: return ScreenshotResult.Failed("Durable storage is unavailable")
        val eventId = UUID.randomUUID().toString()
        val writeResult =
            store.write(
                AttachmentWriteRequest(
                    sessionId = sessionId,
                    eventId = eventId,
                    mimeType = "image/png",
                    bytes = captured.png,
                    isRedacted = false,
                    redactionApplicability = RedactionApplicability.NOT_APPLICABLE,
                ),
            )
        return when (writeResult) {
            is AttachmentWriteResult.Success -> {
                captureBridge.emit(
                    pluginId = "screenshot",
                    type = "screenshot.captured",
                    severity = io.devconsole.api.EventSeverity.INFO,
                    summary = "Screenshot captured (${captured.widthPx}x${captured.heightPx})",
                    tagsJson = "{\"widthPx\":\"${captured.widthPx}\",\"heightPx\":\"${captured.heightPx}\"}",
                    attachmentId = writeResult.attachment.id,
                    sessionIdOverride = sessionId,
                    idOverride = eventId,
                )
                lifecycleScope.launch {
                    sessionStore?.enforceRetention(writeResult.attachment.sessionId)
                    storedSessions = sessionStore?.sessions().orEmpty()
                }
                ScreenshotResult.Captured(
                    attachmentId = writeResult.attachment.id,
                    eventId = eventId,
                    widthPx = captured.widthPx,
                    heightPx = captured.heightPx,
                    byteCount = captured.png.size,
                )
            }
            AttachmentWriteResult.RejectedUnredactedContent,
            AttachmentWriteResult.Unavailable,
            -> ScreenshotResult.Failed("Unable to store the screenshot attachment")
        }
    }

    /** Reached by the later change that wires the evidence tray's HTTP routes onto the server engine. */
    internal fun evidenceStore(): EvidenceStore? = roomEvidenceStore

    /** The evidence tray's session scope: falls back to "current" the same way export-route callers already do. */
    private fun currentOrFallbackSessionId(): String = runtime.currentSessionId() ?: "current"

    /** Whether the keep-alive notification-permission snackbar should be offered right now. */
    private fun keepAlivePromptNeeded(): Boolean =
        keepAliveGate?.shouldOfferNotificationPrompt(runtime.state.value is DevConsoleState.Running) ?: false

    /**
     * Permission preflight used only by the SDK-owned More-screen Start button. Explicit host/API
     * starts retain their existing contract and still return [StartResult.PermissionRequired] when
     * the caller requested a LAN bind without the local-network grant.
     *
     * AUTO and explicit LAN both surface the missing local-network grant here because this is the
     * SDK-owned start path: the user can act on the permission before receiving a less useful
     * loopback-only URL. Notification permission is deliberately not returned here. Android can
     * run the foreground service while hiding its notification when POST_NOTIFICATIONS is denied,
     * and the running-server snackbar remains available to request it later.
     */
    private fun serverStartPermissionNeeded(): String? {
        val binding = activeConfig?.browserConfig?.binding ?: return null
        return if (binding == BrowserBinding.LOOPBACK) null else missingLanPermission(application)
    }

    /** Device/app metadata for the evidence bundle's session.json; honestly null before a durable session exists. */
    private suspend fun currentSessionSnapshot(): StoredSession? = sessionStore?.session(activeSessionId())

    private companion object {
        const val DATABASE_NAME = "devconsole-events.db"
        const val DEVCONSOLE_DIRECTORY = "devconsole"
        const val BOOTSTRAP_TIMEOUT_MS = 5_000L
    }
}

/** Fragments and queries can carry the one-time bootstrap secret and must never enter Logcat. */
private fun String.withoutCredentials(): String = substringBefore('#').substringBefore('?')

private fun StopReason.markerLabel(): String =
    when (this) {
        StopReason.UserRequested -> "USER_REQUESTED"
        StopReason.ApplicationTerminated -> "APPLICATION_TERMINATED"
        is StopReason.Failure -> "FAILURE"
    }

/** Maps the runtime's live counters to the read-only SDK Health panel on the More surface. */
private fun io.devconsole.core.SdkHealth.toInspectorHealthUi(): InspectorHealthUi =
    InspectorHealthUi(
        state = state.javaClass.simpleName,
        initializationCount = initializationCount,
        publishedEventCount = publishedEventCount,
        droppedEventCount = droppedEventCount,
    )

/** Durable app-run rows replace the old synthetic marker-derived session list. */
private fun List<StoredSession>.toInspectorSessions(): List<InspectorSessionUi> =
    sortedByDescending(StoredSession::startedAtMs).map { session ->
        InspectorSessionUi(
            id = session.id,
            startedAtEpochMs = session.startedAtMs,
            label = "${session.status.name.lowercase()} • ${session.applicationId ?: "app"}",
            status = session.status.name,
            recordCount = session.recordCount,
            estimatedBytes = session.estimatedBytes,
        )
    }

private fun io.devconsole.api.RetentionPolicy.toSessionRetentionPolicy(): SessionRetentionPolicy =
    SessionRetentionPolicy(maxSessions = maxSessions, maxAgeMs = maxAgeMs, maxBytes = maxBytes)

private fun Application.storedSession(id: String): StoredSession {
    val packageInfo = packageManager.getPackageInfo(packageName, 0)
    val versionCode =
        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.P
        ) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    return StoredSession(
        id = id,
        status = StoredSessionStatus.ACTIVE,
        startedAtMs = System.currentTimeMillis(),
        startedAtMonotonicNs = System.nanoTime(),
        applicationId = packageName,
        appVersionName = packageInfo.versionName,
        appVersionCode = versionCode,
        buildType = if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) "debug" else "release",
        deviceModel = Build.MODEL,
        deviceApiLevel = Build.VERSION.SDK_INT,
        deviceOsVersion = Build.VERSION.RELEASE,
    )
}

/** Read-only display of the host's browser policy for the More surface; changes no auth behavior. */
private fun DevConsoleConfig.toInspectorBrowserUi(
    endpoint: BrowserEndpoint?,
    principals: List<BrowserPrincipal>,
    sessionCode: SessionCodeInfo?,
    sessionCodeRemainingTtlMs: Long?,
    bindAddressChanged: Boolean,
): InspectorBrowserUi =
    InspectorBrowserUi(
        // The mode the server actually bound to, not the (not-yet-consumed) browserConfig.binding
        // declaration -- so the "LAN MODE — UNENCRYPTED" banner matches reality. Falls back to the
        // configured declaration only before a server has started.
        binding = endpoint?.bindingMode?.name ?: browserConfig.binding.name,
        endpoint = endpoint?.let { "${it.host}:${it.port}" },
        principals = principals.map { it.toInspectorPrincipalUi() },
        sessionCodeUrl =
            if (browserSecurity == BrowserSecurity.NONE) {
                endpoint?.let { "http://${it.host}:${it.port}" }
            } else {
                sessionCode?.browserUrl
            },
        sessionCode = if (browserSecurity == BrowserSecurity.NONE) "" else sessionCode?.code,
        sessionCodeExpiresAtEpochMs =
            if (browserSecurity == BrowserSecurity.NONE) null else sessionCode?.expiresAtEpochMs,
        sessionCodeRemainingTtlMs =
            if (browserSecurity == BrowserSecurity.NONE) null else sessionCodeRemainingTtlMs,
        bindAddressChanged = bindAddressChanged,
    )

private fun BrowserPrincipal.toInspectorPrincipalUi(): InspectorBrowserPrincipalUi =
    InspectorBrowserPrincipalUi(
        id = id,
        label = browserLabel,
        sourceIp = sourceIp,
        expiresAtEpochMs = expiresAtEpochMs,
    )

/** Configured retention caps for the More surface's usage panel; mirrors what [RoomSessionStore] enforces. */
private fun io.devconsole.api.RetentionPolicy.toInspectorRetentionUi(): InspectorRetentionUi =
    InspectorRetentionUi(maxSessions = maxSessions, maxAgeMs = maxAgeMs, maxBytes = maxBytes)

private const val CURSOR_SECRET_BYTES = 16

private fun randomCursorSecret(): ByteArray = ByteArray(CURSOR_SECRET_BYTES).also(SecureRandom()::nextBytes)

/**
 * Whether a LAN bind would be allowed to reach the network right now.
 *
 * API 32 and below do not require a runtime LAN permission. API 33-36 require
 * `NEARBY_WIFI_DEVICES`; API 37 and above require `ACCESS_LOCAL_NETWORK`. The latter can otherwise
 * silently drop LAN traffic even though the socket binds and completes handshakes -- see
 * [LocalNetworkPermissionGate], which deliberately ignores `targetSdk` for that reason.
 */
private fun missingLanPermission(application: Application): String? {
    val nearbyGranted =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            application.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) ==
            PackageManager.PERMISSION_GRANTED
    val localNetworkGranted =
        application.checkSelfPermission(LocalNetworkPermissionGate.PERMISSION) == PackageManager.PERMISSION_GRANTED
    return LanPermissionPolicy.missingPermission(
        deviceApi = Build.VERSION.SDK_INT,
        nearbyWifiGranted = nearbyGranted,
        localNetworkGranted = localNetworkGranted,
    )
}

/**
 * Null means the start may proceed; a non-null result is the early-return for
 * [PlatformFacadeProvider.start].
 *
 * Only an explicit [PublicBindingMode.LAN] is rejected here. [PublicBindingMode.AUTO] still keeps
 * its host/API contract of falling back to loopback, while the SDK-owned More-screen preflight
 * surfaces the same missing grant before it chooses a URL for the user.
 */
private fun rejectUnpermittedLan(
    runtime: DevConsoleRuntime,
    request: StartRequest,
    missingPermission: String?,
): StartResult? {
    if (request.bindingMode != PublicBindingMode.LAN || missingPermission == null) return null
    runtime.serverRequiresPermission()
    return StartResult.PermissionRequired(missingPermission)
}

private fun Application.serverMetadata(): ServerMetadata {
    val packageInfo = packageManager.getPackageInfo(packageName, 0)
    val appInfo = applicationInfo
    return ServerMetadata(
        appDisplayName = appInfo.loadLabel(packageManager).toString(),
        appPackageName = packageName,
        appVersionName = packageInfo.versionName ?: "unknown",
        buildVariant = if (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) "debug" else "release",
    )
}

private fun Application.isDebuggable(): Boolean = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

internal fun logcatInfo(
    tag: String,
    message: String,
) {
    runCatching {
        android.util.Log.i(tag, message)
    }.onFailure {
        println("[$tag] $message")
    }
}
