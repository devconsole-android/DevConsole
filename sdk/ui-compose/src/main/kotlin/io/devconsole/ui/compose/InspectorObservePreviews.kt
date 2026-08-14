/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "UnusedPrivateMember", "TooManyFunctions")

package io.devconsole.ui.compose

// TooManyFunctions is suppressed above because this file is one @Preview function per Observe tab
// and per detail worth eyeballing: its function count tracks the number of tabs, not complexity.

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

private val PreviewTransactions =
    listOf(
        InspectorTransactionUi(
            id = "tx-1",
            method = "GET",
            host = "api.acmeship.test",
            path = "/v1/orders",
            statusCode = 200,
            durationMs = 128,
        ),
        InspectorTransactionUi(
            id = "tx-2",
            method = "POST",
            host = "api.acmeship.test",
            path = "/v1/orders/checkout",
            statusCode = 500,
            durationMs = 812,
            error = "Upstream returned 500",
            requestHeaders = mapOf("authorization" to "<redacted>"),
        ),
        InspectorTransactionUi(
            id = "tx-3",
            method = "DELETE",
            host = "api.acmeship.test",
            path = "/v1/cart/8812",
            statusCode = null,
            durationMs = null,
            error = "Timed out waiting for upstream",
        ),
    )

private val PreviewSockets =
    listOf(
        InspectorSocketUi(
            id = "socket-1",
            url = "wss://api.acmeship.test/stream",
            state = "OPEN",
            sentCount = 12,
            receivedCount = 184,
            openedAtEpochMs = 0,
            frames =
                listOf(
                    InspectorSocketFrameUi("SENT", "TEXT", "{\"subscribe\":\"orders\"}", 1_000),
                    InspectorSocketFrameUi("RECEIVED", "TEXT", "{\"orderId\":8812,\"state\":\"PICKED_UP\"}", 2_000),
                ),
        ),
    )

private val PreviewPushEvents =
    listOf(
        InspectorPushUi(
            provider = "fcm",
            messageId = "msg-8812",
            lifecycle = "OPENED",
            simulated = false,
            receivedAtEpochMs = 3_000,
            dataPreview = mapOf("title" to "Order picked up", "orderId" to "8812"),
        ),
        InspectorPushUi(
            provider = "fcm",
            messageId = null,
            lifecycle = "RECEIVED",
            simulated = true,
            receivedAtEpochMs = 4_000,
            dataPreview = mapOf("title" to "Simulated delivery update"),
        ),
    )

private val PreviewLogs =
    listOf(
        InspectorLogUi("log-1", "ERROR", "network", "Checkout request failed", 5_000, "IllegalStateException"),
        InspectorLogUi("log-2", "WARN", "main", "Slow frame detected", 6_000),
        InspectorLogUi("log-3", "INFO", "main", "App resumed", 7_000),
    )

private val PreviewCrashes =
    listOf(
        InspectorCrashUi(
            id = "crash-1",
            kind = "ANR",
            summary = "Main thread unresponsive",
            thread = "main",
            timestampEpochMs = 8_000,
            stackTrace = "\"main\"\n\tat com.acmeship.CheckoutActivity.onResume(CheckoutActivity.kt:42)",
            breadcrumbs =
                listOf(
                    InspectorBreadcrumbUi(7_800, "network", "request", 2, "POST /v1/orders/checkout"),
                    InspectorBreadcrumbUi(7_900, "logs", "log", 3, "Slow frame detected"),
                ),
        ),
        InspectorCrashUi(
            id = "crash-2",
            kind = "UNCAUGHT",
            summary = "IllegalStateException: Order not found",
            thread = "main",
            timestampEpochMs = 9_000,
            stackTrace = "\"main\"\n\tat com.acmeship.OrderRepository.get(OrderRepository.kt:88)",
        ),
    )

private val PreviewSessions =
    listOf(
        InspectorSessionUi(
            id = "session-active",
            startedAtEpochMs = 10_000,
            label = "This run",
            status = "ACTIVE",
        ),
        InspectorSessionUi(
            id = "session-crashed",
            startedAtEpochMs = 1_000,
            label = "Previous run",
            status = "CRASHED",
        ),
    )

private val PreviewObserveState =
    InspectorState(
        available = true,
        transactions = PreviewTransactions,
        sockets = PreviewSockets,
        pushEvents = PreviewPushEvents,
        logs = PreviewLogs,
        crashes = PreviewCrashes,
        sessions = PreviewSessions,
        remoteConfig = remoteConfigPreviewProviders(),
        capabilities = InspectorEditingUi(requestExecution = true),
    )

