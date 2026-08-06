/**
 * @author Shakib
 * @since 24/07/26
 */
@file:Suppress("TooManyFunctions") // Adapter of many small engine→UI mapper functions.

package io.devconsole

import io.devconsole.api.CaptureCategory
import io.devconsole.api.CaptureRule
import io.devconsole.api.CaptureRuleEngine
import io.devconsole.api.DevConsoleConfig
import io.devconsole.api.EditingCapabilities
import io.devconsole.api.ScreenshotResult
import io.devconsole.composer.ComposerDestinationRejectedException
import io.devconsole.composer.ComposerExecutor
import io.devconsole.composer.UrlConnectionComposerTransport
import io.devconsole.logs.LogLevel
import io.devconsole.mocks.MockAction
import io.devconsole.mocks.MockEngine
import io.devconsole.mocks.MockRule
import io.devconsole.mocks.MockRulePersistence
import io.devconsole.mocks.MockRuleStats
import io.devconsole.mocks.MockScope
import io.devconsole.network.BodyPreview
import io.devconsole.network.CaptureBodyMetadata
import io.devconsole.network.ExportSelection
import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkTimingPhases
import io.devconsole.network.NetworkTransaction
import io.devconsole.network.NetworkTransactionFilters
import io.devconsole.network.NetworkTransactionQuery
import io.devconsole.push.InMemoryPushStore
import io.devconsole.push.PushEvent
import io.devconsole.push.PushStore
import io.devconsole.server.api.DatabaseExecResult
import io.devconsole.server.api.DatabaseInspector
import io.devconsole.server.api.DatabaseListingData
import io.devconsole.server.api.DatabaseQueryData
import io.devconsole.server.api.FileInspector
import io.devconsole.server.api.FileListingData
import io.devconsole.server.api.FilePreviewData
import io.devconsole.server.api.PreferencesFileData
import io.devconsole.server.api.PreferencesInspector
import io.devconsole.socket.InMemorySocketStore
import io.devconsole.socket.MqttFrameMetadata
import io.devconsole.socket.SocketConnection
import io.devconsole.socket.SocketMessage
import io.devconsole.socket.SocketPayload
import io.devconsole.socket.SocketProtocol
import io.devconsole.socket.SocketStore
import io.devconsole.state.FeatureFlag
import io.devconsole.state.FeatureFlagProvider
import io.devconsole.state.StateRegistry
import io.devconsole.state.StateValue
import io.devconsole.storage.api.EvidenceKind
import io.devconsole.storage.api.EvidenceStore
import io.devconsole.storage.api.EvidenceWriteResult
import io.devconsole.storage.api.RetainedCaptureQuery
import io.devconsole.storage.api.StoredEvent
import io.devconsole.storage.api.StoredEvidenceItem
import io.devconsole.timeline.Timeline
import io.devconsole.timeline.TimelinePage
import io.devconsole.timeline.TimelineQuery
import io.devconsole.timeline.TimelineSort
import io.devconsole.ui.compose.InspectorBodyKind
import io.devconsole.ui.compose.InspectorBreadcrumbUi
import io.devconsole.ui.compose.InspectorBrowserUi
import io.devconsole.ui.compose.InspectorCaptureRuleUi
import io.devconsole.ui.compose.InspectorCommandResult
import io.devconsole.ui.compose.InspectorComposerRequest
import io.devconsole.ui.compose.InspectorCrashUi
import io.devconsole.ui.compose.InspectorDataSource
import io.devconsole.ui.compose.InspectorDatabaseListingUi
import io.devconsole.ui.compose.InspectorDatabaseTableUi
import io.devconsole.ui.compose.InspectorEditingUi
import io.devconsole.ui.compose.InspectorFeatureFlagUi
import io.devconsole.ui.compose.InspectorFileEntryUi
import io.devconsole.ui.compose.InspectorFileListingUi
import io.devconsole.ui.compose.InspectorFilePreviewUi
import io.devconsole.ui.compose.InspectorHealthUi
import io.devconsole.ui.compose.InspectorLogUi
import io.devconsole.ui.compose.InspectorMockRuleUi
import io.devconsole.ui.compose.InspectorPreferenceEntryUi
import io.devconsole.ui.compose.InspectorPreferenceFileUi
import io.devconsole.ui.compose.InspectorPushUi
import io.devconsole.ui.compose.InspectorQueryResultUi
import io.devconsole.ui.compose.InspectorRetentionUi
import io.devconsole.ui.compose.InspectorSessionUi
import io.devconsole.ui.compose.InspectorSnapshot
import io.devconsole.ui.compose.InspectorSocketFrameUi
import io.devconsole.ui.compose.InspectorSocketUi
import io.devconsole.ui.compose.InspectorSqlResultUi
import io.devconsole.ui.compose.InspectorStateEntryUi
import io.devconsole.ui.compose.InspectorStateProviderUi
import io.devconsole.ui.compose.InspectorTimingPhasesUi
import io.devconsole.ui.compose.InspectorTrafficQuery
import io.devconsole.ui.compose.InspectorTransactionUi
import io.devconsole.ui.compose.TrafficStatusClass
import java.net.URI
import java.util.Locale
import java.util.UUID
import io.devconsole.composer.ComposerRequest as EngineComposerRequest

/**
 * Adapts the full runtime's existing capture and composer engines to the SDK-owned inspector UI
 * boundary. Constructor-injected so it is unit-testable without an Android context.
 */
