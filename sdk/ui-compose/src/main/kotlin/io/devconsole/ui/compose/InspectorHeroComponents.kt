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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A tinted verdict card with a big value, an optional trailing icon OR a collapse chevron (never
 * both -- the Android state always ends up collapsible), and an optional full-width pill CTA.
 */
@Suppress("LongParameterList") // Every field varies per screen.
@Composable
internal fun HeroCard(
    label: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    valueSuffix: String? = null,
    containerColor: Color = DevConsoleTheme.colors.surface2,
    labelColor: Color = DevConsoleTheme.colors.text3,
    valueColor: Color = DevConsoleTheme.colors.ink,
    icon: (@Composable () -> Unit)? = null,
    iconContainerColor: Color = DevConsoleTheme.colors.panel,
    onCollapse: (() -> Unit)? = null,
    collapseContentDescription: String = "Collapse this summary to one line",
    /** More's uptime clock reads best in mono digits; every other screen leaves this null. */
    valueFontFamily: FontFamily? = null,
    ctaLabel: String? = null,
    ctaIcon: (@Composable () -> Unit)? = null,
    ctaContainerColor: Color = DevConsoleTheme.colors.signal,
    ctaContentColor: Color = DevConsoleTheme.colors.signalInk,
    onCtaClick: (() -> Unit)? = null,
) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), color = containerColor) {
        Column(modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(label.uppercase(), color = labelColor, style = DevConsoleType.groupLabel)
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            value,
                            modifier = Modifier.alignByBaseline(),
                            color = valueColor,
                            style = DevConsoleType.heroValue.copy(fontFamily = valueFontFamily),
                        )
                        if (valueSuffix != null) {
                            Text(
                                valueSuffix,
                                modifier = Modifier.alignByBaseline(),
                                color = DevConsoleTheme.colors.muted,
                                fontSize = 14.sp,
                            )
                        }
                    }
                    Text(
                        subtitle,
                        modifier = Modifier.padding(top = 8.dp),
                        color = DevConsoleTheme.colors.muted,
                        fontSize = 13.5.sp,
                    )
                }
                HeroTrailing(icon, iconContainerColor, onCollapse, collapseContentDescription, labelColor)
            }
            if (ctaLabel != null && onCtaClick != null) {
                InspectorPillButton(
                    label = ctaLabel,
                    onClick = onCtaClick,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    containerColor = ctaContainerColor,
                    contentColor = ctaContentColor,
                    icon = ctaIcon,
                )
            }
        }
    }
}

@Composable
private fun HeroTrailing(
    icon: (@Composable () -> Unit)?,
    iconContainerColor: Color,
    onCollapse: (() -> Unit)?,
    collapseContentDescription: String,
    chevronTint: Color,
) {
    when {
        onCollapse != null ->
            InspectorRoundIconButton(
                contentDescription = collapseContentDescription,
                onClick = onCollapse,
                size = 44.dp,
                containerColor = DevConsoleTheme.colors.panel,
                icon = {
                    InspectorGlyphIcon(
                        InspectorGlyph.ChevronDown,
                        contentDescription = null,
                        tint = chevronTint,
                        size = 18.dp,
                        rotationDegrees = 180f,
                    )
                },
            )
        icon != null ->
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(iconContainerColor),
                contentAlignment = Alignment.Center,
            ) { icon() }
    }
}

/**
 * The collapsed one-line variant of [HeroCard], shown once a screen's hero has been collapsed.
 * Callers own the collapsed/expanded boolean and swap between the two composables.
 */
@Suppress("LongParameterList") // Colors and the expand description vary per screen, same as HeroCard.
@Composable
internal fun HeroBar(
    value: String,
    label: String,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = DevConsoleTheme.colors.surface2,
    valueColor: Color = DevConsoleTheme.colors.ink,
    labelColor: Color = DevConsoleTheme.colors.text3,
    expandContentDescription: String = "Expand the summary",
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(containerColor)
                .clickable(onClick = onExpand, onClickLabel = expandContentDescription, role = Role.Button)
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            value,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = labelColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        InspectorGlyphIcon(InspectorGlyph.ChevronDown, contentDescription = null, tint = labelColor, size = 18.dp)
    }
}
