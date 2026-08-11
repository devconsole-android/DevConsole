/**
 * @author Shakib
 * @since 05/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "TooManyFunctions", "UnusedPrivateMember", "MatchingDeclarationName")

package io.devconsole.ui.compose

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * Which rule the create/edit sheet is showing; [InspectorMockRuleUi.id] is disabled for [Edit].
 * [New.draft] lets a caller prefill a brand-new rule (e.g. mock-from-capture's
 * [mockRuleDraftFromTransaction]) while keeping the id field editable, unlike [Edit].
 * [New.prefillNote], when set, renders under the body field -- honesty about *where* a prefilled
 * body/headers came from (a redacted, possibly-truncated capture preview) rather than presenting it
 * as the real payload.
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
 * Bundle-friendly [Saver] for a nullable [MockRuleEditorTarget.New] so the sheet's *open/closed*
 * state (not just its individual field values, which already use [rememberSaveable]) survives a
 * configuration change such as rotation. Every [InspectorMockRuleUi] field is a primitive or
 * a [Map] of strings, so the map is flattened via the same header-line format the form fields use.
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
                target?.prefillNote,
                target?.sourceKey,
                draft?.sourceBodySnapshot,
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
                        sourceBodySnapshot = saved[18] as String?,
                    ),
                prefillNote = saved[16] as String?,
                sourceKey = saved[17] as String,
            )
        },
    )

internal val MOCK_RULE_METHODS = listOf("ALL", "GET", "POST", "PUT", "PATCH", "DELETE")

/** Everything the sheet's Save button needs validated, recomputed via [remember] as the fields change. */
private data class MockRuleFormErrors(
    val id: String?,
    val status: String?,
    val delay: String?,
) {
    val isValid: Boolean get() = id == null && status == null && delay == null
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
) {
    val initial =
        when (target) {
            is MockRuleEditorTarget.Edit -> target.rule
            is MockRuleEditorTarget.New -> target.draft
        }
    val isNew = target is MockRuleEditorTarget.New
    val colors = DevConsoleTheme.colors
    val form = rememberMockRuleFormFields(initial, target.formSourceKey())
    val errors = rememberMockRuleFormErrors(form)
    var showErrors by remember { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var bodyFormatError by remember { mutableStateOf<String?>(null) }

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
        footer = {
            Column {
                // Save is never a silent no-op: the delay field lives inside the collapsed
                // Advanced expander, so a delay-only error would otherwise be invisible at the
                // point of interaction (the footer is pinned while content scrolls).
                if (showErrors && !errors.isValid) {
                    MockRuleSaveErrorSummary(errors, colors)
                }
                MockRuleEditorFooter(
                    colors = colors,
                    onCancel = onCancel,
                    onSaveClick = {
                        showErrors = true
                        if (errors.delay != null) advancedExpanded = true
                        if (errors.isValid) onSave(form.toMockRule(initial))
                    },
                )
            }
        },
    ) {
        MockRuleEditorPrimaryFields(form, colors, isNew, errors, showErrors)
        MockRuleEditorBodyFields(
            form = form,
            colors = colors,
            showErrors = showErrors,
            delayError = errors.delay,
            advancedExpanded = advancedExpanded,
            onAdvancedExpandedChange = { advancedExpanded = it },
            bodyFormatError = bodyFormatError,
            onBodyFormatErrorChange = { bodyFormatError = it },
            prefillNote = (target as? MockRuleEditorTarget.New)?.prefillNote,
        )
    }
}

@Composable
private fun rememberMockRuleFormErrors(form: MockRuleFormFields): MockRuleFormErrors =
    remember(form.id.value, form.statusText.value, form.delayText.value) {
        MockRuleFormErrors(
            id = mockRuleIdError(form.id.value.trim()),
            status =
                mockRuleStatusError(
                    form.statusText.value
                        .trim()
                        .toIntOrNull(),
                ),
            delay =
                mockRuleDelayError(
                    form.delayText.value.trim(),
                    form.delayText.value
                        .trim()
                        .toLongOrNull(),
                ),
        )
    }

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
                containerColor = colors.surface3,
                contentColor = colors.ink,
            ),
            InspectorFooterAction(label = "Save rule", onClick = onSaveClick, weight = 2f),
        ),
    )
}

