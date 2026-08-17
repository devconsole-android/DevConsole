/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "TooManyFunctions")

package io.devconsole.ui.compose

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

/** Which capture the full-screen detail overlay is currently showing, and how to find it in [InspectorState]. */
internal sealed interface ObserveDetailTarget {
    data class Net(
        val transactionId: String,
    ) : ObserveDetailTarget

    /** Frames have no server id; `(socket id, timestamp)` is stable across snapshot refreshes,
     * unlike a position in the newest-first flattened list, which shifts on every new frame. */
    data class Frame(
        val socketId: String,
        val timestampEpochMs: Long,
    ) : ObserveDetailTarget

    /** Push events have no server id; keyed on the tuple that identifies one delivery. */
    data class Push(
        val provider: String,
        val messageId: String?,
        val receivedAtEpochMs: Long,
    ) : ObserveDetailTarget

    data class Log(
        val logId: String,
    ) : ObserveDetailTarget

    data class Crash(
        val crashId: String,
    ) : ObserveDetailTarget

    /** `(provider, key)` rather than a position: the tab's search box re-filters the list under it. */
    data class RemoteConfigKey(
        val providerId: String,
        val key: String,
    ) : ObserveDetailTarget
}

/**
 * Local, UI-only Observe state that lives outside [InspectorState] entirely -- log bookmarks,
 * per-tab search text, the open detail target and the local dark/light toggle are all presentation
 * state the SDK's data source never needs to know about.
 * [flaggedTransactionIds] and [flaggedCrashIds] are the one exception: they mirror
 * [InspectorState.flaggedTransactionIds]/[InspectorState.flaggedCrashIds] (durable via the
 * `EvidenceStore`) rather than holding their own local copy, so [ObserveRoute] threads the
 * ViewModel's values straight through [ObserveRouteState.toUiState] instead of tracking them itself.
 */
internal data class ObserveUiState(
    val appIdentity: String = "",
    val trafficSearch: String = "",
    val trafficChip: String = "all",
    val socketsSearch: String = "",
    val pushSearch: String = "",
    val logsSearch: String = "",
    val crashesSearch: String = "",
    val remoteConfigSearch: String = "",
    val flaggedTransactionIds: Set<String> = emptySet(),
    val bookmarkedLogIds: Set<String> = emptySet(),
    val flaggedCrashIds: Set<String> = emptySet(),
    val detailTarget: ObserveDetailTarget? = null,
    /** Non-null while the net detail's "Mock this response" action has a create sheet open. */
    val mockDraft: MockRuleEditorTarget.New? = null,
    /** Hero collapse is hoisted per tab, independent of every other tab's. */
    val trafficHeroCollapsed: Boolean = true,
    val socketsHeroCollapsed: Boolean = true,
    val pushHeroCollapsed: Boolean = true,
    val logsHeroCollapsed: Boolean = true,
    val crashesHeroCollapsed: Boolean = true,
)

// One callback per distinct Observe interaction; bundling further would just hide the count.
@Suppress("LongParameterList")
internal data class ObserveActions(
    val onSelectTab: (ObserveTab) -> Unit,
    val onToggleTheme: () -> Unit,
    val onTrafficSearchChange: (String) -> Unit,
    val onTrafficChipClick: (String) -> Unit,
    val onSocketsSearchChange: (String) -> Unit,
    val onPushSearchChange: (String) -> Unit,
    val onLogsSearchChange: (String) -> Unit,
    val onOpenNetDetail: (String) -> Unit,
    val onOpenFrameDetail: (InspectorSocketUi, InspectorSocketFrameUi) -> Unit,
    val onOpenPushDetail: (InspectorPushUi) -> Unit,
    val onOpenLogDetail: (String) -> Unit,
    val onCrashesSearchChange: (String) -> Unit,
    val onOpenCrashDetail: (String) -> Unit,
    val onRemoteConfigSearchChange: (String) -> Unit,
    val onOpenRemoteConfigDetail: (String, String) -> Unit,
    val onToggleCrashFlag: (String) -> Unit,
    val onToggleCrashesHero: () -> Unit,
    /** The previous-run-crashed banner's action: opens that session's crash on the Crashes tab. */
    val onViewPreviousCrash: (String) -> Unit,
    val onCloseDetail: () -> Unit,
    /** Opens the mock-rule create sheet prefilled from a captured transaction. */
    val onMockFromTransaction: (InspectorTransactionUi) -> Unit,
    /** Disables the mock rule serving an already-mocked transaction. */
    val onUnmockTransaction: (String) -> Unit,
    val onSaveMockDraft: (InspectorMockRuleUi) -> Unit,
    val onCancelMockDraft: () -> Unit,
    val onToggleFlag: (String) -> Unit,
    val onToggleBookmark: (String) -> Unit,
    val onOpenEvidenceTray: () -> Unit,
    /** Traffic tab selection-mode row toggle: long-press enters the mode, tap toggles thereafter. */
    val onToggleTransactionSelection: (String) -> Unit,
    /** "Select all matching filter": unions every id in the tab's current search+chip filter into the selection. */
    val onSelectAllFilteredTransactions: (Set<String>) -> Unit,
    /** Explicit close or back-press out of selection mode. */
    val onClearTransactionSelection: () -> Unit,
    /** Exports [InspectorState.selectedTransactionIds] (or everything, if empty) as a HAR file. */
    val onExportHar: () -> Unit,
    /** Same scope as [onExportHar], as a Postman collection instead. */
    val onExportPostman: () -> Unit,
    val copyText: (String) -> Unit,
    val shareText: (String, String) -> Unit,
    val showMessage: (String) -> Unit,
    val onToggleTrafficHero: () -> Unit,
    val onToggleSocketsHero: () -> Unit,
    val onTogglePushHero: () -> Unit,
    val onToggleLogsHero: () -> Unit,
)

