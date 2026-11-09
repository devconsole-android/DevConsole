/**
 * @author Shakib
 * @since 05/08/26
 */
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@file:Suppress("FunctionNaming", "MagicNumber", "TooManyFunctions", "UnusedPrivateMember", "MatchingDeclarationName")

package io.devconsole.ui.compose

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The response body field's own state -- find query, stepped match, caret and the last FORMAT
 * error -- hoisted out of the field so the inline field and the full-screen editor are two views
 * of one editing session. Rendering the full-screen editor composes a second field; without this
 * the query you typed and the match you stepped to would reset the moment you expanded.
 *
 * The body text itself is deliberately NOT here: it lives in [MockRuleFormFields] as the form's
 * single source of truth, so an edit made full-screen is already in the rule when you collapse.
 */
@androidx.compose.runtime.Stable
internal class MockRuleBodyEditorState {
    var fullScreen by mutableStateOf(false)
    var searchExpanded by mutableStateOf(false)
    var query by mutableStateOf("")
    var activeIndex by mutableStateOf(0)
    var selection by mutableStateOf(TextRange.Zero)
    var formatError by mutableStateOf<String?>(null)

    /**
     * Whether the body field currently holds focus, which is what reveals the DONE pill. Not
     * saved: focus is restored by the field itself after a configuration change, and a stale
     * `true` would show a pill for a keyboard that is not up.
     */
    var bodyFocused by mutableStateOf(false)
}

/**
 * Only the three flags a rotation should survive are saved. The caret and the format error are
 * derived from text that is itself restored, and a stale offset into a rebuilt body is worse than
 * starting from the top.
 */
private val MockRuleBodyEditorStateSaver: Saver<MockRuleBodyEditorState, Any> =
    listSaver(
        save = { listOf(it.fullScreen, it.searchExpanded, it.query) },
        restore = { saved ->
            MockRuleBodyEditorState().apply {
                fullScreen = saved[0] as Boolean
                searchExpanded = saved[1] as Boolean
                query = saved[2] as String
            }
        },
    )

@Composable
internal fun rememberMockRuleBodyEditorState(): MockRuleBodyEditorState =
    rememberSaveable(saver = MockRuleBodyEditorStateSaver) { MockRuleBodyEditorState() }

/**
 * Which rule the create/edit sheet is showing; [InspectorMockRuleUi.id] is disabled for [Edit].
 * [New.draft] lets a caller prefill a brand-new rule (e.g. mock-from-capture's
 * [mockRuleDraftFromTransaction]) while keeping the id field editable, unlike [Edit].
 * [New.prefillNote], when set, renders under the body field -- reserved for a prefill the form
 * genuinely could not carry (a binary response body), not for narrating an ordinary one.
 */
internal sealed interface MockRuleEditorTarget {
    /**
     * [sourceKey] identifies *what this draft was prefilled from* (a transaction id, or "new" for a
     * blank rule) -- it keys the sheet's saved form fields (see [rememberMockRuleFormFields]) so
     * re-opening "Mock this response" from a *different* transaction resets the fields instead of
     * restoring the previous transaction's stale draft.
     */
    data class New(
        val draft: InspectorMockRuleUi = InspectorMockRuleUi(id = ""),
        val prefillNote: String? = null,
        val sourceKey: String = "new",
    ) : MockRuleEditorTarget

    data class Edit(
        val rule: InspectorMockRuleUi,
    ) : MockRuleEditorTarget
}

/** [rememberMockRuleFormFields]'s reset key -- see [MockRuleEditorTarget.New.sourceKey]'s doc. */
private fun MockRuleEditorTarget.formSourceKey(): String =
    when (this) {
        is MockRuleEditorTarget.New -> "new:$sourceKey"
        is MockRuleEditorTarget.Edit -> "edit:${rule.id}"
    }

/**
 * Bundle-friendly [Saver] for a nullable new-rule target, preserving draft metadata and open state.
 */
internal val MockRuleEditorNewSaver: Saver<MockRuleEditorTarget.New?, Any> =
    listSaver(
        save = { target ->
            val draft = target?.draft
            listOf(
                target != null,
                draft?.id,
                draft?.method,
                draft?.pathPattern,
                draft?.actionLabel,
                draft?.scheme,
                draft?.host,
                draft?.priority,
                draft?.scope,
                draft?.statusCode,
                draft?.body,
                draft?.enabled,
                draft?.headers?.toMockRuleHeaderLines(),
                draft?.delayMs,
                draft?.hitCount,
                draft?.lastHitEpochMs,
                draft?.sourceBodySnapshot,
                draft?.responseBodyTruncated,
                target?.prefillNote,
                target?.sourceKey,
                draft?.actionType?.ordinal,
                draft?.timeoutMs,
                draft?.failureMessage,
                draft?.throttleBytesPerSecond,
                draft?.throttleLatencyMs,
                draft?.remoteScheme,
                draft?.remoteHost,
                draft?.remotePath,
                draft?.remotePort,
                draft?.queryPredicates?.toMockRulePredicateLines(),
                draft?.headerPredicates?.toMockRulePredicateLines(),
                draft?.bodyPredicate,
            )
        },
        restore = { saved ->
            if (saved[0] != true) return@listSaver null
            MockRuleEditorTarget.New(
                draft =
                    InspectorMockRuleUi(
                        id = saved[1] as String,
                        method = saved[2] as String?,
                        pathPattern = saved[3] as String,
                        actionLabel = saved[4] as String,
                        scheme = saved[5] as String?,
                        host = saved[6] as String?,
                        priority = saved[7] as Int,
                        scope = saved[8] as String,
                        statusCode = saved[9] as Int,
                        body = saved[10] as String,
                        enabled = saved[11] as Boolean,
                        headers = parseMockRuleHeaderLines(saved[12] as String).headers,
                        delayMs = saved[13] as Long?,
                        hitCount = saved[14] as Long,
                        lastHitEpochMs = saved[15] as Long?,
                        sourceBodySnapshot = saved[16] as String?,
                        responseBodyTruncated = saved[17] as Boolean,
                        actionType =
                            InspectorMockActionType.entries[
                                (saved.getOrNull(20) as? Int ?: InspectorMockActionType.STATIC.ordinal)
                                    .coerceIn(0, InspectorMockActionType.entries.lastIndex),
                            ],
                        timeoutMs = saved.getOrNull(21) as Long?,
                        failureMessage = saved.getOrNull(22) as String?,
                        throttleBytesPerSecond = saved.getOrNull(23) as Long?,
                        throttleLatencyMs = saved.getOrNull(24) as Long?,
                        remoteScheme = saved.getOrNull(25) as String?,
                        remoteHost = saved.getOrNull(26) as String?,
                        remotePath = saved.getOrNull(27) as String?,
                        remotePort = saved.getOrNull(28) as Int?,
                        queryPredicates =
                            parseMockRulePredicateLines(saved.getOrNull(29) as? String ?: "").predicates,
                        headerPredicates =
                            parseMockRulePredicateLines(saved.getOrNull(30) as? String ?: "").predicates,
                        bodyPredicate = saved.getOrNull(31) as String?,
                    ),
                prefillNote = saved[18] as String?,
                sourceKey = saved[19] as String,
            )
        },
    )

internal val MOCK_RULE_METHODS = listOf("ALL", "GET", "POST", "PUT", "PATCH", "DELETE")

/** Everything the sheet's Save button needs validated, recomputed via [remember] as the fields change. */
private data class MockRuleFormErrors(
    val id: String? = null,
    val status: String? = null,
    val delay: String? = null,
    val timeout: String? = null,
    val throttleRate: String? = null,
    val throttleLatency: String? = null,
    val remote: String? = null,
    val remoteScheme: String? = null,
    val remoteHost: String? = null,
    val remotePath: String? = null,
    val remotePort: String? = null,
) {
    val allErrors: List<String>
        get() =
            listOfNotNull(
                id,
                status,
                delay,
                timeout,
                throttleRate,
                throttleLatency,
                remote,
                remoteScheme,
                remoteHost,
                remotePath,
                remotePort,
            )

    val isValid: Boolean get() = allErrors.isEmpty()
}