/** "Fix N field(s) before saving" -- pinned above the footer bar so a failed Save is never silent. */
@Composable
private fun MockRuleSaveErrorSummary(
    errors: MockRuleFormErrors,
    colors: DevConsoleColors,
) {
    val count = listOfNotNull(errors.id, errors.status, errors.delay).size
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
    FilterChipRow(
        chips = form.methodChips.map { method -> InspectorFilterChip(method, method, form.method.value == method) },
        onChipClick = { chip -> form.method.value = chip.id },
        modifier = Modifier.padding(bottom = 16.dp),
    )
    MockRuleTextField("Path regex", form.pathPattern.value, { form.pathPattern.value = it }, colors, placeholder = ".*")
    MockRuleTextField(
        "Status",
        form.statusText.value,
        { form.statusText.value = it.filter(Char::isDigit) },
        colors,
        placeholder = "200",
        errorText = if (showErrors) errors.status else null,
    )
}

@Suppress("LongParameterList") // One real input per field group this scaffold's content slot renders.
@Composable
private fun MockRuleEditorBodyFields(
    form: MockRuleFormFields,
    colors: DevConsoleColors,
    showErrors: Boolean,
    delayError: String?,
    advancedExpanded: Boolean,
    onAdvancedExpandedChange: (Boolean) -> Unit,
    bodyFormatError: String?,
    onBodyFormatErrorChange: (String?) -> Unit,
    prefillNote: String?,
) {
    MockRuleTextField(
        "Response headers",
        form.headersText.value,
        { form.headersText.value = it },
        colors,
        placeholder = "Content-Type: application/json",
        singleLine = false,
        minLines = 3,
    )
    MockRuleBodyField(
        value = form.bodyText.value,
        onValueChange = {
            form.bodyText.value = it
            onBodyFormatErrorChange(null)
        },
        formatError = bodyFormatError,
        onFormatClick = {
            when (val result = formatMockRuleBodyJson(form.bodyText.value)) {
                is JsonFormatResult.Formatted -> {
                    form.bodyText.value = result.text
                    onBodyFormatErrorChange(null)
                }
                is JsonFormatResult.Error -> onBodyFormatErrorChange("Not valid JSON: ${result.message}")
            }
        },
        colors = colors,
    )
    if (prefillNote != null) {
        WarnNote(prefillNote, modifier = Modifier.padding(bottom = 16.dp))
    }
    CollapsibleSection(
        label = "Advanced options",
        expanded = advancedExpanded,
        onToggle = { onAdvancedExpandedChange(!advancedExpanded) },
        modifier = Modifier.padding(bottom = 12.dp),
    ) {
        MockRuleAdvancedFields(form, colors, showErrors, delayError)
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
        FilterChipRow(
            chips = form.scopeChips.map { s -> InspectorFilterChip(s, s, form.scope.value == s) },
            onChipClick = { chip -> form.scope.value = chip.id },
            modifier = Modifier.padding(bottom = 16.dp),
        )
        MockRuleTextField("Scheme", form.scheme.value, { form.scheme.value = it }, colors, placeholder = "https")
        MockRuleTextField("Host", form.host.value, { form.host.value = it }, colors, placeholder = "api.example.test")
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
    )
}

