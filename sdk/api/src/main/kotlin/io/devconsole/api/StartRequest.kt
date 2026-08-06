package io.devconsole.api

private const val MIN_TCP_PORT = 1
private const val MAX_TCP_PORT = 65_535
private const val DEFAULT_PORT_RANGE_START = 8080
private const val DEFAULT_PORT_RANGE_END = 8099

/**
 * Network exposure requested by the host, passed per-call as [StartRequest.bindingMode]. Loopback
 * is the safe default -- the dashboard speaks plaintext HTTP, and `LAN` exposes that traffic to
 * anyone else on the local network; see `docs/THREAT_MODEL.md` at the repository root before ever
 * passing `LAN`.
 *
 * This is a distinct type from [BrowserBinding] on [BrowserConfig] (`DevConsoleConfig.browserConfig`),
 * and the two are **not** layered as "config provides a default, [StartRequest] overrides it": as
 * of this writing, [BrowserConfig.binding] is not read by the running server at all -- only
 * [StartRequest.bindingMode] (defaulting to [BindingMode.LOOPBACK] via [StartRequest]'s own
 * default constructor argument, independent of any [DevConsoleConfig] the host installed) decides the
 * actual binding mode for a given [io.devconsole.DevConsole.startBrowser] call. See the KDoc on
 * [BrowserBinding] for the config-side field this leaves currently inert.
 */
enum class BindingMode { LOOPBACK, LAN }

/** Stable host-facing server start options. */
data class StartRequest(
    val bindingMode: BindingMode = BindingMode.LOOPBACK,
    val portRange: IntRange = DEFAULT_PORT_RANGE_START..DEFAULT_PORT_RANGE_END,
) {
    fun validationErrors(): List<ConfigValidationError> {
        val validPorts = MIN_TCP_PORT..MAX_TCP_PORT
        return if (portRange.isEmpty() || portRange.first !in validPorts || portRange.last !in validPorts) {
            listOf(
                ConfigValidationError(
                    code = ConfigValidationCode.INVALID_PORT_RANGE,
                    field = "portRange",
                    message = "portRange must be non-empty and contain only valid TCP ports",
                ),
            )
        } else {
            emptyList()
        }
    }
}

/** Public description of the server selected after a successful [DevConsoleFacadeProvider.startBrowser]. */
data class BrowserEndpoint(
    val host: String,
    val port: Int,
    val bindingMode: BindingMode,
)

/**
 * Ephemeral connect information for the SESSION_CODE flow. [sessionCode] is a complete access
 * credential -- the live 8-character code -- and [connectUrl] embeds it in the `#code=` fragment.
 * There is no on-device approval step: whoever presents the code within its TTL gets a full
 * session. Never log, display outside the trusted device screen, or persist either value.
 */
class AccessInfo(
    val connectUrl: String,
    val sessionCode: String,
    val expiresAtEpochMs: Long,
) {
    override fun toString(): String = "AccessInfo(expiresAtEpochMs=$expiresAtEpochMs)"
}
