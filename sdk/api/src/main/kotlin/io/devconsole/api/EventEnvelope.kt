package io.devconsole.api

import java.util.UUID

data class EventEnvelope(
    val id: UUID,
    val sessionId: UUID,
    val pluginId: String,
    val type: String,
    val timestampEpochMs: Long,
    val monotonicNanos: Long,
    val sequence: Long,
    val severity: EventSeverity,
    val summary: String,
    val correlationId: String? = null,
    val tags: Map<String, String> = emptyMap(),
    val payloadRef: String? = null,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

enum class EventSeverity {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}
