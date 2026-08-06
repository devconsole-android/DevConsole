package io.devconsole.ui.compose

/**
 * The four [InspectorBottomNav] destinations. `marker`/`title`/`description` were dropped: they
 * backed the old `WorkspaceHeader`, which double-stacked a title above each screen's own new
 * `InspectorTopArea` -- every route now owns its own accurate title/sub directly, so this enum
 * only needs to carry what [InspectorBottomNav] and routing still use.
 */
internal enum class InspectorDestination(
    val label: String,
) {
    OBSERVE(label = "Observe"),
    CONTROL(label = "Control"),
    DATA(label = "Data"),
    MORE(label = "More"),
}
