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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                titleContentColor = DevConsoleTheme.colors.ink,
                actionIconContentColor = DevConsoleTheme.colors.muted
            ),
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp)
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
                        .semantics(mergeDescendants = true) { selected = tab.selected },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.label,
                    color = labelColor,
                    style = MaterialTheme.typography.titleSmall,
                )
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(0.72f)
                            .height(2.dp)
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
        colors = TextFieldDefaults.colors(
            focusedContainerColor = DevConsoleTheme.colors.surface2,
            unfocusedContainerColor = DevConsoleTheme.colors.surface2,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = DevConsoleTheme.colors.ink,
            unfocusedTextColor = DevConsoleTheme.colors.ink,
            focusedPlaceholderColor = DevConsoleTheme.colors.text3,
            unfocusedPlaceholderColor = DevConsoleTheme.colors.text3,
        )
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
                    CompositionLocalProvider(LocalContentColor provides if (item.selected) DevConsoleTheme.colors.signalInk else DevConsoleTheme.colors.muted) {
                        item.icon()
                    }
                },
                label = { Text(item.label) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = DevConsoleTheme.colors.signal,
                    selectedIconColor = DevConsoleTheme.colors.signalInk,
                    selectedTextColor = DevConsoleTheme.colors.ink,
                    unselectedIconColor = DevConsoleTheme.colors.muted,
                    unselectedTextColor = DevConsoleTheme.colors.muted,
                )
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
                    CompositionLocalProvider(LocalContentColor provides if (item.selected) DevConsoleTheme.colors.signalInk else DevConsoleTheme.colors.muted) {
                        item.icon()
                    }
                },
                label = { Text(item.label) },
                colors = NavigationRailItemDefaults.colors(
                    indicatorColor = DevConsoleTheme.colors.signal,
                    selectedIconColor = DevConsoleTheme.colors.signalInk,
                    selectedTextColor = DevConsoleTheme.colors.ink,
                    unselectedIconColor = DevConsoleTheme.colors.muted,
                    unselectedTextColor = DevConsoleTheme.colors.muted,
                )
            )
        }
    }
}