@Composable
private fun MockRuleFieldLabel(
    text: String,
    colors: DevConsoleColors,
) {
    Text(
        text.uppercase(Locale.US),
        color = colors.text3,
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
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    // The field's own accessible name, announced on the actual editable/value node (not merged with
    // the sibling label Text) so its edit action stays reachable.
    val fieldDescription = if (errorText != null) "$label, error: $errorText" else label
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        MockRuleFieldLabel(label, colors)
        if (singleLine) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (enabled) colors.surface2 else colors.surface3)
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
        } else {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 22.dp * minLines + 24.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface2)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .semantics(mergeDescendants = true) { contentDescription = fieldDescription },
            ) {
                InspectorMultilineTextField(value, onValueChange, placeholder, colors.ink, colors.text3)
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

/** Response body field with a non-blocking JSON Format action. */
@Composable
private fun MockRuleBodyField(
    value: String,
    onValueChange: (String) -> Unit,
    formatError: String?,
    onFormatClick: () -> Unit,
    colors: DevConsoleColors,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MockRuleFieldLabel("Response body", colors)
            Text(
                "FORMAT",
                color = colors.put,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.5.sp,
                modifier =
                    Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.putSoft)
                        .clickable(onClick = onFormatClick, role = Role.Button)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .semantics { contentDescription = "Format response body as JSON" },
            )
        }
        val bodyDescription = if (formatError != null) "Response body, error: $formatError" else "Response body"
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.codeBg)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics(mergeDescendants = true) { contentDescription = bodyDescription },
        ) {
            InspectorMultilineTextField(value, onValueChange, "{ }", colors.ink, colors.text3)
        }
        if (formatError != null) {
            Text(
                formatError,
                color = colors.warn,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
    }
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
)

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
        bodyText = rememberSaveable(sourceKey) { mutableStateOf(initial.body) },
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
    )
}

/** [initial]'s id-independent fields (actionLabel, enabled, hitCount, lastHitEpochMs) pass through untouched. */
private fun MockRuleFormFields.toMockRule(initial: InspectorMockRuleUi): InspectorMockRuleUi =
    InspectorMockRuleUi(
        id = id.value.trim(),
        method = method.value.takeIf { it != "ALL" },
        pathPattern = pathPattern.value.trim().ifBlank { ".*" },
        actionLabel = initial.actionLabel,
        scheme = scheme.value.trim().takeIf { it.isNotBlank() },
        host = host.value.trim().takeIf { it.isNotBlank() },
        priority = priorityText.value.trim().toIntOrNull() ?: 0,
        scope = scope.value,
        statusCode = statusText.value.trim().toIntOrNull() ?: initial.statusCode,
        body = bodyText.value,
        enabled = initial.enabled,
        headers = parseMockRuleHeaderLines(headersText.value).headers,
        delayMs =
            delayText.value
                .trim()
                .toLongOrNull()
                ?.takeIf { it > 0 },
        hitCount = initial.hitCount,
        lastHitEpochMs = initial.lastHitEpochMs,
        sourceBodySnapshot = initial.sourceBodySnapshot,
    )

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

private const val CAPTURE_PREFILL_NOTE =
    "Prefilled from a captured response — the body and headers are the redacted preview and may " +
        "be truncated. Review before saving."
private const val CAPTURE_PREFILL_BINARY_NOTE =
    "The captured response body is binary and can't be prefilled here — enter one manually."

/**
 * Prefills a brand-new rule from a captured transaction -- the net detail's "Mock this response"
 * action. [existingIds] lets [suggestMockRuleId] avoid suggesting an id that already names a rule.
 * [InspectorTransactionUi.responsePreview] is a *redacted, possibly-truncated* capture preview, not
 * the real payload -- [MockRuleEditorTarget.New.prefillNote] carries that caveat
 * to the sheet; a binary response's literal `"[binary, N bytes]"` placeholder is never usable as a
 * body, so it prefills empty with a more specific note instead.
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
            headers =
                transaction.responseHeaders.filterKeys { name ->
                    name.lowercase(Locale.US) !in MOCK_DRAFT_STRIPPED_HEADERS
                },
        )
    return MockRuleEditorTarget.New(
        draft = draft,
        prefillNote = if (isBinary) CAPTURE_PREFILL_BINARY_NOTE else CAPTURE_PREFILL_NOTE,
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
