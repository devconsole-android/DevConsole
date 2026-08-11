/**
 * @author Shakib
 * @since 05/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")

package io.devconsole.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen overlay for a single [InspectorCodeBlock]'s content:
 * same ground-surface + [BackHandler] pattern [InspectorObserveDetailScreen] itself uses, just a
 * header row (48dp close + mono [title]) over one large, both-axes-scrollable code block at a bigger
 * 14sp so a long payload is actually readable full-screen. [onDismiss] fires from both the header's
 * button and the system back gesture, matching the capture detail's own back/BackHandler pairing.
 * Convenience overload of the [content]-taking overload below, for the common case of one flat
 * [InspectorCodeLine] list; [InspectorFormattableBody] uses the general one to add a JSON tree.
 */
@Composable
internal fun InspectorCodeFullScreenOverlay(
    title: String,
    lines: List<InspectorCodeLine>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InspectorCodeFullScreenOverlay(title = title, onDismiss = onDismiss, modifier = modifier) {
        itemsIndexed(lines, key = { index, _ -> index }) { _, line ->
            CodeLineRow(line, fontSize = 14.sp, lineHeight = 23.8.sp)
        }
    }
}

/**
 * General form: header + a both-axes-scrollable [LazyColumn] body. Unlike the inline
 * [InspectorCodeBlock], this owns the whole remaining viewport (via `weight(1f)` in a `fillMaxSize`
 * column, not nested inside another already-scrolling one), so it virtualizes for real -- a
 * thousands-of-lines body only composes the rows actually scrolled into view. [headerTrailing] is an
 * optional control (the Raw/Formatted toggle) rendered at the header's trailing edge.
 */
@Composable
internal fun InspectorCodeFullScreenOverlay(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    headerTrailing: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val colors = DevConsoleTheme.colors
    // The dashboard's `modalpop`: rise 10px, scale up from 0.98, fade in, over OVERLAY_MS on the
    // emphasized curve. Owned here rather than at the four call sites so every overlay arrives the
    // same way, and driven from one Animatable so the whole entrance runs in the draw phase.
    val entranceSpec = feedbackSpec<Float>(InspectorMotion.OVERLAY_MS)
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, entranceSpec) }
    Surface(
        modifier =
            modifier.fillMaxSize().graphicsLayer {
                val progress = entrance.value
                alpha = progress
                scaleX = OVERLAY_ENTRY_SCALE + (1f - OVERLAY_ENTRY_SCALE) * progress
                scaleY = scaleX
                translationY = (1f - progress) * OVERLAY_ENTRY_RISE.toPx()
            },
        color = colors.ground,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CodeFullScreenHeader(title, onDismiss, headerTrailing)
            LazyColumn(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(16.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun CodeFullScreenHeader(
    title: String,
    onDismiss: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = DevConsoleTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    // Same half-stroke inset as InspectorDetailHeader's own bottom rule -- keeps the
                    // full 1dp line on-canvas instead of clipping half of it past the layout edge.
                    val strokeWidth = 1.dp.toPx()
                    val y = size.height - strokeWidth / 2
                    drawLine(colors.line, Offset(0f, y), Offset(size.width, y), strokeWidth)
                }.padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InspectorRoundIconButton(
            contentDescription = "Close full screen",
            onClick = onDismiss,
            icon = {
                InspectorGlyphIcon(
                    InspectorGlyph.ChevronDown,
                    contentDescription = null,
                    tint = colors.ink,
                    size = 20.dp,
                    rotationDegrees = 90f,
                )
            },
        )
        Text(
            title,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            color = colors.ink,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke()
    }
}

/** `modalpop`'s starting scale: near-full, so the overlay grows into place instead of zooming. */
private const val OVERLAY_ENTRY_SCALE = 0.98f

/** `modalpop`'s starting offset: a short rise, matching `translateY(10px)` on the dashboard. */
private val OVERLAY_ENTRY_RISE = 10.dp