/**
 * Every piece of [ObserveUiState] that [ObserveRoute] owns as mutable Compose state, bundled into
 * one class (rather than a dozen separate `remember`s) so [rememberObserveActions] and
 * [ObserveRouteState.toUiState] can both take just one parameter. The per-tab search strings, the
 * chip filter and the hero-collapse flags are all plain strings/booleans, so -- like [mockDraft],
 * backed by the caller-supplied, [MockRuleEditorNewSaver]-saved [mockDraftState] -- they're backed
 * by caller-supplied `rememberSaveable`-backed [MutableState]s too, so search text and hero-collapse
 * survive a configuration change such as rotation or a multi-window resize instead of resetting: a
 * config change is not process death, so there's no reason for these to behave as if it were.
 * [detailTarget] and [bookmarkedIds] stay plain `mutableStateOf`: transaction/log ids aren't
 * Bundle-friendly without a custom Saver (see [MockRuleEditorNewSaver] for what that would take), and
 * losing an open detail overlay or the bookmark set across a rotation is a narrower, acceptable
 * tradeoff for this debug-only inspector surface.
 */
@Suppress("LongParameterList") // One rememberSaveable-backed MutableState per saved field; see doc above.
private class ObserveRouteState(
    mockDraftState: MutableState<MockRuleEditorTarget.New?>,
    trafficSearchState: MutableState<String>,
    trafficChipState: MutableState<String>,
    socketsSearchState: MutableState<String>,
    pushSearchState: MutableState<String>,
    logsSearchState: MutableState<String>,
    crashesSearchState: MutableState<String>,
    remoteConfigSearchState: MutableState<String>,
    trafficHeroCollapsedState: MutableState<Boolean>,
    socketsHeroCollapsedState: MutableState<Boolean>,
    pushHeroCollapsedState: MutableState<Boolean>,
    logsHeroCollapsedState: MutableState<Boolean>,
    crashesHeroCollapsedState: MutableState<Boolean>,
) {
    var trafficSearch by trafficSearchState
    var trafficChip by trafficChipState
    var socketsSearch by socketsSearchState
    var pushSearch by pushSearchState
    var logsSearch by logsSearchState
    var crashesSearch by crashesSearchState
    var remoteConfigSearch by remoteConfigSearchState
    var bookmarkedIds by mutableStateOf(emptySet<String>())
    var detailTarget by mutableStateOf<ObserveDetailTarget?>(null)
    var mockDraft by mockDraftState

    // Collapsed by default on every tab.
    var trafficHeroCollapsed by trafficHeroCollapsedState
    var socketsHeroCollapsed by socketsHeroCollapsedState
    var pushHeroCollapsed by pushHeroCollapsedState
    var logsHeroCollapsed by logsHeroCollapsedState
    var crashesHeroCollapsed by crashesHeroCollapsedState
}

/**
 * Builds [ObserveRouteState] plus the `rememberSaveable`-backed [MutableState]s it wraps for every
 * saved field -- split out from [ObserveRoute] itself so that function stays within detekt's
 * LongMethod budget now that rotation-survival needs one `rememberSaveable` call per saved field.
 */