private val PreviewObserveUi = ObserveUiState(appIdentity = "io.acmeship.android · 4.12.0")
private val PreviewObserveActions =
    ObserveActions(
        onSelectTab = {},
        onToggleTheme = {},
        onTrafficSearchChange = {},
        onTrafficChipClick = {},
        onSocketsSearchChange = {},
        onPushSearchChange = {},
        onLogsSearchChange = {},
        onOpenNetDetail = {},
        onOpenFrameDetail = { _, _ -> },
        onOpenPushDetail = {},
        onOpenLogDetail = {},
        onCrashesSearchChange = {},
        onOpenCrashDetail = {},
        onRemoteConfigSearchChange = {},
        onOpenRemoteConfigDetail = { _, _ -> },
        onToggleCrashFlag = {},
        onToggleCrashesHero = {},
        onViewPreviousCrash = {},
        onCloseDetail = {},
        onMockFromTransaction = {},
        onUnmockTransaction = {},
        onSaveMockDraft = {},
        onCancelMockDraft = {},
        onToggleFlag = {},
        onToggleBookmark = {},
        onOpenEvidenceTray = {},
        onToggleTransactionSelection = {},
        onSelectAllFilteredTransactions = {},
        onClearTransactionSelection = {},
        onExportHar = {},
        onExportPostman = {},
        copyText = {},
        shareText = { _, _ -> },
        showMessage = {},
        onToggleTrafficHero = {},
        onToggleSocketsHero = {},
        onTogglePushHero = {},
        onToggleLogsHero = {},
    )

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun ObserveScreenTrafficPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        ObserveScreen(state = PreviewObserveState, ui = PreviewObserveUi, actions = PreviewObserveActions)
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Composable
private fun ObserveScreenSocketsPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        ObserveScreen(
            state = PreviewObserveState.copy(observeTab = ObserveTab.SOCKETS),
            ui = PreviewObserveUi,
            actions = PreviewObserveActions,
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Composable
private fun ObserveScreenPushPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        ObserveScreen(
            state = PreviewObserveState.copy(observeTab = ObserveTab.PUSH),
            ui = PreviewObserveUi,
            actions = PreviewObserveActions,
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Composable
private fun ObserveScreenLogsPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        ObserveScreen(
            state = PreviewObserveState.copy(observeTab = ObserveTab.LOGS),
            ui = PreviewObserveUi,
            actions = PreviewObserveActions,
        )
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun ObserveScreenCrashesPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        ObserveScreen(
            state = PreviewObserveState.copy(observeTab = ObserveTab.CRASHES),
            ui = PreviewObserveUi,
            actions = PreviewObserveActions,
        )
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun ObserveScreenRemoteConfigPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        ObserveScreen(
            state = PreviewObserveState.copy(observeTab = ObserveTab.REMOTE_CONFIG),
            ui = PreviewObserveUi,
            actions = PreviewObserveActions,
        )
    }
}

/** The JSON case: `checkout_v2` parses, so the detail opens on the formatted tree rather than raw. */
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Composable
private fun ObserveScreenRemoteConfigDetailPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        ObserveScreen(
            state = PreviewObserveState.copy(observeTab = ObserveTab.REMOTE_CONFIG),
            ui = PreviewObserveUi.copy(detailTarget = ObserveDetailTarget.RemoteConfigKey("firebase", "checkout_v2")),
            actions = PreviewObserveActions,
        )
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun ObserveScreenCrashDetailPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        ObserveScreen(
            state = PreviewObserveState.copy(observeTab = ObserveTab.CRASHES),
            ui = PreviewObserveUi.copy(detailTarget = ObserveDetailTarget.Crash("crash-1")),
            actions = PreviewObserveActions,
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Composable
private fun ObserveScreenEmptyPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        ObserveScreen(state = InspectorState(available = true), ui = PreviewObserveUi, actions = PreviewObserveActions)
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Composable
private fun ObserveScreenUnavailablePreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        ObserveScreen(state = InspectorState(available = false), ui = PreviewObserveUi, actions = PreviewObserveActions)
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun ObserveScreenNetDetailPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        ObserveScreen(
            state = PreviewObserveState.copy(observeTab = ObserveTab.TRAFFIC),
            ui = PreviewObserveUi.copy(detailTarget = ObserveDetailTarget.Net("tx-2")),
            actions = PreviewObserveActions,
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Composable
private fun ObserveScreenTrafficHeroCollapsedPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        ObserveScreen(
            state = PreviewObserveState,
            ui = PreviewObserveUi.copy(trafficHeroCollapsed = true),
            actions = PreviewObserveActions,
        )
    }
}
