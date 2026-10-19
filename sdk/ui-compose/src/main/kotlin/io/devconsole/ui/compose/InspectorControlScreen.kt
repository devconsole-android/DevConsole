/**
 * @author Shakib
 * @since 04/08/26
 */
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@file:Suppress("FunctionNaming", "MagicNumber", "UnusedPrivateMember", "TooManyFunctions")

package io.devconsole.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import io.devconsole.api.CaptureCategory
import kotlinx.coroutines.launch

/**
 * Stateful entry point wired into the CONTROL destination. Theme is owned by [DevConsoleWorkspace]
 * and reaches this screen as ambient [DevConsoleTheme.colors] -- [onToggleTheme] is the only theme
 * plumbing this route needs, now workspace-wide.
 */
@Composable
internal fun ControlRoute(
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier,
    onDetailOverlayOpen: (Boolean) -> Unit = {},
    viewModel: InspectorViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var heroCollapsed by rememberSaveable { mutableStateOf(true) } // starts collapsed
    var editorTarget by remember { mutableStateOf<MockRuleEditorTarget?>(null) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    LifecycleStartEffect(Unit) {
        viewModel.dispatch(InspectorAction.Refresh)
        onStopOrDispose { }
    }
    LaunchedEffect(state.lastCommandResult) {
        state.lastCommandResult?.let { result ->
            scope.launch { snackbarHostState.showSnackbar(result.toFlashMessage()) }
            // Only Success closes an open sheet -- Invalid/Disabled/Failed must leave the draft
            // intact so a rejection (bad header char, unknown scope, uncompilable regex, mocks
            // capability off) doesn't take the operator's authored fields down with it.
            if (editorTarget != null && result is InspectorCommandResult.Success) editorTarget = null
            viewModel.dispatch(InspectorAction.DismissCommandResult)
        }
    }
    LaunchedEffect(editorTarget) { onDetailOverlayOpen(editorTarget != null) }
    KeepAliveNotificationPromptEffect(
        promptNeeded = state.keepAlivePromptNeeded && lifecycleState.isAtLeast(Lifecycle.State.STARTED),
        snackbarHostState = snackbarHostState,
        onPermissionResult = { viewModel.dispatch(InspectorAction.NotificationPermissionGranted) },
    )

    Box(modifier = modifier.fillMaxSize()) {
        ControlScreen(
            state = state,
            heroCollapsed = heroCollapsed,
            onToggleHero = { heroCollapsed = !heroCollapsed },
            onToggleTheme = onToggleTheme,
            onToggleMockRule = { id, enabled -> viewModel.dispatch(InspectorAction.SetMockRuleEnabled(id, enabled)) },
            onNewMockRule = { editorTarget = MockRuleEditorTarget.New() },
            onEditMockRule = { rule -> editorTarget = editMockRuleTargetOrToast(rule, scope, snackbarHostState) },
            onDeleteMockRule = { id -> pendingDeleteId = id },
            onToggleFlag = { flag -> toggleControlFlag(flag, viewModel, scope, snackbarHostState) },
            modifier = Modifier.fillMaxSize(),
        )
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
        editorTarget?.let { target ->
            MockRuleEditorScreen(
                target = target,
                // Closing on Success is handled by the lastCommandResult effect above, not here --
                // dispatch is fire-and-forget/async, so nulling editorTarget on click would destroy
                // the draft before the result (and any rejection) is even known.
                onSave = { rule -> viewModel.dispatch(InspectorAction.UpsertMockRule(rule)) },
                onCancel = { editorTarget = null },
                onDelete = {
                    val id = (target as? MockRuleEditorTarget.Edit)?.rule?.id
                    if (id != null) {
                        editorTarget = null
                        pendingDeleteId = id
                    }
                },
            )
        }
    }
    pendingDeleteId?.takeIf { lifecycleState.isAtLeast(Lifecycle.State.STARTED) }?.let { id ->
        MockRuleDeleteConfirmDialog(
            ruleId = id,
            onConfirm = {
                viewModel.dispatch(InspectorAction.DeleteMockRule(id))
                pendingDeleteId = null
            },
            onDismiss = { pendingDeleteId = null },
        )
    }
}

/**
 * [InspectorMockRuleUi.isEditableOnDevice] gates the row's tap-to-edit affordance -- a rule whose
 * action doesn't round-trip through the sheet (fault injection, template, status-override, ...)
 * would silently become an empty 200 `StaticResponse` on Save, since `upsertMockRule` always builds
 * one. Switch (enable/disable) and Delete are unaffected -- neither touches the action.
 */
private fun editMockRuleTargetOrToast(
    rule: InspectorMockRuleUi,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbarHostState: SnackbarHostState,
): MockRuleEditorTarget? =
    if (rule.isEditableOnDevice()) {
        MockRuleEditorTarget.Edit(rule)
    } else {
        scope.launch { snackbarHostState.showSnackbar("This rule's action can't be edited on device") }
        null
    }

/** Booleans toggle in place; multi-value flags have no on-device editor. */
private fun toggleControlFlag(
    flag: InspectorFeatureFlagUi,
    viewModel: InspectorViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbarHostState: SnackbarHostState,
) {
    when {
        !flag.mutable -> scope.launch { snackbarHostState.showSnackbar("${flag.key} is locked by the host") }
        flag.type == "BOOLEAN" -> {
            val next = if (flag.value == "true") "false" else "true"
            viewModel.dispatch(InspectorAction.SetFeatureFlag(flag.key, next))
        }
        else ->
            scope.launch {
                snackbarHostState.showSnackbar("${flag.key} has more than two values — edit it on the dashboard")
            }
    }
}

/**
 * Stateless, previewable Control surface with grouped mock rules and feature flags.
 */
@Composable
@Suppress("LongParameterList") // One callback per real interaction this screen dispatches.
internal fun ControlScreen(
    state: InspectorState,
    heroCollapsed: Boolean,
    onToggleHero: () -> Unit,
    onToggleTheme: () -> Unit,
    onToggleMockRule: (String, Boolean) -> Unit,
    onNewMockRule: () -> Unit,
    onEditMockRule: (InspectorMockRuleUi) -> Unit,
    onDeleteMockRule: (String) -> Unit,
    onToggleFlag: (InspectorFeatureFlagUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DevConsoleTheme.colors
    val switchColors = controlSwitchColors(colors)
    Column(modifier = modifier.fillMaxSize().background(colors.ground)) {
        InspectorTopArea(
            subLine = "",
            title = "Control",
            actions = listOf(themeToggleTopAction(onToggleTheme)),
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        ) {
            // Mock rules are the MOCKS category's control surface -- when the host didn't enable it
            // at init, the section (header, New-rule affordance and rows) does not exist at all,
            // rather than rendering with a permanently-empty/disabled list.
            if (state.captures(CaptureCategory.MOCKS)) {
                item(contentType = "mock_rules_header") { MockRulesHeader(onNewMockRule, colors) }
                if (state.mockRules.isEmpty()) {
                    item(contentType = "mock_rules_empty") { MockRulesEmptyNote(colors) }
                } else {
                    itemsIndexed(
                        items = state.mockRules,
                        key = { _, rule -> rule.id },
                        contentType = { _, _ -> "mock_rule" },
                    ) { index, rule ->
                        Column(Modifier.groupedPanelItem(GroupPosition.of(index, state.mockRules.size))) {
                            MockRuleRow(rule, onToggleMockRule, onEditMockRule, onDeleteMockRule, colors, switchColors)
                        }
                    }
                }
                item(contentType = "mock_rules_spacer") { Spacer(Modifier.height(8.dp)) }
            }
            // Flags belong to the STATE category; `state.featureFlags` is already emptied by the
            // data source when STATE is off, but the explicit gate keeps this defensive against a
            // directly-navigated route the way DATA's sections are (see InspectorDataScreen).
            if (state.captures(CaptureCategory.STATE) && state.featureFlags.isNotEmpty()) {
                item(contentType = "feature_flags_header") { GroupLabel("Feature flags") }
                itemsIndexed(
                    items = state.featureFlags,
                    key = { _, flag -> flag.key },
                    contentType = { _, _ -> "feature_flag" },
                ) { index, flag ->
                    Column(Modifier.groupedPanelItem(GroupPosition.of(index, state.featureFlags.size))) {
                        FlagRow(flag, colors, switchColors) { onToggleFlag(flag) }
                    }
                }
                item(contentType = "feature_flags_spacer") { Spacer(Modifier.height(8.dp)) }
            }
            // Remote Config used to render here, beside flags. It moved to the Observe surface's
            // Config tab (see RemoteConfigTabContent): it is read-only, so nothing on it was ever
            // a control, and a value big enough to need reading in full needed a detail screen
            // this list had no way to open.
        }
    }
}

/** "Mock rules" group label plus the New rule affordance. */
@Composable
private fun MockRulesHeader(
    onNewRule: () -> Unit,
    colors: DevConsoleColors,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupLabel("Mock rules", modifier = Modifier.weight(1f))
        IconButton(onClick = onNewRule, modifier = Modifier.padding(end = 12.dp)) {
            DevConsoleIcon(R.drawable.devconsole_ic_composer, "New rule", Modifier, colors.muted, 22.dp)
        }
    }
}

@Composable
private fun MockRulesEmptyNote(colors: DevConsoleColors) {
    InspectorEmptyState(EmptySpot.Mocks, "No mock rules yet — tap New rule to add one.", spotSize = 64.dp)
}

/**
 * Tap opens the edit sheet; enable/disable moved onto the trailing [Switch] so it no longer
 * competes with the row's own tap target for "toggle" vs "edit" intent. Delete keeps its own
 * labelled icon action alongside the switch.
 */
@Composable
private fun MockRuleRow(
    rule: InspectorMockRuleUi,
    onToggle: (String, Boolean) -> Unit,
    onEdit: (InspectorMockRuleUi) -> Unit,
    onDelete: (String) -> Unit,
    colors: DevConsoleColors,
    switchColors: SwitchColors,
) {
    val subtitleText =
        remember(rule) {
            val match =
                buildString {
                    append("returns ")
                    append(rule.statusCode)
                    // "Static response (200)" only repeats the status already printed above.
                    if (rule.actionLabel.isNotBlank() && !rule.actionLabel.startsWith("Static response")) {
                        append(" · ${rule.actionLabel}")
                    }
                    if (rule.hitCount > 0) {
                        append(" · ${rule.hitCount} hit${if (rule.hitCount == 1L) "" else "s"}")
                        rule.lastHitEpochMs?.let { append(" · ${formatRelativeHitTime(it)}") }
                    }
                }
            buildAnnotatedString {
                append("returns ")
                withStyle(
                    SpanStyle(
                        color =
                            when (rule.statusCode) {
                                in 500..599 -> colors.error
                                in 400..499 -> colors.warn
                                else -> colors.muted
                            },
                    ),
                ) { append(rule.statusCode.toString()) }
                append(match.substringAfter("returns ${rule.statusCode}"))
            }
        }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onEdit(rule) },
                onLongClick = { onDelete(rule.id) },
                onLongClickLabel = "Delete mock rule",
            ).semantics { contentDescription = "Edit mock rule ${rule.id}" }
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rule.method ?: "ANY",
                    Modifier.width(64.dp),
                    color = colors.muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                )
                Text(
                    rule.pathPattern,
                    color = colors.ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                subtitleText,
                color = colors.muted,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Switch(
            checked = rule.enabled,
            onCheckedChange = { checked -> onToggle(rule.id, checked) },
            colors = switchColors,
            modifier =
                Modifier.semantics {
                    contentDescription = "${if (rule.enabled) "Disable" else "Enable"} mock rule ${rule.id}"
                },
        )
    }
}