@Composable
private fun rememberObserveRouteState(mockDraftState: MutableState<MockRuleEditorTarget.New?>): ObserveRouteState {
    // Per-tab search text and the traffic chip filter -- rememberSaveable so they survive rotation.
    val trafficSearchState = rememberSaveable { mutableStateOf("") }
    val trafficChipState = rememberSaveable { mutableStateOf("all") }
    val socketsSearchState = rememberSaveable { mutableStateOf("") }
    val pushSearchState = rememberSaveable { mutableStateOf("") }
    val logsSearchState = rememberSaveable { mutableStateOf("") }
    val crashesSearchState = rememberSaveable { mutableStateOf("") }
    val remoteConfigSearchState = rememberSaveable { mutableStateOf("") }
    // Collapsed by default on every tab.
    val trafficHeroCollapsedState = rememberSaveable { mutableStateOf(true) }
    val socketsHeroCollapsedState = rememberSaveable { mutableStateOf(true) }
    val pushHeroCollapsedState = rememberSaveable { mutableStateOf(true) }
    val logsHeroCollapsedState = rememberSaveable { mutableStateOf(true) }
    val crashesHeroCollapsedState = rememberSaveable { mutableStateOf(true) }
    return remember {
        ObserveRouteState(
            mockDraftState = mockDraftState,
            trafficSearchState = trafficSearchState,
            trafficChipState = trafficChipState,
            socketsSearchState = socketsSearchState,
            pushSearchState = pushSearchState,
            logsSearchState = logsSearchState,
            crashesSearchState = crashesSearchState,
            remoteConfigSearchState = remoteConfigSearchState,
            trafficHeroCollapsedState = trafficHeroCollapsedState,
            socketsHeroCollapsedState = socketsHeroCollapsedState,
            pushHeroCollapsedState = pushHeroCollapsedState,
            logsHeroCollapsedState = logsHeroCollapsedState,
            crashesHeroCollapsedState = crashesHeroCollapsedState,
        )
    }
}

private fun ObserveRouteState.toUiState(
    appIdentity: String,
    flaggedTransactionIds: Set<String>,
    flaggedCrashIds: Set<String>,
) = ObserveUiState(
    appIdentity = appIdentity,
    trafficSearch = trafficSearch,
    trafficChip = trafficChip,
    socketsSearch = socketsSearch,
    pushSearch = pushSearch,
    logsSearch = logsSearch,
    crashesSearch = crashesSearch,
    remoteConfigSearch = remoteConfigSearch,
    flaggedTransactionIds = flaggedTransactionIds,
    bookmarkedLogIds = bookmarkedIds,
    flaggedCrashIds = flaggedCrashIds,
    detailTarget = detailTarget,
    mockDraft = mockDraft,
    trafficHeroCollapsed = trafficHeroCollapsed,
    socketsHeroCollapsed = socketsHeroCollapsed,
    pushHeroCollapsed = pushHeroCollapsed,
    logsHeroCollapsed = logsHeroCollapsed,
    crashesHeroCollapsed = crashesHeroCollapsed,
)

/**
 * Stateful entry point wired into the OBSERVE destination. Theme is owned by [DevConsoleWorkspace],
 * hoisted so every destination themes together; [onDetailOverlayOpen] reports whether the
 * full-screen capture detail is showing so the workspace can hide its bottom nav while it covers
 * the screen.
 */
