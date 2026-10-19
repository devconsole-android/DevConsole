/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")

package io.devconsole.ui.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
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
 * Large screen title, optional live indicator and quiet 48dp actions, with a muted sub-line below.
 */
@Composable
internal fun InspectorTopArea(
    subLine: String,
    title: String,
    modifier: Modifier = Modifier,
    actions: List<InspectorTopAction> = emptyList(),
    live: Boolean = true,
) {
    val colors = DevConsoleTheme.colors
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = colors.ink, style = MaterialTheme.typography.headlineSmall)
            if (live) {
                Row(
                    modifier = Modifier.padding(start = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(colors.signal))
                    Text("Live", color = colors.signal, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            actions.forEach { action ->
                InspectorRoundIconButton(
                    contentDescription = action.contentDescription,
                    onClick = action.onClick,
                    size = 48.dp,
                    icon = {
                        CompositionLocalProvider(LocalContentColor provides colors.muted) {
                            action.icon()
                        }
                    },
                )
            }
        }
        if (subLine.isNotEmpty()) {
            Text(
                text = subLine,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                color = DevConsoleTheme.colors.muted,
                fontSize = 13.sp,
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
/**
 * The Traffic tab's quiet "clear captures" action.
 */
internal fun clearCapturesTopAction(onClear: () -> Unit): InspectorTopAction =
    InspectorTopAction(
        contentDescription = "Clear captured requests",
        onClick = onClear,
        icon = {
            ObserveGlyphIcon(ObserveGlyph.Trash, contentDescription = null, tint = DevConsoleTheme.colors.muted)
        },
    )

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
 * Scrollable, label-width tabs with a travelling 3dp signal indicator and a 1dp baseline.
 * Measured tab bounds let the indicator interpolate across labels of different widths.
 */
@Composable
internal fun InspectorTabRow(
    tabs: List<InspectorTab>,
    modifier: Modifier = Modifier,
) {
    val colors = DevConsoleTheme.colors
    val selectedIndex = tabs.indexOfFirst { it.selected }
    val tabBounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    // Held at the last real selection while nothing is selected, so the bar never flies to tab 0
    // and back during the frame a gated tab is being swapped out from under it.
    val travel = remember { Animatable(selectedIndex.coerceAtLeast(0).toFloat()) }
    val travelSpec = feedbackSpec<Float>()
    LaunchedEffect(selectedIndex, travelSpec) {
        if (selectedIndex >= 0) travel.animateTo(selectedIndex.toFloat(), travelSpec)
    }
    Box(
        modifier =
            modifier.fillMaxWidth().drawBehind {
                val strokeWidth = 1.dp.toPx()
                val hairlineY = size.height - strokeWidth / 2
                drawLine(colors.line, Offset(0f, hairlineY), Offset(size.width, hairlineY), strokeWidth)
            },
    ) {
        // Padding before the draw: tab bounds come from positionInParent, which is measured
        // inside this padding, so the indicator must draw in the same space or it lands 16dp left.
        Row(
            modifier =
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp)
                    .drawWithContent {
                        drawContent()
                        if (selectedIndex < 0 || tabs.isEmpty()) return@drawWithContent
                        val index = travel.value.coerceIn(0f, tabs.lastIndex.toFloat())
                        val fromIndex = index.toInt()
                        val from = tabBounds[fromIndex] ?: return@drawWithContent
                        val to = tabBounds[(fromIndex + 1).coerceAtMost(tabs.lastIndex)] ?: from
                        val fraction = index - fromIndex
                        val left = from.first + (to.first - from.first) * fraction
                        val width = from.second + (to.second - from.second) * fraction
                        val radius = CornerRadius(2.dp.toPx())
                        val path =
                            Path().apply {
                                addRoundRect(
                                    RoundRect(
                                        left = left,
                                        top = size.height - 3.dp.toPx(),
                                        right = left + width,
                                        bottom = size.height,
                                        topLeftCornerRadius = radius,
                                        topRightCornerRadius = radius,
                                    ),
                                )
                            }
                        drawPath(path, colors.signal)
                    },
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            tabs.forEachIndexed { index, tab ->
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
                            .height(48.dp)
                            .onGloballyPositioned {
                                tabBounds[index] = it.positionInParent().x to it.size.width.toFloat()
                            }.clickable(onClick = tab.onClick, role = Role.Tab)
                            .semantics(mergeDescendants = true) { selected = tab.selected },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tab.label,
                        color = labelColor,
                        fontSize = 14.sp,
                        fontWeight = if (tab.selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Panel search field on the shared 16dp gutter, with a signal outline when focused. Callers use
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
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp),
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
        shape = RoundedCornerShape(12.dp),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = DevConsoleTheme.colors.panel,
                unfocusedContainerColor = DevConsoleTheme.colors.panel,
                focusedBorderColor = DevConsoleTheme.colors.signal,
                unfocusedBorderColor = DevConsoleTheme.colors.line,
                focusedTextColor = DevConsoleTheme.colors.ink,
                unfocusedTextColor = DevConsoleTheme.colors.ink,
                focusedPlaceholderColor = DevConsoleTheme.colors.muted,
                unfocusedPlaceholderColor = DevConsoleTheme.colors.muted,
                cursorColor = DevConsoleTheme.colors.signal,
            ),
    )
}

/** Quiet surface pill with a flag icon. Pair with [EvidenceFabScrollClearance]. */
@Composable
internal fun EvidenceFab(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(DevConsoleTheme.colors.panel)
                .border(1.dp, DevConsoleTheme.colors.line, RoundedCornerShape(24.dp))
                .clickable(onClick = onClick, role = Role.Button)
                .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InspectorGlyphIcon(
            InspectorGlyph.Flag,
            contentDescription = null,
            tint = DevConsoleTheme.colors.ink,
            size = 19.dp,
        )
        Text(label, color = DevConsoleTheme.colors.ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Bottom content padding a scrolling list needs so its last row clears an [EvidenceFab]. */
internal val EvidenceFabScrollClearance = 104.dp

/**
 * Signal marks the selected nav destination on both the bar and rail.
 */
@Composable
private fun navItemIconColor(selected: Boolean): Color =
    if (selected) DevConsoleTheme.colors.signal else DevConsoleTheme.colors.muted

/** One destination in [InspectorBottomNav]. */
internal data class InspectorNavItem(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val icon: @Composable () -> Unit,
)

/**
 * The bottom bar, drawn by hand rather than with Material's NavigationBar: that one animates every
 * item's colours through composition, recomposing the whole bar on each frame of a switch. Here the
 * pill fades in the draw phase and only the two items whose selection changed recompose.
 */
@Composable
internal fun InspectorNavigationBar(
    items: List<InspectorNavItem>,
    modifier: Modifier = Modifier,
) {
    val colors = DevConsoleTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.panel)
                .drawWithContent {
                    drawContent()
                    val strokeWidth = 1.dp.toPx()
                    drawLine(colors.line, Offset(0f, strokeWidth / 2), Offset(size.width, strokeWidth / 2), strokeWidth)
                }.windowInsetsPadding(NavigationBarDefaults.windowInsets)
                .height(80.dp)
                .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item -> key(item.label) { InspectorNavBarItem(item, Modifier.weight(1f)) } }
    }
}

@Composable
private fun InspectorNavBarItem(
    item: InspectorNavItem,
    modifier: Modifier = Modifier,
) {
    val colors = DevConsoleTheme.colors
    val pillAlpha = remember { Animatable(if (item.selected) 1f else 0f) }
    val pillSpec = feedbackSpec<Float>()
    LaunchedEffect(item.selected) { pillAlpha.animateTo(if (item.selected) 1f else 0f, pillSpec) }
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .selectable(
                    selected = item.selected,
                    onClick = item.onClick,
                    role = Role.Tab,
                    interactionSource = interactionSource,
                    indication = null,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = 64.dp, height = 32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .drawBehind { drawRect(colors.signalSoft, alpha = pillAlpha.value) }
                    .indication(interactionSource, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides navItemIconColor(item.selected)) { item.icon() }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            item.label,
            color = if (item.selected) colors.signal else colors.muted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
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
                label = {
                    Text(
                        item.label,
                        fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
                colors =
                    NavigationRailItemDefaults.colors(
                        indicatorColor = DevConsoleTheme.colors.signalSoft,
                        selectedIconColor = DevConsoleTheme.colors.signal,
                        selectedTextColor = DevConsoleTheme.colors.signal,
                        unselectedIconColor = DevConsoleTheme.colors.muted,
                        unselectedTextColor = DevConsoleTheme.colors.muted,
                    ),
            )
        }
    }
}