@Composable
private fun controlSwitchColors(colors: DevConsoleColors): SwitchColors =
    SwitchDefaults.colors(
        checkedTrackColor = colors.signal,
        checkedThumbColor = colors.signalInk,
        uncheckedTrackColor = colors.surface3,
        uncheckedThumbColor = colors.muted,
        uncheckedBorderColor = colors.borderStrong,
    )

@Composable
private fun MockRuleDeleteConfirmDialog(
    ruleId: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DevConsoleTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete mock rule?") },
        text = { Text("\"$ruleId\" will stop matching requests immediately. This can't be undone.") },
        confirmButton = {
            Text(
                "DELETE",
                color = colors.error,
                fontWeight = FontWeight.Bold,
                modifier =
                    Modifier
                        .minimumInteractiveComponentSize()
                        .clickable(onClick = onConfirm, role = Role.Button)
                        .padding(12.dp),
            )
        },
        dismissButton = {
            Text(
                "CANCEL",
                color = colors.muted,
                modifier =
                    Modifier
                        .minimumInteractiveComponentSize()
                        .clickable(onClick = onDismiss, role = Role.Button)
                        .padding(12.dp),
            )
        },
    )
}

// MS_PER_* thresholds live in InspectorObserveFormat.kt -- this file used to duplicate them under
// a _CONTROL suffix.

