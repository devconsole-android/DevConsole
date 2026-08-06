package io.devconsole.ui.compose

import io.devconsole.api.CaptureCategory
import io.devconsole.api.ScreenshotResult

data class InspectorEditingUi(
    val requestExecution: Boolean = false,
    val mocks: Boolean = false,
    val featureFlags: Boolean = false,
    val preferences: Boolean = false,
    val files: Boolean = false,
    val database: Boolean = false,
    val captureRules: Boolean = false,
)

/** A capture exclusion as shown and edited on the Control surface. */
data class InspectorCaptureRuleUi(
    val id: String,
    val host: String,
    val method: String? = null,
    val pathPrefix: String? = null,
    val enabled: Boolean = true,
)

data class InspectorTransactionUi(
    val id: String,
    val method: String,
    val host: String,
    val path: String,
    val statusCode: Int?,
    val durationMs: Long?,
    /** Wall-clock start of the capture, epoch ms; 0 when the adapter predates this field. */
    val startedAtEpochMs: Long = 0,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val requestPreview: String? = null,
    val responsePreview: String? = null,
    val error: String? = null,
    /** Full redacted URL (scheme, host, path, query); empty when the adapter predates this field. */
    val url: String = "",
    /**
     * What [requestPreview] actually contains -- distinguishes a real (possibly truncated) text
     * body from the human-readable "[binary, N bytes]" placeholder, so replay/cURL/fetch never
     * mistake the placeholder for a sendable body.
     */
    val requestBodyKind: InspectorBodyKind = InspectorBodyKind.ABSENT,
    val requestBodyTruncated: Boolean = false,
    /** Per-phase network timing, when the underlying transport captured it; see [InspectorTimingPhasesUi]. */
    val timingPhases: InspectorTimingPhasesUi = InspectorTimingPhasesUi(),
    /** True when a mock rule answered this request instead of the real network -- from the `mocked` capture tag. */
    val isMocked: Boolean = false,
    /** The mock rule that answered this request, when [isMocked] is true; from the `mockRuleId` capture tag. */
    val mockRuleId: String? = null,
)

/**
 * Shape of a captured body preview, mirroring `io.devconsole.network.BodyPreview` without a
 * `sdk:network` dependency.
 */
enum class InspectorBodyKind { TEXT, BINARY, ABSENT }

/**
 * Mirrors `io.devconsole.network.NetworkTimingPhases` without a `sdk:network` dependency. Every
 * phase is legitimately null in normal operation -- a pooled connection does no DNS/connect, a
 * plaintext request has no TLS, a cached response does no network work -- so a null phase must
 * never be rendered as a zero-length bar; it must be omitted. All-null means no per-phase timing
 * was captured for this transaction (an adapter that predates this field, or a transport that
 * never wires up phase timing), in which case a caller should fall back to the total duration.
 */
data class InspectorTimingPhasesUi(
    val dnsMs: Long? = null,
    val connectMs: Long? = null,
    val tlsMs: Long? = null,
    val sendMs: Long? = null,
    val waitMs: Long? = null,
    val receiveMs: Long? = null,
) {
    val hasAnyPhase: Boolean
        get() =
            dnsMs != null ||
                connectMs != null ||
                tlsMs != null ||
                sendMs != null ||
                waitMs != null ||
                receiveMs != null
}

/** Coarse status bucket for the Observe traffic tab's status filter chips. */
enum class TrafficStatusClass(
    val from: Int? = null,
    val to: Int? = null,
) {
    ALL,
    SUCCESS(from = 200, to = 299),
    REDIRECT(from = 300, to = 399),
    CLIENT_ERROR(from = 400, to = 499),
    SERVER_ERROR(from = 500, to = 599),

    /** No HTTP status was ever received (timeout, connection failure, ...). */
    FAILED,
}