/**
 * Full-screen create/edit sheet for a mock rule: id/method/path/status/headers/body up front,
 * priority/scope/scheme/host/delay behind a collapsed "Advanced options" expander per the approved
 * de-clutter decision. [onSave] fires only once every blocking field (id, status, delay) is valid;
 * the body's JSON Format action is non-blocking -- an invalid body is a normal non-JSON mock
 * response, not a reason to refuse Save. Back/Cancel always closes without saving, matching every
 * other detail overlay in this module.
 */
@Composable
internal fun MockRuleEditorScreen(
    target: MockRuleEditorTarget,
    onSave: (InspectorMockRuleUi) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
) {
    val initial = target.initialRule()
    val isNew = target is MockRuleEditorTarget.New
    val colors = DevConsoleTheme.colors
    val form = rememberMockRuleFormFields(initial, target.formSourceKey())
    val errors = rememberMockRuleFormErrors(form)
    var showErrors by remember { mutableStateOf(false) }
    var acknowledgedTruncatedBody by rememberSaveable(target.formSourceKey()) { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    // Collapsed when the rule already carries headers -- a capture prefill dumps 20+ lines here and
    // pushed status/body off-screen. A blank rule opens expanded: nothing to hide, and the field is
    // where a new rule's Content-Type gets typed.
    var headersExpanded by rememberSaveable(target.formSourceKey()) { mutableStateOf(initial.headers.isEmpty()) }
    // Owned here, not in the body field: the full-screen editor has to render outside the
    // scaffold's scrolling column (inside it, a fillMaxSize Surface would be clipped to the
    // column's own bounds and scroll with the form), so the flag that opens it has to live where
    // that sibling can be composed.
    val bodyState = rememberMockRuleBodyEditorState()
    val keyboardOpen = WindowInsets.isImeVisible

    // The editor's own back gesture closes the full-screen body first; the overlay installs its
    // own BackHandler while it is open, so this one only ever sees the form.
    BackHandler(onBack = onCancel)

    InspectorDetailScaffold(
        modifier = modifier,
        header = {
            MockRuleEditorHeader(
                title = if (isNew) "New mock rule" else "Edit mock rule",
                subtitle = if (isNew) "Dispatches UpsertMockRule on save" else initial.id,
                onBack = onCancel,
                colors = colors,
            )
        },
        // Null while the keyboard is up: pinned there the bar eats a third of what is left of the
        // screen to offer Save at the one moment nobody is trying to save, and the field being
        // typed into gets that much less room. Dismissing the keyboard (the Done key on every
        // single-line field, or back) brings it straight back.
        footer =
            if (keyboardOpen) {
                null
            } else {
                {
                    Column {
                        // Save is never a silent no-op: the delay field lives inside the collapsed
                        // Advanced expander, so a delay-only error would otherwise be invisible at
                        // the point of interaction (the footer is pinned while content scrolls).
                        MockRuleEditorFooterContent(
                            errors = errors,
                            showErrors = showErrors,
                            colors = colors,
                            onCancel = onCancel,
                            onSaveClick = {
                                showErrors = true
                                if (errors.delay != null) advancedExpanded = true
                                val bodyCleared = !isTruncatedCapture(target) || acknowledgedTruncatedBody
                                if (errors.isValid && bodyCleared) onSave(form.toMockRule(initial))
                            },
                        )
                    }
                }
            },
    ) {
        MockRuleEditorFormContent(
            form = form,
            target = target,
            colors = colors,
            isNew = isNew,
            errors = errors,
            showErrors = showErrors,
            advancedExpanded = advancedExpanded,
            onAdvancedExpandedChange = { advancedExpanded = it },
            acknowledgedTruncatedBody = acknowledgedTruncatedBody,
            onAcknowledgeTruncatedBody = { acknowledgedTruncatedBody = it },
            bodyState = bodyState,
            headersExpanded = headersExpanded,
            onHeadersExpandedChange = { headersExpanded = it },
        )
        if (!isNew && onDelete != null) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Delete rule",
                    color = colors.error,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier =
                        Modifier
                            .clickable(onClick = onDelete, role = Role.Button)
                            .semantics { contentDescription = "Delete mock rule ${initial.id}" }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
    // Sibling of the scaffold, not a child: it covers the form while open, and the form keeps its
    // scroll position and every other field's state underneath.
    if (bodyState.fullScreen) {
        MockRuleBodyFullScreenEditor(
            value = form.bodyText.value,
            onValueChange = { form.bodyText.value = it },
            colors = colors,
            state = bodyState,
            onDismiss = { bodyState.fullScreen = false },
        )
    }
}

@Composable
private fun MockRuleEditorFooterContent(
    errors: MockRuleFormErrors,
    showErrors: Boolean,
    colors: DevConsoleColors,
    onCancel: () -> Unit,
    onSaveClick: () -> Unit,
) {
    // Errors stay hidden until the first Save: a fresh form (blank id, a just-picked action) is
    // incomplete, not wrong, and greeting it in red reads as a failure the user didn't cause.
    if (showErrors && !errors.isValid) MockRuleSaveErrorSummary(errors, colors)
    MockRuleEditorFooter(colors, onCancel, onSaveClick)
}

@Composable
private fun MockRuleEditorFormContent(
    form: MockRuleFormFields,
    target: MockRuleEditorTarget,
    colors: DevConsoleColors,
    isNew: Boolean,
    errors: MockRuleFormErrors,
    showErrors: Boolean,
    advancedExpanded: Boolean,
    onAdvancedExpandedChange: (Boolean) -> Unit,
    acknowledgedTruncatedBody: Boolean,
    onAcknowledgeTruncatedBody: (Boolean) -> Unit,
    bodyState: MockRuleBodyEditorState,
    headersExpanded: Boolean,
    onHeadersExpandedChange: (Boolean) -> Unit,
) {
    MockRuleEditorPrimaryFields(form, colors, isNew, errors, showErrors)
    MockRuleEditorActionFields(form, colors, errors, showErrors)
    MockRuleEditorBodyFields(
        form = form,
        colors = colors,
        showErrors = showErrors,
        delayError = errors.delay,
        advancedExpanded = advancedExpanded,
        onAdvancedExpandedChange = onAdvancedExpandedChange,
        prefillNote = (target as? MockRuleEditorTarget.New)?.prefillNote,
        truncationWarning =
            if (isTruncatedCapture(target)) {
                "Captured response was truncated. Review or edit the body, then confirm below before saving."
            } else {
                null
            },
        acknowledgedTruncatedBody = acknowledgedTruncatedBody,
        onAcknowledgeTruncatedBody = onAcknowledgeTruncatedBody,
        bodyState = bodyState,
        headersExpanded = headersExpanded,
        onHeadersExpandedChange = onHeadersExpandedChange,
    )
}

private fun MockRuleEditorTarget.initialRule(): InspectorMockRuleUi =
    when (this) {
        is MockRuleEditorTarget.Edit -> rule
        is MockRuleEditorTarget.New -> draft
    }

@Composable
private fun rememberMockRuleFormErrors(form: MockRuleFormFields): MockRuleFormErrors {
    val type = form.actionType.value
    return remember(
        type,
        form.id.value,
        form.statusText.value,
        form.delayText.value,
        form.timeoutText.value,
        form.throttleKbpsText.value,
        form.throttleLatencyText.value,
        form.remoteSchemeText.value,
        form.remoteHostText.value,
        form.remotePortText.value,
        form.remotePathText.value,
    ) {
        MockRuleFormErrors(
            id = mockRuleIdError(form.id.value.trim()),
            status =
                if (type.usesStatusCode) {
                    mockRuleStatusError(
                        form.statusText.value
                            .trim()
                            .toIntOrNull(),
                    )
                } else {
                    null
                },
            delay =
                if (type == InspectorMockActionType.STATIC) {
                    mockRuleDelayError(
                        form.delayText.value.trim(),
                        form.delayText.value
                            .trim()
                            .toLongOrNull(),
                    )
                } else {
                    null
                },
            timeout = if (type == InspectorMockActionType.TIMEOUT) form.timeoutError() else null,
            throttleRate = if (type == InspectorMockActionType.THROTTLE) form.throttleRateError() else null,
            throttleLatency = if (type == InspectorMockActionType.THROTTLE) form.throttleLatencyError() else null,
            remote = if (type == InspectorMockActionType.MAP_REMOTE) form.mapRemoteError() else null,
            remoteScheme = if (type == InspectorMockActionType.MAP_REMOTE) form.remoteSchemeError() else null,
            remoteHost = if (type == InspectorMockActionType.MAP_REMOTE) form.remoteHostError() else null,
            remotePath = if (type == InspectorMockActionType.MAP_REMOTE) form.remotePathError() else null,
            remotePort = if (type == InspectorMockActionType.MAP_REMOTE) form.remotePortError() else null,
        )
    }
}

private val InspectorMockActionType.usesStatusCode: Boolean
    get() = this == InspectorMockActionType.STATIC || this == InspectorMockActionType.STATUS_OVERRIDE

private fun MockRuleFormFields.timeoutError(): String? =
    mockRuleTimeoutError(timeoutText.value.trim(), timeoutText.value.trim().toLongOrNull())

private fun MockRuleFormFields.throttleRateError(): String? {
    val bytesPerSecond =
        throttleKbpsText.value
            .trim()
            .toLongOrNull()
            ?.times(MOCK_RULE_BYTES_PER_KB)
    return mockRuleThrottleRateError(throttleKbpsText.value.trim(), bytesPerSecond)
}

private fun MockRuleFormFields.throttleLatencyError(): String? =
    mockRuleThrottleLatencyError(throttleLatencyText.value.trim(), throttleLatencyText.value.trim().toLongOrNull())

private fun MockRuleFormFields.mapRemoteError(): String? =
    mockRuleMapRemoteError(
        hasAnyField =
            remoteSchemeText.value.isNotBlank() ||
                remoteHostText.value.isNotBlank() ||
                remotePortText.value.isNotBlank() ||
                remotePathText.value.isNotBlank(),
    )

private fun MockRuleFormFields.remoteSchemeError(): String? = mockRuleRemoteSchemeError(remoteSchemeText.value.trim())

private fun MockRuleFormFields.remoteHostError(): String? = mockRuleRemoteHostError(remoteHostText.value.trim())

private fun MockRuleFormFields.remotePathError(): String? = mockRuleRemotePathError(remotePathText.value.trim())

private fun MockRuleFormFields.remotePortError(): String? =
    mockRuleRemotePortError(remotePortText.value.trim(), remotePortText.value.trim().toIntOrNull())

@Composable
private fun MockRuleEditorFooter(
    colors: DevConsoleColors,
    onCancel: () -> Unit,
    onSaveClick: () -> Unit,
) {
    InspectorDetailFooterBar(
        listOf(
            InspectorFooterAction(
                label = "Cancel",
                onClick = onCancel,
                weight = 1f,
                containerColor = colors.panel,
                contentColor = colors.ink,
                outlined = true,
            ),
            InspectorFooterAction(
                label = "Save rule",
                onClick = onSaveClick,
                weight = 2f,
            ),
        ),
    )
}

/** "Fix N field(s) before saving" -- pinned above the footer bar so a failed Save is never silent. */
@Composable
private fun MockRuleSaveErrorSummary(
    errors: MockRuleFormErrors,
    colors: DevConsoleColors,
) {
    val count = errors.allErrors.size
    Text(
        "Fix $count field${if (count == 1) "" else "s"} before saving",
        color = colors.error,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.5.sp,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.panel)
                .padding(start = 16.dp, end = 16.dp, top = 10.dp)
                .semantics(mergeDescendants = true) {},
    )
}

@Composable
private fun MockRuleEditorPrimaryFields(
    form: MockRuleFormFields,
    colors: DevConsoleColors,
    isNew: Boolean,
    errors: MockRuleFormErrors,
    showErrors: Boolean,
) {
    MockRuleTextField(
        "Id",
        form.id.value,
        { form.id.value = it },
        colors,
        enabled = isNew,
        placeholder = "rule-checkout",
        errorText = if (showErrors) errors.id else null,
    )
    MockRuleFieldLabel("Method", colors)
    InspectorSegmentedControl(
        options = form.methodChips,
        selectedIndex = form.methodChips.indexOf(form.method.value),
        onSelect = { form.method.value = form.methodChips[it] },
        modifier = Modifier.padding(bottom = 16.dp).horizontalScroll(rememberScrollState()),
    )
    MockRuleTextField("Path regex", form.pathPattern.value, { form.pathPattern.value = it }, colors, placeholder = ".*")
    MockRuleActionPicker(
        selected = form.actionType.value,
        onSelect = form::selectActionType,
        colors = colors,
    )
    if (form.actionType.value.usesStatusCode) {
        MockRuleTextField(
            "Status",
            form.statusText.value,
            { form.statusText.value = it.filter(Char::isDigit) },
            colors,
            placeholder = "200",
            errorText = if (showErrors) errors.status else null,
        )
    }
}

/** The "Action" dropdown: the seven shapes the on-device sheet can build losslessly. */
@Composable
private fun MockRuleActionPicker(
    selected: InspectorMockActionType,
    onSelect: (InspectorMockActionType) -> Unit,
    colors: DevConsoleColors,
) {
    var expanded by remember { mutableStateOf(false) }
    MockRuleFieldLabel("Action", colors)
    Box(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, colors.line, RoundedCornerShape(12.dp))
                    .background(colors.panel)
                    .clickable(role = Role.Button) { expanded = true }
                    .padding(horizontal = 16.dp)
                    .semantics { contentDescription = "Action, ${selected.label()}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selected.label(),
                color = colors.ink,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text("▾", color = colors.muted, fontSize = 14.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MOCK_RULE_ACTION_TYPES.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.label()) },
                    onClick = {
                        expanded = false
                        onSelect(type)
                    },
                    modifier = Modifier.semantics { contentDescription = "Action ${type.label()}" },
                )
            }
        }
    }
}

