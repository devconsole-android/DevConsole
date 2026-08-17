package io.devconsole.api

private const val MIN_TCP_PORT = 1
private const val MAX_TCP_PORT = 65_535
private const val DEFAULT_PORT_RANGE_START = 8080
private const val DEFAULT_PORT_RANGE_END = 8099

/**
 * Network exposure requested by the host, passed per-call as [StartRequest.bindingMode].
 *
 * [AUTO] is the default: it binds a real network interface when there is one to bind and the
 * local-network permission allows it, and quietly binds loopback when either is missing, so a start
 * always yields a working server. The dashboard speaks plaintext HTTP, so whenever [AUTO] does
 * reach the network it exposes captured headers, tokens, and bodies to anyone who can see that
 * traffic -- read `docs/THREAT_MODEL.md` at the repository root and pass [LOOPBACK] explicitly if
 * that is not acceptable on the networks you develop on.
 *
 * [LAN] differs from [AUTO] only in what it does when LAN is unavailable: it fails, returning
 * [StartResult.NoEligibleNetwork] or [StartResult.PermissionRequired] rather than degrading. Ask for
 * it when a loopback URL would be useless to you -- you are sharing a connect URL or QR code with
 * another device -- or when you want [StartResult.PermissionRequired] back so your app can prompt
 * for `ACCESS_LOCAL_NETWORK`. Under [AUTO] that permission is never requested on your behalf, so on
 * an API 37+ device [AUTO] settles for loopback until something asks for the grant.
 *
 * [BindingMode.AUTO] never appears on a [BrowserEndpoint]: [BrowserEndpoint.bindingMode] reports the
 * mode that actually bound, so it is always [LOOPBACK] or [LAN].
 *
 * The name is reused, not restored. A pre-1.0 `BindingMode.AUTO` existed alongside zero-config
 * auto-start and NSD service discovery and was removed with them; this one is only a binding
 * preference. It discovers nothing, advertises nothing, and starts nothing on its own -- the server
 * still starts only when a host calls [io.devconsole.DevConsole.startBrowser] or taps Start.
 *
 * This is a distinct type from [BrowserBinding] on [BrowserConfig] (`DevConsoleConfig.browserConfig`),
 * and the two are **not** layered as "config provides a default, [StartRequest] overrides it". They
 * answer for different callers: this type decides a start the host issues itself, where the request
 * is right there at the call site and defaults to [BindingMode.AUTO] regardless of what any
 * [DevConsoleConfig] says; [BrowserConfig.binding] decides a start the host does not issue -- the
 * in-app inspector's More-screen Start button, which has no [StartRequest] to carry. Neither reads
 * the other. See the KDoc on [BrowserBinding] for that side.
 */
enum class BindingMode { LOOPBACK, LAN, AUTO }

/** Stable host-facing server start options. */
data class StartRequest(
    val bindingMode: BindingMode = BindingMode.AUTO,
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
