package io.devconsole.api

/**
 * Lifecycle state of the SDK runtime, observable via [io.devconsole.DevConsole.state] (a
 * `StateFlow`). This tracks the *runtime* -- whether [io.devconsole.DevConsole.initialize] has
 * succeeded and whether the embedded browser server is bound -- not individual capture sources;
 * network/socket/push recording is available as soon as [Initialized] is reached, independent of
 * whether the server has ever been started.
 */
sealed class DevConsoleState {
    /** Before the first [io.devconsole.DevConsole.initialize] call. Nothing is captured yet. */
    data object Uninitialized : DevConsoleState()

    /** This build variant is protected (release, wired to `devconsole-noop`); the runtime is permanently a no-op. */
    data object DisabledForBuild : DevConsoleState()

    /** Initialized and not running. Capture/storage/state are ready; the browser server is not bound. */
    data object Initialized : DevConsoleState()

    /**
     * A [io.devconsole.DevConsole.startBrowser] call requested LAN binding but the runtime
     * local-network permission has not been granted. Waiting for the host to request and receive
     * it, then retry.
     */
    data object PermissionRequired : DevConsoleState()

    /** A start is in progress -- between [io.devconsole.DevConsole.startBrowser] being called and it resolving. */
    data object Starting : DevConsoleState()

    /** The server is bound and reachable. Call [io.devconsole.DevConsole.endpoint] / `accessInfo` for where. */
    data object Running : DevConsoleState()

    /** A stop is in progress -- between [io.devconsole.DevConsole.stop] being called and the port being released. */
    data object Stopping : DevConsoleState()

    /** Stopped cleanly after having run. Capture/storage/state remain ready; the server is unbound. */
    data object Stopped : DevConsoleState()

    /** The runtime hit an unrecoverable error; [message] describes it. Re-`initialize` to recover. */
    data class Failed(
        val message: String,
    ) : DevConsoleState()

    companion object {
        val all: Set<DevConsoleState>
            get() =
                setOf(
                    Uninitialized,
                    DisabledForBuild,
                    Initialized,
                    PermissionRequired,
                    Starting,
                    Running,
                    Stopping,
                    Stopped,
                    Failed("sample"),
                )
    }
}
