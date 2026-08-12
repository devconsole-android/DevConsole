/**
 * @author Shakib
 * @since 11/08/26
 */
@file:Suppress("MagicNumber")

package io.devconsole.ui.compose

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * The inspector's motion vocabulary, ported from the dashboard's own keyframes (dashboard.css) so
 * the two surfaces move alike rather than each inventing a house style:
 *
 * | Dashboard | Here | Job |
 * |---|---|---|
 * | `fadein 0.15s` | [FEEDBACK_MS] | acknowledge a switch or a toggle |
 * | `modalpop 0.18s cubic-bezier(0.2, 0.9, 0.3, 1)` | [OVERLAY_MS] + [emphasized] | an overlay arriving over content |
 * | `dcRowFlash 900ms` | [ROW_FLASH_MS] | a row worth finding again after the list moved |
 * | `dcPulse 2.4s infinite` | [PULSE_PERIOD_MS] | a session that is live, not settled |
 *
 * Everything here sits inside the 150-300ms band for state changes. The two
 * outliers are deliberate and match the web: the row flash is a decay the eye reads *after* the
 * fact rather than a transition anyone waits on, and the pulse is a status indicator, not a
 * transition at all.
 */
internal object InspectorMotion {
    /** `fadein 0.15s` -- routine acknowledgement: view switches, toggles, chevrons. */
    const val FEEDBACK_MS = 150

    /** `modalpop 0.18s` -- an overlay taking the screen. */
    const val OVERLAY_MS = 180

    /** Expand/collapse of a section's own content. Longer than feedback because real height moves. */
    const val EXPAND_MS = 220

    /**
     * `dcRowFlash 900ms` -- a signal-soft wash that marks one row for as long as it takes to find
     * it. The dashboard spends it on the row a save just produced, right after scrolling the list
     * to it; here the same wash and duration mark a row a live capture just added. Different
     * trigger, same job: the list changed under you, and this is which row is the reason.
     */
    const val ROW_FLASH_MS = 900

    /** `dcPulse 2.4s` -- one full breath of the live-session dot. */
    const val PULSE_PERIOD_MS = 2400

    /**
     * Travel distance for a shared-axis-X tab change. Deliberately a short nudge rather than a
     * full-width slide: the offset only has to say "this content came from that side", and a
     * screen-width translation of two live lists is both slower to read and more expensive to
     * composite on the low-end hardware a debug console has to stay honest on.
     */
    val sharedAxisOffset = 30.dp

    /** Alpha floor of that breath, straight from the `dcPulse` 50% stop. */
    const val PULSE_MIN_ALPHA = 0.35f

    /** `modalpop`'s curve: a confident arrival that settles without bouncing. */
    val emphasized: Easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1f)

    /** Linear on purpose: a decay should read as an even fade-out, not as an easing gesture. */
    fun <T> decay(durationMs: Int = ROW_FLASH_MS) = tween<T>(durationMs, easing = LinearEasing)
}

/**
 * Routine state change; the default for anything that is not an arrival or a decay.
 *
 * Composable so it can collapse to zero under [LocalReduceMotion] -- the equivalent of the
 * dashboard's `animation-duration: 0.01ms !important` override. Every spec in the inspector should
 * come from here or from a sibling helper, so that "remove animations" is one switch rather than a
 * promise each call site has to remember to keep.
 */
@Composable
internal fun <T> feedbackSpec(durationMs: Int = InspectorMotion.FEEDBACK_MS): FiniteAnimationSpec<T> =
    tween(if (LocalReduceMotion.current) 0 else durationMs, easing = InspectorMotion.emphasized)

/** The chevron spring the collapse affordances share, stilled to a cut under reduced motion. */
@Composable
internal fun chevronSpec(): FiniteAnimationSpec<Float> =
    if (LocalReduceMotion.current) snap() else spring(stiffness = Spring.StiffnessMediumLow)

/**
 * Whether the operator has asked the system to remove animations -- Android's counterpart to the
 * dashboard's `@media (prefers-reduced-motion: reduce)` block, which flattens every duration there.
 *
 * Defaults to `false` so previews and tests animate; [DevConsoleTheme] provides the real value.
 */
internal val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * Reads (and keeps watching) `Settings.Global.ANIMATOR_DURATION_SCALE`, which is what Android's
 * "Remove animations" accessibility setting and the developer-options animation scales both write.
 * A scale of exactly `0` is the platform's own signal that animations should not play -- honoring
 * it is why this console can add motion without taking the setting away from anyone who needs it.
 *
 * Observed rather than sampled once: the developer options that write this scale are exactly the
 * ones an operator running a debug console is likely to toggle mid-session, and a stale read would
 * leave the console animating after the rest of the system stopped.
 */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var reduceMotion by remember(resolver) { mutableStateOf(animationsRemoved(resolver)) }
    DisposableEffect(resolver) {
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val observer =
            object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    reduceMotion = animationsRemoved(resolver)
                }
            }
        // Registration is best-effort: a host that restricts settings reads must not crash the
        // console, it must just animate as if the setting were unset.
        runCatching { resolver.registerContentObserver(uri, false, observer) }
        onDispose { runCatching { resolver.unregisterContentObserver(observer) } }
    }
    return reduceMotion
}

private fun animationsRemoved(resolver: android.content.ContentResolver): Boolean =
    runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