/**
 * Caller-supplied filter for the network-transaction page in [InspectorDataSource.snapshot]. Owned
 * by the UI layer (not [io.devconsole.network.NetworkTransactionQuery]) so this module never takes
 * a dependency on `sdk:network`; adapters translate it into their own engine query type.
 */
data class InspectorTrafficQuery(
    val search: String = "",
    val method: String? = null,
    val statusClass: TrafficStatusClass = TrafficStatusClass.ALL,
)

data class InspectorMockRuleUi(
    val id: String,
    val method: String? = null,
    val pathPattern: String = ".*",
    val actionLabel: String = "",
    val scheme: String? = null,
    val host: String? = null,
    val priority: Int = 0,
    val scope: String = "SESSION",
    val statusCode: Int = 200,
    val body: String = "",
    val enabled: Boolean = true,
    /** Response headers the rule replies with; empty for actions with no headers of their own. */
    val headers: Map<String, String> = emptyMap(),
    /** Non-null only when the rule wraps its response in a delay, mirroring `MockAction.Delay.durationMs`. */
    val delayMs: Long? = null,
    /** How many times this rule has matched a request; 0 if it never has. */
    val hitCount: Long = 0,
    /** Epoch ms of the rule's most recent match; null if it never has. */
    val lastHitEpochMs: Long? = null,
    /**
     * The original transaction's response body, captured only by [mockRuleDraftFromTransaction]'s
     * "Mock this response" flow, so the served mock can later be diffed against it. Session-only:
     * mirrors `MockRule.sourceBodySnapshot` and is never persisted to disk. Not user-editable, so
     * the create/edit form passes it through untouched rather than binding it to a field.
     */
    val sourceBodySnapshot: String? = null,
)

data class InspectorSocketFrameUi(
    val direction: String,
    val frameType: String,
    val preview: String?,
    val timestampEpochMs: Long,
    /**
     * Real payload byte size when known -- the full frame length for BINARY (`SocketPayload.Binary.length`
     * is captured before any preview truncation), or the redacted/possibly-truncated [preview]'s own
     * encoded byte size for TEXT. Null when the adapter predates this field, in which case a caller must
     * fall back to describing [preview]'s character count rather than implying a measured byte size.
     */
    val byteLength: Long? = null,
    /**
     * Whether [preview] itself was truncated before capture. For a TEXT frame this means [byteLength]
     * describes only the (possibly redacted) preview, not the original message -- callers must label
     * that case honestly (e.g. "preview") instead of presenting it as the frame's true size.
     */
    val truncated: Boolean = false,
    /** MQTT topic this frame was published/received on; null for a plain WebSocket frame. */
    val topic: String? = null,
    /** MQTT QoS (0-2) this frame was sent/received with; null for a plain WebSocket frame. */
    val qos: Int? = null,
)

data class InspectorSocketUi(
    val id: String,
    val url: String,
    val state: String,
    val sentCount: Int,
    val receivedCount: Int,
    val openedAtEpochMs: Long,
    val closedAtEpochMs: Long? = null,
    val error: String? = null,
    val frames: List<InspectorSocketFrameUi> = emptyList(),
    /** Wire name of the connection's socket protocol -- "websocket" or "mqtt". */
    val protocol: String = "websocket",
)

data class InspectorPushUi(
    val provider: String,
    val messageId: String? = null,
    val lifecycle: String,
    val simulated: Boolean,
    val receivedAtEpochMs: Long,
    val dataPreview: Map<String, String> = emptyMap(),
)

data class InspectorLogUi(
    val id: String,
    val kind: String,
    val source: String,
    val summary: String,
    val timestampEpochMs: Long,
    val detail: String? = null,
)

/**
 * One lead-up event captured in a crash/ANR's bounded breadcrumb ring buffer. Already-redacted
 * event summaries only -- no payload bodies -- mirroring the `"breadcrumbs"` array
 * [io.devconsole.CrashCapture] serializes into the crash/ANR payload.
 */
data class InspectorBreadcrumbUi(
    val timestampEpochMs: Long,
    val plugin: String,
    val type: String,
    val severity: Int,
    val summary: String,
)

