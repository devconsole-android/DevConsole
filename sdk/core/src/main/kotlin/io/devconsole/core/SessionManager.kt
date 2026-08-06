package io.devconsole.core

import io.devconsole.api.StopReason
import java.security.SecureRandom
import java.util.UUID

internal class SessionManager(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun create(): SessionSnapshot {
        val secret = ByteArray(SESSION_SECRET_BYTES)
        secureRandom.nextBytes(secret)
        return SessionSnapshot(
            id = UUID.randomUUID(),
            startedAtEpochMs = System.currentTimeMillis(),
            secret = secret,
            stopReason = null,
        )
    }

    fun stop(
        snapshot: SessionSnapshot,
        reason: StopReason,
    ): SessionSnapshot =
        snapshot.copy(
            stoppedAtEpochMs = System.currentTimeMillis(),
            stopReason = reason,
        )

    companion object {
        const val SESSION_SECRET_BYTES: Int = 16
    }
}

internal data class SessionSnapshot(
    val id: UUID,
    val startedAtEpochMs: Long,
    val secret: ByteArray,
    val stoppedAtEpochMs: Long? = null,
    val stopReason: StopReason?,
)
