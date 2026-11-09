/**
 * @author Shakib
 * @since 24/07/26
 */
package io.devconsole.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.devconsole.api.ScreenshotResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

private const val MAX_VISIBLE_TRANSACTIONS = 200
private const val OBSERVE_TAB_SWITCH_DEBOUNCE_MS = 300L
private const val CAPABILITY_REQUEST_EXECUTION = "requestExecution"
private const val CAPABILITY_MOCKS = "mocks"
private const val CAPABILITY_FEATURE_FLAGS = "featureFlags"
private const val CAPABILITY_PREFERENCES = "preferences"
private const val CAPABILITY_FILES = "files"
private const val CAPABILITY_CAPTURE_RULES = "captureRules"
private const val NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS"

/**
 * Presentation-layer MVI store for the Observe and Control surfaces. Reads go through
 * [dispatcher] so the polled [InspectorDataSource] snapshot never blocks the main thread; the
 * capability gate for [InspectorAction.ExecuteComposer] and [InspectorAction.SetMocksEnabled] is
 * checked synchronously against the last-loaded [InspectorState.capabilities] before a coroutine
 * is even launched, so a disabled tool never reaches [dataSource].
 *
 * [JvmOverloads] so `viewModel()`'s reflection-based default factory can find a no-arg
 * constructor -- Kotlin does not expose one for an all-defaults constructor otherwise.
 */
