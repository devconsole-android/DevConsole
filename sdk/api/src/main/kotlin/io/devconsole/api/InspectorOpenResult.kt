package io.devconsole.api

/**
 * Outcome of [io.devconsole.DevConsole.open] -- launching the SDK's own in-app inspector (as
 * opposed to the browser dashboard, which is [io.devconsole.DevConsole.startBrowser]'s concern).
 * The SDK never opens this on its own; it only launches from an explicit host trigger.
 */
sealed interface InspectorOpenResult {
    /** The inspector activity was launched successfully. */
    data object Opened : InspectorOpenResult

    /** [io.devconsole.DevConsole.initialize] has not (yet) succeeded on this build. Initialize first, then retry. */
    data object NotInitialized : InspectorOpenResult

    /** This build variant is protected (release, wired to `devconsole-noop`); the call was a no-op. */
    data object DisabledForBuild : InspectorOpenResult

    /** Opening the inspector failed for a reason not covered by the other variants; [message] has the detail. */
    data class Failed(
        val message: String,
    ) : InspectorOpenResult
}
