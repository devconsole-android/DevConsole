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
}