@Suppress("LongParameterList") // Every capture engine is injected so the adapter stays unit-testable.
internal class FullInspectorDataSource(
    private val networkTransactionStore: InMemoryNetworkTransactionStore,
    private val mockEngine: MockEngine,
    private val captureRuleEngine: CaptureRuleEngine = CaptureRuleEngine(),
    private val composerExecutor: ComposerExecutor = ComposerExecutor(UrlConnectionComposerTransport()),
    private val configSupplier: () -> DevConsoleConfig?,
    private val socketStore: SocketStore = InMemorySocketStore(),
    private val pushStore: PushStore = InMemoryPushStore(),
    private val timelineSupplier: () -> Timeline? = { null },
    private val featureFlagsSupplier: () -> FeatureFlagProvider? = { null },
    private val stateRegistry: StateRegistry? = null,
    private val preferencesInspector: PreferencesInspector? = null,
    private val fileInspector: FileInspector? = null,
    private val databaseInspector: DatabaseInspector? = null,
    private val exporter: InspectorExporter? = null,
    private val healthSupplier: () -> InspectorHealthUi? = { null },
    private val sessionsSupplier: () -> List<InspectorSessionUi> = { emptyList() },
    private val browserSupplier: () -> InspectorBrowserUi? = { null },
    private val retentionSupplier: () -> InspectorRetentionUi? = { null },
    /** Backed by `SessionAuthority.revokeIfPresent`; returns false for an unknown/already-expired id. */
    private val revokePrincipalHandler: (id: String) -> Boolean = { false },
    private val retainedCaptures: RetainedCaptureQuery? = null,
    /**
     * Resolves a real [java.io.File] for the Compose Files screen's Share action. `FileInspector`
     * itself never exposes `java.io.File` (it stays platform-neutral), so this comes from
     * `AndroidFileInspector.resolveShareableFile` instead, wired in by `PlatformFacadeProvider`.
     */
    private val shareableFileResolver: (root: String, relativePath: String) -> java.io.File? = { _, _ -> null },
    /**
     * Durable evidence tray: the same `EvidenceStore` the dashboard's `/api/v1/evidence` routes
     * read and write, wired in by `PlatformFacadeProvider` alongside the session/event stores. Null
     * on a build where durable storage never came up (see [InspectorCommandResult.Unavailable] on
     * every method below in that case), matching every other storage-backed capability here.
     */
    private val evidenceStore: EvidenceStore? = null,
    /**
     * The live app-run session's id, mirroring `PlatformFacadeProvider.currentOrFallbackSessionId()`
     * and `DevConsoleKtorModule`'s own `currentSessionId` route parameter exactly -- a network
     * transaction flagged here and one flagged from the dashboard must land under the identical
     * session row, or the tray would show two copies of the same evidence tray.
     */
    private val evidenceSessionId: () -> String = { "current" },
    /**
     * Whether the Control surface should offer the keep-alive notification snackbar right now.
     * Wired by PlatformFacadeProvider to KeepAliveGate.shouldOfferNotificationPrompt with the
     * live runtime state; defaults to false for tests and partial wirings.
     */
    private val keepAlivePromptSupplier: () -> Boolean = { false },
) : InspectorDataSource {
    override suspend fun logsForSession(sessionId: String?): List<InspectorLogUi> =
        retainedCaptures
            ?.events(sessionId)
            .orEmpty()
            .filter { it.pluginId == LOGS_PLUGIN_ID || it.pluginId == CRASH_PLUGIN_ID }
            .asReversed()
            .take(SNAPSHOT_LOG_LIMIT)
            .map { it.toLogUi() }

    /** Session-scoped counterpart of [logsForSession] for the Crashes tab; same retained-store read, crash-only. */
    override suspend fun crashesForSession(sessionId: String?): List<InspectorCrashUi> =
        retainedCaptures
            ?.events(sessionId)
            .orEmpty()
            .filter { it.pluginId == CRASH_PLUGIN_ID }
            .asReversed()
            .take(SNAPSHOT_LOG_LIMIT)
            .map { it.toCrashUi() }

    override suspend fun flaggedTransactionIds(): Set<String> {
        val store = evidenceStore ?: return emptySet()
        return store
            .items(evidenceSessionId())
            .asSequence()
            .filter { it.kind == EvidenceKind.NETWORK }
            .mapTo(mutableSetOf()) { it.subjectId }
    }

    /**
     * Materializes [id]'s subject fresh from [networkTransactionStore] -- never from whatever the
     * Compose layer's own last snapshot happened to hold -- into the exact same detail-JSON shape
     * `DevConsoleKtorModule`'s `POST /api/v1/evidence` route produces for a `NETWORK` subject, so a
     * transaction flagged here and one flagged from the dashboard are indistinguishable in the tray.
     * The label is validated before the store is ever called: [EvidenceStore.flag] enforces the same
     * cap with `require()`, which throws -- an over-long label must come back as a clean
     * [InspectorCommandResult.Invalid], not an exception reaching the UI.
     *
     * Guard-clause early returns (no store, subject gone, over-long label) read clearest here -- see
     * RoomAttachmentStore.kt for the same rationale this codebase already applies elsewhere.
     */
    @Suppress("ReturnCount")
    override suspend fun flagTransaction(id: String): InspectorCommandResult {
        val store = evidenceStore ?: return InspectorCommandResult.Unavailable
        val transaction =
            networkTransactionStore.find(id)
                ?: return InspectorCommandResult.Invalid("This transaction is no longer available")
        val label = transaction.evidenceLabel()
        if (label.length > MAX_EVIDENCE_LABEL_LENGTH) {
            return InspectorCommandResult.Invalid("Label exceeds $MAX_EVIDENCE_LABEL_LENGTH characters")
        }
        val item =
            StoredEvidenceItem(
                id = UUID.randomUUID().toString(),
                sessionId = evidenceSessionId(),
                kind = EvidenceKind.NETWORK,
                subjectId = id,
                label = label,
                flaggedAtMs = System.currentTimeMillis(),
                snapshotJson = transaction.evidenceDetailJson(),
                attachmentId = null,
            )
        return store.flag(item).toCommandResult()
    }

    override suspend fun unflagTransaction(id: String): InspectorCommandResult {
        val store = evidenceStore ?: return InspectorCommandResult.Unavailable
        store.unflag(evidenceSessionId(), EvidenceKind.NETWORK, id)
        return InspectorCommandResult.Success(summary = "Removed from evidence")
    }

    override suspend fun flaggedCrashIds(sessionId: String?): Set<String> {
        val store = evidenceStore ?: return emptySet()
        return store
            .items(sessionId ?: evidenceSessionId())
            .asSequence()
            .filter { it.kind == EvidenceKind.CRASH }
            .mapTo(mutableSetOf()) { it.subjectId }
    }

    /**
     * Same materialize-fresh, never-re-derive discipline as [flagTransaction], for a `CRASH` subject:
     * looked up by id in [sessionId] (or the live session, when null) the same way
     * [crashesForSession] resolves it, and stored under that event's own resolved session id -- which
     * is either the live session (matching every crash the dashboard can reach today) or a genuinely
     * past one the dashboard's live-timeline-only reach cannot flag at all, so there is no existing
     * dashboard behavior to diverge from there.
     *
     * Guard-clause early returns, same rationale as [flagTransaction] above.
     */
    @Suppress("ReturnCount")
    override suspend fun flagCrash(
        id: String,
        sessionId: String?,
    ): InspectorCommandResult {
        val store = evidenceStore ?: return InspectorCommandResult.Unavailable
        val event =
            retainedCaptures
                ?.events(sessionId)
                ?.firstOrNull { it.id == id && it.pluginId == CRASH_PLUGIN_ID }
                ?: return InspectorCommandResult.Invalid("This crash is no longer available")
        val label = event.summary
        if (label.length > MAX_EVIDENCE_LABEL_LENGTH) {
            return InspectorCommandResult.Invalid("Label exceeds $MAX_EVIDENCE_LABEL_LENGTH characters")
        }
        val item =
            StoredEvidenceItem(
                id = UUID.randomUUID().toString(),
                sessionId = event.sessionId,
                kind = EvidenceKind.CRASH,
                subjectId = id,
                label = label,
                flaggedAtMs = System.currentTimeMillis(),
                snapshotJson = event.evidenceCrashSnapshotJson(),
                attachmentId = null,
            )
        return store.flag(item).toCommandResult()
    }

    override suspend fun unflagCrash(
        id: String,
        sessionId: String?,
    ): InspectorCommandResult {
        val store = evidenceStore ?: return InspectorCommandResult.Unavailable
        val resolvedSessionId =
            retainedCaptures
                ?.events(sessionId)
                ?.firstOrNull { it.id == id && it.pluginId == CRASH_PLUGIN_ID }
                ?.sessionId
                ?: sessionId
                ?: evidenceSessionId()
        store.unflag(resolvedSessionId, EvidenceKind.CRASH, id)
        return InspectorCommandResult.Success(summary = "Removed from evidence")
    }

    /** Unfiltered entry point kept for existing callers; delegates to the filtered overload below. */
    override fun snapshot(): InspectorSnapshot = snapshot(InspectorTrafficQuery())

    override fun snapshot(trafficQuery: InspectorTrafficQuery): InspectorSnapshot {
        val config = configSupplier()
        val categories = config?.captureCategories ?: CaptureCategory.all()
        val mocksOn = CaptureCategory.MOCKS in categories
        val inspectionOn = CaptureCategory.INSPECTION in categories
        val stateOn = CaptureCategory.STATE in categories
        return InspectorSnapshot(
            available = true,
            transactions = transactionsFor(categories, trafficQuery),
            capabilities = editingUiFor(config?.editingCapabilities, categories),
            mocksEnabled = mockEngine.isEnabled(),
            mockRules = if (mocksOn) mockEngine.rules().map { it.toUi(mockEngine.stats(it.id)) } else emptyList(),
            captureRules = if (mocksOn) captureRuleEngine.rules().map { it.toUi() } else emptyList(),
            sockets = socketsFor(categories),
            pushEvents = pushEventsFor(categories),
            logs = if (CaptureCategory.LOGS in categories) readLogs() else emptyList(),
            crashes = if (CaptureCategory.CRASHES in categories) readCrashes() else emptyList(),
            featureFlags = if (stateOn) readFeatureFlags() else emptyList(),
            stateProviders = if (stateOn) readStateProviders() else emptyList(),
            preferenceFiles =
                if (inspectionOn) preferencesInspector?.files().orEmpty().map { it.toUi() } else emptyList(),
            fileRoots = if (inspectionOn) fileInspector?.roots().orEmpty() else emptyList(),
            databases = if (inspectionOn) databaseInspector?.databases().orEmpty() else emptyList(),
            sessions = sessionsSupplier(),
            health = healthSupplier(),
            browser = browserSupplier(),
            retention = retentionSupplier(),
            keepAlivePromptNeeded = keepAlivePromptSupplier(),
            captureCategories = categories,
        )
    }

    private fun transactionsFor(
        categories: Set<CaptureCategory>,
        trafficQuery: InspectorTrafficQuery,
    ): List<InspectorTransactionUi> {
        if (CaptureCategory.NETWORK !in categories) return emptyList()
        return networkTransactionStore.page(trafficQuery.toEngineQuery()).transactions.map { it.toUi() }
    }

    private fun editingUiFor(
        capabilities: EditingCapabilities?,
        categories: Set<CaptureCategory>,
    ): InspectorEditingUi {
        val mocksOn = CaptureCategory.MOCKS in categories
        val inspectionOn = CaptureCategory.INSPECTION in categories
        val stateOn = CaptureCategory.STATE in categories
        return InspectorEditingUi(
            requestExecution = capabilities?.requestExecution ?: false,
            mocks = (capabilities?.mocks ?: false) && mocksOn,
            featureFlags = (capabilities?.featureFlags ?: false) && stateOn,
            preferences = (capabilities?.preferences ?: false) && inspectionOn,
            files = (capabilities?.files ?: false) && inspectionOn,
            database = (capabilities?.database ?: false) && inspectionOn,
            captureRules = (capabilities?.captureRules ?: false) && mocksOn,
        )
    }

    private fun socketsFor(categories: Set<CaptureCategory>): List<InspectorSocketUi> =
        socketStore
            .connections()
            .filter { it.protocol.enabledIn(categories) }
            .take(SNAPSHOT_SOCKET_LIMIT)
            .map { it.toUi() }

    private fun pushEventsFor(categories: Set<CaptureCategory>): List<InspectorPushUi> {
        if (CaptureCategory.PUSH !in categories) return emptyList()
        return pushStore
            .events()
            .takeLast(SNAPSHOT_PUSH_LIMIT)
            .asReversed()
            .map { it.toUi() }
    }

    /** SOCKET gates a WebSocket-protocol connection; MQTT gates an MQTT-protocol one. */
    private fun SocketProtocol.enabledIn(categories: Set<CaptureCategory>): Boolean =
        when (this) {
            SocketProtocol.WEBSOCKET -> CaptureCategory.SOCKET in categories
            SocketProtocol.MQTT -> CaptureCategory.MQTT in categories
        }

    private fun readFeatureFlags(): List<InspectorFeatureFlagUi> {
        val provider = featureFlagsSupplier() ?: return emptyList()
        val overrides = provider.overrides()
        return provider.flags().map { flag -> flag.toUi(provider.value(flag.key), overrides.containsKey(flag.key)) }
    }

    private fun readStateProviders(): List<InspectorStateProviderUi> {
        val registry = stateRegistry ?: return emptyList()
        return registry.providerIds().mapNotNull { id ->
            val snapshot = registry.snapshot(id) ?: return@mapNotNull null
            InspectorStateProviderUi(
                id = id,
                entries =
                    snapshot.values.map { (key, value) ->
                        InspectorStateEntryUi(
                            key = key,
                            value = value.toDisplay(),
                            redacted = value is StateValue.Redacted,
                        )
                    },
            )
        }
    }

    private fun readLogs(): List<InspectorLogUi> {
        val timeline = timelineSupplier() ?: return emptyList()
        val query =
            TimelineQuery(
                limit = SNAPSHOT_LOG_LIMIT,
                pluginIds = setOf(LOGS_PLUGIN_ID, CRASH_PLUGIN_ID),
                sort = TimelineSort.DESC,
            )
        return when (val page = timeline.page(query)) {
            is TimelinePage.Success -> page.events.map { it.toLogUi() }
            TimelinePage.InvalidCursor -> emptyList()
        }
    }

    /** Live-session counterpart of [readLogs], but crash/ANR-only and dedicated to the Crashes tab. */
    private fun readCrashes(): List<InspectorCrashUi> {
        val timeline = timelineSupplier() ?: return emptyList()
        val query =
            TimelineQuery(
                limit = SNAPSHOT_LOG_LIMIT,
                pluginIds = setOf(CRASH_PLUGIN_ID),
                sort = TimelineSort.DESC,
            )
        return when (val page = timeline.page(query)) {
            is TimelinePage.Success -> page.events.map { it.toCrashUi() }
            TimelinePage.InvalidCursor -> emptyList()
        }
    }

    override fun execute(request: InspectorComposerRequest): InspectorCommandResult {
        val config = configSupplier()
        return when {
            config?.editingCapabilities?.requestExecution != true -> InspectorCommandResult.Disabled("requestExecution")
            !config.composerEnabled || !config.composerAllowedHosts.permitsHostOf(request.url) ->
                InspectorCommandResult.Invalid("Destination host is not allowed")
            else -> dispatch(request, config)
        }
    }

    /**
     * The composer transport is arbitrary host I/O (sockets, TLS, DNS); any failure there must
     * surface as [InspectorCommandResult.Failed] rather than crash the inspector UI.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun dispatch(
        request: InspectorComposerRequest,
        config: DevConsoleConfig,
    ): InspectorCommandResult {
        val composerRequest =
            EngineComposerRequest(
                method = request.method,
                url = request.url,
                headers = request.headers,
                body = request.body,
            )
        return try {
            val execution = composerExecutor.execute(composerRequest, config.composerAllowedHosts::permitsHostOf)
            InspectorCommandResult.Success(
                summary = "${request.method} ${request.url} -> ${execution.response.statusCode}",
                statusCode = execution.response.statusCode,
                body = execution.response.body,
            )
        } catch (rejected: ComposerDestinationRejectedException) {
            InspectorCommandResult.Invalid("Destination host is not allowed: ${rejected.destination}")
        } catch (failure: Throwable) {
            InspectorCommandResult.Failed(failure.message ?: failure.javaClass.simpleName)
        }
    }

    override fun setMocksEnabled(enabled: Boolean): InspectorCommandResult {
        if (configSupplier()?.editingCapabilities?.mocks != true) return InspectorCommandResult.Disabled("mocks")
        mockEngine.setEnabled(enabled)
        return InspectorCommandResult.Success(summary = if (enabled) "Mocks enabled" else "Mocks disabled")
    }

    /** Ungated read, like the other listings; only the mutations below consult the capability. */
    override fun mockRules(): List<InspectorMockRuleUi> = mockEngine.rules().map { it.toUi(mockEngine.stats(it.id)) }

    /**
     * Invalid id/status/scope/delay input surfaces as [InspectorCommandResult.Invalid]; an invalid
     * path or body regular expression is only discovered when [MockEngine.upsert] compiles it, so
     * that failure surfaces as [InspectorCommandResult.Failed] instead. A positive [InspectorMockRuleUi.delayMs]
     * wraps the response in [MockAction.Delay], mirroring what the browser dashboard's mock-rule form
     * can express; zero or absent stays a plain [MockAction.StaticResponse].
     */
    override fun upsertMockRule(rule: InspectorMockRuleUi): InspectorCommandResult =
        withMocksCapability {
            if (!rule.id.matches(MOCK_RULE_ID_PATTERN)) {
                return@withMocksCapability InspectorCommandResult.Invalid(
                    "Mock rule id must match ${MOCK_RULE_ID_PATTERN.pattern}",
                )
            }
            if (rule.statusCode !in MIN_STATUS_CODE..MAX_STATUS_CODE) {
                return@withMocksCapability InspectorCommandResult.Invalid("Response status must be between 100 and 599")
            }
            if (rule.delayMs != null && rule.delayMs !in 0..MAX_MOCK_RULE_DELAY_MS) {
                return@withMocksCapability InspectorCommandResult.Invalid(
                    "Delay must be between 0 and $MAX_MOCK_RULE_DELAY_MS ms",
                )
            }
            val scope =
                runCatching { MockScope.valueOf(rule.scope) }
                    .getOrElse {
                        return@withMocksCapability InspectorCommandResult.Invalid("Unknown mock scope ${rule.scope}")
                    }
            rule.headers.forEach { (name, value) ->
                val nameOk = name.matches(MOCK_HEADER_NAME_PATTERN)
                val valueOk = value.all { it == '\t' || it in ' '..'~' }
                if (!nameOk || !valueOk) {
                    // Mirrors the server route's boundary check: OkHttp would throw for these
                    // characters on the host app's request thread at interception time.
                    return@withMocksCapability InspectorCommandResult.Invalid("Invalid response header: $name")
                }
            }
            val staticResponse = MockAction.StaticResponse(rule.statusCode, rule.body, rule.headers)
            val delayMs = rule.delayMs
            val action =
                if (delayMs != null && delayMs > 0) MockAction.Delay(delayMs, staticResponse) else staticResponse
            val engineRule =
                MockRule(
                    id = rule.id.trim(),
                    priority = rule.priority,
                    method = rule.method?.uppercase()?.takeIf(String::isNotBlank),
                    scheme = rule.scheme?.lowercase()?.takeIf(String::isNotBlank),
                    host = rule.host?.takeIf(String::isNotBlank),
                    path = rule.pathPattern.takeIf(String::isNotBlank) ?: DEFAULT_MOCK_RULE_PATH,
                    scope = scope,
                    action = action,
                    // Set only by mockRuleDraftFromTransaction's "Mock this response" flow; carried
                    // through untouched by the create/edit form's other fields (never user-editable).
                    sourceBodySnapshot = rule.sourceBodySnapshot,
                ).withPersistence(MockRulePersistence(enabled = rule.enabled))
            runCatching { mockEngine.upsert(engineRule) }.fold(
                onSuccess = { InspectorCommandResult.Success(summary = "Mock rule ${engineRule.id} saved") },
                onFailure = { InspectorCommandResult.Failed(it.mockRuleMessage()) },
            )
        }

    override fun deleteMockRule(id: String): InspectorCommandResult =
        withMocksCapability {
            runCatching { mockEngine.remove(id) }.fold(
                onSuccess = { removed ->
                    if (removed) {
                        InspectorCommandResult.Success(summary = "Mock rule $id deleted")
                    } else {
                        InspectorCommandResult.Invalid("Unknown mock rule $id")
                    }
                },
                onFailure = { InspectorCommandResult.Failed(it.mockRuleMessage()) },
            )
        }

    override fun setMockRuleEnabled(
        id: String,
        enabled: Boolean,
    ): InspectorCommandResult =
        withMocksCapability {
            runCatching { mockEngine.setEnabled(id, enabled) }.fold(
                onSuccess = { changed ->
                    if (changed) {
                        val state = if (enabled) "enabled" else "disabled"
                        InspectorCommandResult.Success(summary = "Mock rule $id $state")
                    } else {
                        InspectorCommandResult.Invalid("Unknown mock rule $id")
                    }
                },
                onFailure = { InspectorCommandResult.Failed(it.mockRuleMessage()) },
            )
        }

    private inline fun withMocksCapability(mutation: () -> InspectorCommandResult): InspectorCommandResult =
        if (configSupplier()?.editingCapabilities?.mocks != true) {
            InspectorCommandResult.Disabled("mocks")
        } else {
            mutation()
        }

    /** Ungated read, like the other listings; only the mutations below consult the capability. */
    override fun captureRules(): List<InspectorCaptureRuleUi> = captureRuleEngine.rules().map { it.toUi() }

    /**
     * Invalid host/method/path input and the rule-count ceiling both surface as
     * [InspectorCommandResult.Invalid]; a storage failure surfaces as [InspectorCommandResult.Failed]
     * so the caller is never told an un-persisted exclusion is in force.
     */
    override fun upsertCaptureRule(rule: InspectorCaptureRuleUi): InspectorCommandResult =
        withCaptureRuleCapability {
            val parsed =
                runCatching { CaptureRule.of(rule.id, rule.host, rule.method, rule.pathPrefix, rule.enabled) }
                    .getOrElse {
                        return@withCaptureRuleCapability InspectorCommandResult.Invalid(it.captureRuleMessage())
                    }
            runCatching { captureRuleEngine.upsert(parsed) }.fold(
                onSuccess = { InspectorCommandResult.Success(summary = "Capture rule ${parsed.id} saved") },
                onFailure = { InspectorCommandResult.Failed(it.captureRuleMessage()) },
            )
        }

    override fun deleteCaptureRule(id: String): InspectorCommandResult =
        withCaptureRuleCapability {
            runCatching { captureRuleEngine.remove(id) }.fold(
                onSuccess = { removed ->
                    if (removed) {
                        InspectorCommandResult.Success(summary = "Capture rule $id deleted")
                    } else {
                        InspectorCommandResult.Invalid("Unknown capture rule $id")
                    }
                },
                onFailure = { InspectorCommandResult.Failed(it.captureRuleMessage()) },
            )
        }

    override fun setCaptureRuleEnabled(
        id: String,
        enabled: Boolean,
    ): InspectorCommandResult =
        withCaptureRuleCapability {
            runCatching { captureRuleEngine.setEnabled(id, enabled) }.fold(
                onSuccess = { changed ->
                    if (changed) {
                        InspectorCommandResult.Success(
                            summary = "Capture rule $id ${if (enabled) "enabled" else "disabled"}",
                        )
                    } else {
                        InspectorCommandResult.Invalid("Unknown capture rule $id")
                    }
                },
                onFailure = { InspectorCommandResult.Failed(it.captureRuleMessage()) },
            )
        }

    private inline fun withCaptureRuleCapability(mutation: () -> InspectorCommandResult): InspectorCommandResult =
        if (configSupplier()?.editingCapabilities?.captureRules != true) {
            InspectorCommandResult.Disabled("captureRules")
        } else {
            mutation()
        }

    /**
     * [FeatureFlagProvider.override] throws for an unknown/immutable key or a disallowed value; both
     * are user-correctable input errors, not bugs, so they surface as [InspectorCommandResult.Invalid]
     * rather than propagating.
     */
    override fun setFeatureFlag(
        key: String,
        value: String,
    ): InspectorCommandResult {
        val provider = featureFlagsSupplier()
        val canEdit = configSupplier()?.editingCapabilities?.featureFlags == true
        return when {
            !canEdit -> InspectorCommandResult.Disabled("featureFlags")
            provider == null -> InspectorCommandResult.Unavailable
            else -> provider.applyOverride(key, value)
        }
    }

    override fun setPreference(
        file: String,
        key: String,
        value: String,
        type: String,
    ): InspectorCommandResult {
        val inspector = preferencesInspector
        val canEdit = configSupplier()?.editingCapabilities?.preferences == true
        return when {
            !canEdit -> InspectorCommandResult.Disabled("preferences")
            inspector == null -> InspectorCommandResult.Unavailable
            inspector.put(file, key, value, type) -> InspectorCommandResult.Success(summary = "$file:$key set")
            else -> InspectorCommandResult.Invalid("Could not set $key in $file")
        }
    }

    override fun removePreference(
        file: String,
        key: String,
    ): InspectorCommandResult {
        val inspector = preferencesInspector
        val canEdit = configSupplier()?.editingCapabilities?.preferences == true
        return when {
            !canEdit -> InspectorCommandResult.Disabled("preferences")
            inspector == null -> InspectorCommandResult.Unavailable
            inspector.remove(file, key) -> InspectorCommandResult.Success(summary = "$file:$key removed")
            else -> InspectorCommandResult.Invalid("Could not remove $key from $file")
        }
    }

    override fun listFiles(
        root: String,
        relativePath: String,
    ): InspectorFileListingUi? = fileInspector?.list(root, relativePath)?.toUi()

    override fun previewFile(
        root: String,
        relativePath: String,
    ): InspectorFilePreviewUi =
        fileInspector?.preview(root, relativePath)?.toUi()
            ?: InspectorFilePreviewUi.Unavailable("Inspector is not connected")

    override fun listTables(database: String): InspectorDatabaseListingUi? = databaseInspector?.tables(database)?.toUi()

    override fun queryTable(
        database: String,
        table: String,
    ): InspectorQueryResultUi? = databaseInspector?.query(database, table)?.toUi()

    /**
     * Read-only statements always run; the engine refuses anything that can mutate data or schema
     * unless the host opted into [io.devconsole.api.EditingCapabilities.database].
     */
    override fun executeSql(
        database: String,
        sql: String,
    ): InspectorSqlResultUi {
        val inspector = databaseInspector ?: return InspectorSqlResultUi.Failed("Inspector is not connected")
        val writeEnabled = configSupplier()?.editingCapabilities?.database == true
        return inspector.execute(database, sql, writeEnabled).toUi()
    }

    override fun deleteFile(
        root: String,
        relativePath: String,
    ): InspectorCommandResult {
        val inspector = fileInspector
        val canEdit = configSupplier()?.editingCapabilities?.files == true
        return when {
            !canEdit -> InspectorCommandResult.Disabled("files")
            inspector == null -> InspectorCommandResult.Unavailable
            inspector.delete(root, relativePath) -> InspectorCommandResult.Success(summary = "$relativePath deleted")
            else -> InspectorCommandResult.Invalid("Could not delete $relativePath")
        }
    }

    /**
     * Gated by the `files` capability like [deleteFile] -- the resulting path is handed straight to
     * `FileProvider`/`ACTION_SEND`, i.e. raw and unredacted, unlike [previewFile].
     */
    override fun shareableFilePath(
        root: String,
        relativePath: String,
    ): String? {
        val canShare = configSupplier()?.editingCapabilities?.files == true
        if (!canShare) return null
        return shareableFileResolver(root, relativePath)?.absolutePath
    }

    /**
     * Ungated by capability: exports read already-captured, already-redacted data, the same as
     * [listFiles]/[previewFile]/[listTables] above.
     */
    override fun exportHar(transactionIds: Set<String>): InspectorCommandResult {
        val result = exporter?.exportHar(transactionIds.toExportSelection())?.toUi()
        return result ?: InspectorCommandResult.Unavailable
    }

    override fun exportPostman(transactionIds: Set<String>): InspectorCommandResult {
        val result = exporter?.exportPostman(transactionIds.toExportSelection())?.toUi()
        return result ?: InspectorCommandResult.Unavailable
    }

    override fun exportSessionZip(): InspectorCommandResult {
        val result = exporter?.exportSessionZip()?.toUi()
        return result ?: InspectorCommandResult.Unavailable
    }

    /**
     * The More screen's screenshot capture button. `DevConsole` (from `facade-shared`, compiled as
     * part of this same module -- see `sdk/full/build.gradle.kts`'s extra `kotlin.directories` entry)
     * is the one public entry point that already owns `ActivityTracker`/`ScreenshotCapture`/the
     * `ScreenshotPolicy` gate end to end, so this adapts straight to it rather than duplicating any
     * of that here.
     */
    override suspend fun captureScreenshot(): ScreenshotResult = DevConsole.captureScreenshot()

    override fun revokePrincipal(id: String): InspectorCommandResult =
        if (revokePrincipalHandler(id)) {
            InspectorCommandResult.Success(summary = "Browser $id revoked")
        } else {
            InspectorCommandResult.Invalid("Unknown or already-expired browser $id")
        }

    private companion object {
        const val SNAPSHOT_SOCKET_LIMIT = 100
        const val SNAPSHOT_PUSH_LIMIT = 200
        const val SNAPSHOT_LOG_LIMIT = 200
    }
}