/**
 * An uncaught-exception or ANR capture, flattened for the Crashes surface. [kind] is
 * "UNCAUGHT" or "ANR" -- mirroring the internal `CrashKind.name` the full runtime tags the event
 * with -- so it reuses [logLevelShortLabel]/[logLevelTint] exactly like the Logs tab's crash rows
 * already do. [stackTrace] is the bounded, explicitly-truncation-marked all-thread dump; an ANR
 * carries every thread, main first, an uncaught exception carries the throwing thread's trace.
 */
data class InspectorCrashUi(
    val id: String,
    val kind: String,
    val summary: String,
    val thread: String,
    val timestampEpochMs: Long,
    val stackTrace: String,
    val breadcrumbs: List<InspectorBreadcrumbUi> = emptyList(),
)

data class InspectorFeatureFlagUi(
    val key: String,
    val value: String,
    val defaultValue: String,
    val allowedValues: List<String>,
    val type: String,
    val mutable: Boolean,
    val description: String,
    val isOverridden: Boolean,
)

data class InspectorStateEntryUi(
    val key: String,
    val value: String,
    val redacted: Boolean,
)

data class InspectorStateProviderUi(
    val id: String,
    val entries: List<InspectorStateEntryUi>,
)

data class InspectorPreferenceEntryUi(
    val key: String,
    val value: String,
    val type: String,
    val redacted: Boolean = false,
)

data class InspectorPreferenceFileUi(
    val name: String,
    val entries: List<InspectorPreferenceEntryUi>,
)

data class InspectorFileEntryUi(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
)

data class InspectorFileListingUi(
    val root: String,
    val relativePath: String,
    val entries: List<InspectorFileEntryUi>,
)

sealed interface InspectorFilePreviewUi {
    data class Text(
        val content: String,
        val truncated: Boolean,
    ) : InspectorFilePreviewUi

    data class Binary(
        val sizeBytes: Long,
    ) : InspectorFilePreviewUi

    data class Unavailable(
        val reason: String,
    ) : InspectorFilePreviewUi
}

data class InspectorDatabaseTableUi(
    val name: String,
    val rowCount: Long,
)

data class InspectorDatabaseListingUi(
    val name: String,
    val tables: List<InspectorDatabaseTableUi>,
    /** Real on-disk `File.length()` bytes; 0 when the adapter predates this field or cannot resolve a file. */
    val sizeBytes: Long = 0,
)

data class InspectorQueryResultUi(
    val columns: List<String>,
    val rows: List<List<String>>,
    val truncated: Boolean,
)

sealed interface InspectorSqlResultUi {
    data class Rows(
        val result: InspectorQueryResultUi,
    ) : InspectorSqlResultUi

    data class Wrote(
        val affectedRows: Int,
    ) : InspectorSqlResultUi

    data object WriteBlocked : InspectorSqlResultUi

    data class Failed(
        val message: String,
    ) : InspectorSqlResultUi
}

data class InspectorSessionUi(
    val id: String,
    val startedAtEpochMs: Long,
    val label: String,
    val status: String = "UNKNOWN",
    val recordCount: Long = 0,
    val estimatedBytes: Long = 0,
)

/** Mirrors [io.devconsole.server.api.SdkHealthSnapshot]'s counter types (all `Long`, plus `state`). */
data class InspectorHealthUi(
    val state: String,
    val initializationCount: Long,
    val publishedEventCount: Long,
    val droppedEventCount: Long,
)

