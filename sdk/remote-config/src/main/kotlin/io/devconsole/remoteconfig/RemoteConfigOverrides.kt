/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole.remoteconfig

/**
 * Told whenever a local override is added, replaced, or removed.
 *
 * A surface renders the value it read a moment ago, so without this signal it would keep showing a
 * stale value until something unrelated caused a refresh. Notification is deliberately a plain
 * callback rather than a flow: the store is read from the Ktor thread and the inspector's
 * dispatcher, and both already know how to schedule their own redraw.
 */
fun interface RemoteConfigOverrideListener {
    fun onOverridesChanged()
}

/**
 * Holds the overrides a developer applies locally while debugging, kept apart from anything a
 * Remote Config provider resolved.
 *
 * This is host-owned state rather than a field on [RemoteConfigRegistry] because an override is
 * user intent that outlives a session, whereas provider registrations are torn down and rebuilt
 * per session. Keeping the two separate is what lets a session end without discarding the values
 * being debugged.
 */
interface RemoteConfigOverrideStore {
    /**
     * Every override, keyed provider id then config key.
     *
     * A copy is returned on purpose: the readers run on the Ktor thread and the inspector's
     * dispatcher while a developer edits on the main thread, so handing out the live map would
     * expose a view that can change mid-iteration.
     */
    fun overrides(): Map<String, Map<String, String>>

    /**
     * Applies [value] for [key] on [providerId], replacing any previous override for that pair.
     *
     * Throws [IllegalArgumentException] for a blank provider id or key, a key longer than
     * [InMemoryRemoteConfigOverrideStore.MAX_KEY_LENGTH], or a value longer than
     * [RemoteConfigRegistry.MAX_VALUE_LENGTH] -- the same cap the registry applies to provider
     * values, so an override cannot smuggle in a payload the rest of the SDK refuses to carry.
     */
    fun setOverride(
        providerId: String,
        key: String,
        value: String,
    )

    /** Removes the override for [key] on [providerId]; a pair that was never overridden is a no-op. */
    fun clearOverride(
        providerId: String,
        key: String,
    )

    /** Removes every override for every provider. */
    fun clearAll()

    /** Registers [listener] for later changes; adding the same listener twice is a no-op. */
    fun addListener(listener: RemoteConfigOverrideListener)

    /** Unregisters [listener]; one that was never added is a no-op. */
    fun removeListener(listener: RemoteConfigOverrideListener)
}

/**
 * A [RemoteConfigOverrideStore] that keeps overrides in memory for the life of the process.
 *
 * Every mutation is guarded by one lock, and listeners are invoked only after it is released:
 * notifying under the lock would make a listener that reads [overrides] deadlock, and a listener
 * that throws would otherwise leave the store permanently locked. Listener failures are contained
 * for the same reason -- a broken observer must not take the overrides down with it.
 *
 * Both levels are insertion-ordered so the dashboard lists providers and keys in the order the
 * developer created them instead of shuffling on every read.
 */
class InMemoryRemoteConfigOverrideStore(
    initial: Map<String, Map<String, String>> = emptyMap(),
) : RemoteConfigOverrideStore {
    private val lock = Any()
    private val byProvider = linkedMapOf<String, LinkedHashMap<String, String>>()
    private val listeners = mutableListOf<RemoteConfigOverrideListener>()

    init {
        initial.forEach { (providerId, values) ->
            values.forEach { (key, value) -> setOverride(providerId, key, value) }
        }
    }

    override fun overrides(): Map<String, Map<String, String>> =
        synchronized(lock) {
            byProvider.mapValues { (_, values) -> LinkedHashMap(values) }
        }

    override fun setOverride(
        providerId: String,
        key: String,
        value: String,
    ) {
        validateProviderId(providerId)
        validateKey(key)
        validateValue(value)
        val changed =
            synchronized(lock) {
                val values = byProvider.getOrPut(providerId) { linkedMapOf() }
                values.put(key, value) != value
            }
        if (changed) {
            notifyListeners()
        }
    }

    override fun clearOverride(
        providerId: String,
        key: String,
    ) {
        validateProviderId(providerId)
        validateKey(key)
        val changed =
            synchronized(lock) {
                val values = byProvider[providerId]
                val removed = values?.remove(key) != null
                if (values != null && values.isEmpty()) {
                    byProvider.remove(providerId)
                }
                removed
            }
        if (changed) {
            notifyListeners()
        }
    }

    override fun clearAll() {
        val changed =
            synchronized(lock) {
                val hadOverrides = byProvider.isNotEmpty()
                byProvider.clear()
                hadOverrides
            }
        if (changed) {
            notifyListeners()
        }
    }

    override fun addListener(listener: RemoteConfigOverrideListener) {
        synchronized(lock) {
            if (listener !in listeners) {
                listeners.add(listener)
            }
        }
    }

    override fun removeListener(listener: RemoteConfigOverrideListener) {
        synchronized(lock) { listeners.remove(listener) }
    }

    private fun notifyListeners() {
        val snapshot = synchronized(lock) { listeners.toList() }
        snapshot.forEach { listener ->
            runCatching { listener.onOverridesChanged() }
        }
    }

    private fun validateProviderId(providerId: String) {
        require(providerId.isNotBlank()) { "Remote config override provider id must not be blank" }
    }

    private fun validateKey(key: String) {
        require(key.isNotBlank()) { "Remote config override key must not be blank" }
        require(key.length <= MAX_KEY_LENGTH) {
            "Remote config override key must be at most $MAX_KEY_LENGTH characters"
        }
    }

    private fun validateValue(value: String) {
        require(value.length <= RemoteConfigRegistry.MAX_VALUE_LENGTH) {
            "Remote config override value must be at most ${RemoteConfigRegistry.MAX_VALUE_LENGTH} characters"
        }
    }

    companion object {
        /**
         * Keys longer than this are rejected rather than stored. An override key is echoed by
         * every snapshot response; an unbounded one (a pasted document, a botched deep link) would
         * bloat responses the same way an unbounded value would, and a real config key is far
         * shorter than this.
         */
        const val MAX_KEY_LENGTH: Int = 256
    }
}
