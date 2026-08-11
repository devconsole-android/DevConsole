/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")

package io.devconsole.ui.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InspectorTopArea(
    subLine: String,
    title: String,
    modifier: Modifier = Modifier,
    actions: List<InspectorTopAction> = emptyList(),
) {
    Column(modifier = modifier) {
        TopAppBar(
            title = { Text(title) },
            actions = {
                actions.forEach { action ->
                    IconButton(onClick = action.onClick) {
                        action.icon()
                    }
                }
            },
            colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = DevConsoleTheme.colors.ink,
                    actionIconContentColor = DevConsoleTheme.colors.muted,
                ),
            windowInsets =
                androidx.compose.foundation.layout
                    .WindowInsets(0.dp),
        )
        if (subLine.isNotEmpty()) {
            Text(
                text = subLine,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                color = DevConsoleTheme.colors.muted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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

/**
 * 52dp underline tabs: one 2dp round-capped indicator inset 14% each side, over a 1dp hairline.
 *
 * The indicator is a single bar that *travels* between tabs rather than one bar per tab flicking
 * between signal and transparent. Material tabs slide, and the slide is what carries the meaning:
 * it says which direction you moved through an ordered set, which a cut cannot. Drawn in
 * [drawBehind] from an animated index, so the travel costs a redraw per frame and not a
 * recomposition of five tabs.
 *
 * The dashboard's own `.detail-tab` keeps a static `border-bottom` here. That is a web idiom, not a
 * shared decision -- on a native surface the platform's tab behaviour wins, the same way the nav
 * bar and chips are real Material components rather than ports of the dashboard's rail.
 */
@Composable
internal fun InspectorTabRow(
    tabs: List<InspectorTab>,
    modifier: Modifier = Modifier,
) {
    val colors = DevConsoleTheme.colors
    val selectedIndex = tabs.indexOfFirst { it.selected }
    // Held at the last real selection while nothing is selected, so the bar never flies to tab 0
    // and back during the frame a gated tab is being swapped out from under it.
    val travel = remember { Animatable(selectedIndex.coerceAtLeast(0).toFloat()) }
    val travelSpec = feedbackSpec<Float>()
    LaunchedEffect(selectedIndex, travelSpec) {
        if (selectedIndex >= 0) travel.animateTo(selectedIndex.toFloat(), travelSpec)
    }
    Row(
        modifier =
            modifier.fillMaxWidth().drawBehind {
                val strokeWidth = 1.dp.toPx()
                val hairlineY = size.height - strokeWidth / 2
                drawLine(colors.line, Offset(0f, hairlineY), Offset(size.width, hairlineY), strokeWidth)
                if (selectedIndex < 0 || tabs.isEmpty()) return@drawBehind
                val tabWidth = size.width / tabs.size
                val indicatorWidth = tabWidth * TAB_INDICATOR_FRACTION
                val centerX = tabWidth * travel.value + tabWidth / 2
                val indicatorHeight = 2.dp.toPx()
                val indicatorY = size.height - indicatorHeight / 2
                drawLine(
                    color = colors.signal,
                    start = Offset(centerX - indicatorWidth / 2, indicatorY),
                    end = Offset(centerX + indicatorWidth / 2, indicatorY),
                    strokeWidth = indicatorHeight,
                    cap = StrokeCap.Round,
                )
            },
    ) {
        tabs.forEach { tab ->
            // Animated so the label meets the bar rather than snapping ahead of it.
            val labelColor by
                animateColorAsState(
                    targetValue = if (tab.selected) colors.signal else colors.muted,
                    animationSpec = feedbackSpec(),
                    label = "tabLabel",
                )
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clickable(onClick = tab.onClick, role = Role.Tab)
                        .semantics(mergeDescendants = true) { selected = tab.selected },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.label,
                    color = labelColor,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

/** Indicator width as a share of one tab's width -- the 14%-a-side inset the mock specifies. */
private const val TAB_INDICATOR_FRACTION = 0.72f

/**
 * Search field on the shared 16dp gutter: a surface-2 [OutlinedTextField] with its indicator lines
 * suppressed, shaped by [DevConsoleShapes]' `medium`. Callers wrap it in
 * `LazyListScope.stickyHeader` when it needs to stick to the top of a scrolling list -- this
 * component is scroll-container agnostic.
 */
@Composable
internal fun InspectorSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        placeholder = { Text(placeholder) },
        leadingIcon = {
            InspectorGlyphIcon(
                InspectorGlyph.Search,
                contentDescription = null,
                tint = DevConsoleTheme.colors.muted,
                size = 18.dp,
            )
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = DevConsoleTheme.colors.surface2,
                unfocusedContainerColor = DevConsoleTheme.colors.surface2,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = DevConsoleTheme.colors.ink,
                unfocusedTextColor = DevConsoleTheme.colors.ink,
                focusedPlaceholderColor = DevConsoleTheme.colors.text3,
                unfocusedPlaceholderColor = DevConsoleTheme.colors.text3,
            ),
    )
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
                .shadow(DevConsoleElevation.level3, MaterialTheme.shapes.extraLarge)
                .clip(MaterialTheme.shapes.extraLarge)
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

/**
 * Icon tint for a nav destination. The selected item sits on a signal-filled indicator pill, so its
 * icon takes `signalInk` (the on-signal role) rather than `signal` itself -- shared by the bar and
 * the rail so the two can never drift apart.
 */
@Composable
private fun navItemIconColor(selected: Boolean): Color =
    if (selected) DevConsoleTheme.colors.signalInk else DevConsoleTheme.colors.muted

/** One destination in [InspectorBottomNav]. */
internal data class InspectorNavItem(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val icon: @Composable () -> Unit,
)

@Composable
internal fun InspectorNavigationBar(
    items: List<InspectorNavItem>,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier,
        containerColor = DevConsoleTheme.colors.panel,
    ) {
        items.forEach { item ->
            NavigationBarItem(
                selected = item.selected,
                onClick = item.onClick,
                icon = {
                    CompositionLocalProvider(LocalContentColor provides navItemIconColor(item.selected)) {
                        item.icon()
                    }
                },
                label = { Text(item.label) },
                colors =
                    NavigationBarItemDefaults.colors(
                        indicatorColor = DevConsoleTheme.colors.signal,
                        selectedIconColor = DevConsoleTheme.colors.signalInk,
                        selectedTextColor = DevConsoleTheme.colors.ink,
                        unselectedIconColor = DevConsoleTheme.colors.muted,
                        unselectedTextColor = DevConsoleTheme.colors.muted,
                    ),
            )
        }
    }
}

@Composable
internal fun InspectorNavigationRail(
    items: List<InspectorNavItem>,
    modifier: Modifier = Modifier,
) {
    NavigationRail(
        modifier = modifier,
        containerColor = DevConsoleTheme.colors.panel,
    ) {
        items.forEach { item ->
            NavigationRailItem(
                selected = item.selected,
                onClick = item.onClick,
                icon = {
                    CompositionLocalProvider(LocalContentColor provides navItemIconColor(item.selected)) {
                        item.icon()
                    }
                },
                label = { Text(item.label) },
                colors =
                    NavigationRailItemDefaults.colors(
                        indicatorColor = DevConsoleTheme.colors.signal,
                        selectedIconColor = DevConsoleTheme.colors.signalInk,
                        selectedTextColor = DevConsoleTheme.colors.ink,
                        unselectedIconColor = DevConsoleTheme.colors.muted,
                        unselectedTextColor = DevConsoleTheme.colors.muted,
                    ),
            )
        }
    }
}