/** Coarse "Ns ago"/"Nm ago"/"Nh ago"/"Nd ago" relative time for a mock rule's last hit. */
private fun formatRelativeHitTime(epochMs: Long): String {
    val elapsed = (System.currentTimeMillis() - epochMs).coerceAtLeast(0)
    return when {
        elapsed < MS_PER_MINUTE -> "just now"
        elapsed < MS_PER_HOUR -> "${elapsed / MS_PER_MINUTE}m ago"
        elapsed < MS_PER_DAY -> "${elapsed / MS_PER_HOUR}h ago"
        else -> "${elapsed / MS_PER_DAY}d ago"
    }
}

@Composable
private fun FlagRow(
    flag: InspectorFeatureFlagUi,
    colors: DevConsoleColors,
    switchColors: SwitchColors,
    onClick: () -> Unit,
) {
    val isBool = flag.type == "BOOLEAN"
    val isOn = flag.value == "true"
    TonalListRow(
        leadText = "",
        leadColor = colors.muted,
        leadContainerColor = colors.panel,
        title = flag.key,
        subtitle =
            listOfNotNull(
                if (!isBool) flag.value else null,
                if (flag.isOverridden) "overridden" else null,
            ).joinToString(" · "),
        trailValue = "",
        trailContent = {
            if (isBool) {
                Switch(
                    checked = isOn,
                    onCheckedChange = { onClick() },
                    colors = switchColors,
                    modifier = Modifier.semantics { contentDescription = "Toggle feature flag ${flag.key}" },
                )
            }
        },
        mergeDescendants = false,
        onClick = onClick,
    )
}

