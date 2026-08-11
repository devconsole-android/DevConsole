/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "UnusedPrivateMember", "TooManyFunctions")

package io.devconsole.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.devconsole.api.CaptureCategory
import kotlinx.coroutines.launch
import java.util.Locale

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
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var heroCollapsed by rememberSaveable { mutableStateOf(true) } // starts collapsed
    var editorTarget by remember { mutableStateOf<MockRuleEditorTarget?>(null) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.dispatch(InspectorAction.Refresh) }
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
        promptNeeded = state.keepAlivePromptNeeded,
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
            )
        }
    }
    pendingDeleteId?.let { id ->
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
 * Stateless, previewable Control surface: hero, mock-rule rows, flag rows, and the composer-absent
 * note. The composer/capture-rules cards the old screen had are gone -- the Control view shows
 * neither ("Composer is intentionally absent" below).
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
    val overrides = rememberControlOverrides(state)
    Column(modifier = modifier.fillMaxSize().background(colors.ground)) {
        InspectorTopArea(
            subLine = "Mocks and flags",
            title = "Control",
            actions = listOf(themeToggleTopAction(onToggleTheme)),
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 16.dp),
        ) {
            item { ControlHero(overrides, heroCollapsed, onToggleHero, colors) }
            item { Spacer(Modifier.height(16.dp)) }
            // Mock rules are the MOCKS category's control surface -- when the host didn't enable it
            // at init, the section (header, New-rule affordance and rows) does not exist at all,
            // rather than rendering with a permanently-empty/disabled list.
            if (state.captures(CaptureCategory.MOCKS)) {
                item { MockRulesHeader(onNewMockRule, colors) }
                if (state.mockRules.isEmpty()) {
                    item { MockRulesEmptyNote(colors) }
                } else {
                    items(state.mockRules, key = { it.id }) { rule ->
                        MockRuleRow(rule, onToggleMockRule, onEditMockRule, onDeleteMockRule, colors)
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            // Flags belong to the STATE category; `state.featureFlags` is already emptied by the
            // data source when STATE is off, but the explicit gate keeps this defensive against a
            // directly-navigated route the way DATA's sections are (see InspectorDataScreen).
            if (state.captures(CaptureCategory.STATE) && state.featureFlags.isNotEmpty()) {
                item { GroupLabel("Flags") }
                items(state.featureFlags, key = { it.key }) { flag ->
                    FlagRow(flag, colors) { onToggleFlag(flag) }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            item {
                WarnNote(
                    "Composer is intentionally absent — typing URLs on a phone is hostile, so " +
                        "cloning a request hands off to the dashboard.",
                )
            }
        }
    }
}

/** Real, computed count of active overrides. */
private data class ControlOverrides(
    val activeCount: Int,
    val total: Int,
    val sub: String,
)

@Composable
private fun rememberControlOverrides(state: InspectorState): ControlOverrides =
    remember(state.mockRules, state.featureFlags) {
        val enabledMocks = state.mockRules.count { it.enabled }
        val overriddenFlags = state.featureFlags.count { it.isOverridden }
        val total = state.mockRules.size + state.featureFlags.size
        val sub =
            if (enabledMocks > 0 || overriddenFlags > 0) {
                val parts =
                    buildList {
                        if (enabledMocks > 0) add("$enabledMocks mock rule${if (enabledMocks == 1) "" else "s"}")
                        if (overriddenFlags > 0) {
                            add("$overriddenFlags flag${if (overriddenFlags == 1) "" else "s"} overridden locally")
                        }
                    }
                "${parts.joinToString(" and ")} — the app is not in its default state."
            } else {
                "Nothing is overridden — the app matches its default configuration."
            }
        ControlOverrides(enabledMocks + overriddenFlags, total, sub)
    }

@Composable
private fun ControlHero(
    overrides: ControlOverrides,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    colors: DevConsoleColors,
) {
    if (collapsed) {
        HeroBar(
            value = overrides.activeCount.toString(),
            label = "of ${overrides.total} overrides active",
            onExpand = onToggleCollapse,
            containerColor = colors.putSoft,
            valueColor = colors.put,
            labelColor = colors.put,
        )
    } else {
        HeroCard(
            label = "Active overrides",
            value = overrides.activeCount.toString(),
            valueSuffix = "of ${overrides.total}",
            subtitle = overrides.sub,
            containerColor = colors.putSoft,
            labelColor = colors.put,
            valueColor = colors.put,
            onCollapse = onToggleCollapse,
        )
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
        androidx.compose.material3.OutlinedButton(
            onClick = onNewRule,
        ) {
            Text("New rule", fontSize = 13.sp, color = colors.ink)
        }
    }
}

@Composable
private fun MockRulesEmptyNote(colors: DevConsoleColors) {
    Text(
        "No mock rules yet — tap New rule to add one.",
        color = colors.muted,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 8.dp, bottom = 16.dp),
    )
}

/**
 * Tap opens the edit sheet; enable/disable moved onto the trailing [Switch] so it no longer
 * competes with the row's own tap target for "toggle" vs "edit" intent. Delete is a separate small
 * destructive action below the row, matching FilesSection's SHARE/DEL convention for a row that
 * already does something else on tap.
 */
@Composable
private fun MockRuleRow(
    rule: InspectorMockRuleUi,
    onToggle: (String, Boolean) -> Unit,
    onEdit: (InspectorMockRuleUi) -> Unit,
    onDelete: (String) -> Unit,
    colors: DevConsoleColors,
) {
    val leadColor = if (rule.enabled) colors.signal else colors.text3
    val leadBg = if (rule.enabled) colors.signalSoft else colors.surface3
    val match =
        buildString {
            append(rule.method ?: "*")
            append(' ')
            append(rule.pathPattern)
            append(" → ")
            append(rule.statusCode)
            if (rule.actionLabel.isNotBlank()) append(" · ${rule.actionLabel}")
            if (rule.hitCount > 0) {
                append(" · ${rule.hitCount} hit${if (rule.hitCount == 1L) "" else "s"}")
                rule.lastHitEpochMs?.let { append(" · ${formatRelativeHitTime(it)}") }
            }
        }
    Column {
        TonalListRow(
            leadText = if (rule.enabled) "ON" else "OFF",
            leadColor = leadColor,
            leadContainerColor = leadBg,
            title = rule.id,
            subtitle = match,
            trailValue = rule.scope.lowercase(Locale.US),
            trailValueColor = colors.muted,
            trailContent = {
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { checked -> onToggle(rule.id, checked) },
                    modifier =
                        Modifier.semantics {
                            contentDescription =
                                "${if (rule.enabled) "Disable" else "Enable"} mock rule ${rule.id}"
                        },
                )
            },
            onClick = { onEdit(rule) },
            // The trailing Switch is its own actionable control -- keep it out of the row's merged
            // semantics node so TalkBack exposes both the row's "edit" tap and the switch's toggle.
            mergeDescendants = false,
        )
        Row(modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)) {
            MockRuleDeleteAction(rule.id, colors) { onDelete(rule.id) }
        }
    }
}

@Composable
private fun MockRuleDeleteAction(
    ruleId: String,
    colors: DevConsoleColors,
    onClick: () -> Unit,
) {
    Text(
        "DELETE",
        color = colors.error,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.5.sp,
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(6.dp))
                .background(colors.errorSoft)
                .clickable(onClick = onClick, role = Role.Button)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .semantics { contentDescription = "Delete mock rule $ruleId" },
    )
}

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
    onClick: () -> Unit,
) {
    val isBool = flag.type == "BOOLEAN"
    val isOn = flag.value == "true"
    val leadColor =
        if (flag.isOverridden) {
            colors.warn
        } else if (isBool && isOn) {
            colors.signal
        } else {
            colors.text3
        }
    val leadBg =
        if (flag.isOverridden) {
            colors.warnSoft
        } else if (isBool && isOn) {
            colors.signalSoft
        } else {
            colors.surface3
        }
    val sub =
        buildString {
            append("flag")
            // Non-boolean flags carry their real value in the sub-line -- an ON/OFF badge would lie.
            if (!isBool) append(" · ${flag.value}")
            if (flag.isOverridden) append(" · overridden locally")
            if (flag.description.isNotBlank()) append(" · ${flag.description}")
        }
    TonalListRow(
        leadText =
            if (!isBool) {
                "VAL"
            } else if (isOn) {
                "ON"
            } else {
                "OFF"
            },
        leadColor = leadColor,
        leadContainerColor = leadBg,
        title = flag.key,
        subtitle = sub,
        trailValue = if (flag.isOverridden) "local" else "remote",
        trailValueColor = if (flag.isOverridden) colors.warn else colors.muted,
        onClick = onClick,
    )
}

/** Hand-built fixture for [ControlScreenPreview] -- previews are the only visual check on this branch. */
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
