package io.devconsole

import android.Manifest
import io.devconsole.server.api.LocalNetworkPermissionGate

/** Android-version-specific runtime grants needed before the full SDK exposes a LAN URL. */
internal object LanPermissionPolicy {
    private const val NEARBY_WIFI_PERMISSION_API = 33
    private const val LOCAL_NETWORK_PERMISSION_API = 37

    /**
     * Android 13 through 16 use NEARBY_WIFI_DEVICES for the full runtime's local-network path;
     * Android 17 and later use ACCESS_LOCAL_NETWORK. Loopback does not call this policy.
     */
    fun missingPermission(
        deviceApi: Int,
        nearbyWifiGranted: Boolean,
        localNetworkGranted: Boolean,
    ): String? =
        when {
            deviceApi >= LOCAL_NETWORK_PERMISSION_API && !localNetworkGranted -> LocalNetworkPermissionGate.PERMISSION
            deviceApi in NEARBY_WIFI_PERMISSION_API until LOCAL_NETWORK_PERMISSION_API && !nearbyWifiGranted ->
                Manifest.permission.NEARBY_WIFI_DEVICES
            else -> null
        }
}
