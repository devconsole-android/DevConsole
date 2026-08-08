package io.devconsole.server.api

sealed interface LocalNetworkPermissionDecision {
    data object Allowed : LocalNetworkPermissionDecision

    data class PermissionRequired(
        val permission: String,
    ) : LocalNetworkPermissionDecision
}

/** Keeps Android-version policy outside the transport so it can be tested without framework APIs. */
object LocalNetworkPermissionGate {
    const val PERMISSION: String = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val LOCAL_NETWORK_PERMISSION_API = 37

    /**
     * [targetSdk] is deliberately **not** consulted.
     *
     * The obvious reading of an Android restriction is that it arrives with the app's target API
     * level, and this gate originally required `targetSdk >= 37` alongside the device check. That is
     * wrong for Local Network Access, and wrong in the worst possible way: on an API 37 device a
     * `targetSdk = 35` app was told it needed no permission, bound its LAN socket, and got a real
     * endpoint back -- while the platform silently dropped the traffic. The kernel still completed
     * TCP handshakes, so the port even answered a connection probe; every HTTP request then hung
     * until it timed out. Verified on an Android 17 device: granting [PERMISSION] by hand, with
     * nothing else changed, turned a timeout into an immediate `200`.
     *
     * So the device version alone decides. A host on an old [targetSdk] now gets an honest
     * [LocalNetworkPermissionDecision.PermissionRequired] it can act on, instead of a server that
     * reports success and serves nobody.
     */
    fun evaluate(
        bindingMode: BindingMode,
        deviceApi: Int,
        @Suppress("UNUSED_PARAMETER") targetSdk: Int,
        isGranted: Boolean,
    ): LocalNetworkPermissionDecision =
        when {
            bindingMode == BindingMode.LOOPBACK -> LocalNetworkPermissionDecision.Allowed
            deviceApi >= LOCAL_NETWORK_PERMISSION_API && !isGranted ->
                LocalNetworkPermissionDecision.PermissionRequired(PERMISSION)
            else -> LocalNetworkPermissionDecision.Allowed
        }
}
