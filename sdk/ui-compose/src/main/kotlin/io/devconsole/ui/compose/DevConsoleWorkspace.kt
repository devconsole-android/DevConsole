/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "UnusedPrivateMember")

package io.devconsole.ui.compose

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun DevConsoleWorkspace() {
    var selectedName by rememberSaveable { mutableStateOf(InspectorDestination.OBSERVE.name) }

    DevConsoleWorkspace(
        selected = InspectorDestination.valueOf(selectedName),
        onDestinationSelected = { selectedName = it.name },
    )
}

/**
 * The workspace shell: [InspectorBottomNav] plus whichever destination route is active. Owns
 * [darkTheme] and wraps every destination in one [DevConsoleTheme] so the toggle themes the whole
 * console, not just whichever screen last owned it, and hides the bottom nav entirely while a detail
 * overlay covers the screen. Every route now owns its mock-accurate `InspectorTopArea` directly.
 */
internal enum class InspectorNavigationLayout { Bar, Rail }

internal fun inspectorNavigationLayout(width: androidx.compose.ui.unit.Dp): InspectorNavigationLayout =
    if (width < 600.dp) InspectorNavigationLayout.Bar else InspectorNavigationLayout.Rail

@Composable
internal fun DevConsoleWorkspace(
    selected: InspectorDestination,
    onDestinationSelected: (InspectorDestination) -> Unit,
    viewModel: InspectorViewModel = viewModel(),
) {
    val context = LocalContext.current
    val store = remember { SharedPreferencesInspectorThemeStore(context.applicationContext) }
    val systemDark = isSystemInDarkTheme()
    var darkTheme by rememberSaveable { mutableStateOf(resolveDarkTheme(store.readOverride(), systemDark)) }
    var detailOverlayOpen by rememberSaveable { mutableStateOf(false) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // A capture category disabled at init (or, dynamically, mid-session) can take the currently
    // selected destination out of workspaceNavItems() entirely -- e.g. INSPECTION off hides DATA. A
    // bottom nav that still reports a hidden destination as "selected" would strand the operator on
    // a screen with no way back to it via the nav itself, so this snaps to the first still-visible
    // destination the moment that happens. visibleDestinations() always includes MORE (see its own
    // doc), so `first()` never throws.
    LaunchedEffect(state.captureCategories, selected) {
        val visible = state.visibleDestinations()
        if (selected !in visible) onDestinationSelected(visible.first())
    }
    DevConsoleTheme(darkTheme = darkTheme) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val layout = inspectorNavigationLayout(maxWidth)
            if (layout == InspectorNavigationLayout.Bar) {
                Scaffold(
                    containerColor = DevConsoleTheme.colors.ground,
                    bottomBar = {
                        if (!detailOverlayOpen) {
                            InspectorNavigationBar(items = workspaceNavItems(selected, state, onDestinationSelected))
                        }
                    },
                ) { padding ->
                    WorkspaceContent(
                        selected = selected,
                        onToggleTheme = {
                            val next = !darkTheme
                            store.writeOverride(next)
                            darkTheme = next
                        },
                        onDetailOverlayOpen = { open -> detailOverlayOpen = open },
                        modifier = Modifier.fillMaxSize().padding(padding),
                        viewModel = viewModel,
                    )
                }
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    if (!detailOverlayOpen) {
                        InspectorNavigationRail(items = workspaceNavItems(selected, state, onDestinationSelected))
                    }
                    WorkspaceContent(
                        selected = selected,
                        onToggleTheme = {
                            val next = !darkTheme
                            store.writeOverride(next)
                            darkTheme = next
                        },
                        onDetailOverlayOpen = { open -> detailOverlayOpen = open },
                        modifier = Modifier.weight(1f),
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}

/**
 * [InspectorBottomNav]'s items, filtered to whichever destinations
 * [InspectorState.captureCategories] currently allows -- see [InspectorState.visibleDestinations]
 * for the gating rule.
 */
@Composable
private fun workspaceNavItems(
    selected: InspectorDestination,
    state: InspectorState,
    onDestinationSelected: (InspectorDestination) -> Unit,
): List<InspectorNavItem> =
    state.visibleDestinations().map { destination ->
        InspectorNavItem(
            label = destination.label,
            selected = destination == selected,
            onClick = { onDestinationSelected(destination) },
            icon = { DestinationNavIcon(destination) },
        )
    }

@Composable
private fun DestinationNavIcon(destination: InspectorDestination) {
    // The nav pill provides the icon color (signalInk on the green pill, muted otherwise) via
    // LocalContentColor; the glyphs' own default tint is theme ink, which is near-white in dark
    // and vanishes against the selected pill.
    val tint = LocalContentColor.current
    when (destination) {
        InspectorDestination.OBSERVE -> ObserveGlyphIcon(ObserveGlyph.Activity, contentDescription = null, tint = tint)
        InspectorDestination.CONTROL -> NavGlyphIcon(NavGlyph.Send, contentDescription = null, tint = tint)
        InspectorDestination.DATA -> NavGlyphIcon(NavGlyph.Db, contentDescription = null, tint = tint)
        InspectorDestination.MORE -> NavGlyphIcon(NavGlyph.Grid, contentDescription = null, tint = tint)
    }
}

/**
 * Hosts the one [InspectorState.pendingShareFilePath] side effect (launching the system Share
 * sheet) for every destination, not just DATA -- MORE's export actions set the same pending path a
 * Files-screen share does, but only the currently selected destination's route composable is part
 * of the composition, so a `LaunchedEffect` scoped to one route would never fire for the other.
 *
 * Each destination's content is wrapped in [rememberSaveableStateHolder]'s
 * [androidx.compose.runtime.saveable.SaveableStateHolder.SaveableStateProvider], keyed on the
 * destination itself -- the plain `when` below disposes the composition of whichever destination
 * just lost selection, so without this every route's `rememberSaveable` UI state (search text,
 * hero-collapse, scroll position, ...) would reset on every bottom-nav switch instead of surviving
 * re-selection like the standard bottom-nav contract expects. This only covers `rememberSaveable`
 * state, not every field a route happens to hold -- e.g. ObserveRoute's bookmarkedIds and
 * detailTarget are plain `mutableStateOf` and still reset on destination switch like any other
 * disposed composition's state would.
 */
@Composable
private fun WorkspaceContent(
    selected: InspectorDestination,
    onToggleTheme: () -> Unit,
    onDetailOverlayOpen: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InspectorViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.pendingShareFilePath) {
        val path = state.pendingShareFilePath ?: return@LaunchedEffect
        shareFileFromPath(context, path)
        viewModel.dispatch(InspectorAction.ConsumeShareFile)
    }
    // Observe (capture detail) and Control (mock rule sheet) both have full-screen overlays; reset
    // on every destination switch so the nav never stays hidden after navigating away from one --
    // the newly active route's own LaunchedEffect asserts the correct value right after.
    LaunchedEffect(selected) { onDetailOverlayOpen(false) }
    val stateHolder = rememberSaveableStateHolder()
    when (selected) {
        InspectorDestination.OBSERVE ->
            stateHolder.SaveableStateProvider(InspectorDestination.OBSERVE.name) {
                ObserveRoute(
                    onToggleTheme = onToggleTheme,
                    onDetailOverlayOpen = onDetailOverlayOpen,
                    modifier = modifier,
                )
            }
        InspectorDestination.CONTROL ->
            stateHolder.SaveableStateProvider(InspectorDestination.CONTROL.name) {
                ControlRoute(
                    onToggleTheme = onToggleTheme,
                    onDetailOverlayOpen = onDetailOverlayOpen,
                    modifier = modifier,
                )
            }
        InspectorDestination.DATA ->
            stateHolder.SaveableStateProvider(InspectorDestination.DATA.name) {
                DataRoute(onToggleTheme = onToggleTheme, modifier = modifier)
            }
        InspectorDestination.MORE ->
            stateHolder.SaveableStateProvider(InspectorDestination.MORE.name) {
                MoreRoute(onToggleTheme = onToggleTheme, modifier = modifier)
            }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0E0D)
@Composable
private fun DevConsoleWorkspacePreview() {
    DevConsoleWorkspace(
        selected = InspectorDestination.OBSERVE,
        onDestinationSelected = {},
    )
}
