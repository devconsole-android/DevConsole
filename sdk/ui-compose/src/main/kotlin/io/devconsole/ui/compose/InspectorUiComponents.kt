/**
 * @author Shakib
 * @since 24/07/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "MatchingDeclarationName")

package io.devconsole.ui.compose

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * M3's default `Typography()` sizes for the label/body slots this file (and its Files/Database
 * consumers) used to read via [androidx.compose.material3.MaterialTheme.typography] -- pulled out
 * to explicit, bespoke [TextStyle]s per [DevConsoleType]'s "component-local sizes ... declared
 * where they're used" convention. Every slot restates M3's `LineHeightStyle(Alignment.Center,
 * Trim.None)` (M3 applies it to ALL typography slots via `TypographyTokens.DefaultTextStyle`, not
 * just labels), and [labelSmall]/[labelMedium] additionally restate M3's `FontWeight.Medium`
 * (W500) -- these are part of the slots' real defaults, not just size/letter-spacing/line-height,
 * so leaving them off would silently lighten and re-trim every text this file (and its
 * Files/Database consumers) renders relative to the `MaterialTheme.typography` read it replaced.
 */
internal object TerminalType {
    private val m3SlotLineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    val labelSmall =
        TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
            lineHeight = 16.sp,
            lineHeightStyle = m3SlotLineHeightStyle,
        )
    val labelMedium =
        TextStyle(
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
            lineHeight = 16.sp,
            lineHeightStyle = m3SlotLineHeightStyle,
        )
    val bodySmall =
        TextStyle(
            fontSize = 12.sp,
            letterSpacing = 0.4.sp,
            lineHeight = 16.sp,
            lineHeightStyle = m3SlotLineHeightStyle,
        )

    // letterSpacing 0.2 (not 0.25): m3 1.4.0's BodyMediumTracking, which this slot replaces.
    val bodyMedium =
        TextStyle(
            fontSize = 14.sp,
            letterSpacing = 0.2.sp,
            lineHeight = 20.sp,
            lineHeightStyle = m3SlotLineHeightStyle,
        )
}

/** Shared bordered panel used by the Observe and Control surfaces to match the workspace shell. */
@Composable
internal fun TerminalCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DevConsoleTheme.colors
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .border(1.dp, colors.borderStrong, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        color = colors.ground,
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
internal fun TerminalSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = DevConsoleTheme.colors.muted,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        style = TerminalType.labelMedium,
    )
}

/** Read-only banner shown in place of an editing control when its capability is disabled. */
@Composable
internal fun CapabilityDisabledNotice(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        color = DevConsoleTheme.colors.muted,
        style = TerminalType.bodySmall,
    )
}

@Composable
internal fun DetailStat(
    label: String,
    value: String,
) {
    Column {
        TerminalSectionLabel(label)
        Text(value, color = DevConsoleTheme.colors.ink, style = TerminalType.bodyMedium)
    }
}

/** Muted single-line notice used when a Data section has nothing to show. */
@Composable
internal fun SectionEmptyText(message: String) {
    Text(
        text = message,
        modifier = Modifier.semantics { contentDescription = message },
        color = DevConsoleTheme.colors.muted,
        style = TerminalType.bodySmall,
    )
}

@Composable
internal fun HeaderLines(headers: Map<String, String>) {
    val colors = DevConsoleTheme.colors
    if (headers.isEmpty()) {
        Text("(none)", color = colors.muted, style = TerminalType.bodySmall)
        return
    }
    Column {
        headers.forEach { (key, value) ->
            Text(
                text = "$key: $value",
                color = colors.ink,
                fontFamily = FontFamily.Monospace,
                style = TerminalType.bodySmall,
            )
        }
    }
}