/** The fields that only exist for one action type; STATIC's fields live in the main body section. */
@Composable
private fun MockRuleEditorActionFields(
    form: MockRuleFormFields,
    colors: DevConsoleColors,
    errors: MockRuleFormErrors,
    showErrors: Boolean,
) {
    when (form.actionType.value) {
        InspectorMockActionType.CONNECTION_FAILURE ->
            MockRuleTextField(
                "Failure message (optional)",
                form.failureMessageText.value,
                { form.failureMessageText.value = it },
                colors,
                placeholder = "DevConsole simulated connection failure",
            )
        InspectorMockActionType.TIMEOUT ->
            MockRuleTextField(
                "Timeout (ms)",
                form.timeoutText.value,
                { form.timeoutText.value = it.filter(Char::isDigit) },
                colors,
                placeholder = "1000",
                errorText = if (showErrors) errors.timeout else null,
            )
        InspectorMockActionType.THROTTLE -> {
            MockRuleTextField(
                "Rate (KB/s)",
                form.throttleKbpsText.value,
                { form.throttleKbpsText.value = it.filter(Char::isDigit) },
                colors,
                placeholder = "64",
                errorText = if (showErrors) errors.throttleRate else null,
            )
            MockRuleTextField(
                "Latency (ms)",
                form.throttleLatencyText.value,
                { form.throttleLatencyText.value = it.filter(Char::isDigit) },
                colors,
                placeholder = "0",
                errorText = if (showErrors) errors.throttleLatency else null,
            )
        }
        InspectorMockActionType.MAP_REMOTE -> MockRuleMapRemoteFields(form, colors, errors, showErrors)
        else -> Unit
    }
}