private const val MAX_SOCKET_FRAMES = 50
private const val LOGS_PLUGIN_ID = "logs"
private const val CRASH_PLUGIN_ID = "crash"
private const val SNAPSHOT_TRANSACTION_LIMIT = 200

/** Translates the UI-owned traffic filter into the engine's query/filter types. */
private fun InspectorTrafficQuery.toEngineQuery(): NetworkTransactionQuery {
    val filters =
        NetworkTransactionFilters(
            statusFrom = statusClass.from,
            statusTo = statusClass.to,
            hasError = if (statusClass == TrafficStatusClass.FAILED) true else null,
            query = search.trim().takeIf(String::isNotEmpty),
        )
    val methods =
        method
            ?.uppercase()
            ?.takeIf(String::isNotBlank)
            ?.let(::setOf)
            .orEmpty()
    return NetworkTransactionQuery(limit = SNAPSHOT_TRANSACTION_LIMIT, methods = methods).withFilters(filters)
}

/** Exact-host allow-list match, mirroring the server's Composer host gate. */
private fun Set<String>.permitsHostOf(url: String): Boolean {
    val host = url.hostOrEmpty().lowercase()
    return host.isNotEmpty() && any { it.lowercase() == host }
}

private fun String.hostOrEmpty(): String = runCatching { URI(this).host.orEmpty() }.getOrDefault("")

