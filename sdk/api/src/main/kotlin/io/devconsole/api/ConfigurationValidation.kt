package io.devconsole.api

enum class ConfigValidationCode {
    INVALID_EVENT_BUFFER_CAPACITY,
    INVALID_PORT_RANGE,
    INVALID_REDACTION_EXPRESSION,
    INVALID_STORAGE_POLICY,
    INVALID_SESSION_POLICY,
    INVALID_RETENTION_POLICY,
    INVALID_BROWSER_CONFIGURATION,
    UNSAFE_PRODUCTION_RUNTIME,
    INVALID_ANR_THRESHOLD,
    INVALID_BREADCRUMB_DEPTH,
    INVALID_MAX_STACK_CHARS,
    INVALID_MAX_THREADS_IN_DUMP,
    INVALID_MAX_FRAMES_PER_THREAD,
    INVALID_SCREENSHOT_MAX_LONGEST_EDGE,
    INVALID_SCREENSHOT_MAX_BYTES,
    NO_CAPTURE_CATEGORIES_ENABLED,
}

data class ConfigValidationError(
    val code: ConfigValidationCode,
    val field: String,
    val message: String,
)

data class StoragePolicy(
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    val maxTimelineEvents: Int = DEFAULT_MAX_TIMELINE_EVENTS,
    val maxNetworkTransactions: Int = DEFAULT_MAX_NETWORK_TRANSACTIONS,
) {
    companion object {
        const val DEFAULT_MAX_BYTES: Long = 100L * 1024L * 1024L
        const val DEFAULT_MAX_AGE_MS: Long = 24L * 60L * 60L * 1000L
        const val DEFAULT_MAX_TIMELINE_EVENTS: Int = 50_000
        const val DEFAULT_MAX_NETWORK_TRANSACTIONS: Int = 2_000
    }
}

/**
 * Concurrent-session cap. One access tier -- every session is equivalent, so the old
 * CONTROL/ADMIN-specific caps are gone; [maxAuthenticatedSessions] alone still bounds total live
 * sessions.
 */
data class SessionPolicy(
    val maxAuthenticatedSessions: Int = DEFAULT_MAX_AUTHENTICATED_SESSIONS,
) {
    companion object {
        const val DEFAULT_MAX_AUTHENTICATED_SESSIONS: Int = 10
    }
}
