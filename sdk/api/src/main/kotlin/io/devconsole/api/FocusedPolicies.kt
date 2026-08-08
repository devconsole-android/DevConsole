package io.devconsole.api

private const val MIN_TCP_PORT = 1
private const val MAX_TCP_PORT = 65_535
private const val DEFAULT_PORT_RANGE_START = 8080
private const val DEFAULT_PORT_RANGE_END = 8099

/**
 * Network exposure declared on [BrowserConfig] (`DevConsoleConfig.browserConfig.binding`), for
 * server starts the host does not issue itself.
 *
 * The two binding types cover the two ways a server can start, and neither overrides the other:
 * a [io.devconsole.DevConsole.startBrowser] call the host makes carries its own
 * [StartRequest.bindingMode] and this field is not consulted, while a start the host does *not*
 * make -- the Start button on the in-app inspector's More screen, which has no request of its own --
 * binds what this field says. Configure this when you want an on-device start to reach the browser
 * over the network; pass [StartRequest.bindingMode] when your own code is doing the starting.
 *
 * [LOOPBACK] is the default here for the same reason it is on [BindingMode]: the dashboard speaks
 * plaintext HTTP, so [LAN] is an explicit decision to expose captured headers, tokens, and bodies to
 * anyone who can see the traffic. See the KDoc on [BindingMode] and `docs/THREAT_MODEL.md`.
 */
enum class BrowserBinding { LOOPBACK, LAN }

data class RetentionPolicy(
    val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    fun validationErrors(): List<ConfigValidationError> =
        if (maxSessions > 0 && maxAgeMs > 0 && maxBytes > 0) {
            emptyList()
        } else {
            listOf(
                ConfigValidationError(
                    ConfigValidationCode.INVALID_RETENTION_POLICY,
                    "retention",
                    "Session count, age, and byte limits must all be positive",
                ),
            )
        }

    companion object {
        const val DEFAULT_MAX_SESSIONS = 10
        const val DEFAULT_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
        const val DEFAULT_MAX_BYTES = 100L * 1024L * 1024L
    }
}

/** SESSION_CODE is the only browser-access flow; there is no longer an access-mode field to set. */
data class BrowserConfig(
    val binding: BrowserBinding = BrowserBinding.LOOPBACK,
    val portRange: IntRange = DEFAULT_PORT_RANGE_START..DEFAULT_PORT_RANGE_END,
    val sessionCodeTtlMs: Long = DEFAULT_SESSION_CODE_TTL_MS,
) {
    fun validationErrors(): List<ConfigValidationError> {
        val validPorts = MIN_TCP_PORT..MAX_TCP_PORT
        val validRange =
            !portRange.isEmpty() &&
                portRange.first in validPorts &&
                portRange.last in validPorts
        if (validRange && sessionCodeTtlMs > 0) return emptyList()
        return listOf(
            ConfigValidationError(
                ConfigValidationCode.INVALID_BROWSER_CONFIGURATION,
                "browser",
                "Browser ports must be valid and the session-code TTL must be positive",
            ),
        )
    }

    companion object {
        const val DEFAULT_SESSION_CODE_TTL_MS = 5L * 60L * 1000L
    }
}