private fun NetworkTransaction.toUi(): InspectorTransactionUi {
    val request = capture.request
    val response = capture.response
    val tags = request.metadata.tags
    return InspectorTransactionUi(
        id = id,
        method = request.method,
        host = request.url.host,
        path = request.url.path,
        statusCode = response?.statusCode,
        durationMs = durationMs,
        startedAtEpochMs = startedAtEpochMs,
        requestHeaders = request.headers,
        responseHeaders = response?.headers ?: emptyMap(),
        requestPreview = request.body.previewText(),
        responsePreview = response?.body?.previewText(),
        error = response?.error,
        url = request.url.display,
        requestBodyKind = request.body.toUiKind(),
        requestBodyTruncated = request.body.isTruncated(),
        timingPhases = response?.metadata?.timings.toUi(),
        isMocked = tags["mocked"] == "true",
        mockRuleId = tags["mockRuleId"],
    )
}

private fun BodyPreview.previewText(): String? =
    when (this) {
        is BodyPreview.Text -> value
        is BodyPreview.Binary -> "[binary, $length bytes]"
        BodyPreview.Absent -> null
    }

private fun BodyPreview.toUiKind(): InspectorBodyKind =
    when (this) {
        is BodyPreview.Text -> InspectorBodyKind.TEXT
        is BodyPreview.Binary -> InspectorBodyKind.BINARY
        BodyPreview.Absent -> InspectorBodyKind.ABSENT
    }

