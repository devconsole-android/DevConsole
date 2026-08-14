/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("TooManyFunctions")

package io.devconsole.ui.compose

// TooManyFunctions is suppressed above because this file is one small, independent formatter per
// thing the inspector renders: its function count tracks that list, not any complexity.

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val LOG_SHORT_LABEL_LENGTH = 3
private const val PUSH_SHORT_LABEL_LENGTH = 5

/**
 * Short lead-badge label for a log/crash event's `kind`, covering every real
 * [io.devconsole.logs.LogLevel] and crash type this module can see -- see
 * FullInspectorDataSource.toLogUi for where these strings actually come from.
 */
internal fun logLevelShortLabel(kind: String): String =
    when (kind.uppercase(Locale.US)) {
        "ERROR" -> "ERR"
        "WARN" -> "WRN"
        "DEBUG" -> "DBG"
        "INFO" -> "INF"
        "VERBOSE" -> "VRB"
        "ASSERT" -> "AST"
        "ANR" -> "ANR"
        "UNCAUGHT" -> "CRA"
        else -> kind.take(LOG_SHORT_LABEL_LENGTH).uppercase(Locale.US)
    }

/** Whether a log's `kind` counts toward the Logs hero's "warnings and errors" total. */
internal fun isWarnOrErrorLevel(kind: String): Boolean =
    kind.uppercase(Locale.US) in setOf("ERROR", "WARN", "ANR", "UNCAUGHT", "ASSERT")

/** Returns a log level's (text color, badge background) pair. */
internal fun logLevelTint(
    kind: String,
    colors: DevConsoleColors,
): Pair<Color, Color> =
    when (kind.uppercase(Locale.US)) {
        "ERROR", "ANR", "UNCAUGHT" -> colors.error to colors.errorSoft
        "WARN", "ASSERT" -> colors.warn to colors.warnSoft
        "DEBUG", "VERBOSE" -> colors.text3 to colors.surface3
        else -> colors.muted to colors.surface3
    }

internal fun pushLifecycleShortLabel(lifecycle: String): String =
    when (lifecycle) {
        "RECEIVED" -> "RCVD"
        "DISPLAYED" -> "SHOWN"
        "OPENED" -> "OPENED"
        "SUPPRESSED" -> "SUPP"
        "ACTION_CLICKED" -> "CLICK"
        "DEEP_LINK_RESOLVED" -> "LINK"
        "HANDLING_FAILED" -> "FAIL"
        else -> lifecycle.take(PUSH_SHORT_LABEL_LENGTH)
    }

private const val CAPTURE_CLOCK_FORMAT = "HH:mm:ss"
private const val CAPTURE_CLOCK_FORMAT_SHORT = "HH:mm"
private const val CAPTURE_DATE_TIME_FORMAT = "d MMM, HH:mm:ss"

/** `HH:mm:ss` of [epochMs] in the device's default locale digits (fresh formatter per call, not shared/cached). */
internal fun formatCaptureClockTime(epochMs: Long): String =
    SimpleDateFormat(CAPTURE_CLOCK_FORMAT, Locale.US).format(Date(epochMs))

/** `HH:mm` of [epochMs], for rows tight on trailing-column width. */
internal fun formatCaptureClockTimeShort(epochMs: Long): String =
    SimpleDateFormat(CAPTURE_CLOCK_FORMAT_SHORT, Locale.US).format(Date(epochMs))

/**
 * `d MMM, HH:mm:ss` of [epochMs], for timestamps that are routinely not from today. Captures are
 * session-scoped and a bare clock time reads fine for them, but a Remote Config fetch is throttled
 * to a 12h minimum interval by default -- so `09:14:02` alone reads identically whether the fetch
 * was a minute ago or last week, which is the distinction that surface exists to make. The
 * dashboard's `toLocaleString()` already carries the date; this is the on-device counterpart.
 */
internal fun formatCaptureDateTime(epochMs: Long): String =
    SimpleDateFormat(CAPTURE_DATE_TIME_FORMAT, Locale.US).format(Date(epochMs))

private const val BYTES_PER_KB = 1024L
private const val BYTES_PER_MB = BYTES_PER_KB * 1024L
private const val BYTES_PER_GB = BYTES_PER_MB * 1024L

/** Single source for every millisecond-duration threshold in this module. */
internal const val MS_PER_SECOND = 1000L
internal const val MS_PER_MINUTE = 60L * MS_PER_SECOND
internal const val MS_PER_HOUR = 60L * MS_PER_MINUTE
internal const val MS_PER_DAY = 24L * MS_PER_HOUR

/** Shared human-readable byte size, e.g. "812 B" / "4 KB" / "1 MB". */
internal fun formatByteSize(bytes: Long): String =
    when {
        bytes >= BYTES_PER_GB -> "${bytes / BYTES_PER_GB} GB"
        bytes >= BYTES_PER_MB -> "${bytes / BYTES_PER_MB} MB"
        bytes >= BYTES_PER_KB -> "${bytes / BYTES_PER_KB} KB"
        else -> "$bytes B"
    }

/**
 * The [io.devconsole.security.RedactionEngine] default policy's replacement marker, duplicated as a
 * literal here rather than adding a `sdk:security` dependency just to read one constant. Used only
 * to *detect* an already-redacted value for warn-tinted rendering in the capture detail's
 * Redactions section. A host that customizes `RedactionPolicy.replacement` away from the default
 * simply won't get the highlight; the underlying value is rendered exactly as captured either way.
 */
private const val KNOWN_REDACTION_MARKER = "<redacted>"

internal fun String.looksRedacted(): Boolean = contains(KNOWN_REDACTION_MARKER)

/**
 * Turns a dispatched [InspectorCommandResult] into a snackbar-ready message -- the Control/Data/More
 * flash pattern, shared across every route that dispatches a gated or ungated command and just
 * wants to tell the developer what happened.
 */
internal fun InspectorCommandResult.toFlashMessage(): String =
    when (this) {
        is InspectorCommandResult.Success -> summary
        is InspectorCommandResult.Disabled -> "Blocked — $capability capability is off"
        is InspectorCommandResult.Invalid -> "Invalid: $message"
        is InspectorCommandResult.Failed -> "Failed: $message"
        InspectorCommandResult.Unavailable -> "Not available on this build"
    }

/**
 * Shares [text] as plain text via the system Share sheet, standing in for "download JSON" actions
 * -- there is no on-device JSON file to write for a single capture, and real clipboard/share
 * behavior beats inventing a fake download. Failures (no activity can handle `ACTION_SEND`) are
 * swallowed, matching [shareFileFromPath]'s existing convention for this debug-only convenience
 * action.
 */
internal fun shareTextSnippet(
    context: Context,
    text: String,
    chooserTitle: String,
) {
    runCatching {
        val sendIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        context.startActivity(Intent.createChooser(sendIntent, chooserTitle))
    }
}