@Composable
internal fun ObserveRoute(
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier,
    onDetailOverlayOpen: (Boolean) -> Unit = {},
    viewModel: InspectorViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message -> scope.launch { snackbarHostState.showSnackbar(message) } }

    val mockDraftState =
        rememberSaveable(stateSaver = MockRuleEditorNewSaver) { mutableStateOf<MockRuleEditorTarget.New?>(null) }
    val routeState = rememberObserveRouteState(mockDraftState)
    val ui = routeState.toUiState(rememberAppIdentitySubtitle(), state.flaggedTransactionIds, state.flaggedCrashIds)
    val actions =
        rememberObserveActions(
            viewModel,
            routeState,
            context,
            clipboard,
            showMessage,
            onToggleTheme,
            state.mockRules.mapTo(mutableSetOf()) { it.id },
            state.flaggedTransactionIds,
        )
    // Live tail: re-read the capture store whenever Observe (re)enters composition; tab switches
    // refresh again via the ViewModel (see selectObserveTab).
    LaunchedEffect(Unit) { viewModel.dispatch(InspectorAction.Refresh) }
    LaunchedEffect(ui.detailTarget, ui.mockDraft) {
        onDetailOverlayOpen(ui.detailTarget != null || ui.mockDraft != null)
    }
    // Same toast+dismiss consumer ControlRoute/MoreRoute already have -- previously missing here
    // entirely, so a mock-rule save's rejection (or the mocks capability being off) vanished
    // silently and the stale result leaked its toast onto whichever screen the operator opened next.
    LaunchedEffect(state.lastCommandResult) {
        state.lastCommandResult?.let { result ->
            showMessage(result.toFlashMessage())
            if (routeState.mockDraft != null && result is InspectorCommandResult.Success) {
                routeState.mockDraft = null
            }
            viewModel.dispatch(InspectorAction.DismissCommandResult)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        ObserveScreen(state = state, ui = ui, actions = actions, modifier = Modifier.fillMaxSize())
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

/**
 * Builds every [ObserveActions] callback against [routeState] and the platform hooks ObserveRoute
 * owns. Bundles exactly the inputs ObserveActions' ~23 callbacks are built from; see its own
 * suppression.
 */
@Suppress("LongParameterList")
@Composable
private fun rememberObserveActions(
    viewModel: InspectorViewModel,
    routeState: ObserveRouteState,
    context: Context,
    clipboard: ClipboardManager,
    showMessage: (String) -> Unit,
    onToggleTheme: () -> Unit,
    existingMockRuleIds: Set<String>,
    flaggedTransactionIds: Set<String>,
): ObserveActions =
    ObserveActions(
        onSelectTab = { tab -> viewModel.dispatch(InspectorAction.SelectObserveTab(tab)) },
        onToggleTheme = onToggleTheme,
        onTrafficSearchChange = { routeState.trafficSearch = it },
        onTrafficChipClick = { routeState.trafficChip = it },
        onSocketsSearchChange = { routeState.socketsSearch = it },
        onPushSearchChange = { routeState.pushSearch = it },
        onLogsSearchChange = { routeState.logsSearch = it },
        onOpenNetDetail = { id -> routeState.detailTarget = ObserveDetailTarget.Net(id) },
        onOpenFrameDetail = { socket, frame ->
            routeState.detailTarget = ObserveDetailTarget.Frame(socket.id, frame.timestampEpochMs)
        },
        onOpenPushDetail = { push ->
            routeState.detailTarget = ObserveDetailTarget.Push(push.provider, push.messageId, push.receivedAtEpochMs)
        },
        onOpenLogDetail = { id -> routeState.detailTarget = ObserveDetailTarget.Log(id) },
        onCrashesSearchChange = { routeState.crashesSearch = it },
        onOpenCrashDetail = { id -> routeState.detailTarget = ObserveDetailTarget.Crash(id) },
        onRemoteConfigSearchChange = { routeState.remoteConfigSearch = it },
        onOpenRemoteConfigDetail = { providerId, key ->
            routeState.detailTarget = ObserveDetailTarget.RemoteConfigKey(providerId, key)
        },
        // Flag/unflag now go through the durable EvidenceStore via the ViewModel; the resulting
        // toast comes from InspectorState.lastCommandResult (see ObserveRoute's own LaunchedEffect),
        // not a locally-composed message here.
        onToggleCrashFlag = { id -> viewModel.dispatch(InspectorAction.ToggleCrashFlag(id)) },
        onToggleCrashesHero = { routeState.crashesHeroCollapsed = !routeState.crashesHeroCollapsed },
        onViewPreviousCrash = { sessionId -> viewPreviousCrash(viewModel, routeState, sessionId) },
        onCloseDetail = { routeState.detailTarget = null },
        onMockFromTransaction = { transaction ->
            routeState.mockDraft = mockRuleDraftFromTransaction(transaction, existingMockRuleIds)
        },
        // Gating + the success/blocked toast are handled generically by mutateMockRules/
        // lastCommandResult (same path SetMockRuleEnabled already uses from the Control screen).
        onUnmockTransaction = { ruleId -> viewModel.dispatch(InspectorAction.SetMockRuleEnabled(ruleId, false)) },
        // Closing on Success is handled by the lastCommandResult effect in ObserveRoute, not here --
        // see the identical reasoning on ControlRoute's onSave.
        onSaveMockDraft = { rule -> viewModel.dispatch(InspectorAction.UpsertMockRule(rule)) },
        onCancelMockDraft = { routeState.mockDraft = null },
        onToggleFlag = { id -> viewModel.dispatch(InspectorAction.ToggleTransactionFlag(id)) },
        onToggleBookmark = { id -> toggleBookmark(routeState, id, showMessage) },
        onOpenEvidenceTray = { announceEvidenceTray(flaggedTransactionIds, showMessage) },
        onToggleTransactionSelection = { id -> viewModel.dispatch(InspectorAction.ToggleTransactionSelection(id)) },
        onSelectAllFilteredTransactions = { ids -> viewModel.dispatch(InspectorAction.SelectTransactions(ids)) },
        onClearTransactionSelection = { viewModel.dispatch(InspectorAction.ClearTransactionSelection) },
        onExportHar = { viewModel.dispatch(InspectorAction.ExportHar) },
        onExportPostman = { viewModel.dispatch(InspectorAction.ExportPostman) },
        copyText = { text ->
            clipboard.setText(AnnotatedString(text))
            showMessage("Copied to clipboard")
        },
        shareText = { text, title -> shareTextSnippet(context, text, title) },
        showMessage = showMessage,
        onToggleTrafficHero = { routeState.trafficHeroCollapsed = !routeState.trafficHeroCollapsed },
        onToggleSocketsHero = { routeState.socketsHeroCollapsed = !routeState.socketsHeroCollapsed },
        onTogglePushHero = { routeState.pushHeroCollapsed = !routeState.pushHeroCollapsed },
        onToggleLogsHero = { routeState.logsHeroCollapsed = !routeState.logsHeroCollapsed },
    )

private fun toggleBookmark(
    routeState: ObserveRouteState,
    id: String,
    showMessage: (String) -> Unit,
) {
    val wasBookmarked = id in routeState.bookmarkedIds
    routeState.bookmarkedIds = if (wasBookmarked) routeState.bookmarkedIds - id else routeState.bookmarkedIds + id
    showMessage(if (wasBookmarked) "Bookmark removed" else "Event bookmarked")
}

/**
 * The previous-run-crashed banner's action: selects the crashed session (loading its retained
 * crashes/logs the same way the More screen's Retained runs row does) and switches to the Crashes
 * tab so the operator lands directly on it, rather than just naming the session and leaving the
 * navigation to them.
 */
private fun viewPreviousCrash(
    viewModel: InspectorViewModel,
    routeState: ObserveRouteState,
    sessionId: String,
) {
    viewModel.dispatch(InspectorAction.SelectSession(sessionId))
    viewModel.dispatch(InspectorAction.SelectObserveTab(ObserveTab.CRASHES))
    routeState.detailTarget = null
}

private fun announceEvidenceTray(
    flaggedTransactionIds: Set<String>,
    showMessage: (String) -> Unit,
) {
    val count = flaggedTransactionIds.size
    val message =
        if (count == 0) {
            "No captures flagged yet"
        } else {
            "$count flagged ${if (count == 1) "capture" else "captures"} in the evidence tray"
        }
    showMessage(message)
}

/**
 * Reads the host app's own package name and version -- real, always-available Android platform
 * data, not anything invented -- for the top area's `pkg · version` sub-line. Falls back to just
 * the package name if PackageManager can't resolve version info for some reason.
 */
@Composable
private fun rememberAppIdentitySubtitle(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${context.packageName} · ${info.versionName}"
        }.getOrDefault(context.packageName)
    }
}