private fun BodyPreview.isTruncated(): Boolean =
    when (this) {
        is BodyPreview.Text -> truncated
        is BodyPreview.Binary -> truncated
        BodyPreview.Absent -> false
    }

/**
 * Null when no response was ever captured (timeout/connection failure); each phase inside a real
 * [NetworkTimingPhases] is independently nullable too (see [InspectorTimingPhasesUi]) and is passed
 * through as-is -- never defaulted to zero, which would fabricate a measurement that never happened.
 */
private fun NetworkTimingPhases?.toUi(): InspectorTimingPhasesUi =
    if (this == null) {
        InspectorTimingPhasesUi()
    } else {
        InspectorTimingPhasesUi(
            dnsMs = dnsMs,
            connectMs = connectMs,
            tlsMs = tlsMs,
            sendMs = sendMs,
            waitMs = waitMs,
            receiveMs = receiveMs,
        )
    }

private fun CaptureRule.toUi(): InspectorCaptureRuleUi =
    InspectorCaptureRuleUi(
        id = id,
        host = host,
        method = method,
        pathPrefix = pathPrefix,
        enabled = enabled,
    )

private fun Throwable.captureRuleMessage(): String = message ?: javaClass.simpleName

private fun MockRule.toUi(stats: MockRuleStats): InspectorMockRuleUi {
    val delay = action as? MockAction.Delay
    val staticResponse = (delay?.next ?: action) as? MockAction.StaticResponse
    return InspectorMockRuleUi(
        id = id,
        method = method,
        pathPattern = path,
        actionLabel = action.toLabel(),
        scheme = scheme,
        host = host,
        priority = priority,
        scope = scope.name,
        statusCode = staticResponse?.statusCode ?: DEFAULT_STATIC_STATUS,
        body = staticResponse?.body.orEmpty(),
        enabled = persistence.enabled,
        headers = staticResponse?.headers.orEmpty(),
        delayMs = delay?.durationMs,
        hitCount = stats.hitCount,
        lastHitEpochMs = stats.lastHitEpochMs,
        sourceBodySnapshot = sourceBodySnapshot,
    )
}

