package io.devconsole

import io.devconsole.server.api.ServerStartResult
import io.devconsole.api.BindingMode as PublicBindingMode
import io.devconsole.server.api.BindingMode as ServerBindingMode

/**
 * Decides what [PublicBindingMode.AUTO] actually binds.
 *
 * AUTO's promise is that a start always yields a working server: prefer a real network interface,
 * settle for loopback when there isn't one. That splits into two questions -- which mode to attempt
 * first, and whether a failed attempt is worth retrying on loopback -- and both are pure, so they
 * are testable without an API 37+ device or a machine with its interfaces torn down.
 *
 * [PublicBindingMode.LAN] deliberately routes through neither rescue path. Its whole reason to exist
 * next to AUTO is that it fails loudly, so a host sharing a connect URL with another device hears
 * about it instead of receiving a `127.0.0.1` link that works for nobody but itself.
 */
internal object AutoBinding {
    /**
     * The mode to attempt first. AUTO checks [lanPermitted] up front rather than letting an
     * unpermitted LAN bind fail and retrying, because that failure has a side effect worth avoiding:
     * it would move the runtime through a failed-start transition on the way to a start that
     * ultimately succeeds.
     */
    fun initialMode(
        requested: PublicBindingMode,
        lanPermitted: Boolean,
    ): ServerBindingMode =
        when (requested) {
            PublicBindingMode.LOOPBACK -> ServerBindingMode.LOOPBACK
            PublicBindingMode.LAN -> ServerBindingMode.LAN
            PublicBindingMode.AUTO -> if (lanPermitted) ServerBindingMode.LAN else ServerBindingMode.LOOPBACK
        }

    /**
     * Whether a failed LAN attempt is one loopback could still serve. Only reachability refusals
     * qualify; a port clash or a bad configuration would fail identically on loopback, and retrying
     * those would report the same error twice while hiding which bind it came from.
     */
    fun rescuableByLoopback(result: ServerStartResult): Boolean =
        result is ServerStartResult.NoEligibleNetwork ||
            result is ServerStartResult.LocalNetworkPermissionRequired
}