/** Read-only display of the host's current browser policy; changing access is out of scope here. */
data class InspectorBrowserUi(
    val binding: String,
    val endpoint: String?,
    /** Authenticated browsers, mirroring the dashboard's Session view; see [InspectorAction.RevokePrincipal]. */
    val principals: List<InspectorBrowserPrincipalUi> = emptyList(),
    /**
     * Populated while a session code is live (unexpired). [sessionCodeUrl] is the fragment URL a
     * browser would open; [sessionCode] is the same 8-character code shown standalone for manual
     * entry. Null once the server stops or the code expires with no fallback.
     */
    val sessionCodeUrl: String? = null,
    val sessionCode: String? = null,
    val sessionCodeExpiresAtEpochMs: Long? = null,
    val sessionCodeRemainingTtlMs: Long? = null,
    /**
     * True once the device's live LAN address no longer matches [endpoint] -- e.g. a DHCP lease
     * change while the server keeps running. The server is still advertising the now-dead address;
     * the More screen surfaces this explicitly rather than silently keeping it.
     */
    val bindAddressChanged: Boolean = false,
)

/** One authenticated browser, as shown (and revocable) on the dashboard's Session view and this More surface. */
data class InspectorBrowserPrincipalUi(
    val id: String,
    val label: String,
    val sourceIp: String,
    val expiresAtEpochMs: Long,
)

/** Mirrors [io.devconsole.api.RetentionPolicy]'s configured caps, for display next to actual usage. */
data class InspectorRetentionUi(
    val maxSessions: Int,
    val maxAgeMs: Long,
    val maxBytes: Long,
)

data class InspectorSnapshot(
    val available: Boolean = false,
    val transactions: List<InspectorTransactionUi> = emptyList(),
    val capabilities: InspectorEditingUi = InspectorEditingUi(),
    val mocksEnabled: Boolean = false,
    val mockRules: List<InspectorMockRuleUi> = emptyList(),
    val captureRules: List<InspectorCaptureRuleUi> = emptyList(),
    val sockets: List<InspectorSocketUi> = emptyList(),
    val pushEvents: List<InspectorPushUi> = emptyList(),
    val logs: List<InspectorLogUi> = emptyList(),
    val crashes: List<InspectorCrashUi> = emptyList(),
    val featureFlags: List<InspectorFeatureFlagUi> = emptyList(),
    val stateProviders: List<InspectorStateProviderUi> = emptyList(),
    val preferenceFiles: List<InspectorPreferenceFileUi> = emptyList(),
    val fileRoots: List<String> = emptyList(),
    val databases: List<String> = emptyList(),
    val sessions: List<InspectorSessionUi> = emptyList(),
    val health: InspectorHealthUi? = null,
    val browser: InspectorBrowserUi? = null,
    val retention: InspectorRetentionUi? = null,
    /**
     * True when the Control surface should offer the notification-permission snackbar for the
     * keep-alive foreground service: server running, host opted into the service, host declares
     * POST_NOTIFICATIONS, grant still missing. Computed by the full adapter's KeepAliveGate;
     * defaults to false so every other adapter and fake is unaffected.
     */
    val keepAlivePromptNeeded: Boolean = false,
    /** Capture categories the host enabled at init; every list above is already filtered to this set. */
    val captureCategories: Set<CaptureCategory> = CaptureCategory.all(),
)

data class InspectorComposerRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

sealed interface InspectorCommandResult {
    /**
     * [sharePath] is set only by the export actions ([InspectorAction.ExportHar],
     * [InspectorAction.ExportPostman], [InspectorAction.ExportSessionZip]): an absolute path to the
     * artifact just written, already inside a `FileProvider`-covered sandbox root, so the ViewModel
     * can hand it straight to the same one-shot Share-sheet flow [InspectorAction.ShareFile] uses.
     */
    data class Success(
        val summary: String,
        val statusCode: Int? = null,
        val body: String? = null,
        val sharePath: String? = null,
    ) : InspectorCommandResult

    data class Disabled(
        val capability: String,
    ) : InspectorCommandResult

    data class Invalid(
        val message: String,
    ) : InspectorCommandResult

    data class Failed(
        val message: String,
    ) : InspectorCommandResult

    data object Unavailable : InspectorCommandResult
}

