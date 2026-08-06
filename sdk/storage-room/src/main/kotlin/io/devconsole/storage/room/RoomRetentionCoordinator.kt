package io.devconsole.storage.room

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared Phase 1B injection point serializing session-aware writes and retention decisions. */
class RoomRetentionCoordinator {
    private val mutex = Mutex()

    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }
}