@Composable
private fun MockRuleMapRemoteFields(
    form: MockRuleFormFields,
    colors: DevConsoleColors,
    errors: MockRuleFormErrors,
    showErrors: Boolean,
) {
    if (showErrors && errors.remote != null) {
        Text(
            errors.remote,
            color = colors.error,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    MockRuleTextField(
        "Scheme",
        form.remoteSchemeText.value,
        { form.remoteSchemeText.value = it },
        colors,
        placeholder = "https",
        errorText = if (showErrors) errors.remoteScheme else null,
    )
    MockRuleTextField(
        "Host",
        form.remoteHostText.value,
        { form.remoteHostText.value = it },
        colors,
        placeholder = "staging.example.test",
        errorText = if (showErrors) errors.remoteHost else null,
    )
    MockRuleTextField(
        "Port",
        form.remotePortText.value,
        { form.remotePortText.value = it.filter(Char::isDigit) },
        colors,
        placeholder = "443",
        errorText = if (showErrors) errors.remotePort else null,
    )
    MockRuleTextField(
        "Path",
        form.remotePathText.value,
        { form.remotePathText.value = it },
        colors,
        placeholder = "/v2/orders",
        errorText = if (showErrors) errors.remotePath else null,
    )
}

@Suppress("LongParameterList", "LongMethod") // One real input per field group this scaffold renders.
@Composable
private fun MockRuleEditorBodyFields(
    form: MockRuleFormFields,
    colors: DevConsoleColors,
    showErrors: Boolean,
    delayError: String?,
    advancedExpanded: Boolean,
    onAdvancedExpandedChange: (Boolean) -> Unit,
    prefillNote: String?,
    truncationWarning: String?,
    acknowledgedTruncatedBody: Boolean,
    onAcknowledgeTruncatedBody: (Boolean) -> Unit,
    headersExpanded: Boolean,
    onHeadersExpandedChange: (Boolean) -> Unit,
    bodyState: MockRuleBodyEditorState,
) {
    when (form.actionType.value) {
        InspectorMockActionType.STATIC -> {
            MockRuleHeadersSection(
                form = form,
                colors = colors,
                expanded = headersExpanded,
                onToggle = { onHeadersExpandedChange(!headersExpanded) },
            )
            MockRuleBodyField(
                value = form.bodyText.value,
                onValueChange = { form.bodyText.value = it },
                colors = colors,
                state = bodyState,
            )
        }
        InspectorMockActionType.BODY_REPLACEMENT ->
            MockRuleBodyField(
                value = form.bodyText.value,
                onValueChange = { form.bodyText.value = it },
                colors = colors,
                state = bodyState,
            )
        else -> Unit
    }
    if (prefillNote != null) {
        WarnNote(prefillNote, modifier = Modifier.padding(bottom = 16.dp))
    }
    if (truncationWarning != null) {
        WarnNote(truncationWarning, modifier = Modifier.padding(bottom = 8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .clickable {
                        onAcknowledgeTruncatedBody(!acknowledgedTruncatedBody)
                    }.padding(bottom = 16.dp),
        ) {
            Checkbox(
                checked = acknowledgedTruncatedBody,
                onCheckedChange = onAcknowledgeTruncatedBody,
            )
            Text("I reviewed the captured body", color = colors.ink)
        }
    }
    MockRuleMatchConditionsSection(form = form, colors = colors)
    CollapsibleSection(
        label = "Advanced options",
        expanded = advancedExpanded,
        onToggle = { onAdvancedExpandedChange(!advancedExpanded) },
        modifier = Modifier.padding(bottom = 12.dp),
    ) {
        MockRuleAdvancedFields(form, colors, showErrors, delayError)
    }
}

/**
 * Request-matching predicates: query params and headers as `key=value` lines plus a body regex.
 * Lines without `=` are counted and shown as a non-blocking hint, matching the headers section.
 */
@Composable
private fun MockRuleMatchConditionsSection(
    form: MockRuleFormFields,
    colors: DevConsoleColors,
) {
    val query = remember(form.queryPredicatesText.value) { parseMockRulePredicateLines(form.queryPredicatesText.value) }
    val headers =
        remember(form.headerPredicatesText.value) { parseMockRulePredicateLines(form.headerPredicatesText.value) }
    var expanded by rememberSaveable { mutableStateOf(query.skippedLines + headers.skippedLines > 0) }
    val skipped = query.skippedLines + headers.skippedLines
    CollapsibleSection(
        label = "Match conditions",
        expanded = expanded,
        onToggle = { expanded = !expanded },
        meta =
            if (skipped == 0) {
                (
                    query.predicates.size + headers.predicates.size +
                        if (form.bodyPredicateText.value.isBlank()) 0 else 1
                ).let { count ->
                    when (count) {
                        0 -> "Any request"
                        1 -> "1 condition"
                        else -> "$count conditions"
                    }
                }
            } else {
                "${query.predicates.size + headers.predicates.size} \u00b7 $skipped ignored"
            },
        metaColor = if (skipped > 0) colors.warn else colors.muted,
        modifier = Modifier.padding(bottom = 12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            MockRuleMultilineField(
                label = "Query predicates (key=value)",
                value = form.queryPredicatesText.value,
                onValueChange = { form.queryPredicatesText.value = it },
                colors = colors,
                placeholder = "page=1",
            )
            MockRuleMultilineField(
                label = "Header predicates (key=value)",
                value = form.headerPredicatesText.value,
                onValueChange = { form.headerPredicatesText.value = it },
                colors = colors,
                placeholder = "Accept=application/json",
            )
            MockRuleTextField(
                "Body regex (whole body, up to 8 KB)",
                form.bodyPredicateText.value,
                { form.bodyPredicateText.value = it },
                colors,
                placeholder = ".*\"paid\".*",
            )
        }
    }
}

@Composable
private fun MockRuleMultilineField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    colors: DevConsoleColors,
    placeholder: String,
) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        MockRuleFieldLabel(label, colors)
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .onFocusChanged { focused = it.hasFocus }
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, if (focused) colors.signal else colors.line, RoundedCornerShape(12.dp))
                    .background(colors.panel)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics(mergeDescendants = true) { contentDescription = label },
        ) {
            InspectorMultilineTextField(value, onValueChange, placeholder, colors.ink, colors.text3)
        }
    }
}

@Composable
private fun MockRuleAdvancedFields(
    form: MockRuleFormFields,
    colors: DevConsoleColors,
    showErrors: Boolean,
    delayError: String?,
) {
    Column(Modifier.padding(12.dp)) {
        MockRuleTextField(
            "Priority",
            form.priorityText.value,
            { form.priorityText.value = it.filter(Char::isDigit) },
            colors,
        )
        MockRuleFieldLabel("Scope", colors)
        InspectorSegmentedControl(
            options = form.scopeChips,
            selectedIndex = form.scopeChips.indexOf(form.scope.value),
            onSelect = { form.scope.value = form.scopeChips[it] },
            modifier = Modifier.padding(bottom = 16.dp).horizontalScroll(rememberScrollState()),
        )
        MockRuleTextField("Scheme", form.scheme.value, { form.scheme.value = it }, colors, placeholder = "https")
        MockRuleTextField("Host", form.host.value, { form.host.value = it }, colors, placeholder = "api.example.test")
        if (form.actionType.value == InspectorMockActionType.STATIC) {
            MockRuleTextField(
                "Delay (ms)",
                form.delayText.value,
                { form.delayText.value = it.filter(Char::isDigit) },
                colors,
                placeholder = "0",
                errorText = if (showErrors) delayError else null,
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MockRuleEditorHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    colors: DevConsoleColors,
) {
    androidx.compose.material3.TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    color = colors.ink,
                )
                Text(
                    text = subtitle,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    maxLines = 1,
                )
            }
        },
        navigationIcon = {
            androidx.compose.material3.IconButton(onClick = onBack) {
                InspectorGlyphIcon(
                    InspectorGlyph.ChevronDown,
                    contentDescription = "Cancel",
                    tint = colors.ink,
                    size = 20.dp,
                    rotationDegrees = 90f,
                )
            }
        },
        colors =
            androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
            ),
        // The workspace Scaffold already pads its content by the status-bar inset, and this editor
        // renders inside that padding -- TopAppBar's default windowInsets would add it a second
        // time, which is the blank strip above the title. Same zeroing InspectorTopArea does for
        // every other screen's header.
        windowInsets =
            androidx.compose.foundation.layout
                .WindowInsets(0.dp),
    )
}

