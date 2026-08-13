/**
 * @author Shakib
 * @since 24/07/26
 */
package io.devconsole.ui.compose

import io.devconsole.api.CaptureCategory
import io.devconsole.api.ScreenshotResult

/** Sub-surfaces of the Observe workspace: live traffic plus the other captured signal sources. */
enum class ObserveTab { TRAFFIC, SOCKETS, PUSH, LOGS, CRASHES }

/**
 * Presentation state for the Observe and Control surfaces. Both surfaces read the same
 * [InspectorViewModel] since they are two views over one polled snapshot.
 */
data class InspectorState(
    val available: Boolean = false,
    val serverControlSupported: Boolean = false,
    val transactions: List<InspectorTransactionUi> = emptyList(),
    val capabilities: InspectorEditingUi = InspectorEditingUi(),
    val mocksEnabled: Boolean = false,
    val mockRules: List<InspectorMockRuleUi> = emptyList(),
    val captureRules: List<InspectorCaptureRuleUi> = emptyList(),
    val lastCommandResult: InspectorCommandResult? = null,
    val sockets: List<InspectorSocketUi> = emptyList(),
    val pushEvents: List<InspectorPushUi> = emptyList(),
    val logs: List<InspectorLogUi> = emptyList(),
    val crashes: List<InspectorCrashUi> = emptyList(),
    /** Evidence-tray flag state read through [InspectorDataSource.flaggedTransactionIds]; see its own doc. */
    val flaggedTransactionIds: Set<String> = emptySet(),
    /** Evidence-tray flag state read through [InspectorDataSource.flaggedCrashIds]; see its own doc. */
    val flaggedCrashIds: Set<String> = emptySet(),
    val observeTab: ObserveTab = ObserveTab.TRAFFIC,
    val selectedTransactionIds: Set<String> = emptySet(),
    val featureFlags: List<InspectorFeatureFlagUi> = emptyList(),
    val stateProviders: List<InspectorStateProviderUi> = emptyList(),
    /** Remote Config providers, already redacted and STATE-gated by the data source. */
    val remoteConfig: List<InspectorRemoteConfigUi> = emptyList(),
    val preferenceFiles: List<InspectorPreferenceFileUi> = emptyList(),
    val fileRoots: List<String> = emptyList(),
    val fileListing: InspectorFileListingUi? = null,
    val filePreview: InspectorFilePreviewUi? = null,
    /** One-shot: a resolved path awaiting the Share sheet; consumed by [InspectorAction.ConsumeShareFile]. */
    val pendingShareFilePath: String? = null,
    val databases: List<String> = emptyList(),
    val databaseListing: InspectorDatabaseListingUi? = null,
    val queryResult: InspectorQueryResultUi? = null,
    val sqlResult: InspectorSqlResultUi? = null,
    val sessions: List<InspectorSessionUi> = emptyList(),
    val selectedSessionId: String? = null,
    val sessionLogsLoading: Boolean = false,
    val health: InspectorHealthUi? = null,
    val browser: InspectorBrowserUi? = null,
    val retention: InspectorRetentionUi? = null,
    /** One-shot: the More screen's last screenshot capture attempt, consumed into a flash message. */
    val lastScreenshotResult: ScreenshotResult? = null,
    /** Mirrors [InspectorSnapshot.keepAlivePromptNeeded]; consumed by the Control surface's snackbar. */
    val keepAlivePromptNeeded: Boolean = false,
    /**
     * Mirrors [InspectorSnapshot.captureCategories] -- the set of capture categories the host
     * enabled at `DevConsole.initialize()`. [InspectorDataSource] already empties out the lists a
     * disabled category would otherwise populate (e.g. an empty [sockets] when neither SOCKET nor
     * MQTT is enabled), but that alone only produces an *empty state*, not a *hidden surface* -- the
     * helpers below (`captures`, [visibleObserveTabs], [visibleDestinations]) are the one place that
     * turns "this category is off" into "this tab/destination does not exist right now", so every
     * caller (tab row, bottom nav, section gating) reads the same gate instead of re-deriving it.
     */
    val captureCategories: Set<CaptureCategory> = CaptureCategory.all(),
)

/** True when [category] is enabled for this session; the one-line building block every gate below composes. */
internal fun InspectorState.captures(category: CaptureCategory): Boolean = category in captureCategories

/**
 * Which [ObserveTab]s should be shown given [InspectorState.captureCategories]. SOCKETS covers both
 * plain WebSocket and MQTT capture -- either one enabled is enough to keep the tab (its contents,
 * [InspectorSocketUi.protocol], already distinguish the two per-row). Order matches [ObserveTab]'s
 * declaration order, which is also the tab row's display order.
 */
internal fun InspectorState.visibleObserveTabs(): List<ObserveTab> =
    ObserveTab.entries.filter { tab ->
        when (tab) {
            ObserveTab.TRAFFIC -> captures(CaptureCategory.NETWORK)
            ObserveTab.SOCKETS -> captures(CaptureCategory.SOCKET) || captures(CaptureCategory.MQTT)
            ObserveTab.PUSH -> captures(CaptureCategory.PUSH)
            ObserveTab.LOGS -> captures(CaptureCategory.LOGS)
            ObserveTab.CRASHES -> captures(CaptureCategory.CRASHES)
        }
    }

/**
 * Which top-level [InspectorDestination]s should be shown given [InspectorState.captureCategories].
 * OBSERVE follows its own tabs (hidden only once every one of them is hidden); DATA follows
 * INSPECTION (preferences/database/files); CONTROL and MORE are never hidden by category -- CONTROL
 * hosts server/session controls that exist independent of any one capture category (its mock-rule and
 * feature-flag *sections* are gated individually, see [InspectorControlScreen]), and MORE is the
 * always-available fallback destination this list must never be empty of.
 */
internal fun InspectorState.visibleDestinations(): List<InspectorDestination> =
    InspectorDestination.entries.filter { destination ->
        when (destination) {
            InspectorDestination.OBSERVE -> visibleObserveTabs().isNotEmpty()
            InspectorDestination.DATA -> captures(CaptureCategory.INSPECTION)
            InspectorDestination.CONTROL -> true
            InspectorDestination.MORE -> true
        }
    }
