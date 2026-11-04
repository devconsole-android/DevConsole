/**
 * @author Shakib
 * @since 03/11/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.devconsole.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Methods offerable from the resend sheet's dropdown. */
private val RESEND_METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

/** A completed resend, as the response sheet shows it. */
internal data class InspectorResendResult(
    val summary: String,
    val statusCode: Int,
    val body: String?,
)

/**
 * Narrows a composer result to what the resend sheet can actually show. Only a [InspectorCommandResult.Success]
 * that carries an HTTP status is a resend -- an export's or an enable/disable's Success has no response
 * to display and must keep its plain snackbar.
 */
internal fun resendResultFrom(result: InspectorCommandResult): InspectorResendResult? =
    (result as? InspectorCommandResult.Success)?.let { success ->
        success.statusCode?.let { statusCode ->
            // The sheet shows the status large on its own, so drop the composer's "-> 200" echo.
            InspectorResendResult(success.summary.replace(" -> $statusCode", ""), statusCode, success.body)
        }
    }

/** The editable shape of a captured request, as the resend sheet holds it. */
internal data class InspectorResendDraft(
    val method: String,
    val url: String,
    /** One `Name: value` per line, the same shape the mock-rule editor uses for headers. */
    val headerLines: String,
    val body: String,
    /** True from Send until the composer answers; the sheet stays open (and Send disabled) meanwhile. */
    val sending: Boolean = false,
    /** Why the last Send was rejected, shown in the sheet so the operator can fix the request in place. */
    val error: String? = null,
)

/** Seeds the sheet from an already-redacted capture, reusing the same URL fallback the snippets use. */
internal fun resendDraftFromTransaction(transaction: InspectorTransactionUi): InspectorResendDraft =
    InspectorResendDraft(
        method = transaction.method,
        url = transaction.url.ifBlank { "https://${transaction.host}${transaction.pathWithQuery()}" },
        headerLines = formatResendHeaderLines(transaction.requestHeaders),
        body = transaction.requestPreview.orEmpty(),
    )

internal fun formatResendHeaderLines(headers: Map<String, String>): String =
    headers.entries.joinToString("\n") { (name, value) -> "$name: $value" }

/**
 * Parses `Name: value` lines into a header map. Blank lines and lines without a name are skipped
 * rather than rejected, so a stray newline never blocks sending the rest of the request.
 */
internal fun parseResendHeaderLines(text: String): Map<String, String> =
    text
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) {
                null
            } else {
                line
                    .substring(0, separator)
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?.let { name -> name to line.substring(separator + 1).trim() }
            }
        }.toMap()

/** Turns the edited draft back into the composer request the ViewModel executes. */
internal fun InspectorResendDraft.toComposerRequest(): InspectorComposerRequest =
    InspectorComposerRequest(
        method = method.trim().uppercase(),
        url = url.trim(),
        headers = parseResendHeaderLines(headerLines),
        body = body.takeIf(String::isNotEmpty),
    )

/** Edit-and-resend modal: method dropdown, URL, header lines and body, then Send/Cancel. */
@Composable
internal fun InspectorResendSheet(
    draft: InspectorResendDraft,
    onSend: (InspectorResendDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DevConsoleTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.panel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        InspectorResendSheetContent(draft, onSend, onDismiss)
    }
}

@Suppress("LongMethod") // One field per editable request component, in one reader-friendly column.
@Composable
private fun InspectorResendSheetContent(
    draft: InspectorResendDraft,
    onSend: (InspectorResendDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DevConsoleTheme.colors
    var method by rememberSaveable { mutableStateOf(draft.method.uppercase()) }
    var url by rememberSaveable { mutableStateOf(draft.url) }
    var headerLines by rememberSaveable { mutableStateOf(draft.headerLines) }
    var body by rememberSaveable { mutableStateOf(draft.body) }
    val canSend = method.isNotBlank() && url.isNotBlank()
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Edit & resend", color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        ResendMethodDropdown(method) { method = it }
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = headerLines,
            onValueChange = { headerLines = it },
            label = { Text("Headers (one Name: value per line)") },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        )
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("Body") },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        )
        draft.error?.let { error ->
            Text(
                error,
                color = colors.error,
                fontSize = 13.sp,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("Cancel", color = colors.muted)
            }
            InspectorPillButton(
                label = if (draft.sending) "Sending…" else "Send",
                onClick = {
                    onSend(
                        InspectorResendDraft(
                            method = method.trim().uppercase(),
                            url = url.trim(),
                            headerLines = headerLines,
                            body = body,
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
                enabled = canSend && !draft.sending,
            )
        }
    }
}

@Composable
private fun ResendMethodDropdown(
    method: String,
    onMethodChange: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val colors = DevConsoleTheme.colors
    Box {
        InspectorPillButton(
            label = method,
            onClick = { expanded = true },
            containerColor = colors.surface3,
            contentColor = colors.ink,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RESEND_METHODS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onMethodChange(option)
                    },
                )
            }
        }
    }
}

/** Ceiling on what the response sheet renders; the full body is still what Copy body copies. */
private const val MAX_RESEND_RESULT_BODY_CHARS = 64_000

/** Bottom sheet showing what a resend actually returned: status, summary and response body. */
@Composable
internal fun InspectorResendResultSheet(
    result: InspectorResendResult,
    onCopyBody: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DevConsoleTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.panel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        InspectorResendResultContent(result, onCopyBody, onDismiss)
    }
}

@Composable
private fun InspectorResendResultContent(
    result: InspectorResendResult,
    onCopyBody: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DevConsoleTheme.colors
    val bodyText = result.body?.takeIf(String::isNotBlank)
    // A snackbar would render under this modal sheet, so the button itself confirms the copy.
    var copied by remember(result) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_LABEL_MS)
            copied = false
        }
    }
    val statusColor =
        when (result.statusCode) {
            in 500..599 -> colors.error
            in 400..499 -> colors.warn
            else -> colors.ink
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Response", color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(result.statusCode.toString(), color = statusColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(result.summary, color = colors.muted, fontSize = 13.sp)
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = bodyText?.let { prettyOrRaw(it.take(MAX_RESEND_RESULT_BODY_CHARS)) } ?: "(empty body)",
                color = colors.ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        }
        Text(
            "Sent from the device; not shown in Traffic and not affected by mock rules.",
            color = colors.muted,
            fontSize = 12.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("Close", color = colors.muted)
            }
            InspectorPillButton(
                label = if (copied) "Copied" else "Copy body",
                onClick = {
                    bodyText?.let(onCopyBody)
                    copied = bodyText != null
                },
                modifier = Modifier.weight(1f),
                enabled = bodyText != null,
            )
        }
    }
}

private const val COPIED_LABEL_MS = 2_000L
