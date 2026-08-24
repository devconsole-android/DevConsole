package io.devconsole

import io.devconsole.api.BindingMode
import io.devconsole.server.api.ServerStartResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.devconsole.server.api.BindingMode as ServerBindingMode

/**
 * The policy half of [BindingMode.AUTO], kept as pure functions so both halves are testable without
 * a device: the real fallback needs an API 37+ runtime with the permission withheld, or a device
 * with every network interface down, neither of which Robolectric can stage. The wiring half -- that
 * `startLocked` actually consults these -- is covered by `PlatformFacadeProviderAutoBindingTest`.
 */
class AutoBindingTest {
    @Test
    fun `auto attempts LAN when the local network permission is granted`() {
        assertEquals(ServerBindingMode.LAN, AutoBinding.initialMode(BindingMode.AUTO, lanPermitted = true))
    }

    /**
     * The distinguishing case. An unpermitted LAN bind is refused by the platform, and AUTO must not
     * spend a failed start discovering that -- it goes straight to loopback, which needs no grant.
     */
    @Test
    fun `auto goes straight to loopback when the local network permission is missing`() {
        assertEquals(ServerBindingMode.LOOPBACK, AutoBinding.initialMode(BindingMode.AUTO, lanPermitted = false))
    }

    @Test
    fun `explicit modes ignore the permission state and bind what was asked`() {
        assertEquals(ServerBindingMode.LAN, AutoBinding.initialMode(BindingMode.LAN, lanPermitted = false))
        assertEquals(ServerBindingMode.LOOPBACK, AutoBinding.initialMode(BindingMode.LOOPBACK, lanPermitted = true))
    }

    @Test
    fun `a LAN attempt with no eligible network is rescuable by loopback`() {
        assertTrue(AutoBinding.rescuableByLoopback(ServerStartResult.NoEligibleNetwork("airplane mode")))
        assertTrue(AutoBinding.rescuableByLoopback(ServerStartResult.LocalNetworkPermissionRequired))
    }

    /**
     * Everything else is a genuine failure that retrying would turn from one honest error into two.
     * A port clash is the sharp case: loopback binds a different address, so it is not strictly
     * doomed -- but exhausting all 20 ports is an environment problem, and quietly downgrading to a
     * `127.0.0.1` URL the caller asked not to get is worse than saying so.
     */
    @Test
    fun `failures loopback cannot rescue are left alone`() {
        assertFalse(AutoBinding.rescuableByLoopback(ServerStartResult.PortUnavailable(8080..8099)))
        assertFalse(AutoBinding.rescuableByLoopback(ServerStartResult.Failed("boom")))
        assertFalse(AutoBinding.rescuableByLoopback(ServerStartResult.InvalidConfiguration("bad range")))
        assertFalse(AutoBinding.rescuableByLoopback(ServerStartResult.DisabledForBuild))
    }
}
