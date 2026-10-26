/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")

package io.devconsole.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A compact grouped notice: status dot, one-line summary and optional navigation chevron.
 * Legacy headline and CTA styling parameters remain for source compatibility; [ctaLabel] names
 * the whole row's click action. [onCtaClick] takes precedence over [onCollapse].
 */
@Suppress("LongParameterList", "UnusedParameter") // Preserve the existing summary API.
@Composable
internal fun HeroCard(
    label: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    valueSuffix: String? = null,
    labelColor: Color = DevConsoleTheme.colors.text3,
    valueColor: Color = DevConsoleTheme.colors.ink,
    onCollapse: (() -> Unit)? = null,
    collapseContentDescription: String = "Collapse this summary to one line",
    valueFontFamily: FontFamily? = null,
    ctaLabel: String? = null,
    ctaIcon: (@Composable () -> Unit)? = null,
    ctaContainerColor: Color = DevConsoleTheme.colors.signal,
    ctaContentColor: Color = DevConsoleTheme.colors.signalInk,
    onCtaClick: (() -> Unit)? = null,
) {
    HeroNoticeRow(
        value = value,
        label = subtitle.ifBlank { label },
        valueColor = valueColor,
        modifier = modifier,
        onClick = onCtaClick ?: onCollapse,
        onClickLabel = ctaLabel ?: collapseContentDescription,
    )
}

/**
 * The collapsed one-line variant of [HeroCard], shown once a screen's hero has been collapsed.
 * Callers own the collapsed/expanded boolean and swap between the two composables.
 */
@Suppress("LongParameterList", "UnusedParameter") // Preserve the existing summary API.
@Composable
internal fun HeroBar(
    value: String,
    label: String,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    valueColor: Color = DevConsoleTheme.colors.ink,
    labelColor: Color = DevConsoleTheme.colors.text3,
    expandContentDescription: String = "Expand the summary",
) {
    HeroNoticeRow(value, label, valueColor, modifier, onExpand, expandContentDescription)
}

/** Shared notice surface for expanded and collapsed summaries. */
@Composable
private fun HeroNoticeRow(
    value: String,
    label: String,
    valueColor: Color,
    modifier: Modifier,
    onClick: (() -> Unit)?,
    onClickLabel: String?,
) {
    val colors = DevConsoleTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .noticeStrip()
                .heightIn(min = 48.dp)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick, onClickLabel = onClickLabel, role = Role.Button)
                    } else {
                        Modifier
                    },
                ).semantics(mergeDescendants = true) {}
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(8.dp).clip(CircleShape).background(
                if (valueColor !=
                    colors.ink
                ) {
                    valueColor
                } else {
                    colors.signal
                },
            ),
        )
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(value) }
                append(" $label")
            },
            modifier = Modifier.weight(1f),
            color = colors.ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onClick != null) {
            InspectorGlyphIcon(
                InspectorGlyph.ChevronDown,
                contentDescription = null,
                tint = colors.muted,
                size = 20.dp,
                rotationDegrees = -90f,
            )
        }
    }
}