// One read/write method per Data-surface capability (files, database, ...); splitting further would
// fragment a single MVI boundary across multiple interfaces for no readability gain.
@Suppress("TooManyFunctions")
interface InspectorDataSource {
    fun snapshot(): InspectorSnapshot

    /**
     * Filtered variant of [snapshot] backing the Observe traffic tab's search/method/status
     * filters. Defaults to ignoring the filter and deferring to [snapshot] so every existing
     * adapter and fake keeps compiling and behaving unchanged; only adapters that want filtering
     * (see `FullInspectorDataSource`) need to override it.
     */
    fun snapshot(trafficQuery: InspectorTrafficQuery): InspectorSnapshot = snapshot()

    suspend fun logsForSession(sessionId: String?): List<InspectorLogUi> = snapshot().logs

    /**
     * Session-scoped counterpart of [logsForSession] for the Crashes tab: retained crash/ANR
     * captures for a past (non-active) session, e.g. the one the previous-run-crashed banner links
     * to. Defaults to the live snapshot's crashes so every existing adapter and fake keeps compiling
     * unchanged; only an adapter with retained-session storage (see `FullInspectorDataSource`) needs
     * to override it.
     */
    suspend fun crashesForSession(sessionId: String?): List<InspectorCrashUi> = snapshot().crashes

    /**
     * Evidence-tray flag state for captured network transactions, durable via `EvidenceStore` --
     * the same store the dashboard's evidence tray reads and writes, so a flag made on this device
     * and one made from the browser are the same fact rather than two independent copies. Defaults
     * to empty so every existing adapter/fake keeps compiling; only an adapter with a durable
     * `EvidenceStore` (see `FullInspectorDataSource`) needs to override it.
     */
    suspend fun flaggedTransactionIds(): Set<String> = emptySet()

    /**
     * Flags [id] (a captured [InspectorTransactionUi]) into the evidence tray. The subject is
     * materialized fresh from whatever this data source's own capture store currently holds for
     * [id] -- never re-derived from a value the caller already has cached -- so the stored snapshot
     * matches exactly what the dashboard's own flag route would have produced for the same
     * transaction. [InspectorCommandResult.Invalid] covers both "already flagged" and "evidence tray
     * full"; [InspectorCommandResult.Unavailable] covers no durable store being wired up.
     */
    suspend fun flagTransaction(id: String): InspectorCommandResult = InspectorCommandResult.Unavailable

    /** Removes [id]'s evidence flag; a no-op if it was never flagged. */
    suspend fun unflagTransaction(id: String): InspectorCommandResult = InspectorCommandResult.Unavailable

    /**
     * Same durable mechanism as [flaggedTransactionIds], scoped to [sessionId] like
     * [crashesForSession] (null means the live session).
     */
    suspend fun flaggedCrashIds(sessionId: String? = null): Set<String> = emptySet()

    /** Crash counterpart of [flagTransaction], scoped to [sessionId] like [crashesForSession]. */
    suspend fun flagCrash(
        id: String,
        sessionId: String? = null,
    ): InspectorCommandResult = InspectorCommandResult.Unavailable

    /** Crash counterpart of [unflagTransaction], scoped to [sessionId] like [crashesForSession]. */
    suspend fun unflagCrash(
        id: String,
        sessionId: String? = null,
    ): InspectorCommandResult = InspectorCommandResult.Unavailable

    fun execute(request: InspectorComposerRequest): InspectorCommandResult

    fun setMocksEnabled(enabled: Boolean): InspectorCommandResult

    /** Ungated read, mirroring the other listing methods; only the mutations below are gated. */
    fun mockRules(): List<InspectorMockRuleUi> = snapshot().mockRules

    fun upsertMockRule(rule: InspectorMockRuleUi): InspectorCommandResult

    fun deleteMockRule(id: String): InspectorCommandResult

    fun setMockRuleEnabled(
        id: String,
        enabled: Boolean,
    ): InspectorCommandResult

    /** Ungated read, mirroring the other listing methods; only the mutations below are gated. */
    fun captureRules(): List<InspectorCaptureRuleUi> = snapshot().captureRules