@Composable
private fun MockRuleFieldLabel(
    text: String,
    colors: DevConsoleColors,
) {
    Text(
        text,
        color = colors.muted,
        style = DevConsoleType.groupLabel,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

@Suppress("LongParameterList") // Value/placeholder/error/single-vs-multiline all vary per call site.
@Composable
private fun MockRuleTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    colors: DevConsoleColors,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    errorText: String? = null,
) {
    // The field's own accessible name, announced on the actual editable/value node (not merged with
    // the sibling label Text) so its edit action stays reachable.
    val fieldDescription = if (errorText != null) "$label, error: $errorText" else label
    var focused by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        MockRuleFieldLabel(label, colors)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .onFocusChanged { focused = it.hasFocus }
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, if (focused) colors.signal else colors.line, RoundedCornerShape(12.dp))
                    .background(if (enabled) colors.panel else colors.surface3)
                    .padding(horizontal = 16.dp)
                    .semantics(mergeDescendants = true) { contentDescription = fieldDescription },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (enabled) {
                InspectorPlainTextField(value, onValueChange, placeholder, colors.ink, colors.text3)
            } else {
                Text(
                    value.ifBlank { placeholder },
                    color = colors.muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    maxLines = 1,
                )
            }
        }
        if (errorText != null) {
            Text(
                errorText,
                color = colors.error,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
    }
}

/**
 * Response headers behind a [CollapsibleSection]. The meta line carries what collapsing would
 * otherwise hide: how many headers are in there, and how many lines Save is going to drop on the
 * floor (a line with no colon parses to nothing -- silent until now).
 */
@Composable
private fun MockRuleHeadersSection(
    form: MockRuleFormFields,
    colors: DevConsoleColors,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val parsed = remember(form.headersText.value) { parseMockRuleHeaderLines(form.headersText.value) }
    var focused by remember { mutableStateOf(false) }
    CollapsibleSection(
        label = "Response headers",
        expanded = expanded,
        onToggle = onToggle,
        meta = parsed.metaLabel(),
        metaColor = if (parsed.skippedLines > 0) colors.warn else colors.muted,
        modifier = Modifier.padding(bottom = 12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .heightIn(min = 90.dp)
                    .onFocusChanged { focused = it.hasFocus }
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, if (focused) colors.signal else colors.line, RoundedCornerShape(12.dp))
                    .background(colors.panel)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "Response headers" },
        ) {
            InspectorMultilineTextField(
                form.headersText.value,
                { form.headersText.value = it },
                "Content-Type: application/json",
                colors.ink,
                colors.text3,
            )
        }
    }
}

/**
 * Kept short on purpose: the section header gives the label only what is left after the meta, and
 * "23 headers \u00b7 1 ignored" is long enough to ellipsize "Response headers" itself. The noun is
 * already in the label, so the flagged variant drops it.
 */
private fun ParsedMockRuleHeaders.metaLabel(): String =
    if (skippedLines == 0) {
        "${headers.size} header${if (headers.size == 1) "" else "s"}"
    } else {
        "${headers.size} \u00b7 $skippedLines ignored"
    }

/**
 * Response body field with a non-blocking JSON Format action and a FIND toggle that reveals a
 * key search: matching keys get the same wash the read-only viewer uses, and the count sits in the
 * search field itself. Collapsed by default -- most rules are small enough to read.
 */
@Composable
private fun MockRuleBodyField(
    value: String,
    onValueChange: (String) -> Unit,
    colors: DevConsoleColors,
    state: MockRuleBodyEditorState,
) {
    MockRuleBodyEditor(
        value = value,
        onValueChange = onValueChange,
        colors = colors,
        state = state,
        modifier = Modifier.padding(bottom = 16.dp),
        expandLabel = "EXPAND",
        expandDescription = "Edit response body full screen",
        onToggleFullScreen = { state.fullScreen = true },
    )
}

/**
 * The response body, edited on its own screen.
 *
 * A mocked payload is routinely hundreds of lines, and inline the field is a 140dp window sitting
 * under a sticky Save bar -- with the keyboard up, roughly one line of what you are typing is
 * visible. This is the same editor given the whole viewport: same [state], same form-backed text,
 * so it is a bigger window onto one editing session rather than a separate draft with its own
 * save/discard semantics to get wrong. `imePadding` here (not in the caller) because this Surface
 * covers the scaffold that would otherwise provide it.
 */
@Composable
private fun MockRuleBodyFullScreenEditor(
    value: String,
    onValueChange: (String) -> Unit,
    colors: DevConsoleColors,
    state: MockRuleBodyEditorState,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Surface(modifier = Modifier.fillMaxSize(), color = colors.ground) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            MockRuleEditorHeader(
                title = "Response body",
                subtitle = "Edits apply to the rule as you type",
                onBack = onDismiss,
                colors = colors,
            )
            MockRuleBodyEditor(
                value = value,
                onValueChange = onValueChange,
                colors = colors,
                state = state,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
                showLabel = false,
                fillHeight = true,
                expandLabel = "COLLAPSE",
                expandDescription = "Return to the rule form",
                onToggleFullScreen = onDismiss,
            )
            // Same rule as the form's own footer: while the keyboard is up the body gets the
            // space, and "Done" would be offering to finish an edit still being typed. The body is
            // multi-line, so its Enter key inserts a newline rather than confirming -- dismissing
            // the keyboard is back (or the toolbar's own key), and the bar returns with it.
            if (!WindowInsets.isImeVisible) {
                InspectorDetailFooterBar(
                    listOf(
                        InspectorFooterAction(
                            label = "Done",
                            onClick = onDismiss,
                            weight = 1f,
                            supportingText = "Edits are already in the rule",
                        ),
                    ),
                )
            }
        }
    }
}

/**
 * Response body editor: a non-blocking JSON Format action, a FIND toggle that reveals a key search
 * (matching keys get the same wash the read-only viewer uses, with the count in the search field),
 * and an expand/collapse action. Collapsed search by default -- most rules are small enough to
 * read.
 *
 * Rendered twice against one [state]: inline in the form ([fillHeight] false, so the field keeps a
 * 140dp floor inside the scrolling column) and full screen ([fillHeight] true, so it takes the
 * viewport that is left). Everything that is per-session -- query, stepped match, caret, format
 * error -- lives in [state] rather than here, so switching between the two keeps it.
 */
