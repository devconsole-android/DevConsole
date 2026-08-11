/**
 * @author Shakib
 * @since 24/07/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "MatchingDeclarationName")

package io.devconsole.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
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
}

/**
 * Shared section container for the Data screen's Files and Database blocks: a rule, then content.
 *
 * Flat on purpose, like [CollapsibleSection] and [HeroCard] and like the dashboard's own
 * `.card-shell` (`border: 0; border-radius: 0; background: transparent`). It used to be a 14dp
 * bordered panel, which left the Data screen wearing card chrome after every sibling surface had
 * dropped it -- the same list could show a bordered card directly above a flat [TonalListRow].
 */
@Composable
internal fun TerminalCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = DevConsoleTheme.colors.line)
        Column(modifier = Modifier.padding(vertical = 12.dp), content = content)
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
