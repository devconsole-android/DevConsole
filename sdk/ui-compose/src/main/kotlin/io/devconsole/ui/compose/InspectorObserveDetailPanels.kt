/**
 * @author Shakib
 * @since 19/10/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")

package io.devconsole.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Detail-only collapsible panels; the mock editor keeps its shared section layout. */
@Suppress("LongParameterList")
@Composable
internal fun ObserveDetailPanel(
    label: String,
    keyValueGroup: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    meta: String?,
    metaColor: Color,
    onCopy: (() -> Unit)?,
    copyContentDescription: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = chevronSpec(),
        label = "sectionChevron",
    )
    val heading: @Composable () -> Unit = {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (keyValueGroup) {
                    GroupLabel(label, Modifier.weight(1f))
                } else {
                    Text(
                        label,
                        modifier = Modifier.weight(1f).padding(start = 16.dp),
                        color = DevConsoleTheme.colors.ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (meta != null) {
                    Text(meta, color = metaColor, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                InspectorGlyphIcon(
                    InspectorGlyph.ChevronDown,
                    null,
                    modifier = Modifier.padding(horizontal = 12.dp),
                    tint = DevConsoleTheme.colors.muted,
                    size = 16.dp,
                    rotationDegrees = rotation,
                )
            }
            if (onCopy != null) {
                IconButton(onClick = onCopy, modifier = Modifier.size(48.dp)) {
                    InspectorGlyphIcon(
                        InspectorGlyph.Copy,
                        copyContentDescription,
                        tint = DevConsoleTheme.colors.muted,
                        size = 18.dp,
                    )
                }
            }
        }
    }
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        if (keyValueGroup) heading()
        GroupedPanel {
            if (!keyValueGroup) heading()
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(feedbackSpec(InspectorMotion.EXPAND_MS)) + fadeIn(feedbackSpec()),
                exit = shrinkVertically(feedbackSpec()) + fadeOut(feedbackSpec()),
            ) {
                Column(content = content)
            }
        }
    }
}

/** The empty Timing tab has no collapsible heading or fabricated duration bars. */
@Composable
internal fun ObserveDetailNoTiming() {
    Text(
        "No timing captured",
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        color = DevConsoleTheme.colors.muted,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
    )
}

/** One neutral phase bar, with measured values and labels below. */
@Composable
internal fun ObserveDetailTiming(stats: List<InspectorProgressStat>) {
    val colors = DevConsoleTheme.colors
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.surface3),
        ) {
            stats.forEach { stat ->
                if (stat.fraction > 0f) {
                    Box(Modifier.weight(stat.fraction).height(24.dp).background(stat.color))
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            stats.forEach { stat ->
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stat.valueText, color = colors.ink, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text(stat.label, color = colors.muted, fontSize = 11.sp)
                }
            }
        }
    }
}

/** Footer operations scroll with the capture as plain panel rows. */
@Composable
internal fun ObserveDetailActionRows(actions: List<InspectorFooterAction>) {
    if (actions.isEmpty()) return
    val colors = DevConsoleTheme.colors
    GroupedPanel(modifier = Modifier.padding(bottom = 16.dp)) {
        actions.forEachIndexed { index, action ->
            if (index > 0) GroupedPanelDivider()
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable(enabled = action.enabled, role = Role.Button, onClick = action.onClick)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        action.label,
                        color = if (action.enabled) colors.ink else colors.muted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    )
                    action.supportingText?.let { Text(it, color = colors.muted, fontSize = 12.sp) }
                }
                DevConsoleIcon(R.drawable.devconsole_ic_chevron_right, null, Modifier, colors.muted, 20.dp)
            }
        }
    }
}