private fun Throwable.mockRuleMessage(): String = message ?: javaClass.simpleName

private const val DEFAULT_STATIC_STATUS = 200
private const val DEFAULT_MOCK_RULE_PATH = ".*"
private const val MIN_STATUS_CODE = 100
private const val MAX_STATUS_CODE = 599
private const val MAX_MOCK_RULE_DELAY_MS = 30_000L
private val MOCK_RULE_ID_PATTERN = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")

private fun MockAction.toLabel(): String =
    when (this) {
        is MockAction.StaticResponse -> "Static response ($statusCode)"
        is MockAction.TemplateResponse -> "Template response ($statusCode)"
        is MockAction.Delay -> "Delay (${durationMs}ms)"
        is MockAction.ConnectionFailure -> "Connection failure"
        is MockAction.Timeout -> "Timeout (${durationMs}ms)"
        is MockAction.StatusOverride -> "Status override ($statusCode)"
        is MockAction.BodyReplacement -> "Body replacement"
        MockAction.Passthrough -> "Passthrough"
    }

private fun SocketConnection.toUi(): InspectorSocketUi =
    InspectorSocketUi(
        id = id,
        url = url,
        state = state.name,
        sentCount = sentCount,
        receivedCount = receivedCount,
        openedAtEpochMs = openedAtEpochMs,
        closedAtEpochMs = closedAtEpochMs,
        error = error,
        frames = messages.takeLast(MAX_SOCKET_FRAMES).map { it.toUi() },
        protocol = protocol.wireName,
    )

private fun SocketMessage.toUi(): InspectorSocketFrameUi =
    InspectorSocketFrameUi(
        direction = direction.name,
        frameType = metadata.frameType.name,
        preview =
            when (val value = payload) {
                is SocketPayload.Text -> value.preview
                is SocketPayload.Binary -> value.preview ?: "[binary, ${value.length} bytes]"
            },
        timestampEpochMs = timestampEpochMs,
        byteLength =
            when (val value = payload) {
                // The full frame length, captured before any preview truncation.
                is SocketPayload.Binary -> value.length
                // No true length is captured for TEXT frames; this is only the (possibly
                // truncated/redacted) preview's own encoded size -- see [truncated] below.
                is SocketPayload.Text ->
                    value.preview
                        .encodeToByteArray()
                        .size
                        .toLong()
            },
        truncated =
            when (val value = payload) {
                is SocketPayload.Binary -> value.truncated
                is SocketPayload.Text -> value.truncated
            },
        topic = MqttFrameMetadata.topic(contentType),
        qos = MqttFrameMetadata.qos(contentType),
    )

private fun PushEvent.toUi(): InspectorPushUi =
    InspectorPushUi(
        provider = provider,
        messageId = messageId,
        lifecycle = lifecycle.name,
        simulated = simulated,
        receivedAtEpochMs = receivedAtEpochMs,
        dataPreview = data,
    )

private fun FeatureFlagProvider.applyOverride(
    key: String,
    value: String,
): InspectorCommandResult =
    runCatching { override(key, value) }
        .fold(
            onSuccess = { InspectorCommandResult.Success(summary = "Feature flag $key set to $value") },
            onFailure = { InspectorCommandResult.Invalid(it.message ?: "Invalid feature flag value") },
        )

private fun FeatureFlag.toUi(
    currentValue: String,
    isOverridden: Boolean,
): InspectorFeatureFlagUi =
    InspectorFeatureFlagUi(
        key = key,
        value = currentValue,
        defaultValue = defaultValue,
        allowedValues = allowedValues.toList(),
        type = type.name,
        mutable = mutable,
        description = description,
        isOverridden = isOverridden,
    )

private fun PreferencesFileData.toUi(): InspectorPreferenceFileUi =
    InspectorPreferenceFileUi(
        name = name,
        entries = entries.map { InspectorPreferenceEntryUi(it.key, it.value, it.type, it.redacted) },
    )

private fun FileListingData.toUi(): InspectorFileListingUi =
    InspectorFileListingUi(
        root = root,
        relativePath = relativePath,
        entries =
            entries.map {
                InspectorFileEntryUi(it.name, it.relativePath, it.isDirectory, it.sizeBytes, it.lastModifiedEpochMs)
            },
    )

private fun DatabaseListingData.toUi(): InspectorDatabaseListingUi =
    InspectorDatabaseListingUi(
        name = name,
        tables = tables.map { InspectorDatabaseTableUi(it.name, it.rowCount) },
        sizeBytes = sizeBytes,
    )

private fun DatabaseQueryData.toUi(): InspectorQueryResultUi = InspectorQueryResultUi(columns, rows, truncated)