    fun upsertCaptureRule(rule: InspectorCaptureRuleUi): InspectorCommandResult

    fun deleteCaptureRule(id: String): InspectorCommandResult

    fun setCaptureRuleEnabled(
        id: String,
        enabled: Boolean,
    ): InspectorCommandResult

    fun setFeatureFlag(
        key: String,
        value: String,
    ): InspectorCommandResult

    fun setPreference(
        file: String,
        key: String,
        value: String,
        type: String,
    ): InspectorCommandResult

    fun removePreference(
        file: String,
        key: String,
    ): InspectorCommandResult

    fun listFiles(
        root: String,
        relativePath: String,
    ): InspectorFileListingUi?

    fun previewFile(
        root: String,
        relativePath: String,
    ): InspectorFilePreviewUi

    fun deleteFile(
        root: String,
        relativePath: String,
    ): InspectorCommandResult

    /**
     * Absolute filesystem path to a regular file, for local sharing (the Android Compose Share
     * action wraps it in a `content://` URI via `androidx.core.content.FileProvider`). Returns
     * `null` when unavailable, out of bounds, or a directory. Gated by the same `files` capability
     * as [deleteFile] -- unlike [previewFile], the bytes a caller ultimately shares are raw and
     * unredacted, the same reasoning the browser download route documents.
     */
    fun shareableFilePath(
        root: String,
        relativePath: String,
    ): String? = null

    fun listTables(database: String): InspectorDatabaseListingUi?

    fun queryTable(
        database: String,
        table: String,
    ): InspectorQueryResultUi?

    fun executeSql(
        database: String,
        sql: String,
    ): InspectorSqlResultUi

    /**
     * Exports captured network traffic as a HAR file, sharing the result via the same
     * `FileProvider`/Share-sheet flow as [shareableFilePath]. [transactionIds] mirrors whatever the
     * Traffic screen's multi-select ([InspectorAction.ToggleTransactionSelection]) currently holds;
     * an empty set exports every captured transaction.
     */
    fun exportHar(transactionIds: Set<String> = emptySet()): InspectorCommandResult

    /** Same selection semantics as [exportHar], as a Postman v2.1 collection instead. */
    fun exportPostman(transactionIds: Set<String> = emptySet()): InspectorCommandResult

    /** Exports the whole current session (timeline, network, and app metadata) as one ZIP bundle. */
    fun exportSessionZip(): InspectorCommandResult = InspectorCommandResult.Unavailable

    /**
     * Revokes an authenticated browser by id, the same action the dashboard's Session view exposes
     * through `DELETE /api/v1/auth/principals/{id}` -- this is the device-side equivalent. Ungated
     * by capability, like the exports above: the device owner opening this screen is already the
     * highest trust level there is.
     */
    fun revokePrincipal(id: String): InspectorCommandResult = InspectorCommandResult.Unavailable

    /**
     * Starts or stops the embedded local server -- the More surface's "Start/Stop server" CTA.
     * [setServerRunning] defaults to [InspectorCommandResult.Unavailable] like the exports above:
     * `sdk/ui-compose` only owns the affordance, not the host's actual `DevConsoleFacade.start()`/
     * `stop()` lifecycle, which lives in `sdk/full` and is out of this module's scope to wire.
     * [supportsServerControl] reports whether [setServerRunning] actually does anything on this
     * build; UIs hide the control when false.
     */
    fun supportsServerControl(): Boolean = false

    fun setServerRunning(running: Boolean): InspectorCommandResult = InspectorCommandResult.Unavailable

    /**
     * The More screen's screenshot capture button. Delegates to `DevConsole.captureScreenshot()` on
     * a build that has it wired up; defaults to a not-connected [ScreenshotResult.Failed] so every
     * existing adapter and fake keeps compiling and behaving unchanged, matching this interface's
     * other "only override if you actually implement it" defaults.
     */
    suspend fun captureScreenshot(): ScreenshotResult = ScreenshotResult.Failed("Inspector is not connected")
}