@Suppress("LongParameterList") // Two renderings of one editor; each difference is its own flag.
@Composable
private fun MockRuleBodyEditor(
    value: String,
    onValueChange: (String) -> Unit,
    colors: DevConsoleColors,
    state: MockRuleBodyEditorState,
    expandLabel: String,
    expandDescription: String,
    onToggleFullScreen: () -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    fillHeight: Boolean = false,
) {
    val matches = remember(value, state.query) { bodySearchMatches(value, state.query) }
    // Editing the body or the query re-keys this, so the arrows always restart from the first hit
    // rather than pointing at an ordinal that no longer means the same match.
    LaunchedEffect(matches) { state.activeIndex = 0 }
    val activeMatch = matches.getOrNull(state.activeIndex)
    // Coerced on read: FORMAT rewrites the body underneath us, which can leave a stale range past
    // its end.
    val fieldValue = TextFieldValue(text = value, selection = state.selection.within(value.length))

    // Scroll-to-match without focus: a FocusRequester would work too, but focusing a text field
    // summons the keyboard (hide() loses the race against the IME's own show), which both covers the
    // body and leaves the scaffold's ime padding fighting the scroll. bringIntoView needs neither.
    val bringMatchIntoView = remember { BringIntoViewRequester() }
    var bodyTextLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    fun revealOffset(offset: Int) {
        val at = offset.coerceIn(0, (value.length - 1).coerceAtLeast(0))
        state.selection = TextRange(at, (at + 1).coerceAtMost(value.length))
        val layout = bodyTextLayout ?: return
        scope.launch { bringMatchIntoView.bringIntoView(layout.getBoundingBox(at)) }
    }

    // Wraps in both directions -- "next" off the last hit lands on the first, same as any find bar.
    fun jumpToMatch(step: Int) {
        if (matches.isEmpty()) return
        val next = (state.activeIndex + step).mod(matches.size)
        state.activeIndex = next
        revealOffset(matches[next].first)
        state.selection = TextRange(matches[next].first, matches[next].last + 1)
    }

    // Re-indenting valid-but-mangled JSON is the whole job here. Broken JSON can't be reformatted --
    // a formatter that guessed where your missing comma went would be worse than one that refuses --
    // so instead the caret goes to the character the parser choked on, which is the actionable half.
    fun formatBody() {
        when (val result = formatMockRuleBodyJson(value)) {
            is JsonFormatResult.Formatted -> {
                onValueChange(result.text)
                state.formatError = null
            }
            is JsonFormatResult.Error -> {
                state.formatError = "Not valid JSON: ${result.message}"
                revealOffset(result.offset)
            }
        }
    }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showLabel) MockRuleFieldLabel("Response body", colors)
            MockRuleBodyActions(
                colors = colors,
                searchExpanded = state.searchExpanded,
                // Collapsing clears the query: a hidden find that still filters nothing but is
                // secretly holding "aut" is a surprise waiting for the next time it opens. It also
                // collapses the selection the arrows left behind -- otherwise the match you stepped
                // to stays visibly highlighted by the field's own selection colour, so closing the
                // search looks like it kept one hit selected for no reason. Caret stays where the
                // match was, so typing carries on from there.
                onToggleSearch = {
                    state.searchExpanded = !state.searchExpanded
                    if (!state.searchExpanded) {
                        state.query = ""
                        state.selection = TextRange(state.selection.start)
                    }
                },
                onFormat = { formatBody() },
                expandLabel = expandLabel,
                expandDescription = expandDescription,
                onToggleFullScreen = onToggleFullScreen,
                showDone = state.bodyFocused,
                // Focus goes first: dropping it is what hides this pill and brings the Save bar
                // back, and hide() alone would leave a focused field with no keyboard.
                onDone = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                },
            )
        }
        if (state.searchExpanded) {
            MockRuleBodySearchRow(
                query = state.query,
                onQueryChange = { state.query = it },
                activeIndex = state.activeIndex,
                matchCount = matches.size,
                colors = colors,
                onStep = ::jumpToMatch,
            )
        }
        MockRuleBodyTextArea(
            value = value,
            onValueChange = onValueChange,
            fieldValue = fieldValue,
            colors = colors,
            state = state,
            activeMatch = activeMatch,
            bringMatchIntoView = bringMatchIntoView,
            onTextLayout = { bodyTextLayout = it },
            modifier = if (fillHeight) Modifier.weight(1f) else Modifier.heightIn(min = 140.dp),
            fillHeight = fillHeight,
        )
        state.formatError?.let { message ->
            Text(
                message,
                color = colors.warn,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
    }
}

/**
 * The code-styled box the body is typed into. Extracted from [MockRuleBodyEditor] so that
 * composable stays under detekt's complexity budget, and so the one real difference between the
 * inline and full-screen renderings -- who scrolls -- is stated in one place.
 */
@Suppress("LongParameterList") // Field plumbing (value, caret, highlight, layout) has no natural grouping.
@Composable
private fun MockRuleBodyTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    fieldValue: TextFieldValue,
    colors: DevConsoleColors,
    state: MockRuleBodyEditorState,
    activeMatch: IntRange?,
    bringMatchIntoView: BringIntoViewRequester,
    onTextLayout: (TextLayoutResult) -> Unit,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
) {
    val bodyDescription = state.formatError?.let { "Response body, error: $it" } ?: "Response body"
    // Full screen the field owns the box, so it has to scroll its own overflow; inline it is the
    // scaffold's column that scrolls, and a nested scroller there would trap the gesture.
    val fieldModifier =
        if (fillHeight) {
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        } else {
            Modifier
        }
    // A BasicTextField is only as tall as its text, and sizing it to the box cannot fix that: the
    // box has a minimum height and no maximum, so fillMaxSize has nothing bounded to fill. Tapping
    // the empty space under a one-line body therefore hit the box and focused nothing. Routing the
    // box's own taps to the field makes the whole code-coloured area behave like the field it
    // looks like. Taps that land on the text itself are consumed by the field first, so this never
    // steals caret placement.
    val fieldFocus = remember { FocusRequester() }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, if (state.bodyFocused) colors.signal else colors.line, RoundedCornerShape(12.dp))
                .background(colors.panel)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    // No ripple: this is a text field's dead space, not a button.
                    indication = null,
                    onClick = { fieldFocus.requestFocus() },
                ).padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics(mergeDescendants = true) { contentDescription = bodyDescription },
    ) {
        InspectorMultilineTextField(
            fieldValue,
            { edited ->
                state.selection = edited.selection
                if (edited.text != value) {
                    state.formatError = null
                    onValueChange(edited.text)
                }
            },
            "{ }",
            colors.ink,
            colors.text3,
            modifier =
                Modifier
                    .focusRequester(fieldFocus)
                    .bringIntoViewRequester(bringMatchIntoView)
                    .onFocusChanged { state.bodyFocused = it.isFocused }
                    .then(fieldModifier),
            onTextLayout = onTextLayout,
            visualTransformation = rememberJsonSyntaxTransformation(colors, state.query, activeMatch),
        )
    }
}

/** The body field's find row: query, "3/12", and the two step arrows. [onStep] takes -1 or +1. */
@Composable
private fun MockRuleBodySearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    activeIndex: Int,
    matchCount: Int,
    colors: DevConsoleColors,
    onStep: (Int) -> Unit,
) {
    InspectorDetailSearchField(
        query = query,
        onQueryChange = onQueryChange,
        matchLabel = bodyMatchLabel(query, activeIndex, matchCount),
        placeholder = "Find in body",
        matchColor = if (query.isNotBlank() && matchCount == 0) colors.warn else colors.muted,
        onPrevious = { onStep(-1) },
        onNext = { onStep(1) },
        navigationEnabled = matchCount > 0,
    )
}

/** Keeps a caret from pointing past the end of a body that FORMAT just rewrote shorter. */
private fun TextRange.within(length: Int): TextRange = TextRange(start.coerceIn(0, length), end.coerceIn(0, length))

/**
 * FIND / FORMAT / EXPAND, plus a DONE pill while the body holds focus.
 *
 * DONE exists because the body is multi-line: its Enter key inserts a newline (JSON needs it), so
 * unlike every single-line field here it cannot carry `ImeAction.Done`. Without this the only way
 * back from the keyboard is the system's own hide key, which is a different shape on every OEM and
 * absent on gesture-nav setups -- and the Save bar stays hidden until the keyboard goes.
 *
 * [FlowRow] rather than [Row]: four pills plus the "Response body" label is close to the full width
 * on a 1080px phone, so they wrap instead of clipping on anything narrower.
 */
@Suppress("LongParameterList") // Four pills, each with its own label/description/handler.
@Composable
private fun MockRuleBodyActions(
    colors: DevConsoleColors,
    searchExpanded: Boolean,
    onToggleSearch: () -> Unit,
    onFormat: () -> Unit,
    expandLabel: String,
    expandDescription: String,
    onToggleFullScreen: () -> Unit,
    showDone: Boolean,
    onDone: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MockRuleBodyAction(
            label = "FIND",
            color = if (searchExpanded) colors.ink else colors.muted,
            container = if (searchExpanded) colors.surface3 else colors.panel,
            description = if (searchExpanded) "Hide body search" else "Search response body",
            onClick = onToggleSearch,
        )
        MockRuleBodyAction(
            label = "FORMAT",
            color = colors.muted,
            container = colors.panel,
            description = "Format response body as JSON",
            onClick = onFormat,
        )
        MockRuleBodyAction(
            label = expandLabel,
            color = colors.muted,
            container = colors.panel,
            description = expandDescription,
            onClick = onToggleFullScreen,
        )
        if (showDone) {
            MockRuleBodyAction(
                label = "DONE",
                color = colors.signalInk,
                container = colors.signal,
                description = "Finish editing the body and close the keyboard",
                onClick = onDone,
            )
        }
    }
}

