package io.devconsole.api

private const val MIN_TCP_PORT = 1
private const val MAX_TCP_PORT = 65_535
private const val DEFAULT_PORT_RANGE_START = 8080
private const val DEFAULT_PORT_RANGE_END = 8099

/**
 * Network exposure declared on [BrowserConfig] (`DevConsoleConfig.browserConfig.binding`). Despite
 * living on the host's [DevConsoleConfig], this field is **not currently consumed** by the running
 * server -- as of this writing, binding mode for an actual
 * [io.devconsole.DevConsole.startBrowser] call is decided entirely by the separate
 * [StartRequest.bindingMode] (which has its own [BindingMode] type and its own [BindingMode.LOOPBACK]
 * default, independent of this field). Setting [BrowserConfig.binding] round-trips through
 * [DevConsoleConfig] and back out, but has no effect on what the server actually binds to; treat
 * [StartRequest.bindingMode] as the only thing that matters until this is wired up. See the KDoc on
 * [BindingMode] for the other half of this relationship.
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
