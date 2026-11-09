/**
 * @author Md. Asaduzzaman Nur
 * @since 13/08/26
 */
package io.devconsole.remoteconfig

/**
 * Holds the host's registered [RemoteConfigProvider]s and normalizes what they return.
 *
 * Normalization lives here rather than in each adapter so that a host-written provider inherits the
 * same value cap and the same never-throw guarantee as the adapters DevConsole ships, instead of
 * having to remember them.
 *
 * Local overrides are layered on top of the normalized provider values, after the cap, so an
 * override is the value a debugger sees while the provider's own result stays available on
 * [RemoteConfigEntry.providerValue].
 */
class RemoteConfigRegistry(
    /**
     * Where local overrides live. Injected so a host can persist them and keep the same store
     * across a session teardown; the default in-memory store suits hosts that do not need that.
     */
    overrideStore: RemoteConfigOverrideStore = InMemoryRemoteConfigOverrideStore(),
) {
    /**
     * Local overrides layered on top of every provider snapshot. Public so a surface can edit them
     * in place; [clear] deliberately leaves them untouched because they are user intent.
     */
    val overrides: RemoteConfigOverrideStore = overrideStore

    /**
     * Guarded by [lock] on every access, read included. Late registration is a documented path
     * (`DevConsole.registerRemoteConfigProvider`), and the readers are the Ktor thread and the
     * inspector's dispatcher -- so a host registering a provider from a post-fetch listener while
     * the dashboard polls would otherwise throw `ConcurrentModificationException` straight out of
     * the read path, into the host app. That is the one outcome [read]'s own doc promises not to
     * cause. A `ConcurrentHashMap` would drop the insertion order surfaces list providers in, so
     * the readers copy under the lock and iterate outside it instead.
     */
    private val lock = Any()
    private val providers = linkedMapOf<String, RemoteConfigProvider>()

    fun register(provider: RemoteConfigProvider) {
        require(provider.id.isNotBlank()) { "Remote config provider id must not be blank" }
        synchronized(lock) {
            require(provider.id !in providers) { "Duplicate remote config provider: ${provider.id}" }
            providers[provider.id] = provider
        }
    }

    /**
     * Drops every registration. Called when a session ends so a re-`initialize()` starts clean.
     *
     * Overrides are deliberately left in place: they are user intent, not session state, and the
     * Android side persists them across a session teardown. Clearing them here would silently
     * discard the values a developer is still debugging with.
     */
    fun clear() {
        synchronized(lock) { providers.clear() }
    }

    fun providerIds(): List<String> = synchronized(lock) { providers.keys.toList() }

    /**
     * The override in force for [key] on [providerId], or null when none is set.
     *
     * Read straight from the override store rather than from a snapshot because an override must
     * answer even when the provider is unavailable or never reported the key; deriving it from
     * provider entries would make it disappear exactly when it is most useful.
     */
    fun overrideValue(
        providerId: String,
        key: String,
    ): String? = overrides.overrides()[providerId]?.get(key)

    /** Null for an unknown id; a provider that throws yields an unavailable snapshot, never an exception. */
    fun snapshot(id: String): RemoteConfigSnapshot? = synchronized(lock) { providers[id] }?.let { read(id, it) }

    /**
     * The copy is taken under the lock and read outside it: [read] calls into host code, which may
     * be slow or may itself register a provider, and neither should happen with the lock held.
     */
    fun snapshots(): List<RemoteConfigSnapshot> =
        synchronized(lock) { providers.toList() }.map { (id, provider) -> read(id, provider) }

    /**
     * A debugging tool that crashes the app it exists to observe is worse than no tool, so a
     * provider that throws is reported as unavailable rather than propagated to the host.
     */
    private fun read(
        id: String,
        provider: RemoteConfigProvider,
    ): RemoteConfigSnapshot =
        runCatching { provider.snapshot().normalized(id, overrides.overrides()[id]) }
            .getOrElse { failure -> unavailable(id, failure) }

    /**
     * Forces the registered id onto the result: the registry's own key is what every surface
     * addresses a provider by, so a provider reporting a different [RemoteConfigSnapshot.providerId]
     * must not produce a snapshot nothing can look up again.
     *
     * The cap runs before [withOverrides] so [RemoteConfigEntry.providerValue] records the same
     * value the provider would have served, not the uncapped original.
     *
     * A snapshot that reports itself unavailable keeps the entries it reported (the contract says
     * empty) instead of gaining override entries. The Firebase adapter reaches this state by
     * returning rather than throwing, and inventing keys there would show config the provider
     * never answered for.
     */
    private fun RemoteConfigSnapshot.normalized(
        id: String,
        overrides: Map<String, String>?,
    ): RemoteConfigSnapshot {
        val capped = entries.map { it.capped() }
        val revised =
            if (unavailableReason == null) {
                capped.withOverrides(overrides.orEmpty())
            } else {
                capped
            }
        return copy(providerId = id, entries = revised)
    }

    companion object {
        /**
         * Values longer than this are cut. One pathological config value (a base64 blob, an inlined
         * JSON document) must not bloat every snapshot response on a phone-hosted server.
         */
        const val MAX_VALUE_LENGTH: Int = 8 * 1024
    }
}

/**
 * An unavailable provider keeps an empty entry list even when overrides exist for it. Fabricating
 * override entries here would present them as if the provider had reported the key, which is
 * precisely the confusion the unavailable state exists to prevent.
 */
private fun unavailable(
    id: String,
    failure: Throwable,
): RemoteConfigSnapshot =
    RemoteConfigSnapshot(
        providerId = id,
        entries = emptyList(),
        fetchInfo = RemoteConfigFetchInfo.unknown(),
        unavailableReason =
            failure.message?.takeIf(String::isNotBlank)
                ?: failure.javaClass.simpleName,
    )

private fun RemoteConfigEntry.capped(): RemoteConfigEntry =
    if (value.length <= RemoteConfigRegistry.MAX_VALUE_LENGTH) {
        this
    } else {
        copy(value = value.take(RemoteConfigRegistry.MAX_VALUE_LENGTH), truncated = true)
    }

/**
 * Applies this provider's overrides while preserving the provider's own ordering: reported keys
 * stay where the provider put them, and keys the provider never reported are appended in key order
 * so repeated reads produce a stable list rather than map-hash order.
 */
private fun List<RemoteConfigEntry>.withOverrides(overrides: Map<String, String>): List<RemoteConfigEntry> {
    if (overrides.isEmpty()) {
        return this
    }
    val overridden = map { entry -> entry.withOverride(overrides[entry.key]) }
    val reportedKeys = mapTo(mutableSetOf<String>()) { it.key }
    val added =
        overrides.keys
            .filterNot(reportedKeys::contains)
            .sorted()
            .map { key -> addedEntry(key, overrides.getValue(key)) }
    return overridden + added
}

private fun RemoteConfigEntry.withOverride(overrideValue: String?): RemoteConfigEntry =
    if (overrideValue == null) {
        this
    } else {
        copy(
            value = overrideValue,
            source = RemoteConfigSource.OVERRIDE,
            providerValue = value,
            providerSource = source,
        )
    }

private fun addedEntry(
    key: String,
    value: String,
): RemoteConfigEntry =
    RemoteConfigEntry(
        key = key,
        value = value,
        source = RemoteConfigSource.OVERRIDE,
    )