private fun DatabaseExecResult.toUi(): InspectorSqlResultUi =
    when (this) {
        is DatabaseExecResult.Query -> InspectorSqlResultUi.Rows(result.toUi())
        is DatabaseExecResult.Write -> InspectorSqlResultUi.Wrote(affectedRows)
        DatabaseExecResult.WriteBlocked -> InspectorSqlResultUi.WriteBlocked
        is DatabaseExecResult.Failed -> InspectorSqlResultUi.Failed(message)
    }

private fun ExportOutcome.toUi(): InspectorCommandResult =
    when (this) {
        is ExportOutcome.Written -> InspectorCommandResult.Success(summary = "Saved to $path", sharePath = path)
        is ExportOutcome.Failed -> InspectorCommandResult.Failed(message)
    }

/**
 * Empty means every captured transaction ([ExportSelection.All]); see [InspectorDataSource.exportHar].
 *
 * Wrapped is deliberate: the one-line form is 128 chars, over detekt's 120-char MaxLineLength, but
 * ktlint's function-signature rule wants it collapsed back since it fits within ktlint's own
 * 140-char limit -- the same irreconcilable two-linter conflict documented on
 * UnavailableInspectorDataSource.upsertCaptureRule in sdk:ui-compose. Suppressing ktlint here keeps
 * detekt's line-length wrapping intact rather than growing the detekt baseline.
 */
@Suppress("ktlint:standard:function-signature")
private fun Set<String>.toExportSelection(): ExportSelection =
    if (isEmpty()) ExportSelection.All else ExportSelection.Ids(this)

private fun FilePreviewData.toUi(): InspectorFilePreviewUi =
    when (this) {
        is FilePreviewData.Text -> InspectorFilePreviewUi.Text(content, truncated)
        is FilePreviewData.Binary -> InspectorFilePreviewUi.Binary(sizeBytes)
        is FilePreviewData.Unavailable -> InspectorFilePreviewUi.Unavailable(reason)
    }

/** Short, redaction-safe rendering for the read-only State Providers surface. */
private fun StateValue.toDisplay(): String =
    when (this) {
        StateValue.Null -> "null"
        is StateValue.BooleanValue -> value.toString()
        is StateValue.NumberValue -> value.toString()
        is StateValue.StringValue -> value
        is StateValue.ObjectValue -> "{${values.size} ${"field".pluralize(values.size)}}"
        is StateValue.ArrayValue -> "[${values.size} ${"item".pluralize(values.size)}]"
        StateValue.Redacted -> "(redacted)"
        is StateValue.Unavailable -> reason?.let { "(unavailable: $it)" } ?: "(unavailable)"
        is StateValue.BinaryMetadata -> "[binary, $byteLength bytes]"
    }

private fun String.pluralize(count: Int): String = if (count == 1) this else "${this}s"

/** A timeline event from the "logs" or "crash" plugin, flattened for the read-only Logs surface. */
private fun StoredEvent.toLogUi(): InspectorLogUi {
    val isCrash = pluginId == CRASH_PLUGIN_ID
    val kind =
        if (isCrash) {
            type
        } else {
            tagsJson.jsonStringField("level") ?: LogLevel.entries.getOrNull(severity)?.name ?: "LOG"
        }
    val source =
        if (isCrash) {
            tagsJson.jsonStringField("thread")
        } else {
            tagsJson.jsonStringField("tag")
        }?.takeIf { it.isNotBlank() } ?: pluginId
    val detail = payloadJson?.jsonStringField("stackTrace") ?: payloadJson?.jsonStringField("message") ?: payloadJson
    return InspectorLogUi(
        id = id,
        kind = kind,
        source = source,
        summary = summary,
        timestampEpochMs = wallTimeMs,
        detail = detail,
    )
}

/** [stackTrace] and [breadcrumbs] parsed out of a "crash" plugin event's `payloadJson`; see [CrashCapture]. */
private data class ParsedCrashPayload(
    val stackTrace: String,
    val breadcrumbs: List<InspectorBreadcrumbUi>,
)

/**
 * Locates one flat `{"ts":<long>,"plugin":"...","type":"...","severity":<int>,"summary":"..."}`
 * breadcrumb object -- the exact, always-in-this-field-order shape [CrashCapture.breadcrumbsJson]
 * writes -- capturing only the numeric `ts`/`severity` fields itself and leaving the whole matched
 * object text (group 0) for [String.jsonStringField] to pull the string fields out of. Regex, not
 * `org.json`: this module deliberately stays off `org.json`/Android-provided JSON parsers everywhere
 * else (see [jsonStringField]) so `FullInspectorDataSourceTest`'s plain JVM unit tests -- no
 * Robolectric here, unlike `AndroidMockRuleStoreTest` -- can exercise this without every
 * `org.json.JSONObject` call throwing "not mocked". A string field's own pattern is
 * `(?:[^"\\]|\\.)*`, mirroring [jsonStringField]'s own matching group, so an escaped quote inside
 * `summary` can never terminate the match early.
 */
private val BREADCRUMB_PATTERN =
    Regex(
        "\\{\"ts\":(\\d+),\"plugin\":\"(?:[^\"\\\\]|\\\\.)*\"," +
            "\"type\":\"(?:[^\"\\\\]|\\\\.)*\",\"severity\":(\\d+)," +
            "\"summary\":\"(?:[^\"\\\\]|\\\\.)*\"\\}",
    )

/**
 * [jsonStringField] already handles unescaping, so each string field is re-extracted from this
 * single breadcrumb object's own matched text ([MatchResult.value]) rather than threading a second,
 * duplicate unescaper through this file.
 */
private fun MatchResult.toBreadcrumb(): InspectorBreadcrumbUi {
    val objectText = value
    return InspectorBreadcrumbUi(
        timestampEpochMs = groupValues[1].toLongOrNull() ?: 0L,
        plugin = objectText.jsonStringField("plugin").orEmpty(),
        type = objectText.jsonStringField("type").orEmpty(),
        severity = groupValues[2].toIntOrNull() ?: 0,
        summary = objectText.jsonStringField("summary").orEmpty(),
    )
}

/**
 * Parses the whole `{"stackTrace":"...","breadcrumbs":[...]}` payload object [CrashCapture] writes.
 * [BREADCRUMB_PATTERN] is matched against the payload directly rather than isolating the
 * `"breadcrumbs"` array substring first -- a stack trace's escaped text matching that exact
 * five-field shape is not a realistic collision. Malformed/absent input degrades to an empty payload
 * rather than throwing -- a crash event with an unreadable payload should still show up as a row,
 * just without a dump or breadcrumbs.
 */
private fun String?.parseCrashPayload(): ParsedCrashPayload {
    if (this == null) return ParsedCrashPayload("", emptyList())
    val stackTrace = jsonStringField("stackTrace").orEmpty()
    val breadcrumbs = BREADCRUMB_PATTERN.findAll(this).map { it.toBreadcrumb() }.toList()
    return ParsedCrashPayload(stackTrace, breadcrumbs)
}

/** A "crash" plugin timeline event (uncaught exception or ANR), flattened for the Crashes surface. */
private fun StoredEvent.toCrashUi(): InspectorCrashUi {
    val parsed = payloadJson.parseCrashPayload()
    return InspectorCrashUi(
        id = id,
        kind = tagsJson.jsonStringField("kind") ?: type.uppercase(Locale.US),
        summary = summary,
        thread = tagsJson.jsonStringField("thread").orEmpty(),
        timestampEpochMs = wallTimeMs,
        stackTrace = parsed.stackTrace,
        breadcrumbs = parsed.breadcrumbs,
    )
}

