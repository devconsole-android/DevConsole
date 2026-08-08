/**
 * @author Shakib
 * @since 24/07/26
 */
package io.devconsole.ui.compose

/** User intents dispatched from the Observe and Control surfaces to [InspectorViewModel]. */
sealed interface InspectorAction {
    data object Refresh : InspectorAction

    /**
     * The operator allowed notifications from the keep-alive prompt. Re-posts the foreground
     * service's notification, which the platform suppressed when the service started without the
     * permission and does not post retroactively on grant, then refreshes so the prompt clears.
     */
    data object NotificationPermissionGranted : InspectorAction

    data class SelectObserveTab(
        val tab: ObserveTab,
    ) : InspectorAction

    data class ToggleTransactionSelection(
        val id: String,
    ) : InspectorAction

    /**
     * Unions [ids] into [InspectorState.selectedTransactionIds] without touching ids already
     * selected from outside [ids] -- the Observe traffic tab's "Select all matching filter"
     * affordance, mirroring the dashboard's same-named/same-semantics action (widen, never replace).
     */
    data class SelectTransactions(
        val ids: Set<String>,
    ) : InspectorAction

    /**
     * Empties [InspectorState.selectedTransactionIds] -- explicit close or back-press out of the
     * traffic tab's selection mode.
     */
    data object ClearTransactionSelection : InspectorAction

    data class SelectSession(
        val id: String?,
    ) : InspectorAction

    /**
     * Toggles [id]'s evidence-tray flag through the durable `EvidenceStore` -- flags it if
     * unflagged, unflags it if already flagged, per [InspectorState.flaggedTransactionIds].
     */
    data class ToggleTransactionFlag(
        val id: String,
    ) : InspectorAction

    /** Crash counterpart of [ToggleTransactionFlag], scoped to [InspectorState.selectedSessionId]. */
    data class ToggleCrashFlag(
        val id: String,
    ) : InspectorAction

    data class ExecuteComposer(
        val request: InspectorComposerRequest,
    ) : InspectorAction

    data class SetMocksEnabled(
        val enabled: Boolean,
    ) : InspectorAction

    data class UpsertMockRule(
        val rule: InspectorMockRuleUi,
    ) : InspectorAction

    data class DeleteMockRule(
        val id: String,
    ) : InspectorAction

    data class SetMockRuleEnabled(
        val id: String,
        val enabled: Boolean,
    ) : InspectorAction

    data class UpsertCaptureRule(
        val rule: InspectorCaptureRuleUi,
    ) : InspectorAction

    data class DeleteCaptureRule(
        val id: String,
    ) : InspectorAction

    data class SetCaptureRuleEnabled(
        val id: String,
        val enabled: Boolean,
    ) : InspectorAction

    data class SetFeatureFlag(
        val key: String,
        val value: String,
    ) : InspectorAction

    data class SetPreference(
        val file: String,
        val key: String,
        val value: String,
        val type: String,
    ) : InspectorAction

    data class RemovePreference(
        val file: String,
        val key: String,
    ) : InspectorAction

    data class OpenFilePath(
        val root: String,
        val relativePath: String,
    ) : InspectorAction

    data class PreviewFile(
        val root: String,
        val relativePath: String,
    ) : InspectorAction

    data object CloseFilePreview : InspectorAction

    data class DeleteFile(
        val root: String,
        val relativePath: String,
    ) : InspectorAction

    /** Resolves a shareable path for the file and stores it in [InspectorState.pendingShareFilePath]. */
    data class ShareFile(
        val root: String,
        val relativePath: String,
    ) : InspectorAction

    /** Consumes [InspectorState.pendingShareFilePath] once the Compose layer has launched the share sheet for it. */
    data object ConsumeShareFile : InspectorAction

    data class OpenDatabase(
        val database: String,
    ) : InspectorAction

    data class OpenTable(
        val database: String,
        val table: String,
    ) : InspectorAction

    data class ExecuteSql(
        val database: String,
        val sql: String,
    ) : InspectorAction

    data object DismissCommandResult : InspectorAction

    /** Exports the current traffic selection ([InspectorState.selectedTransactionIds]) as a HAR file. */
    data object ExportHar : InspectorAction

    /** Same selection as [ExportHar], as a Postman collection instead. */
    data object ExportPostman : InspectorAction

    /** Exports the whole current session (timeline, network, and app metadata) as one ZIP bundle. */
    data object ExportSessionZip : InspectorAction

    /** Revokes an authenticated browser principal shown on the More surface's Browser sessions section. */
    data class RevokePrincipal(
        val id: String,
    ) : InspectorAction

    /** The More surface's Start/Stop server CTA. */
    data class SetServerRunning(
        val running: Boolean,
    ) : InspectorAction

    /** The More surface's screenshot capture button, next to the export actions. */
    data object CaptureScreenshot : InspectorAction

    /** Consumes [InspectorState.lastScreenshotResult] once its flash message has been shown. */
    data object DismissScreenshotResult : InspectorAction
}