/**
 * Process-local handoff from the full runtime to the SDK-owned Activity.
 *
 * Hosts should use [io.devconsole.DevConsole.open]; this bridge is public only because the runtime
 * and UI are separate artifacts.
 */
object DevConsoleInspectorBridge {
    @Volatile
    private var installedSource: InspectorDataSource = UnavailableInspectorDataSource

    fun install(source: InspectorDataSource) {
        installedSource = source
    }

    fun source(): InspectorDataSource = installedSource

    fun reset() {
        installedSource = UnavailableInspectorDataSource
    }
}

// Mirrors every method on InspectorDataSource with an inert default; see the suppression there.
@Suppress("TooManyFunctions")
private object UnavailableInspectorDataSource : InspectorDataSource {
    override fun snapshot(): InspectorSnapshot = InspectorSnapshot()

    override fun execute(request: InspectorComposerRequest): InspectorCommandResult = InspectorCommandResult.Unavailable

    override fun setMocksEnabled(enabled: Boolean): InspectorCommandResult = InspectorCommandResult.Unavailable

    override fun upsertMockRule(rule: InspectorMockRuleUi): InspectorCommandResult = InspectorCommandResult.Unavailable

    override fun deleteMockRule(id: String): InspectorCommandResult = InspectorCommandResult.Unavailable

    override fun setMockRuleEnabled(
        id: String,
        enabled: Boolean,
    ): InspectorCommandResult = InspectorCommandResult.Unavailable

    // Block body is deliberate: the equivalent one-line expression body is 125 chars, over
    // detekt's 120-char MaxLineLength, but ktlint's function-expression-body rule wants a block
    // this short converted back to an expression -- an irreconcilable conflict between the two
    // linters' thresholds for this exact declaration. Suppressing the ktlint rule here (rather
    // than growing the detekt baseline) keeps the conflict visible at the one call site it
    // affects instead of hiding it in a baseline file.
    @Suppress("ktlint:standard:function-expression-body")
    override fun upsertCaptureRule(rule: InspectorCaptureRuleUi): InspectorCommandResult {
        return InspectorCommandResult.Unavailable
    }

    override fun deleteCaptureRule(id: String): InspectorCommandResult = InspectorCommandResult.Unavailable

    override fun setCaptureRuleEnabled(
        id: String,
        enabled: Boolean,
    ): InspectorCommandResult = InspectorCommandResult.Unavailable

    override fun setFeatureFlag(
        key: String,
        value: String,
    ): InspectorCommandResult = InspectorCommandResult.Unavailable

    override fun setPreference(
        file: String,
        key: String,
        value: String,
        type: String,
    ): InspectorCommandResult = InspectorCommandResult.Unavailable

    override fun removePreference(
        file: String,
        key: String,
    ): InspectorCommandResult = InspectorCommandResult.Unavailable

    override fun listFiles(
        root: String,
        relativePath: String,
    ): InspectorFileListingUi? = null

    override fun previewFile(
        root: String,
        relativePath: String,
    ): InspectorFilePreviewUi = InspectorFilePreviewUi.Unavailable("Inspector is not connected")

    override fun deleteFile(
        root: String,
        relativePath: String,
    ): InspectorCommandResult = InspectorCommandResult.Unavailable

    override fun listTables(database: String): InspectorDatabaseListingUi? = null

    override fun queryTable(
        database: String,
        table: String,
    ): InspectorQueryResultUi? = null

    override fun executeSql(
        database: String,
        sql: String,
    ): InspectorSqlResultUi = InspectorSqlResultUi.Failed("Inspector is not connected")

    override fun exportHar(transactionIds: Set<String>): InspectorCommandResult = InspectorCommandResult.Unavailable

    override fun exportPostman(transactionIds: Set<String>): InspectorCommandResult = InspectorCommandResult.Unavailable
}