/**
 * The selected tab's content, entering along the axis the tabs themselves run on -- Material's
 * shared-axis-X. Moving right slides the incoming content in from the right; moving left reverses
 * it, so the transition agrees with the indicator travelling under the tab row and with the
 * operator's own sense of where they are in an ordered set.
 *
 * The offset is [InspectorMotion.sharedAxisOffset], not a screen width: two live capture lists are
 * composed at once for the length of this transition, and a short nudge keeps that window brief on
 * the low-end hardware this console has to stay honest on. Under reduced motion [feedbackSpec]
 * flattens the whole thing to a cut.
 *
 * Keyed on the tab itself, so each tab's content keeps being a fresh composition on switch exactly
 * as the plain `when` made it -- the transition changes how it arrives, never what it holds.
 */
@Composable
private fun ObserveTabPager(
    selected: ObserveTab,
    visibleTabs: List<ObserveTab>,
    state: InspectorState,
    ui: ObserveUiState,
    actions: ObserveActions,
) {
    // Hoisted out of transitionSpec: that lambda is neither a composable nor a density scope, so
    // the gated specs and the dp->px conversion have to be resolved here and captured.
    val slideSpec = feedbackSpec<IntOffset>()
    val fadeSpec = feedbackSpec<Float>()
    val offsetPx = with(LocalDensity.current) { InspectorMotion.sharedAxisOffset.roundToPx() }
    AnimatedContent(
        targetState = selected,
        transitionSpec = {
            val forward = visibleTabs.indexOf(targetState) >= visibleTabs.indexOf(initialState)
            val enterFrom = if (forward) 1 else -1
            (
                slideInHorizontally(slideSpec) { width -> enterFrom * minOf(width, offsetPx) } +
                    fadeIn(fadeSpec)
            ) togetherWith (
                slideOutHorizontally(slideSpec) { width -> -enterFrom * minOf(width, offsetPx) } +
                    fadeOut(fadeSpec)
            )
        },
        label = "observeTab",
    ) { tab ->
        when (tab) {
            ObserveTab.TRAFFIC -> TrafficTabContent(state, ui, actions)
            ObserveTab.SOCKETS -> SocketsTabContent(state, ui, actions)
            ObserveTab.PUSH -> PushTabContent(state, ui, actions)
            ObserveTab.LOGS -> LogsTabContent(state, ui, actions)
            ObserveTab.CRASHES -> CrashesTabContent(state, ui, actions)
            ObserveTab.REMOTE_CONFIG -> RemoteConfigTabContent(state, ui, actions)
        }
    }
}

