package io.devconsole

import android.Manifest
import io.devconsole.server.api.LocalNetworkPermissionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanPermissionPolicyTest {
    @Test
    fun `android 13 through 16 surface nearby wifi when LAN grant is missing`() {
        assertEquals(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            LanPermissionPolicy.missingPermission(
                deviceApi = 33,
                nearbyWifiGranted = false,
                localNetworkGranted = false,
            ),
        )
        assertNull(
            LanPermissionPolicy.missingPermission(
                deviceApi = 36,
                nearbyWifiGranted = true,
                localNetworkGranted = false,
            ),
        )
    }

    @Test
    fun `android 17 and later surface local network instead of nearby wifi`() {
        assertEquals(
            LocalNetworkPermissionGate.PERMISSION,
            LanPermissionPolicy.missingPermission(
                deviceApi = 37,
                nearbyWifiGranted = false,
                localNetworkGranted = false,
            ),
        )
        assertNull(
            LanPermissionPolicy.missingPermission(
                deviceApi = 37,
                nearbyWifiGranted = true,
                localNetworkGranted = true,
            ),
        )
    }

    @Test
    fun `older Android does not require either runtime grant`() {
        assertNull(
            LanPermissionPolicy.missingPermission(
                deviceApi = 32,
                nearbyWifiGranted = false,
                localNetworkGranted = false,
            ),
        )
    }
}
