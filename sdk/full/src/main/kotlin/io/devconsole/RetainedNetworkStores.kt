/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole

import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkTransaction
import io.devconsole.storage.api.StoredEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Builds and caches in-memory transaction stores for historical/retained sessions.
 * Completed sessions never change, so caching is safe; the active/live session
 * must not be served from here.
 */
internal class RetainedNetworkStores(
    private val events: suspend (sessionId: String) -> List<StoredEvent>,
    private val cursorCodec: NetworkCursorCodec,
    private val maxCachedSessions: Int = DEFAULT_MAX_CACHED_SESSIONS,
) {
    private val mutex = Mutex()
    private val lock = Any()
    private val cache =
        object : LinkedHashMap<String, InMemoryNetworkTransactionStore>(
            maxCachedSessions,
            LOAD_FACTOR,
            true,
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, InMemoryNetworkTransactionStore>?,
            ): Boolean = size > maxCachedSessions
        }

    /** A run with no decodable network captures yields an empty store, not null. */
    suspend fun storeFor(sessionId: String): InMemoryNetworkTransactionStore =
        mutex.withLock {
            val cached = synchronized(lock) { cache[sessionId] }
            if (cached != null) return@withLock cached

            val store = buildStore(sessionId)
            synchronized(lock) {
                cache[sessionId] = store
            }
            store
        }

    private suspend fun buildStore(sessionId: String): InMemoryNetworkTransactionStore {
        val transactions =
            events(sessionId)
                .asSequence()
                .filter { it.pluginId == PLUGIN_NETWORK }
                .mapNotNull { RetainedCaptureDecoder.network(it.payloadJson) }
                .sortedBy(NetworkTransaction::startedAtEpochMs)
                .toList()

        val capacity = transactions.size.coerceAtLeast(1)
        val store =
            InMemoryNetworkTransactionStore(
                cursorCodec = cursorCodec,
                maxTransactions = capacity,
            ).withByteCapacity(DEFAULT_STORE_BYTE_CAPACITY)

        transactions.forEach(store::record)
        return store
    }

    fun invalidate(sessionId: String) {
        synchronized(lock) {
            cache.remove(sessionId)
        }
    }

    fun clear() {
        synchronized(lock) {
            cache.clear()
        }
    }

    private companion object {
        const val DEFAULT_MAX_CACHED_SESSIONS = 4
        const val LOAD_FACTOR = 0.75f
        const val PLUGIN_NETWORK = "network"
        const val DEFAULT_STORE_BYTE_CAPACITY = 64L * 1024L * 1024L
    }
}
