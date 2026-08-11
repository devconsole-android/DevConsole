/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")

package io.devconsole.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A screen's summary readout: rules above and below, a label, a big value with an optional suffix,
 * a subtitle, an optional collapse chevron, and an optional full-width pill CTA.
 *
 * Deliberately unfilled. This is the native twin of the dashboard's `.metric-strip` -- transparent
 * ground, `border-block: 1px solid var(--line)` -- so state reads through [labelColor] and
 * [valueColor] (error red for failing traffic, warn amber for logs) rather than through a tinted
 * container. Callers that want to shout pass the hue in those two, not in a background: a filled
 * card would reintroduce exactly the chrome this surface and its web sibling both dropped.
 */
@Suppress("LongParameterList") // Label, value, subtitle, collapse, and the optional CTA's five knobs.
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
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = DevConsoleTheme.colors.line)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeroReadout(label, value, subtitle, valueSuffix, labelColor, valueColor, valueFontFamily)
            if (onCollapse != null) {
                InspectorRoundIconButton(
                    contentDescription = collapseContentDescription,
                    onClick = onCollapse,
                    size = 40.dp,
                    icon = {
                        InspectorGlyphIcon(
                            InspectorGlyph.ChevronDown,
                            contentDescription = null,
                            tint = labelColor,
                            size = 18.dp,
                            rotationDegrees = 180f,
                        )
                    },
                )
            }
        }
        if (ctaLabel != null && onCtaClick != null) {
            InspectorPillButton(
                label = ctaLabel,
                onClick = onCtaClick,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp),
                containerColor = ctaContainerColor,
                contentColor = ctaContentColor,
                icon = ctaIcon,
            )
        }
        HorizontalDivider(color = DevConsoleTheme.colors.line)
    }
}

/** The label / value+suffix / subtitle stack that fills [HeroCard]'s leading column. */
@Suppress("LongParameterList") // Every part of the readout varies per screen.
@Composable
private fun RowScope.HeroReadout(
    label: String,
    value: String,
    subtitle: String,
    valueSuffix: String?,
    labelColor: Color,
    valueColor: Color,
    valueFontFamily: FontFamily?,
) {
    Column(modifier = Modifier.weight(1f)) {
        Text(label, color = labelColor, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                color = valueColor,
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = valueFontFamily),
            )
            if (valueSuffix != null) {
                Text(
                    valueSuffix,
                    color = DevConsoleTheme.colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
        Text(subtitle, color = DevConsoleTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
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
    valueColor: Color = DevConsoleTheme.colors.ink,
    labelColor: Color = DevConsoleTheme.colors.text3,
    expandContentDescription: String = "Expand the summary",
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
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
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = labelColor,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        InspectorGlyphIcon(InspectorGlyph.ChevronDown, contentDescription = null, tint = labelColor, size = 18.dp)
    }
}