private fun ObserveTab.title(): String =
    when (this) {
        ObserveTab.TRAFFIC -> "Traffic"
        ObserveTab.SOCKETS -> "Sockets"
        ObserveTab.PUSH -> "Push"
        ObserveTab.LOGS -> "Logs"
        ObserveTab.CRASHES -> "Crashes"
        // One word, like every other tab: the row divides its width equally, so "Remote Config"
        // would wrap or truncate on a phone.
        ObserveTab.REMOTE_CONFIG -> "Config"
    }

/**
 * Stateless, previewable Observe surface: top area, tabs, the active tab's content, and the
 * full-screen capture detail overlay when one is open. Theme is ambient [DevConsoleTheme.colors]
 * from [DevConsoleWorkspace]'s wrap, hoisted workspace-wide instead of being scoped to just this
 * screen.
 */
@Composable
internal fun ObserveScreen(
    state: InspectorState,
    ui: ObserveUiState,
    actions: ObserveActions,
    modifier: Modifier = Modifier,
) {
    val colors = DevConsoleTheme.colors
    Box(modifier = modifier.background(colors.ground)) {
        Column(Modifier.fillMaxSize()) {
            InspectorTopArea(
                subLine = ui.appIdentity,
                title = state.observeTab.title(),
                // The old search icon here only ever showed a "search opens the keyboard on device"
                // message (dead chrome) -- InspectorSearchBar below, in each tab's own list, is the
                // one real search affordance, so the top-area action is dropped rather than kept.
                actions = listOf(themeToggleTopAction(actions.onToggleTheme)),
            )
            previousCrashedSession(state.sessions)?.let { session ->
                PreviousRunCrashedBanner(session = session, onViewCrash = { actions.onViewPreviousCrash(session.id) })
            }
            // Only categories the host enabled at init get a tab at all -- see
            // InspectorState.visibleObserveTabs for the gating rule (SOCKETS covers both plain
            // WebSocket and MQTT capture).
            val visibleTabs = state.visibleObserveTabs()
            InspectorTabRow(
                tabs =
                    visibleTabs.map { tab ->
                        InspectorTab(tab.title(), tab == state.observeTab) { actions.onSelectTab(tab) }
                    },
            )
            Box(Modifier.weight(1f)) {
                when {
                    !state.available -> ObserveUnavailableNotice()
                    // Defensive: InspectorViewModel.applySnapshot already snaps observeTab back onto
                    // a visible tab the moment its category is disabled, but a stray selection this
                    // screen didn't cause (e.g. a `SelectObserveTab` dispatched before that snap runs)
                    // must render nothing rather than a hidden tab's content.
                    state.observeTab !in visibleTabs -> ObserveTabEmptyState("This section is not available.")
                    else -> ObserveTabPager(state.observeTab, visibleTabs, state, ui, actions)
                }
            }
        }
        ui.detailTarget?.let { target -> ObserveDetailOverlay(target, state, ui, actions) }
        ui.mockDraft?.let { target ->
            MockRuleEditorScreen(
                target = target,
                onSave = actions.onSaveMockDraft,
                onCancel = actions.onCancelMockDraft,
            )
        }
    }
}

/**
 * The previous-run-crashed marker: the most recent *non-active* session (i.e. not the one
 * currently capturing) whose [InspectorSessionUi.status] is `"CRASHED"` -- mirroring
 * `StoredSessionStatus.CRASHED`, which `io.devconsole.CrashCapture.markCrashed` writes on every
 * uncaught exception, and which session bootstrap writes for a run that died before that could
 * finish (`closeSessionsLeftByDeadProcesses` -- a run killed *without* crashing is closed COMPLETED
 * there and deliberately does not reach this banner). This only surfaces that existing marker;
 * nothing here creates it.
 */