@Suppress("TooManyFunctions") // One handler per MVI action plus small state helpers.
class InspectorViewModel
    @JvmOverloads
    constructor(
        private val dataSource: InspectorDataSource = DevConsoleInspectorBridge.source(),
        private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(InspectorState())
        val state: StateFlow<InspectorState> = mutableState.asStateFlow()

        /**
         * A transient, route-only permission preflight. This intentionally lives outside
         * [InspectorState]: it is not persisted snapshot data and adding it to that public data
         * class changes its constructor and `copy` JVM ABI for every SDK consumer.
         */
        private val mutableServerStartPermission = MutableStateFlow<String?>(null)
        internal val serverStartPermission: StateFlow<String?> = mutableServerStartPermission.asStateFlow()

        /** False until the first snapshot attempt settles, so screens show loading, not "unavailable". */
        private val mutableSnapshotLoaded = MutableStateFlow(false)
        internal val snapshotLoaded: StateFlow<Boolean> = mutableSnapshotLoaded.asStateFlow()

        /**
         * Tracks the in-flight snapshot fetch so a newer refresh (filter change, mutation,
         * explicit [InspectorAction.Refresh]) always requests cancellation of an older one still
         * in flight. [InspectorDataSource.snapshot] is a plain blocking call with no suspension
         * point inside it, so `cancel()` alone cannot interrupt a fetch that is already running --
         * it only marks the job cancelled. [loadSnapshot] pairs this with an
         * [kotlinx.coroutines.ensureActive] check right before the result is written, so a
         * superseded fetch that finishes late is discarded instead of overwriting state with a
         * snapshot for a stale filter.
         */
        private var snapshotJob: Job? = null
        private val snapshotJobLock = Any()
        private val snapshotGeneration = AtomicLong()
        private val mockMutations = Channel<() -> InspectorCommandResult>(Channel.UNLIMITED)

        /**
         * The traffic filter the next read is made with. Owned here rather than in the Compose
         * route because [InspectorDataSource.snapshot] takes it: a selection change has to re-read
         * the store with the new filter, not just re-filter an already-loaded page.
         */
        @Volatile
        private var trafficQuery = InspectorTrafficQuery()

        init {
            viewModelScope.launch(dispatcher) {
                for (mutation in mockMutations) {
                    mutation().takeUnless { it.isTestConditionSuccess() }?.let(::showComposerResult)
                    loadSnapshot()
                }
            }
            viewModelScope.launch(dispatcher) {
                DevConsoleInspectorBridge.serverStateChanges().collect { refreshSnapshot() }
            }
            refreshSnapshot()
        }

        // One branch per MVI action; a when-based dispatcher is the clearest shape for this even as
        // action count grows, so complexity is accepted here rather than split across dispatchers.
        @Suppress("CyclomaticComplexMethod", "LongMethod")
        fun dispatch(action: InspectorAction) {
            when (action) {
                InspectorAction.Refresh -> refreshSnapshot()
                InspectorAction.NotificationPermissionGranted -> {
                    dataSource.onNotificationPermissionGranted()
                    refreshSnapshot()
                }
                is InspectorAction.ServerStartPermissionResult ->
                    serverStartPermissionResult(action.granted)
                is InspectorAction.SelectObserveTab -> selectObserveTab(action.tab)
                is InspectorAction.ToggleTransactionSelection -> toggleTransactionSelection(action.id)
                is InspectorAction.SelectTransactions -> selectTransactions(action.ids)
                InspectorAction.ClearTransactionSelection -> clearTransactionSelection()
                is InspectorAction.SelectSession -> selectSession(action.id)
                is InspectorAction.SelectTrafficStatusClass -> selectTrafficStatusClass(action.statusClass)
                is InspectorAction.SelectTrafficHost -> selectTrafficHost(action.host)
                is InspectorAction.SetSessionPinned -> setSessionPinned(action.id, action.pinned)
                is InspectorAction.DeleteSession -> deleteSession(action.id)
                is InspectorAction.ToggleTransactionFlag -> toggleTransactionFlag(action.id)
                is InspectorAction.ToggleCrashFlag -> toggleCrashFlag(action.id)
                is InspectorAction.ExecuteComposer -> executeComposer(action.request)
                is InspectorAction.SetMocksEnabled -> setMocksEnabled(action.enabled)
                is InspectorAction.SetLocationPingStreamEnabled ->
                    setLocationPingStreamEnabled(action.name, action.enabled)
                is InspectorAction.UpsertMockRule -> upsertMockRule(action.rule)
                is InspectorAction.DeleteMockRule -> deleteMockRule(action.id)
                is InspectorAction.SetMockRuleEnabled -> setMockRuleEnabled(action.id, action.enabled)
                is InspectorAction.UpsertCaptureRule -> upsertCaptureRule(action.rule)
                is InspectorAction.DeleteCaptureRule -> deleteCaptureRule(action.id)
                is InspectorAction.SetCaptureRuleEnabled -> setCaptureRuleEnabled(action.id, action.enabled)
                is InspectorAction.SetFeatureFlag -> setFeatureFlag(action.key, action.value)
                is InspectorAction.SetPreference -> setPreference(action.file, action.key, action.value, action.type)
                is InspectorAction.RemovePreference -> removePreference(action.file, action.key)
                is InspectorAction.OpenFilePath -> openFilePath(action.root, action.relativePath)
                is InspectorAction.PreviewFile -> previewFile(action.root, action.relativePath)
                InspectorAction.CloseFilePreview -> closeFilePreview()
                is InspectorAction.DeleteFile -> deleteFile(action.root, action.relativePath)
                is InspectorAction.ShareFile -> shareFile(action.root, action.relativePath)
                InspectorAction.ConsumeShareFile -> consumeShareFile()
                is InspectorAction.OpenDatabase -> openDatabase(action.database)
                is InspectorAction.OpenTable -> openTable(action.database, action.table)
                is InspectorAction.ExecuteSql -> executeSql(action.database, action.sql)
                InspectorAction.DismissCommandResult -> dismissCommandResult()
                InspectorAction.ClearTransactions -> clearTransactions()
                InspectorAction.ExportHar -> exportHar()
                InspectorAction.ExportPostman -> exportPostman()
                InspectorAction.ExportSessionZip -> exportSessionZip()
                is InspectorAction.RevokePrincipal -> revokePrincipal(action.id)
                is InspectorAction.SetServerRunning -> setServerRunning(action.running)
                is InspectorAction.SetCapturePaused -> setCapturePaused(action.paused)
                InspectorAction.PurgeCapturedData -> purgeCapturedData()
                is InspectorAction.SendSocketFrame ->
                    sendSocketFrame(action.connectionId, action.text, action.topic, action.qos, action.retained)
                is InspectorAction.SetRemoteConfigOverride ->
                    setRemoteConfigOverride(action.providerId, action.key, action.value)
                is InspectorAction.ClearRemoteConfigOverride ->
                    clearRemoteConfigOverride(action.providerId, action.key)
                InspectorAction.ClearAllRemoteConfigOverrides -> clearAllRemoteConfigOverrides()
                InspectorAction.CaptureScreenshot -> captureScreenshot()
                InspectorAction.DismissScreenshotResult -> dismissScreenshotResult()
            }
        }

        private fun refreshSnapshot() {
            val generation = snapshotGeneration.incrementAndGet()
            synchronized(snapshotJobLock) {
                snapshotJob?.cancel()
                snapshotJob = viewModelScope.launch(dispatcher) { loadSnapshot(generation) }
            }
        }

        /**
         * Same as [refreshSnapshot] but waits out [OBSERVE_TAB_SWITCH_DEBOUNCE_MS] first, so a
         * rapid run of tab switches only fires one fetch instead of one per tab. Still requests
         * cancellation of any older in-flight fetch immediately -- see [snapshotJob] for why that
         * alone is not sufficient to stop it.
         */
        private fun refreshSnapshotDebounced() {
            val generation = snapshotGeneration.incrementAndGet()
            synchronized(snapshotJobLock) {
                snapshotJob?.cancel()
                snapshotJob =
                    viewModelScope.launch(dispatcher) {
                        delay(OBSERVE_TAB_SWITCH_DEBOUNCE_MS)
                        loadSnapshot(generation)
                    }
            }
        }

        /**
         * The [ensureActive] call after the (blocking, uninterruptible) fetch is what actually
         * stops a superseded result from being applied: cancelling [snapshotJob] cannot interrupt
         * the blocking [InspectorDataSource.snapshot] call itself, but it does mark this coroutine
         * cancelled, so this throws instead of reaching [applySnapshot] once a newer job has taken
         * over.
         */
        private suspend fun loadSnapshot(generation: Long = snapshotGeneration.incrementAndGet()) {
            val result = runCatching { dataSource.snapshot(trafficQuery) }
            val snapshot =
                result.getOrElse { error ->
                    if (error is CancellationException) throw error
                    currentCoroutineContext().ensureActive()
                    if (generation == snapshotGeneration.get()) mutableState.update { it.copy(available = false) }
                    mutableSnapshotLoaded.value = true
                    return
                }
            currentCoroutineContext().ensureActive()
            if (generation != snapshotGeneration.get()) return
            applySnapshot(snapshot)
            mutableSnapshotLoaded.value = true
            // Evidence flags are a separate durable read (Room, not the in-memory snapshot above) --
            // refreshed alongside every snapshot load so a flag made from the dashboard shows up here
            // on the next poll, not just after a flag/unflag made on this device. Crash flags follow
            // the same selectedSessionId guard as `crashes`/`logs` in applySnapshot: a live refresh
            // must not clobber a past session's flag set while it stays selected.
            refreshFlaggedTransactionIds()
            currentCoroutineContext().ensureActive()
            val shouldRefreshCurrentSession =
                generation == snapshotGeneration.get() && mutableState.value.selectedSessionId == null
            if (shouldRefreshCurrentSession) refreshFlaggedCrashIds(null)
        }

        private suspend fun refreshFlaggedTransactionIds() {
            val ids = runCatching { dataSource.flaggedTransactionIds() }.getOrDefault(emptySet())
            mutableState.update { it.copy(flaggedTransactionIds = ids) }
        }

        private suspend fun refreshFlaggedCrashIds(sessionId: String?) {
            val ids = runCatching { dataSource.flaggedCrashIds(sessionId) }.getOrDefault(emptySet())
            mutableState.update { state ->
                if (state.selectedSessionId == sessionId) state.copy(flaggedCrashIds = ids) else state
            }
        }

        /**
         * Flags [id] if it is not already flagged, unflags it otherwise -- the Observe traffic tab's
         * "Flag as evidence" toggle. Both outcomes refresh [InspectorState.flaggedTransactionIds] from
         * the durable store afterward rather than optimistically toggling local state, so a rejection
         * (already flagged by the dashboard in the meantime, quota exceeded) is reflected accurately.
         */
        private fun toggleTransactionFlag(id: String) {
            viewModelScope.launch(dispatcher) {
                val result =
                    if (id in mutableState.value.flaggedTransactionIds) {
                        dataSource.unflagTransaction(id)
                    } else {
                        dataSource.flagTransaction(id)
                    }
                showComposerResult(result)
                refreshFlaggedTransactionIds()
            }
        }

        /** Crash counterpart of [toggleTransactionFlag], scoped to whichever session the Crashes tab shows. */
        private fun toggleCrashFlag(id: String) {
            val sessionId = mutableState.value.selectedSessionId
            viewModelScope.launch(dispatcher) {
                val result =
                    if (id in mutableState.value.flaggedCrashIds) {
                        dataSource.unflagCrash(id, sessionId)
                    } else {
                        dataSource.flagCrash(id, sessionId)
                    }
                showComposerResult(result)
                refreshFlaggedCrashIds(sessionId)
            }
        }

        private fun applySnapshot(snapshot: InspectorSnapshot) {
            val boundedTransactions = snapshot.transactions.takeLast(MAX_VISIBLE_TRANSACTIONS)
            val sendProtocols = socketSendProtocols(snapshot.sockets)
            mutableState.update { current ->
                val next = current.withSnapshot(snapshot, boundedTransactions, dataSource, sendProtocols)
                // A category disabled mid-session (or a filter change while a now-hidden tab stayed
                // selected) must not strand the Observe tab row on a tab [visibleObserveTabs] no
                // longer lists -- snap to the first still-visible one. [visibleObserveTabs] always
                // returns a non-empty list when `next.observeTab` itself is already visible, so this
                // only ever reassigns when a snap is actually needed; when every tab is hidden (an
                // empty `captureCategories`), `firstOrNull()` leaves the tab as-is rather than
                // crashing on an empty list -- ObserveScreen's own `state.available`/empty-tab guard
                // covers that case visually.
                val visibleTabs = next.visibleObserveTabs()
                if (next.observeTab in visibleTabs) {
                    next
                } else {
                    visibleTabs.firstOrNull()?.let { next.copy(observeTab = it) } ?: next
                }
            }
        }

        /**
         * Asks the data source which live connections accept a frame. Only non-null answers make it
         * into the map; a connection the adapter cannot write to therefore has no composer.
         */
        private fun socketSendProtocols(sockets: List<InspectorSocketUi>): Map<String, String> =
            sockets
                .mapNotNull { socket ->
                    dataSource.socketSendProtocol(socket.id)?.let { protocol -> socket.id to protocol }
                }.toMap()

        private fun selectObserveTab(tab: ObserveTab) {
            mutableState.update { it.copy(observeTab = tab) }
            // Observe is a live tail: every tab switch re-reads the capture store so the screen
            // is never frozen at whatever snapshot init happened to load.
            refreshSnapshotDebounced()
        }

        /**
         * Toggling never reaches [dataSource] itself; the selection it builds up is read by
         * [exportHar]/[exportPostman] when the operator later triggers an export.
         */
        private fun toggleTransactionSelection(id: String) {
            mutableState.update { state ->
                val current = state.selectedTransactionIds
                state.copy(selectedTransactionIds = if (id in current) current - id else current + id)
            }
        }

        /** See [InspectorAction.SelectTransactions]: a union, never a replace. */
        private fun selectTransactions(ids: Set<String>) {
            mutableState.update { it.copy(selectedTransactionIds = it.selectedTransactionIds + ids) }
        }

        private fun clearTransactionSelection() {
            mutableState.update { it.copy(selectedTransactionIds = emptySet()) }
        }

        /**
         * Loads the retained logs, crashes and traffic (network/socket/push) for [id] (or the live
         * session's, when `null`) --
         * the Crashes tab is a session-scoped read exactly like Logs, so the More screen's Retained
         * runs row and the Crashes tab's previous-run-crashed banner both go through this one path.
         * [applySnapshot]'s own guard keeps a later live-snapshot refresh from clobbering either list
         * while [id] stays selected.
         */
        private fun selectSession(requestedId: String?) {
            // The current run is always browsed live; selecting it from a list means "back to live".
            val id =
                requestedId?.takeUnless { candidate ->
                    mutableState.value.sessions.any {
                        it.id == candidate &&
                            it.isCurrent
                    }
                }
            mutableState.update { it.copy(selectedSessionId = id, sessionLogsLoading = id != null) }
            viewModelScope.launch(dispatcher) {
                val logs = runCatching { dataSource.logsForSession(id) }.getOrDefault(emptyList())
                val crashes = runCatching { dataSource.crashesForSession(id) }.getOrDefault(emptyList())
                val flaggedCrashIds = runCatching { dataSource.flaggedCrashIds(id) }.getOrDefault(emptySet())
                val captures =
                    runCatching {
                        dataSource.capturesForSession(
                            id,
                        )
                    }.getOrDefault(InspectorSessionCaptures())
                mutableState.update { state ->
                    if (state.selectedSessionId == id) {
                        state.copy(
                            logs = logs.take(MAX_VISIBLE_TRANSACTIONS),
                            crashes = crashes.take(MAX_VISIBLE_TRANSACTIONS),
                            flaggedCrashIds = flaggedCrashIds,
                            transactions = captures.transactions.takeLast(MAX_VISIBLE_TRANSACTIONS),
                            sockets = captures.sockets.take(MAX_VISIBLE_TRANSACTIONS),
                            pushEvents = captures.pushEvents.take(MAX_VISIBLE_TRANSACTIONS),
                            selectedTransactionIds = emptySet(),
                            sessionLogsLoading = false,
                        )
                    } else {
                        state
                    }
                }
            }
        }

        /**
         * Re-reads the capture store with a new status-class filter. The state field is written
         * before the reload so the chip selection paints immediately, in step with the refresh.
         */
        private fun selectTrafficStatusClass(statusClass: TrafficStatusClass) {
            trafficQuery = trafficQuery.copy(statusClass = statusClass)
            mutableState.update { it.copy(trafficStatusClass = statusClass) }
            refreshSnapshot()
        }

        /** Host counterpart of [selectTrafficStatusClass]; null shows every host. */
        private fun selectTrafficHost(host: String?) {
            val normalized = host?.trim()?.takeIf(String::isNotEmpty)
            trafficQuery = trafficQuery.copy(host = normalized)
            mutableState.update { it.copy(trafficHost = normalized) }
            refreshSnapshot()
        }

        private fun setSessionPinned(
            id: String,
            pinned: Boolean,
        ) {
            runCommandThenReload { dataSource.setSessionPinned(id, pinned) }
        }

        private fun deleteSession(id: String) {
            viewModelScope.launch(dispatcher) {
                val result = dataSource.deleteSession(id)
                showComposerResult(result)
                if (result is InspectorCommandResult.Success && mutableState.value.selectedSessionId == id) {
                    selectSession(null)
                }
                loadSnapshot()
            }
        }

        private fun executeComposer(request: InspectorComposerRequest) {
            if (!mutableState.value.capabilities.requestExecution) {
                showComposerResult(InspectorCommandResult.Disabled(CAPABILITY_REQUEST_EXECUTION))
                return
            }
            runCommandThenReload { dataSource.execute(request) }
        }

        private fun setLocationPingStreamEnabled(
            name: String,
            enabled: Boolean,
        ) {
            viewModelScope.launch(dispatcher) {
                showComposerResult(dataSource.setLocationPingStreamEnabled(name, enabled))
                loadSnapshot()
            }
        }

        private fun setMocksEnabled(enabled: Boolean) {
            mutateMockRules { dataSource.setMocksEnabled(enabled) }
        }

        /**
         * Mock-rule mutations are gated here against the last-loaded capabilities, exactly like the
         * mock toggle, so a disabled tool never reaches [dataSource]; the adapter and the server
         * enforce the same gate independently.
         */
        private fun upsertMockRule(rule: InspectorMockRuleUi) {
            mutateMockRules { dataSource.upsertMockRule(rule) }
        }

        private fun deleteMockRule(id: String) {
            mutateMockRules { dataSource.deleteMockRule(id) }
        }

        private fun setMockRuleEnabled(
            id: String,
            enabled: Boolean,
        ) {
            mutateMockRules { dataSource.setMockRuleEnabled(id, enabled) }
        }

        private fun mutateMockRules(mutation: () -> InspectorCommandResult) {
            if (!mutableState.value.capabilities.mocks) {
                showComposerResult(InspectorCommandResult.Disabled(CAPABILITY_MOCKS))
                return
            }
            mockMutations.trySend(mutation)
        }

        /**
         * Capture-rule mutations are gated here against the last-loaded capabilities, exactly like
         * the mock toggle, so a disabled tool never reaches [dataSource]; the adapter and the
         * server enforce the same gate independently.
         */
        private fun upsertCaptureRule(rule: InspectorCaptureRuleUi) {
            mutateCaptureRules { dataSource.upsertCaptureRule(rule) }
        }

        private fun deleteCaptureRule(id: String) {
            mutateCaptureRules { dataSource.deleteCaptureRule(id) }
        }

        private fun setCaptureRuleEnabled(
            id: String,
            enabled: Boolean,
        ) {
            mutateCaptureRules { dataSource.setCaptureRuleEnabled(id, enabled) }
        }

        private fun mutateCaptureRules(mutation: () -> InspectorCommandResult) {
            if (!mutableState.value.capabilities.captureRules) {
                showComposerResult(InspectorCommandResult.Disabled(CAPABILITY_CAPTURE_RULES))
                return
            }
            viewModelScope.launch(dispatcher) {
                showComposerResult(mutation())
                loadSnapshot()
            }
        }

        private fun setFeatureFlag(
            key: String,
            value: String,
        ) {
            if (!mutableState.value.capabilities.featureFlags) {
                showComposerResult(InspectorCommandResult.Disabled(CAPABILITY_FEATURE_FLAGS))
                return
            }
            runCommandThenReload { dataSource.setFeatureFlag(key, value) }
        }

        private fun setPreference(
            file: String,
            key: String,
            value: String,
            type: String,
        ) {
            if (!mutableState.value.capabilities.preferences) {
                showComposerResult(InspectorCommandResult.Disabled(CAPABILITY_PREFERENCES))
                return
            }
            runCommandThenReload { dataSource.setPreference(file, key, value, type) }
        }

        private fun removePreference(
            file: String,
            key: String,
        ) {
            if (!mutableState.value.capabilities.preferences) {
                showComposerResult(InspectorCommandResult.Disabled(CAPABILITY_PREFERENCES))
                return
            }
            runCommandThenReload { dataSource.removePreference(file, key) }
        }

        private fun openFilePath(
            root: String,
            relativePath: String,
        ) {
            viewModelScope.launch(dispatcher) {
                val listing = dataSource.listFiles(root, relativePath)
                mutableState.update { it.copy(fileListing = listing, filePreview = null) }
            }
        }

        private fun previewFile(
            root: String,
            relativePath: String,
        ) {
            viewModelScope.launch(dispatcher) {
                val preview = dataSource.previewFile(root, relativePath)
                mutableState.update {
                    it.copy(filePreview = preview, filePreviewName = relativePath.substringAfterLast('/'))
                }
            }
        }

        private fun closeFilePreview() {
            mutableState.update { it.copy(filePreview = null) }
        }

        private fun deleteFile(
            root: String,
            relativePath: String,
        ) {
            if (!mutableState.value.capabilities.files) {
                showComposerResult(InspectorCommandResult.Disabled(CAPABILITY_FILES))
                return
            }
            viewModelScope.launch(dispatcher) {
                showComposerResult(dataSource.deleteFile(root, relativePath))
                val current = mutableState.value.fileListing
                val refreshed = current?.let { dataSource.listFiles(it.root, it.relativePath) }
                mutableState.update { it.copy(fileListing = refreshed, filePreview = null) }
            }
        }

        private fun shareFile(
            root: String,
            relativePath: String,
        ) {
            if (!mutableState.value.capabilities.files) {
                showComposerResult(InspectorCommandResult.Disabled(CAPABILITY_FILES))
                return
            }
            viewModelScope.launch(dispatcher) {
                val path = dataSource.shareableFilePath(root, relativePath)
                if (path == null) {
                    showComposerResult(InspectorCommandResult.Invalid("Could not prepare $relativePath for sharing"))
                    return@launch
                }
                mutableState.update { it.copy(pendingShareFilePath = path) }
            }
        }

        private fun consumeShareFile() {
            mutableState.update { it.copy(pendingShareFilePath = null) }
        }

        private fun openDatabase(database: String) {
            viewModelScope.launch(dispatcher) {
                val listing = dataSource.listTables(database)
                mutableState.update { it.copy(databaseListing = listing, queryResult = null, sqlResult = null) }
            }
        }

        private fun openTable(
            database: String,
            table: String,
        ) {
            viewModelScope.launch(dispatcher) {
                val rows = dataSource.queryTable(database, table)
                mutableState.update { it.copy(queryResult = rows, sqlResult = null) }
            }
        }

        /**
         * Not gated here: the engine classifies the statement and refuses writes itself unless the
         * `database` capability is on, so read-only SQL stays available exactly like the other reads.
         */
        private fun executeSql(
            database: String,
            sql: String,
        ) {
            viewModelScope.launch(dispatcher) {
                val result = dataSource.executeSql(database, sql)
                mutableState.update { it.copy(sqlResult = result) }
            }
        }

        /**
         * Ungated, like the exports below: clearing discards this SDK's own capture buffer and never
         * touches host application state (see FullInspectorDataSource). The selection is dropped
         * before the reload because it holds ids, not captures -- keeping it would leave the traffic
         * tab in selection mode over rows that no longer exist. [loadSnapshot] then re-reads, so the
         * emptied list comes from the adapter's real post-clear state rather than an assumed-empty
         * local one.
         */
        private fun clearTransactions() {
            viewModelScope.launch(dispatcher) {
                showComposerResult(dataSource.clearTransactions())
                mutableState.update { it.copy(selectedTransactionIds = emptySet()) }
                loadSnapshot()
            }
        }

        /** Ungated: exports read already-captured, already-redacted data (see FullInspectorDataSource). */
        private fun exportHar() {
            val selection = mutableState.value.selectedTransactionIds
            viewModelScope.launch(dispatcher) { showComposerResult(dataSource.exportHar(selection)) }
        }

        /** Ungated: exports read already-captured, already-redacted data (see FullInspectorDataSource). */
        private fun exportPostman() {
            val selection = mutableState.value.selectedTransactionIds
            viewModelScope.launch(dispatcher) { showComposerResult(dataSource.exportPostman(selection)) }
        }

        /** Ungated: exports read already-captured, already-redacted data (see FullInspectorDataSource). */
        private fun exportSessionZip() {
            viewModelScope.launch(dispatcher) { showComposerResult(dataSource.exportSessionZip()) }
        }

        /**
         * Ungated, like the exports above: revoking a browser is a device-owner action with no
         * separate capability of its own. Refreshes the snapshot afterward so the revoked principal
         * disappears from [InspectorState.browser] immediately.
         */
        private fun revokePrincipal(id: String) {
            runCommandThenReload { dataSource.revokePrincipal(id) }
        }

        /** Ungated, like the exports above: starting/stopping is a device-owner action, not a data capability. */
        private fun setServerRunning(running: Boolean) {
            viewModelScope.launch(dispatcher) {
                if (running) {
                    // A fast double-tap while the snackbar/dialog is visible must leave one
                    // preflight in flight rather than queue duplicate permission requests.
                    if (mutableServerStartPermission.value != null) return@launch
                    val permission =
                        (dataSource as? InspectorServerStartPermissionProvider)?.serverStartPermission()
                    if (permission != null) {
                        mutableServerStartPermission.value = permission
                        return@launch
                    }
                } else {
                    mutableServerStartPermission.value = null
                }
                runServerCommand(running)
            }
        }

        /** Ungated like the capture commands: pausing only touches the SDK's own live capture gate. */
        private fun setCapturePaused(paused: Boolean) {
            viewModelScope.launch(dispatcher) {
                showComposerResult(dataSource.setCapturePaused(paused))
                // The snapshot carries the authoritative flag, so the switch and the Observe banner
                // both reflect what the runtime actually applied rather than the requested value.
                loadSnapshot()
            }
        }

        /** Ungated: purging empties the SDK's own capture buffers and old unpinned runs only. */
        private fun purgeCapturedData() {
            viewModelScope.launch(dispatcher) {
                showComposerResult(dataSource.purgeCapturedData())
                mutableState.update { it.copy(selectedTransactionIds = emptySet()) }
                loadSnapshot()
            }
        }

        /** Ungated, like the capture commands: sending only writes to a connection the adapter offered. */
        private fun sendSocketFrame(
            connectionId: String,
            text: String,
            topic: String?,
            qos: Int,
            retained: Boolean,
        ) {
            runCommandThenReload { dataSource.sendSocketFrame(connectionId, text, topic, qos, retained) }
        }

        /** Ungated: an override only touches the SDK's own local Remote Config layer. */
        private fun setRemoteConfigOverride(
            providerId: String,
            key: String,
            value: String,
        ) {
            runCommandThenReload { dataSource.setRemoteConfigOverride(providerId, key, value) }
        }

        /** Ungated counterpart of [setRemoteConfigOverride]; the provider's own value returns. */
        private fun clearRemoteConfigOverride(
            providerId: String,
            key: String,
        ) {
            runCommandThenReload { dataSource.clearRemoteConfigOverride(providerId, key) }
        }

        /** Ungated counterpart of [setRemoteConfigOverride], clearing every provider's overrides. */
        private fun clearAllRemoteConfigOverrides() {
            runCommandThenReload { dataSource.clearAllRemoteConfigOverrides() }
        }

        /**
         * Continues a preflighted start. Local-network access is required for the requested LAN
         * bind, whereas notification permission only affects foreground-notification visibility;
         * declining the latter must not make the Start button a dead end.
         */
        private fun serverStartPermissionResult(granted: Boolean) {
            val permission = mutableServerStartPermission.value ?: return
            mutableServerStartPermission.value = null
            if (!granted && permission != NOTIFICATION_PERMISSION) {
                // Keep the runtime stopped and refresh the explanatory health/browser state. The
                // permission prompt owns the separate Settings snackbar for a blocked denial.
                refreshSnapshot()
                return
            }
            // Do not ask the adapter to preflight again: the permission result is authoritative
            // for this Start attempt, and a notification denial is explicitly allowed through.
            viewModelScope.launch(dispatcher) { runServerCommand(running = true) }
        }

        /** Performs the adapter call after any required permission preflight has completed. */
        private suspend fun runServerCommand(running: Boolean) {
            showComposerResult(dataSource.setServerRunning(running))
            loadSnapshot()
        }

        /**
         * The More screen's screenshot capture button. Ungated here, like the exports above: every
         * [ScreenshotResult] variant (including [ScreenshotResult.Disabled]) is a legitimate, always-
         * dispatchable outcome the adapter/host decide, not something this ViewModel pre-checks --
         * unlike [InspectorState.capabilities], there is no client-known "screenshot capability" to
         * gate on before even asking. A successful capture refreshes the snapshot afterward so a
         * screenshot's mirrored timeline event shows up on the Logs tab without a manual pull.
         */
        private fun captureScreenshot() {
            viewModelScope.launch(dispatcher) {
                val result = dataSource.captureScreenshot()
                mutableState.update { it.copy(lastScreenshotResult = result) }
                if (result is ScreenshotResult.Captured) loadSnapshot()
            }
        }

        private fun dismissScreenshotResult() {
            mutableState.update { it.copy(lastScreenshotResult = null) }
        }

        /** Runs one data-source command off the main thread, flashes its result, then reloads the snapshot. */
        private fun runCommandThenReload(command: suspend () -> InspectorCommandResult) {
            viewModelScope.launch(dispatcher) {
                showComposerResult(command())
                loadSnapshot()
            }
        }

        /**
         * A successful export's [InspectorCommandResult.Success.sharePath] also opens the system
         * Share sheet, exactly like a Files-screen [InspectorAction.ShareFile] would -- see the
         * `LaunchedEffect` in `DevConsoleWorkspace`'s `WorkspaceContent`, which is what actually
         * consumes [InspectorState.pendingShareFilePath].
         */

        private fun showComposerResult(result: InspectorCommandResult) {
            val sharePath = (result as? InspectorCommandResult.Success)?.sharePath
            mutableState.update {
                it.copy(lastCommandResult = result, pendingShareFilePath = sharePath ?: it.pendingShareFilePath)
            }
        }

        private fun dismissCommandResult() {
            mutableState.update { it.copy(lastCommandResult = null) }
        }
    }