/** The FORMAT/FIND pills above the body field -- same shape, different tone. */
@Composable
private fun MockRuleBodyAction(
    label: String,
    color: Color,
    container: Color,
    description: String,
    onClick: () -> Unit,
) {
    val colors = DevConsoleTheme.colors
    Text(
        label,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.5.sp,
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(50))
                .border(1.dp, if (container == colors.signal) colors.signal else colors.line, RoundedCornerShape(50))
                .background(container)
                .clickable(onClick = onClick, role = Role.Button)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .semantics { contentDescription = description },
    )
}

/**
 * "3/12" while there is something to step through -- same shape as [detailSearchMatchLabel] on the
 * read-only detail screens. Empty while nothing is typed: a "0" on an empty query reads as a failed
 * search.
 */
private fun bodyMatchLabel(
    query: String,
    activeIndex: Int,
    matchCount: Int,
): String =
    when {
        query.isBlank() -> ""
        matchCount == 0 -> "No match"
        else -> "${activeIndex + 1}/$matchCount"
    }

/**
 * Editable Compose state for every field on the sheet, seeded once from [initial]. [methodChips]/
 * [scopeChips] carry the chip options actually offered -- [chipsIncluding] appends the initial
 * value when it falls outside the fixed set (e.g. a `HEAD` rule, or a scope this module's literal
 * [MOCK_RULE_SCOPES] doesn't know) so opening then saving the sheet can never silently coerce a
 * value that changes what the rule matches.
 */
@Suppress("LongParameterList") // One MutableState per real form field; this is the sheet's whole payload.
private class MockRuleFormFields(
    val id: MutableState<String>,
    val method: MutableState<String>,
    val methodChips: List<String>,
    val pathPattern: MutableState<String>,
    val statusText: MutableState<String>,
    val headersText: MutableState<String>,
    val bodyText: MutableState<String>,
    val priorityText: MutableState<String>,
    val scope: MutableState<String>,
    val scopeChips: List<String>,
    val scheme: MutableState<String>,
    val host: MutableState<String>,
    val delayText: MutableState<String>,
    val actionType: MutableState<InspectorMockActionType>,
    val timeoutText: MutableState<String>,
    val failureMessageText: MutableState<String>,
    val throttleKbpsText: MutableState<String>,
    val throttleLatencyText: MutableState<String>,
    val remoteSchemeText: MutableState<String>,
    val remoteHostText: MutableState<String>,
    val remotePortText: MutableState<String>,
    val remotePathText: MutableState<String>,
    val queryPredicatesText: MutableState<String>,
    val headerPredicatesText: MutableState<String>,
    val bodyPredicateText: MutableState<String>,
)

/**
 * Switches the rule's action and seeds that action's required fields when they are still empty, so
 * picking "Timeout" or "Throttle" yields a rule that saves as-is instead of a blank, failing field.
 * Anything the user already typed is kept.
 */
private fun MockRuleFormFields.selectActionType(type: InspectorMockActionType) {
    actionType.value = type
    when (type) {
        InspectorMockActionType.TIMEOUT -> timeoutText.fillIfBlank(MOCK_RULE_DEFAULT_TIMEOUT_MS)
        InspectorMockActionType.THROTTLE -> {
            throttleKbpsText.fillIfBlank(MOCK_RULE_DEFAULT_THROTTLE_KBPS)
            throttleLatencyText.fillIfBlank("0")
        }
        InspectorMockActionType.STATIC, InspectorMockActionType.STATUS_OVERRIDE -> statusText.fillIfBlank("200")
        else -> Unit
    }
}

private fun MutableState<String>.fillIfBlank(default: String) {
    if (value.isBlank()) value = default
}

private const val MOCK_RULE_DEFAULT_TIMEOUT_MS = "1000"
private const val MOCK_RULE_DEFAULT_THROTTLE_KBPS = "64"

/** Appends [value] to [base] only when it isn't already there -- never drops or reorders [base]. */
internal fun chipsIncluding(
    base: List<String>,
    value: String,
): List<String> = if (value.isBlank() || value in base) base else base + value

@Composable
private fun rememberMockRuleFormFields(
    initial: InspectorMockRuleUi,
    sourceKey: String,
): MockRuleFormFields {
    // Blank/null genuinely means "no filter" ("ALL"), a real value; anything else -- HEAD, OPTIONS,
    // TRACE -- is a real, narrower filter that chipsIncluding must preserve.
    val initialMethod = initial.method?.uppercase(Locale.US)?.takeIf { it.isNotBlank() } ?: "ALL"
    // sourceKey as a rememberSaveable input: when it changes (a different transaction/rule), the
    // saved value resets to initial instead of restoring a previous, unrelated draft.
    return MockRuleFormFields(
        id = rememberSaveable(sourceKey) { mutableStateOf(initial.id) },
        method = rememberSaveable(sourceKey) { mutableStateOf(initialMethod) },
        methodChips = remember(initialMethod) { chipsIncluding(MOCK_RULE_METHODS, initialMethod) },
        pathPattern = rememberSaveable(sourceKey) { mutableStateOf(initial.pathPattern) },
        statusText = rememberSaveable(sourceKey) { mutableStateOf(initial.statusCode.toString()) },
        headersText = rememberSaveable(sourceKey) { mutableStateOf(initial.headers.toMockRuleHeaderLines()) },
        // Opens pretty-printed so a captured one-line JSON body is readable without tapping
        // Format; a non-JSON body (HTML, plain text, template) stays exactly as captured.
        bodyText = rememberSaveable(sourceKey) { mutableStateOf(prettyOrRaw(initial.body)) },
        priorityText = rememberSaveable(sourceKey) { mutableStateOf(initial.priority.toString()) },
        scope = rememberSaveable(sourceKey) { mutableStateOf(initial.scope) },
        scopeChips = remember(initial.scope) { chipsIncluding(MOCK_RULE_SCOPES, initial.scope) },
        scheme = rememberSaveable(sourceKey) { mutableStateOf(initial.scheme.orEmpty()) },
        host = rememberSaveable(sourceKey) { mutableStateOf(initial.host.orEmpty()) },
        delayText =
            rememberSaveable(sourceKey) {
                mutableStateOf(
                    initial.delayMs
                        ?.takeIf { it > 0 }
                        ?.toString()
                        .orEmpty(),
                )
            },
        actionType =
            rememberSaveable(sourceKey) {
                mutableStateOf(
                    initial.actionType.takeUnless { it == InspectorMockActionType.OTHER }
                        ?: InspectorMockActionType.STATIC,
                )
            },
        timeoutText = rememberSaveable(sourceKey) { mutableStateOf(initial.timeoutMs?.toString().orEmpty()) },
        failureMessageText = rememberSaveable(sourceKey) { mutableStateOf(initial.failureMessage.orEmpty()) },
        throttleKbpsText =
            rememberSaveable(sourceKey) {
                // Rounded up, not truncated: a rule stored at 1000 B/s must not open showing "0"
                // KB/s and block Save until the user retypes a value that was already valid.
                mutableStateOf(
                    initial.throttleBytesPerSecond
                        ?.let { (it + MOCK_RULE_BYTES_PER_KB - 1) / MOCK_RULE_BYTES_PER_KB }
                        ?.toString()
                        .orEmpty(),
                )
            },
        throttleLatencyText =
            rememberSaveable(sourceKey) { mutableStateOf(initial.throttleLatencyMs?.toString().orEmpty()) },
        remoteSchemeText = rememberSaveable(sourceKey) { mutableStateOf(initial.remoteScheme.orEmpty()) },
        remoteHostText = rememberSaveable(sourceKey) { mutableStateOf(initial.remoteHost.orEmpty()) },
        remotePortText = rememberSaveable(sourceKey) { mutableStateOf(initial.remotePort?.toString().orEmpty()) },
        remotePathText = rememberSaveable(sourceKey) { mutableStateOf(initial.remotePath.orEmpty()) },
        queryPredicatesText =
            rememberSaveable(sourceKey) { mutableStateOf(initial.queryPredicates.toMockRulePredicateLines()) },
        headerPredicatesText =
            rememberSaveable(sourceKey) { mutableStateOf(initial.headerPredicates.toMockRulePredicateLines()) },
        bodyPredicateText = rememberSaveable(sourceKey) { mutableStateOf(initial.bodyPredicate.orEmpty()) },
    )
}

