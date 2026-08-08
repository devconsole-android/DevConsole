package io.devconsole.server.api

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalNetworkPermissionGateTest {
    @Test
    fun `api 37 LAN start requires granted local-network permission`() {
        assertEquals(
            LocalNetworkPermissionDecision.PermissionRequired(LocalNetworkPermissionGate.PERMISSION),
            LocalNetworkPermissionGate.evaluate(
                bindingMode = BindingMode.LAN,
                deviceApi = 37,
                targetSdk = 37,
                isGranted = false,
            ),
        )
    }

    @Test
    fun `loopback and older compatibility paths are allowed without LAN permission`() {
        assertEquals(
            LocalNetworkPermissionDecision.Allowed,
            LocalNetworkPermissionGate.evaluate(BindingMode.LOOPBACK, 37, 37, isGranted = false),
        )
        assertEquals(
            LocalNetworkPermissionDecision.Allowed,
            LocalNetworkPermissionGate.evaluate(BindingMode.LAN, 36, 36, isGranted = false),
        )
    }

    /**
     * The regression this gate was actually shipped with: an app targeting an older SDK on an API 37
     * device was waved through, bound a LAN socket the platform then refused to serve, and reported
     * a healthy endpoint whose every request hung. Enforcement follows the device, not the target.
     */
    @Test
    fun `api 37 device requires the permission even for an app targeting an older sdk`() {
        assertEquals(
            LocalNetworkPermissionDecision.PermissionRequired(LocalNetworkPermissionGate.PERMISSION),
            LocalNetworkPermissionGate.evaluate(BindingMode.LAN, deviceApi = 37, targetSdk = 35, isGranted = false),
        )
    }

    @Test
    fun `a granted permission allows LAN on an api 37 device`() {
        assertEquals(
            LocalNetworkPermissionDecision.Allowed,
            LocalNetworkPermissionGate.evaluate(BindingMode.LAN, deviceApi = 37, targetSdk = 35, isGranted = true),
        )
    }
}