private fun InspectorState.withSnapshot(
    snapshot: InspectorSnapshot,
    boundedTransactions: List<InspectorTransactionUi>,
    dataSource: InspectorDataSource,
    sendProtocols: Map<String, String>,
): InspectorState {
    val current = this
    // While a past run is selected, its retained captures (loaded by selectSession) stay on
    // screen: a periodic or tab-switch refresh must not snap them back to the live run.
    val pastRunSelected =
        current.selectedSessionId != null && snapshot.sessions.any { it.id == current.selectedSessionId }
    val shownTransactions = if (pastRunSelected) current.transactions else boundedTransactions
    return copy(
        available = snapshot.available,
        serverControlSupported = dataSource.supportsServerControl(),
        captureControlSupported = dataSource.supportsCaptureControl(),
        // Re-read on every snapshot refresh, so a pause toggled on the More screen (or from
        // the dashboard) reaches the Observe banner on its next poll without new plumbing.
        capturePaused = dataSource.isCapturePaused(),
        transactions = shownTransactions,
        // selectedTransactionIds backs the Observe traffic tab's selection-mode UI and
        // InspectorAction.ExportHar/ExportPostman's scope; prune whatever aged out of the
        // bounded page (capacity eviction), same as every other list below.
        selectedTransactionIds =
            current.selectedTransactionIds.filterTo(mutableSetOf()) { id ->
                shownTransactions.any { it.id == id }
            },
        capabilities = snapshot.capabilities,
        keepAlivePromptNeeded = snapshot.keepAlivePromptNeeded,
        mocksEnabled = snapshot.mocksEnabled,
        mockRules = snapshot.mockRules,
        captureRules = snapshot.captureRules,
        sockets = if (pastRunSelected) current.sockets else snapshot.sockets.take(MAX_VISIBLE_TRANSACTIONS),
        // A retained run's connections are long closed; only the live run can be written to.
        socketSendProtocols = if (pastRunSelected) emptyMap() else sendProtocols,
        pushEvents =
            if (pastRunSelected) current.pushEvents else snapshot.pushEvents.take(MAX_VISIBLE_TRANSACTIONS),
        featureFlags = snapshot.featureFlags,
        stateProviders = snapshot.stateProviders,
        remoteConfig = snapshot.remoteConfig,
        remoteConfigOverridesEditable = dataSource.supportsRemoteConfigOverrides(),
        locationPingStreams = snapshot.locationPingStreams,
        preferenceFiles = snapshot.preferenceFiles,
        fileRoots = snapshot.fileRoots,
        databases = snapshot.databases,
        sessions = snapshot.sessions,
        selectedSessionId =
            current.selectedSessionId?.takeIf { id ->
                snapshot.sessions.any { it.id == id }
            },
        // Same past-run guard; see selectSession for why the Crashes banner depends on it.
        logs = if (pastRunSelected) current.logs else snapshot.logs.take(MAX_VISIBLE_TRANSACTIONS),
        crashes = if (pastRunSelected) current.crashes else snapshot.crashes.take(MAX_VISIBLE_TRANSACTIONS),
        health = snapshot.health,
        browser = snapshot.browser,
        retention = snapshot.retention,
        captureCategories = snapshot.captureCategories,
        redactionEnabled = snapshot.redactionEnabled,
    )
}