private fun previousCrashedSession(sessions: List<InspectorSessionUi>): InspectorSessionUi? =
    sessions
        .filter { it.status != "ACTIVE" }
        .maxByOrNull { it.startedAtEpochMs }
        ?.takeIf { it.status == "CRASHED" }

/**
 * Shown at the top of every Observe tab -- not just Crashes -- so it is visible the moment the
 * app launches into its default OBSERVE/TRAFFIC landing screen, rather than requiring the operator to
 * already know to check the Crashes tab. Tapping it jumps straight to that session's crash via
 * [ObserveActions.onViewPreviousCrash].
 */
@Composable
private fun PreviousRunCrashedBanner(
    session: InspectorSessionUi,
    onViewCrash: () -> Unit,
) {
    val colors = DevConsoleTheme.colors
    TonalListRow(
        leadText = "CRA",
        leadColor = colors.error,
        leadContainerColor = colors.errorSoft,
        title = "Previous run crashed",
        subtitle = "${session.label} · ${formatCaptureClockTime(session.startedAtEpochMs)}",
        trailValue = "View",
        trailValueColor = colors.error,
        containerColor = colors.errorSoft,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp),
        onClick = onViewCrash,
    )
}

@Composable
private fun ObserveUnavailableNotice() {
    val colors = DevConsoleTheme.colors
    val message = "Inspector is not connected to a running host runtime."
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            modifier = Modifier.padding(24.dp).semantics { contentDescription = message },
            color = colors.muted,
        )
    }
}

/**
 * The open detail's mock-response diff, off the hot path: only ever recomputed (via [remember]) when
 * the relevant response body or its rule's snapshot text actually changes, not on every poll-driven
 * recomposition of [InspectorState]. Null whenever [target] isn't a net capture, that capture isn't
 * mocked, its serving rule is unknown or carries no `sourceBodySnapshot`, or either body fails to
 * parse as JSON -- [computeJsonMockDiff] itself covers that last case.
 */
@Composable
private fun rememberNetMockDiff(
    target: ObserveDetailTarget,
    state: InspectorState,
): JsonMockDiffResult? {
    val transaction =
        (target as? ObserveDetailTarget.Net)?.let { net -> state.transactions.find { it.id == net.transactionId } }
    val snapshot =
        transaction
            ?.takeIf { it.isMocked }
            ?.mockRuleId
            ?.let { ruleId -> state.mockRules.find { it.id == ruleId } }
            ?.sourceBodySnapshot
    val responseBody = transaction?.responsePreview
    return remember(snapshot, responseBody) {
        if (snapshot != null && responseBody != null) computeJsonMockDiff(snapshot, responseBody) else null
    }
}

