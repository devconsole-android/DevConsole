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
 */
class RemoteConfigRegistry {
    private val providers = linkedMapOf<String, RemoteConfigProvider>()

    fun register(provider: RemoteConfigProvider) {
        require(provider.id.isNotBlank()) { "Remote config provider id must not be blank" }
        require(provider.id !in providers) { "Duplicate remote config provider: ${provider.id}" }
        providers[provider.id] = provider
    }

    fun providerIds(): List<String> = providers.keys.toList()

    /** Null for an unknown id; a provider that throws yields an unavailable snapshot, never an exception. */
    fun snapshot(id: String): RemoteConfigSnapshot? = providers[id]?.let { read(id, it) }

    fun snapshots(): List<RemoteConfigSnapshot> = providers.map { (id, provider) -> read(id, provider) }

    /**
     * A debugging tool that crashes the app it exists to observe is worse than no tool, so a
     * provider that throws is reported as unavailable rather than propagated to the host.
     */
    private fun read(
        id: String,
        provider: RemoteConfigProvider,
    ): RemoteConfigSnapshot =
        runCatching { provider.snapshot().normalized(id) }
            .getOrElse { failure ->
                RemoteConfigSnapshot(
                    providerId = id,
                    entries = emptyList(),
                    fetchInfo = RemoteConfigFetchInfo.unknown(),
                    unavailableReason =
                        failure.message?.takeIf(String::isNotBlank)
                            ?: failure.javaClass.simpleName,
                )
            }

    /**
     * Forces the registered id onto the result: the registry's own key is what every surface
     * addresses a provider by, so a provider reporting a different [RemoteConfigSnapshot.providerId]
     * must not produce a snapshot nothing can look up again.
     */
    private fun RemoteConfigSnapshot.normalized(id: String): RemoteConfigSnapshot =
        copy(providerId = id, entries = entries.map { it.capped() })

    private fun RemoteConfigEntry.capped(): RemoteConfigEntry =
        if (value.length <= MAX_VALUE_LENGTH) {
            this
        } else {
            copy(value = value.take(MAX_VALUE_LENGTH), truncated = true)
        }

    companion object {
        /**
         * Values longer than this are cut. One pathological config value (a base64 blob, an inlined
         * JSON document) must not bloat every snapshot response on a phone-hosted server.
         */
        const val MAX_VALUE_LENGTH: Int = 8 * 1024
    }
}
