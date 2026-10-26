/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "UnusedPrivateMember")

package io.devconsole.ui.compose

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

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
    val visibleDestinations by rememberVisibleDestinations(viewModel)
    // A capture category disabled at init (or, dynamically, mid-session) can take the currently
    // selected destination out of the visible destinations entirely -- e.g. INSPECTION off hides DATA. A
    // bottom nav that still reports a hidden destination as "selected" would strand the operator on
    // a screen with no way back to it via the nav itself, so this snaps to the first still-visible
    // destination the moment that happens. visibleDestinations() always includes MORE (see its own
    // doc), so `first()` never throws.
    LaunchedEffect(visibleDestinations, selected) {
        if (selected !in visibleDestinations) onDestinationSelected(visibleDestinations.first())
    }
    DevConsoleTheme(darkTheme = darkTheme) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            WorkspaceContent(
                selected = selected,
                onDestinationSelected = onDestinationSelected,
                onToggleTheme = {
                    val next = !darkTheme
                    store.writeOverride(next)
                    darkTheme = next
                },
                navigationLayout = inspectorNavigationLayout(maxWidth),
                modifier = Modifier.fillMaxSize(),
                viewModel = viewModel,
            )
        }
    }
}

/**
 * [InspectorBottomNav]'s items, filtered to whichever destinations
 * [InspectorState.captureCategories] currently allows -- see [InspectorState.visibleDestinations]
 * for the gating rule.
 */
@Composable
private fun rememberWorkspaceNavItems(
    selected: InspectorDestination,
    visibleDestinations: List<InspectorDestination>,
    onDestinationSelected: (InspectorDestination) -> Unit,
): List<InspectorNavItem> {
    val currentOnDestinationSelected by rememberUpdatedState(onDestinationSelected)
    // Remembered so live state updates (new traffic, polling) don't hand the nav a fresh list and
    // recompose all four items; only a selection or visibility change rebuilds it.
    return remember(selected, visibleDestinations) {
        visibleDestinations.map { destination ->
            InspectorNavItem(
                label = destination.label,
                selected = destination == selected,
                onClick = { currentOnDestinationSelected(destination) },
                icon = { DestinationNavIcon(destination) },
            )
        }
    }
}

/**
 * The visible destinations as their own state: [derivedStateOf] only invalidates readers when the
 * list actually changes, so the workspace shell does not recompose on every [InspectorState] emission.
 */
@Composable
private fun rememberVisibleDestinations(viewModel: InspectorViewModel): State<List<InspectorDestination>> {
    val state = viewModel.state.collectAsStateWithLifecycle()
    return remember(state) { derivedStateOf { state.value.visibleDestinations() } }
}

@Composable
private fun DestinationNavIcon(destination: InspectorDestination) {
    // The nav pill provides the icon color (signalInk on the green pill, muted otherwise) via
    // LocalContentColor; the glyphs' own default tint is theme ink, which is near-white in dark
    // and vanishes against the selected pill.
    val tint = LocalContentColor.current
    when (destination) {
        InspectorDestination.OBSERVE -> NavGlyphIcon(NavGlyph.Observe, contentDescription = null, tint = tint)
        InspectorDestination.CONTROL -> NavGlyphIcon(NavGlyph.Control, contentDescription = null, tint = tint)
        InspectorDestination.DATA -> NavGlyphIcon(NavGlyph.Data, contentDescription = null, tint = tint)
        InspectorDestination.MORE -> NavGlyphIcon(NavGlyph.More, contentDescription = null, tint = tint)
    }
}

/**
 * Hosts the one [InspectorState.pendingShareFilePath] side effect (launching the system Share
 * sheet) for every destination, not just DATA -- MORE's export actions set the same pending path a
 * Files-screen share does. Keeping it here ensures one consumer even while several destination
 * routes remain composed.
 *
 * Visited destinations stay composed inside [DestinationHost], preserving scroll and open detail
 * state while hidden routes are unplaced and capped at CREATED. Each destination retains its named
 * [androidx.compose.runtime.saveable.SaveableStateHolder.SaveableStateProvider] so saveable state
 * also survives configuration changes and process recreation.
 */
@Composable
private fun WorkspaceContent(
    selected: InspectorDestination,
    onDestinationSelected: (InspectorDestination) -> Unit,
    onToggleTheme: () -> Unit,
    navigationLayout: InspectorNavigationLayout,
    modifier: Modifier = Modifier,
    viewModel: InspectorViewModel = viewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle()
    val pendingShareFilePath by remember(state) { derivedStateOf { state.value.pendingShareFilePath } }
    val context = LocalContext.current
    LaunchedEffect(pendingShareFilePath) {
        val path = pendingShareFilePath ?: return@LaunchedEffect
        shareFileFromPath(context, path)
        viewModel.dispatch(InspectorAction.ConsumeShareFile)
    }
    val overlays = remember { mutableStateMapOf<InspectorDestination, Boolean>() }
    val detailOverlayOpen = overlays[selected] == true
    val stateHolder = rememberSaveableStateHolder()
    var visited by
        rememberSaveable(
            stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() }),
        ) { mutableStateOf(setOf(selected.name)) }
    val visibleDestinations by remember(state) { derivedStateOf { state.value.visibleDestinations() } }
    val currentVisibleDestinations by rememberUpdatedState(visibleDestinations)
    LaunchedEffect(selected) { visited += selected.name }
    // Let the first screen settle, then spread hidden composition work across frame batches.
    LaunchedEffect(Unit) {
        delay(1_000)
        for (destination in currentVisibleDestinations) {
            if (destination in currentVisibleDestinations && destination.name !in visited) {
                visited += destination.name
                withFrameNanos { }
                delay(300)
            }
        }
    }
    val destinationContent: @Composable (Modifier) -> Unit = { contentModifier ->
        Box(modifier = contentModifier) {
            for (destination in visibleDestinations.filter { it == selected || it.name in visited }) {
                key(destination) {
                    DestinationHost(active = destination == selected) {
                        stateHolder.SaveableStateProvider(destination.name) {
                            when (destination) {
                                InspectorDestination.OBSERVE ->
                                    ObserveRoute(
                                        onToggleTheme = onToggleTheme,
                                        onDetailOverlayOpen = { open -> overlays[destination] = open },
                                    )
                                InspectorDestination.CONTROL ->
                                    ControlRoute(
                                        onToggleTheme = onToggleTheme,
                                        onDetailOverlayOpen = { open -> overlays[destination] = open },
                                    )
                                InspectorDestination.DATA -> DataRoute(onToggleTheme = onToggleTheme)
                                InspectorDestination.MORE -> MoreRoute(onToggleTheme = onToggleTheme)
                            }
                        }
                    }
                }
            }
        }
    }
    if (navigationLayout == InspectorNavigationLayout.Bar) {
        Scaffold(
            modifier = modifier,
            containerColor = DevConsoleTheme.colors.ground,
            bottomBar = {
                if (!detailOverlayOpen) {
                    InspectorNavigationBar(
                        items = rememberWorkspaceNavItems(selected, visibleDestinations, onDestinationSelected),
                    )
                }
            },
        ) { padding ->
            destinationContent(Modifier.fillMaxSize().padding(padding))
        }
    } else {
        Row(modifier = modifier) {
            if (!detailOverlayOpen) {
                InspectorNavigationRail(
                    items = rememberWorkspaceNavItems(selected, visibleDestinations, onDestinationSelected),
                )
            }
            destinationContent(Modifier.weight(1f))
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
