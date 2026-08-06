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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One 48dp round icon action in [InspectorTopArea] or a detail header. */
internal data class InspectorTopAction(
    val contentDescription: String,
    val onClick: () -> Unit,
    val icon: @Composable () -> Unit,
    val containerColor: Color = Color.Transparent,
)

/**
 * The Android inspector's screen head: a muted 13sp sub-line, trailing 48dp round icon actions,
 * and a large 27sp title below.
 */
@Composable
internal fun InspectorTopArea(
    subLine: String,
    title: String,
    modifier: Modifier = Modifier,
    actions: List<InspectorTopAction> = emptyList(),
) {
    Column(modifier = modifier.padding(top = 12.dp, start = 8.dp, end = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                subLine,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                color = DevConsoleTheme.colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            actions.forEach { action ->
                InspectorRoundIconButton(
                    contentDescription = action.contentDescription,
                    onClick = action.onClick,
                    containerColor = action.containerColor,
                    icon = action.icon,
                )
            }
        }
        Text(
            title,
            modifier = Modifier.padding(top = 2.dp, start = 8.dp, end = 8.dp, bottom = 8.dp),
            color = DevConsoleTheme.colors.ink,
            style = DevConsoleType.title,
        )
    }
}

/**
 * The theme-toggle round action every destination's [InspectorTopArea] carries. The toggle is
 * hoisted to [DevConsoleWorkspace], so every screen just needs this one action wired to the
 * workspace's callback. Added here rather than duplicated per screen since it is exactly the
 * kind of thing [InspectorTopAction] already models.
 */
@Composable
internal fun themeToggleTopAction(onToggleTheme: () -> Unit): InspectorTopAction =
    InspectorTopAction(
        contentDescription = "Toggle theme",
        onClick = onToggleTheme,
        icon = {
            ObserveGlyphIcon(ObserveGlyph.Sun, contentDescription = null, tint = DevConsoleTheme.colors.muted)
        },
    )

/** One tab in an [InspectorTabRow]. */
internal data class InspectorTab(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/** 52dp underline tabs: 3dp rounded-top indicator inset 14% each side. */
@Composable
internal fun InspectorTabRow(
    tabs: List<InspectorTab>,
    modifier: Modifier = Modifier,
) {
    val lineColor = DevConsoleTheme.colors.line
    Row(
        modifier =
            modifier.fillMaxWidth().drawBehind {
                // Centred on the exact bottom edge clips half the stroke outside the layout's
                // bounds; inset by half the stroke width so the whole 1dp line stays on-canvas.
                val strokeWidth = 1.dp.toPx()
                val y = size.height - strokeWidth / 2
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth)
            },
    ) {
        tabs.forEach { tab ->
            val indicatorColor = if (tab.selected) DevConsoleTheme.colors.signal else Color.Transparent
            val labelColor = if (tab.selected) DevConsoleTheme.colors.signal else DevConsoleTheme.colors.muted
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clickable(onClick = tab.onClick, role = Role.Tab)
                        // mergeDescendants: without it TalkBack reads the label text as a node
                        // separate from the tab's own click target.
                        .semantics(mergeDescendants = true) { selected = tab.selected },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.label,
                    color = labelColor,
                    fontSize = 13.sp,
                    fontWeight = if (tab.selected) FontWeight.SemiBold else FontWeight.Medium,
                )
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(0.72f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(topStart = 99.dp, topEnd = 99.dp))
                            .background(indicatorColor),
                )
            }
        }
    }
}

/**
 * 52dp pill search field. Callers wrap it in `LazyListScope.stickyHeader` when it needs to stick
 * to the top of a scrolling list -- this component is scroll-container agnostic.
 */
@Composable
internal fun InspectorSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(50))
                .background(DevConsoleTheme.colors.surface2)
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InspectorGlyphIcon(
            InspectorGlyph.Search,
            contentDescription = null,
            tint = DevConsoleTheme.colors.muted,
            size = 18.dp,
        )
        Box(modifier = Modifier.weight(1f)) {
            InspectorPlainTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = placeholder,
                textColor = DevConsoleTheme.colors.ink,
                placeholderColor = DevConsoleTheme.colors.text3,
            )
        }
    }
}

/** Extended FAB: flag icon + bold label. Pair with [EvidenceFabScrollClearance]. */
@Composable
internal fun EvidenceFab(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .height(56.dp)
                .shadow(DevConsoleElevation.level3, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(DevConsoleTheme.colors.signal)
                .clickable(onClick = onClick, role = Role.Button)
                .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InspectorGlyphIcon(
            InspectorGlyph.Flag,
            contentDescription = null,
            tint = DevConsoleTheme.colors.signalInk,
            size = 19.dp,
        )
        Text(label, color = DevConsoleTheme.colors.signalInk, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
    }
}

/** Bottom content padding a scrolling list needs so its last row clears an [EvidenceFab]. */
internal val EvidenceFabScrollClearance = 104.dp

/** One destination in [InspectorBottomNav]. */
internal data class InspectorNavItem(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val icon: @Composable () -> Unit,
)

/** 4-destination bottom nav: 64x32dp pill indicator, 12sp labels. */
@Composable
internal fun InspectorBottomNav(
    items: List<InspectorNavItem>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(DevConsoleTheme.colors.panel)
                .padding(top = 12.dp, start = 4.dp, end = 4.dp, bottom = 16.dp),
    ) {
        items.forEach { item -> InspectorNavDestination(item, modifier = Modifier.weight(1f)) }
    }
}

@Composable
private fun InspectorNavDestination(
    item: InspectorNavItem,
    modifier: Modifier = Modifier,
) {
    val colors = DevConsoleTheme.colors
    val pillColor = if (item.selected) colors.signal else Color.Transparent
    val labelColor = if (item.selected) colors.ink else colors.muted
    val iconColor = if (item.selected) colors.signalInk else colors.muted
    Column(
        modifier =
            modifier
                .heightIn(min = 56.dp)
                .clickable(onClick = item.onClick, role = Role.Tab)
                // mergeDescendants: without it TalkBack reads the icon and label text as nodes
                // separate from the destination's own click target. See InspectorTabRow above
                // for the same fix.
                .semantics(mergeDescendants = true) { selected = item.selected }
                .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .width(64.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(50))
                    .background(pillColor),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides iconColor) { item.icon() }
        }
        Text(
            item.label,
            color = labelColor,
            fontSize = 12.sp,
            fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}
