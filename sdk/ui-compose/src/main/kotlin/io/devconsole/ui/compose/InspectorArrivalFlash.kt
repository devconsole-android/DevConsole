/**
 * @author Shakib
 * @since 11/08/26
 */
package io.devconsole.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

/**
 * The ids that appeared in [ids] since the previous composition of this list -- the rows a live
 * capture just added.
 *
 * The first snapshot primes silently and returns nothing: a screen opening onto two hundred
 * retained captures must not flash two hundred rows. Only what lands while the operator is
 * watching counts as an arrival, which is the same rule the dashboard follows by flashing on its
 * live socket event rather than on render.
 *
 * Keyed on the caller's own remember scope, so each list (traffic, logs, crashes) tracks its own
 * arrivals and a destination switch that disposes the list starts over.
 */
@Composable
internal fun rememberArrivals(ids: List<String>): Set<String> {
    val tracker = remember { ArrivalTracker() }
    return remember(ids) { tracker.accept(ids) }
}

/**
 * The arrival rule of [rememberArrivals], as plain Kotlin so it can be tested without a Compose
 * harness -- the same split [inspectorNavigationLayout] uses for the layout breakpoint.
 */
internal class ArrivalTracker {
    private val seen = mutableSetOf<String>()
    private var primed = false

    /** The ids in [ids] not present at the previous call; always empty for the very first call. */
    fun accept(ids: List<String>): Set<String> {
        val fresh = if (primed) ids.filterNotTo(mutableSetOf()) { it in seen } else emptySet()
        // Retained-buffer eviction and filtering both shrink the list; dropping what left keeps an
        // id that comes back (a filter cleared, say) from being mistaken for a fresh capture only
        // for as long as it is genuinely gone, and stops the set growing without bound.
        seen.retainAll(ids.toSet())
        seen.addAll(ids)
        primed = true
        return fresh
    }
}

/**
 * The dashboard's `dcRowFlash` for a Compose row: a signal-soft wash that decays to nothing over
 * [InspectorMotion.ROW_FLASH_MS], marking the row a live capture just put there.
 *
 * Drawn in [drawBehind] rather than as a background color so the decay runs entirely in the draw
 * phase -- a list taking rows at socket speed never recomposes for the fade -- and so it composites
 * *over* whatever container color the row already carries (flagged, selected) instead of replacing
 * it.
 *
 * Reduced motion removes the fade, not the fact. The wash is the only thing that says "this row
 * just arrived", so dropping it outright would delete information rather than motion -- it would
 * leave an operator who turned animations off unable to tell a new capture from an old one at all.
 * Instead the wash is held flat for the same [InspectorMotion.ROW_FLASH_MS] and then cleared: a
 * state change, which is what the setting asks for, rather than a transition, which is what it asks
 * to be spared. Same treatment the dashboard's `dcRowFlash` gets under `prefers-reduced-motion`.
 */
@Composable
internal fun Modifier.arrivalFlash(isArrival: Boolean): Modifier {
    val reduceMotion = LocalReduceMotion.current
    val wash = DevConsoleTheme.colors.signalSoft
    val decay = remember { Animatable(0f) }
    LaunchedEffect(isArrival, reduceMotion) {
        if (!isArrival) return@LaunchedEffect
        decay.snapTo(1f)
        if (reduceMotion) {
            delay(InspectorMotion.ROW_FLASH_MS.toLong())
            decay.snapTo(0f)
        } else {
            decay.animateTo(0f, InspectorMotion.decay())
        }
    }
    return drawBehind {
        val progress = decay.value
        if (progress > 0f) drawRect(Color(wash.red, wash.green, wash.blue, wash.alpha * progress))
    }
}