/** [initial]'s id-independent fields (actionLabel, enabled, hitCount, lastHitEpochMs) pass through untouched. */
private fun MockRuleFormFields.toMockRule(initial: InspectorMockRuleUi): InspectorMockRuleUi {
    val type = actionType.value
    return InspectorMockRuleUi(
        id = id.value.trim(),
        method = method.value.takeIf { it != "ALL" },
        pathPattern = pathPattern.value.trim().ifBlank { ".*" },
        actionLabel = initial.actionLabel,
        scheme = scheme.value.trim().takeIf { it.isNotBlank() },
        host = host.value.trim().takeIf { it.isNotBlank() },
        priority = priorityText.value.trim().toIntOrNull() ?: 0,
        scope = scope.value,
        statusCode = statusText.value.trim().toIntOrNull() ?: initial.statusCode,
        // The editor shows JSON pretty-printed; an untouched body is saved byte-for-byte as it was.
        body = bodyText.value.takeUnless { it == prettyOrRaw(initial.body) } ?: initial.body,
        enabled = initial.enabled,
        headers = parseMockRuleHeaderLines(headersText.value).headers,
        delayMs =
            delayText.value
                .trim()
                .toLongOrNull()
                ?.takeIf { it > 0 && type == InspectorMockActionType.STATIC },
        hitCount = initial.hitCount,
        lastHitEpochMs = initial.lastHitEpochMs,
        sourceBodySnapshot = initial.sourceBodySnapshot,
        actionType = type,
        timeoutMs =
            timeoutText.value
                .trim()
                .toLongOrNull()
                .takeIf { type == InspectorMockActionType.TIMEOUT },
        failureMessage =
            failureMessageText.value
                .trim()
                .takeIf { type == InspectorMockActionType.CONNECTION_FAILURE && it.isNotBlank() },
        throttleBytesPerSecond =
            throttleKbpsText.value
                .trim()
                .toLongOrNull()
                ?.times(MOCK_RULE_BYTES_PER_KB)
                .takeIf { type == InspectorMockActionType.THROTTLE },
        throttleLatencyMs =
            (throttleLatencyText.value.trim().toLongOrNull() ?: 0L)
                .takeIf { type == InspectorMockActionType.THROTTLE },
        remoteScheme =
            remoteSchemeText.value.trim().takeIf { type == InspectorMockActionType.MAP_REMOTE && it.isNotBlank() },
        remoteHost =
            remoteHostText.value.trim().takeIf { type == InspectorMockActionType.MAP_REMOTE && it.isNotBlank() },
        remotePath =
            remotePathText.value.trim().takeIf { type == InspectorMockActionType.MAP_REMOTE && it.isNotBlank() },
        remotePort =
            remotePortText.value
                .trim()
                .toIntOrNull()
                .takeIf { type == InspectorMockActionType.MAP_REMOTE },
        queryPredicates = parseMockRulePredicateLines(queryPredicatesText.value).predicates,
        headerPredicates = parseMockRulePredicateLines(headerPredicatesText.value).predicates,
        bodyPredicate = bodyPredicateText.value.trim().takeIf { it.isNotBlank() },
    )
}

/**
 * Header names describing the *original transport framing* of the captured response -- never valid
 * on a synthetic mock response, since [DevConsoleMockInterceptor] applies the rule's headers and
 * body directly with nothing downstream to correct them. Left in, the mock would ship a
 * `Content-Length` that no longer matches its (edited, re-formatted) body, or advertise
 * `Content-Encoding: gzip`/a stale `Transfer-Encoding`/`Connection` over what is now plaintext.
 */
private val MOCK_DRAFT_STRIPPED_HEADERS =
    setOf("content-length", "content-encoding", "transfer-encoding", "connection")

/** The exact placeholder [FullInspectorDataSource]'s `previewText()` returns for a non-textual response. */
private val BINARY_PREVIEW_PLACEHOLDER = Regex("""\[binary, \d+ bytes]""")

private const val CAPTURE_PREFILL_BINARY_NOTE =
    "The captured response body is binary and can't be prefilled here — enter one manually."

private fun isTruncatedCapture(target: MockRuleEditorTarget): Boolean =
    (target as? MockRuleEditorTarget.New)?.draft?.responseBodyTruncated == true

/**
 * Prefills a brand-new rule from a captured transaction -- the net detail's "Mock this response"
 * action. [existingIds] lets [suggestMockRuleId] avoid suggesting an id that already names a rule.
 * [InspectorTransactionUi.responsePreview] is a *redacted, possibly-truncated* capture preview, not
 * the real payload. Truncated bodies require explicit review before saving. A binary
 * response's literal `"[binary, N bytes]"` placeholder, though, is never usable as a body, so it
 * prefills empty and carries a [MockRuleEditorTarget.New.prefillNote] saying to enter one by hand.
 */
internal fun mockRuleDraftFromTransaction(
    transaction: InspectorTransactionUi,
    existingIds: Set<String> = emptySet(),
): MockRuleEditorTarget.New {
    val preview = transaction.responsePreview.orEmpty()
    val isBinary = BINARY_PREVIEW_PLACEHOLDER.matches(preview)
    val draft =
        InspectorMockRuleUi(
            id = suggestMockRuleId(transaction.method, transaction.path, existingIds),
            method = transaction.method,
            pathPattern = Regex.escape(transaction.path.substringBefore('?')),
            host = transaction.host.takeIf { it.isNotBlank() },
            statusCode = transaction.statusCode ?: 200,
            body = if (isBinary) "" else preview,
            sourceBodySnapshot = if (isBinary) null else preview,
            responseBodyTruncated = transaction.responseBodyTruncated,
            headers =
                transaction.responseHeaders.filterKeys { name ->
                    name.lowercase(Locale.US) !in MOCK_DRAFT_STRIPPED_HEADERS
                },
        )
    return MockRuleEditorTarget.New(
        draft = draft,
        prefillNote = if (isBinary) CAPTURE_PREFILL_BINARY_NOTE else null,
        sourceKey = transaction.id,
    )
}

private fun mockRuleEditorPreviewRule() =
    InspectorMockRuleUi(
        id = "rule-checkout",
        method = "POST",
        pathPattern = "/v1/checkout",
        actionLabel = "Static response (200)",
        scheme = "https",
        host = "api.acmeship.test",
        priority = 10,
        scope = "SESSION",
        statusCode = 200,
        body = "{\"ok\":true}",
        enabled = true,
        headers = mapOf("Content-Type" to "application/json"),
        delayMs = 500,
        hitCount = 3,
        lastHitEpochMs = 0L,
    )

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun MockRuleEditorNewPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        MockRuleEditorScreen(target = MockRuleEditorTarget.New(), onSave = {}, onCancel = {})
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B0E0D)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF3F6EE)
@Composable
private fun MockRuleEditorEditPreview() {
    DevConsoleTheme(darkTheme = isSystemInDarkTheme()) {
        MockRuleEditorScreen(
            target = MockRuleEditorTarget.Edit(mockRuleEditorPreviewRule()),
            onSave = {},
            onCancel = {},
        )
    }
}
