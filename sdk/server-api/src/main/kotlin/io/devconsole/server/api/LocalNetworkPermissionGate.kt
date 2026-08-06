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

    fun evaluate(
        bindingMode: BindingMode,
        deviceApi: Int,
        targetSdk: Int,
        isGranted: Boolean,
    ): LocalNetworkPermissionDecision =
        when {
            bindingMode == BindingMode.LOOPBACK -> LocalNetworkPermissionDecision.Allowed
            deviceApi >= LOCAL_NETWORK_PERMISSION_API && targetSdk >= LOCAL_NETWORK_PERMISSION_API && !isGranted ->
                LocalNetworkPermissionDecision.PermissionRequired(PERMISSION)
            else -> LocalNetworkPermissionDecision.Allowed
        }
}
