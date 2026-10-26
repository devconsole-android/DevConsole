/**
 * @author Shakib
 * @since 19/10/26
 */
@file:Suppress("DEPRECATION", "FunctionNaming")

package io.devconsole.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalLifecycleOwner as LegacyLocalLifecycleOwner

/** Keeps a destination composed while its lifecycle, placement and input follow selection. */
@Composable
internal fun DestinationHost(
    active: Boolean,
    content: @Composable () -> Unit,
) {
    val parentLifecycle = LocalLifecycleOwner.current.lifecycle
    val owner = remember(parentLifecycle) { InspectorDestinationLifecycleOwner() }
    val currentActive by rememberUpdatedState(active)
    DisposableEffect(parentLifecycle, owner) {
        val observer = LifecycleEventObserver { _, _ -> owner.update(parentLifecycle.currentState, currentActive) }
        parentLifecycle.addObserver(observer)
        owner.update(parentLifecycle.currentState, currentActive)
        onDispose {
            parentLifecycle.removeObserver(observer)
            owner.update(Lifecycle.State.DESTROYED, active = false)
        }
    }
    SideEffect { owner.update(parentLifecycle.currentState, active) }

    val alpha = remember { Animatable(0f) }
    val animationSpec = feedbackSpec<Float>()
    LaunchedEffect(active) {
        alpha.snapTo(0f)
        if (active) alpha.animateTo(1f, animationSpec)
    }
    // Supply both entry points so legacy BackHandlers and lifecycle-aware collectors share the cap.
    CompositionLocalProvider(
        LocalLifecycleOwner provides owner,
        LegacyLocalLifecycleOwner provides owner,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(if (active) Modifier else Modifier.clearAndSetSemantics {})
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            if (active) placeable.place(0, 0)
                        }
                    }.graphicsLayer { this.alpha = alpha.value },
        ) {
            content()
        }
    }
}

/** A main-thread registry capped at CREATED whenever its destination is hidden. */
private class InspectorDestinationLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle = registry

    fun update(
        parentState: Lifecycle.State,
        active: Boolean,
    ) {
        val cap = if (active) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
        val target = minOf(parentState, cap)
        // A destination can leave composition before its parent has emitted ON_CREATE.
        if (target == Lifecycle.State.DESTROYED && registry.currentState == Lifecycle.State.INITIALIZED) {
            registry.currentState = Lifecycle.State.CREATED
        }
        registry.currentState = target
    }
}