/**
 * Resolves [target] against the still-live snapshot to a `(resetKey, content)` pair, or `null` if
 * the referenced capture disappeared from the snapshot between the tap and this recomposition (see
 * the [ObserveDetailTarget.Frame]/[ObserveDetailTarget.Push] docs for why that can happen).
 * A single `when` expression -- no early returns -- so this stays within detekt's ReturnCount
 * budget without hiding four real "not found" cases behind one shared message.
 *
 * One real input per capture kind this can resolve; see above. LongMethod: the Unmock-vs-Mock
 * branch pushed the Net case a couple lines past the budget.
 * CyclomaticComplexMethod: the Crash branch added a fifth capture kind on top of that.
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
private fun resolveObserveDetail(
    target: ObserveDetailTarget,
    state: InspectorState,
    ui: ObserveUiState,
    actions: ObserveActions,
    colors: DevConsoleColors,
    recentFrames: List<Pair<InspectorSocketUi, InspectorSocketFrameUi>>,
    netMockDiff: JsonMockDiffResult?,
): Pair<Any, ObserveDetailContent>? =
    when (target) {
        is ObserveDetailTarget.Net ->
            state.transactions.find { it.id == target.transactionId }?.let { transaction ->
                val content =
                    netDetailContent(
                        transaction = transaction,
                        colors = colors,
                        isFlagged = target.transactionId in ui.flaggedTransactionIds,
                        onToggleFlag = { actions.onToggleFlag(target.transactionId) },
                        copyText = actions.copyText,
                        shareText = actions.shareText,
                        // Same "blocked" message pattern pushFooterActions uses for canReplay --
                        // never open a sheet whose Save can only end in a Disabled toast.
                        onMockThisResponse = {
                            if (state.capabilities.mocks) {
                                actions.onMockFromTransaction(transaction)
                            } else {
                                actions.showMessage("Blocked — mocks off")
                            }
                        },
                        onUnmockThisResponse = actions.onUnmockTransaction,
                        mockDiff = netMockDiff,
                    )
                target.transactionId to content
            }
        is ObserveDetailTarget.Frame ->
            recentFrames
                .firstOrNull { (socket, frame) ->
                    socket.id == target.socketId && frame.timestampEpochMs == target.timestampEpochMs
                }?.let { (socket, frame) ->
                    val content = frameDetailContent(socket, frame, colors, actions.copyText, actions.shareText)
                    "${target.socketId}@${target.timestampEpochMs}" to content
                }
        is ObserveDetailTarget.Push ->
            state.pushEvents
                .firstOrNull { push ->
                    push.provider == target.provider &&
                        push.messageId == target.messageId &&
                        push.receivedAtEpochMs == target.receivedAtEpochMs
                }?.let { push ->
                    val content =
                        pushDetailContent(
                            push = push,
                            colors = colors,
                            copyText = actions.copyText,
                            shareText = actions.shareText,
                        )
                    "${target.provider}@${target.receivedAtEpochMs}" to content
                }
        is ObserveDetailTarget.Log ->
            state.logs.find { it.id == target.logId }?.let { log ->
                val content =
                    logDetailContent(
                        log = log,
                        colors = colors,
                        isBookmarked = target.logId in ui.bookmarkedLogIds,
                        onToggleBookmark = { actions.onToggleBookmark(target.logId) },
                        copyText = actions.copyText,
                        shareText = actions.shareText,
                    )
                target.logId to content
            }
        is ObserveDetailTarget.Crash ->
            state.crashes.find { it.id == target.crashId }?.let { crash ->
                val content =
                    crashDetailContent(
                        crash = crash,
                        colors = colors,
                        isFlagged = target.crashId in ui.flaggedCrashIds,
                        onToggleFlag = { actions.onToggleCrashFlag(target.crashId) },
                        copyText = actions.copyText,
                        shareText = actions.shareText,
                    )
                target.crashId to content
            }
        is ObserveDetailTarget.RemoteConfigKey ->
            state.remoteConfig
                .firstOrNull { it.id == target.providerId }
                ?.let { provider ->
                    provider.entries.firstOrNull { it.key == target.key }?.let { entry ->
                        val content =
                            remoteConfigDetailContent(
                                provider = provider,
                                entry = entry,
                                colors = colors,
                                copyText = actions.copyText,
                                shareText = actions.shareText,
                            )
                        "${target.providerId}/${target.key}" to content
                    }
                }
    }

/** Dispatches [target] to the matching `*DetailContent` builder and renders [InspectorObserveDetailScreen]. */
@Composable
private fun ObserveDetailOverlay(
    target: ObserveDetailTarget,
    state: InspectorState,
    ui: ObserveUiState,
    actions: ObserveActions,
) {
    val colors = DevConsoleTheme.colors
    val recentFrames = rememberRecentFrames(state.sockets)
    val netMockDiff = rememberNetMockDiff(target, state)
    val resolved = resolveObserveDetail(target, state, ui, actions, colors, recentFrames, netMockDiff)
    if (resolved == null) {
        // The capture aged out of the snapshot while its detail was open. Close explicitly:
        // rendering nothing while `detailTarget` stays set would drop BackHandler from the
        // composition, so system back would exit the whole console instead of the detail.
        LaunchedEffect(target) {
            actions.showMessage("This capture is no longer available")
            actions.onCloseDetail()
        }
    } else {
        val (resetKey, content) = resolved
        ObserveDetailFromContent(resetKey, content, actions.onCloseDetail)
    }
}

@Composable
private fun ObserveDetailFromContent(
    resetKey: Any,
    content: ObserveDetailContent,
    onBack: () -> Unit,
) {
    InspectorObserveDetailScreen(
        resetKey = resetKey,
        header = content.header,
        sections = content.sections,
        initiallyOpenSectionKeys = content.initiallyOpenSectionKeys,
        footerActions = content.footerActions,
        onBack = onBack,
        searchPlaceholder = content.searchPlaceholder,
        searchOptions = content.searchOptions,
    )
}

/** The Sockets tab's "Recent frames" list and the Frame detail's index target share this exact ordering. */
@Composable
internal fun rememberRecentFrames(
    sockets: List<InspectorSocketUi>,
): List<Pair<InspectorSocketUi, InspectorSocketFrameUi>> =
    remember(sockets) {
        sockets
            .flatMap { socket -> socket.frames.map { frame -> socket to frame } }
            .sortedByDescending { it.second.timestampEpochMs }
    }
