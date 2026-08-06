package io.devconsole.api

/** Outcome of [io.devconsole.DevConsole.initialize] (or the internal provisional auto-init call). */
sealed interface InitResult {
    /** Initialization succeeded; capture/storage/state are ready. Nothing else changed. */
    data object Initialized : InitResult

    /**
     * A call with a [DevConsoleConfig] equivalent to the one already active. No-op: the previously
     * active configuration keeps running unchanged.
     */
    data object ExistingRuntime : InitResult

    /** This build variant is protected (release, wired to `devconsole-noop`); the call was a no-op. */
    data object Disabled : InitResult

    /**
     * A **different**, explicit [DevConsoleConfig] was already active (from an earlier explicit
     * `initialize` call, not the provisional auto-init default) and this call's config does not
     * match it. The two configs are not merged and the previously active one keeps running --
     * [message] describes the conflict. Does *not* apply when the previously active config was
     * provisional: an explicit call is always allowed to replace that one, returning
     * [Initialized] instead.
     */
    data class Conflict(
        val message: String,
    ) : InitResult

    /** Initialization could not complete for a reason other than validation or a conflict. */
    data class Failed(
        val message: String,
    ) : InitResult

    /** The supplied [DevConsoleConfig] failed validation; [errors] lists every problem found. */
    data class InvalidConfiguration(
        val errors: List<ConfigValidationError>,
    ) : InitResult
}

/** Outcome of [io.devconsole.DevConsole.startBrowser] / `startBrowserAsync`. */
sealed interface StartResult {
    /** The server bound successfully. [endpoint] is where it's listening; [access] is the session credential. */
    data class Started(
        val endpoint: BrowserEndpoint,
        val access: AccessInfo,
    ) : StartResult

    /** This build variant is protected (release, wired to `devconsole-noop`); the call was a no-op. */
    data object DisabledForBuild : StartResult

    /** [io.devconsole.DevConsole.initialize] has not (yet) succeeded on this build. Initialize first, then retry. */
    data object NotInitialized : StartResult

    /** A concrete platform server is unavailable; retained for core-only integrations. */
    data object ServerUnavailable : StartResult

    /**
     * LAN binding was requested but the runtime local-network permission has not been granted yet.
     * [permission] is the exact permission string to request; retry the same [StartRequest] after
     * the host grants it (or fall back to [BindingMode.LOOPBACK], which needs no permission).
     */
    data class PermissionRequired(
        val permission: String,
    ) : StartResult

    /** Every port in [attempted] was already in use. Retry with a different [StartRequest.portRange]. */
    data class PortUnavailable(
        val attempted: IntRange,
    ) : StartResult

    /**
     * LAN binding was requested but no eligible network interface was found to bind to (for
     * example, no active Wi-Fi/Ethernet connection). [details] describes what was checked. Retry
     * once connectivity changes, or fall back to [BindingMode.LOOPBACK].
     */
    data class NoEligibleNetwork(
        val details: String,
    ) : StartResult

    /** Start failed for a reason not covered by the other variants; [message] has the detail. */
    data class Failed(
        val message: String,
    ) : StartResult

    /** The supplied [StartRequest] failed validation (e.g. an empty or out-of-range port range). */
    data class InvalidConfiguration(
        val errors: List<ConfigValidationError>,
    ) : StartResult
}

/** Why [io.devconsole.DevConsole.stop] / `stopAsync` was called; informational, does not change stop behavior. */
sealed interface StopReason {
    /** The host explicitly asked to stop (a debug-menu action, a button, etc.). */
    data object UserRequested : StopReason

    /** The host process is going away; called from a shutdown/`onDestroy`-adjacent path. */
    data object ApplicationTerminated : StopReason

    /** Stopping in response to an internal failure; [message] describes what went wrong. */
    data class Failure(
        val message: String,
    ) : StopReason
}