private val MOCK_HEADER_NAME_PATTERN = Regex("[!#\\$%&'*+.^_`|~0-9A-Za-z-]+")

// ============================================================================================
// Evidence snapshot materialization (A -- see the design spec's "Snapshot at flag time" section).
// Mirrors DevConsoleKtorModule's own NETWORK/CRASH snapshot shapes field-for-field, so a subject
// flagged from this device and one flagged from the dashboard are stored (and later rendered)
// identically. There is no shared module either side can depend on for this without inventing a
// second boundary just to hold it, so the shape is reproduced here byte-for-byte rather than
// referenced -- see FullInspectorDataSourceTest for the assertions that keep the two in sync.
// ============================================================================================

// Reuse EvidenceStore's own caps (sdk:storage-api) rather than re-declaring them here -- the CRASH
// evidence snapshot had diverged between the device and the server precisely because this logic was
// duplicated in two places; a single shared source is how that stops recurring.
private val MAX_EVIDENCE_LABEL_LENGTH = EvidenceStore.MAX_LABEL_LENGTH
private val EVIDENCE_MAX_ITEMS_PER_SESSION = EvidenceStore.MAX_ITEMS_PER_SESSION

private fun EvidenceWriteResult.toCommandResult(): InspectorCommandResult =
    when (this) {
        is EvidenceWriteResult.Success -> InspectorCommandResult.Success(summary = "Flagged — added to evidence tray")
        EvidenceWriteResult.AlreadyFlagged ->
            InspectorCommandResult.Invalid("Already flagged — see the evidence tray")
        EvidenceWriteResult.QuotaExceeded ->
            InspectorCommandResult.Invalid(
                "Evidence tray is full ($EVIDENCE_MAX_ITEMS_PER_SESSION items) — clear some flags first",
            )
        EvidenceWriteResult.Unavailable -> InspectorCommandResult.Unavailable
    }

/** Evidence-tray NETWORK label: mirrors `materializeEvidenceSubject`'s NETWORK case exactly. */
private fun NetworkTransaction.evidenceLabel(): String =
    "${capture.request.method} ${capture.request.url.host}${capture.request.url.path}"

/**
 * Evidence-tray NETWORK snapshot: mirrors `DevConsoleKtorModule`'s own `NetworkTransaction.summaryJson()`
 * plus `.detailJson()`, concatenated in the exact same field order, so the two produce byte-identical
 * JSON for the same transaction.
 */
private fun NetworkTransaction.evidenceDetailJson(): String =
    buildString {
        append("{\"id\":\"").append(id.escapeJson()).append('"')
        append(",\"startedAtEpochMs\":").append(startedAtEpochMs)
        append(",\"completedAtEpochMs\":").append(completedAtEpochMs ?: "null")
        append(",\"durationMs\":").append(durationMs ?: "null")
        append(",\"method\":\"").append(capture.request.method.escapeJson()).append('"')
        append(",\"host\":\"")
            .append(
                capture.request.url.host
                    .escapeJson(),
            ).append('"')
        append(",\"path\":\"")
            .append(
                capture.request.url.path
                    .escapeJson(),
            ).append('"')
        append(",\"status\":").append(capture.response?.statusCode ?: "null")
        val contentType = (capture.response?.contentType ?: capture.request.contentType).orEmpty()
        append(",\"contentType\":\"").append(contentType.escapeJson()).append('"')
        append(",\"error\":").append(capture.response?.error.evidenceJsonStringOrNull())
        append(",\"tags\":").append(
            capture.request.metadata.tags
                .evidenceJsonObject(),
        )
        append(",\"correlationId\":").append(capture.request.correlationId.evidenceJsonStringOrNull())
        append(",\"request\":{\"url\":\"")
            .append(
                capture.request.url.display
                    .escapeJson(),
            ).append('"')
        append(",\"headers\":").append(capture.request.headers.evidenceJsonHeaders())
        append(",\"contentType\":").append(capture.request.contentType.evidenceJsonStringOrNull())
        append(",\"body\":").append(capture.request.body.evidenceDetailJson())
        append(",\"bodyMetadata\":").append(
            capture.request.metadata.body
                .evidenceDetailJson(),
        )
        append(",\"attachmentId\":").append(capture.request.attachmentId.evidenceJsonStringOrNull())
        append("},\"response\":")
        val response = capture.response
        if (response == null) {
            append("null")
        } else {
            append("{\"headers\":").append(response.headers.evidenceJsonHeaders())
            append(",\"contentType\":").append(response.contentType.evidenceJsonStringOrNull())
            append(",\"body\":").append(response.body.evidenceDetailJson())
            append(",\"bodyMetadata\":").append(response.metadata.body.evidenceDetailJson())
            append(",\"attachmentId\":").append(response.attachmentId.evidenceJsonStringOrNull())
            append(",\"timings\":").append(response.metadata.timings.evidenceDetailJson())
            append('}')
        }
        append('}')
    }

private fun BodyPreview.evidenceDetailJson(): String =
    when (this) {
        is BodyPreview.Text -> "{\"type\":\"text\",\"value\":\"${value.escapeJson()}\",\"truncated\":$truncated}"
        is BodyPreview.Binary -> "{\"type\":\"binary\",\"length\":$length,\"truncated\":$truncated}"
        BodyPreview.Absent -> "{\"type\":\"absent\"}"
    }

private fun CaptureBodyMetadata.evidenceDetailJson(): String =
    "{\"declaredLength\":${declaredLength ?: "null"},\"capturedBytes\":$capturedBytes," +
        "\"truncated\":$truncated,\"omittedReason\":${omittedReason.evidenceJsonStringOrNull()}}"

private fun NetworkTimingPhases.evidenceDetailJson(): String =
    "{\"dnsMs\":${dnsMs ?: "null"},\"connectMs\":${connectMs ?: "null"},\"tlsMs\":${tlsMs ?: "null"}," +
        "\"sendMs\":${sendMs ?: "null"},\"waitMs\":${waitMs ?: "null"},\"receiveMs\":${receiveMs ?: "null"}}"

private fun String?.evidenceJsonStringOrNull(): String = this?.let { "\"${it.escapeJson()}\"" } ?: "null"

private fun Map<String, String>.evidenceJsonHeaders(): String =
    entries.joinToString(prefix = "[", postfix = "]") { (name, value) ->
        "{\"name\":\"${name.escapeJson()}\",\"value\":\"${value.escapeJson()}\"}"
    }

private fun Map<String, String>.evidenceJsonObject(): String =
    entries.joinToString(prefix = "{", postfix = "}") { (name, value) ->
        "\"${name.escapeJson()}\":\"${value.escapeJson()}\""
    }

/**
 * Evidence-tray CRASH snapshot: mirrors [io.devconsole.CrashCapture]'s own `crashSnapshotJson()` (the
 * auto-flag path) and `DevConsoleKtorModule`'s `StoredEvent.crashSnapshotJson()` field-for-field, so a
 * crash flagged here, one auto-flagged at crash time, and one flagged from the dashboard are all
 * indistinguishable in the tray.
 */
private fun StoredEvent.evidenceCrashSnapshotJson(): String =
    buildString {
        val kind = tagsJson.jsonStringField("kind")
        val thread = tagsJson.jsonStringField("thread")
        append("{\"kind\":").append(kind.evidenceJsonStringOrNull())
        append(",\"thread\":").append(thread.evidenceJsonStringOrNull())
        append(",\"summary\":\"").append(summary.escapeJson()).append('"')
        payloadJson?.let { append(",\"payload\":").append(it) }
        append('}')
    }
