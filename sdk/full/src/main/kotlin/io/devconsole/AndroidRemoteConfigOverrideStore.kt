/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole

import android.content.Context
import android.content.SharedPreferences
import io.devconsole.remoteconfig.InMemoryRemoteConfigOverrideStore
import io.devconsole.remoteconfig.RemoteConfigOverrideListener
import io.devconsole.remoteconfig.RemoteConfigOverrideStore

/**
 * A [RemoteConfigOverrideStore] that keeps live overrides in memory and mirrors them to app-private
 * SharedPreferences, so the values a developer is debugging with survive a process restart.
 *
 * Constructed without a [Context] because the facade owns one as a field before a runtime exists;
 * [attach] supplies the context once, on the first [PlatformFacadeProvider.initialize]. Later calls
 * are no-ops, which keeps a re-initialize from reloading the file over overrides the developer has
 * edited since.
 */
internal class AndroidRemoteConfigOverrideStore : RemoteConfigOverrideStore {
    private val delegate = InMemoryRemoteConfigOverrideStore()
    private val lock = Any()
    private var preferences: SharedPreferences? = null

    /**
     * Binds this store to [context]'s SharedPreferences and merges the persisted overrides in.
     *
     * Persisted values never overwrite one set in memory before attach: an override applied during
     * the window before initialize is user intent from the same session, and is the newer fact.
     * Everything the store knows is written back afterwards, so a losing persisted value is
     * replaced by the winning one rather than lingering in the file.
     */
    fun attach(context: Context) {
        val target =
            runCatching {
                context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            }.getOrNull() ?: return
        synchronized(lock) {
            if (preferences != null) return
            preferences = target
            loadPersisted(target)
            writeAll(target)
        }
    }

    override fun overrides(): Map<String, Map<String, String>> = delegate.overrides()

    override fun setOverride(
        providerId: String,
        key: String,
        value: String,
    ) {
        delegate.setOverride(providerId, key, value)
        persist()
    }

    override fun clearOverride(
        providerId: String,
        key: String,
    ) {
        delegate.clearOverride(providerId, key)
        persist()
    }

    override fun clearAll() {
        delegate.clearAll()
        persist()
    }

    override fun addListener(listener: RemoteConfigOverrideListener) = delegate.addListener(listener)

    override fun removeListener(listener: RemoteConfigOverrideListener) = delegate.removeListener(listener)

    /** Invalid persisted keys/values are skipped; a corrupt file must never fail the host's initialize. */
    private fun loadPersisted(prefs: SharedPreferences) {
        val existing = delegate.overrides()
        val persisted = runCatching { prefs.all }.getOrDefault(emptyMap())
        persisted
            .mapNotNull { (prefKey, value) ->
                when (val stored = value) {
                    is String ->
                        decodeOverrideKey(prefKey)?.let { (providerId, key) ->
                            Triple(providerId, key, stored)
                        }
                    else -> null
                }
            }.filterNot { (providerId, key, _) -> existing[providerId]?.containsKey(key) == true }
            .forEach { (providerId, key, value) -> runCatching { delegate.setOverride(providerId, key, value) } }
    }

    private fun persist() {
        synchronized(lock) {
            preferences?.let(::writeAll)
        }
    }

    private fun writeAll(prefs: SharedPreferences) {
        runCatching {
            val editor = prefs.edit().clear()
            delegate.overrides().forEach { (providerId, values) ->
                values.forEach { (key, value) ->
                    editor.putString(encodeOverrideKey(providerId, key), value)
                }
            }
            editor.apply()
        }
    }
}

private const val PREFERENCES_NAME = "devconsole_remote_config_overrides"
private const val SEPARATOR = ':'

private fun encodeOverrideKey(
    providerId: String,
    key: String,
): String = "${providerId.length}$SEPARATOR$providerId$key"

/**
 * The leading length is what makes the encoding reversible for a provider id containing the
 * separator: the id's own colon cannot be mistaken for the one after its length.
 */
private fun decodeOverrideKey(encoded: String): Pair<String, String>? {
    val separatorIndex = encoded.indexOf(SEPARATOR)
    val providerIdLength = if (separatorIndex > 0) encoded.substring(0, separatorIndex).toIntOrNull() else null
    val providerIdStart = separatorIndex + 1
    val providerIdEnd = providerIdStart + (providerIdLength ?: 0)
    val valid = providerIdLength != null && providerIdLength > 0 && providerIdEnd < encoded.length
    return if (valid) {
        encoded.substring(providerIdStart, providerIdEnd) to encoded.substring(providerIdEnd)
    } else {
        null
    }
}