/** Hand-built fixture for [ControlScreenPreview]. */
private fun controlScreenPreviewState() =
    InspectorState(
        available = true,
        mockRules =
            listOf(
                InspectorMockRuleUi(
                    id = "rule-timeout",
                    method = "GET",
                    pathPattern = "/v1/menu/.*",
                    actionLabel = "Timeout",
                    statusCode = 504,
                    scope = "SESSION",
                    enabled = true,
                ),
                InspectorMockRuleUi(
                    id = "rule-empty-cart",
                    method = "GET",
                    pathPattern = "/v1/cart",
                    actionLabel = "Empty cart",
                    statusCode = 200,
                    scope = "PERSISTENT_INTERNAL",
                    enabled = false,
                ),
            ),
        featureFlags =
            listOf(
                InspectorFeatureFlagUi(
                    key = "checkout_v2",
                    value = "true",
                    defaultValue = "false",
                    allowedValues = listOf("true", "false"),
                    type = "BOOLEAN",
                    mutable = true,
                    description = "New checkout flow",
                    isOverridden = true,
                ),
                InspectorFeatureFlagUi(
                    key = "max_cart_items",
                    value = "12",
                    defaultValue = "12",
                    allowedValues = emptyList(),
                    type = "INT",
                    mutable = false,
                    description = "Server-enforced cap",
                    isOverridden = false,
                ),
            ),
    )

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun ControlScreenPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        ControlScreen(
            state = controlScreenPreviewState(),
            heroCollapsed = false,
            onToggleHero = {},
            onToggleTheme = {},
            onToggleMockRule = { _, _ -> },
            onNewMockRule = {},
            onEditMockRule = {},
            onDeleteMockRule = {},
            onToggleFlag = {},
        )
    }
}
