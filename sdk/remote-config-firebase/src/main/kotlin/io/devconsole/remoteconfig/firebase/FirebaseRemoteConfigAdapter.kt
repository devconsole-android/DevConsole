/**
 * @author Md. Asaduzzaman Nur
 * @since 13/08/26
 */
package io.devconsole.remoteconfig.firebase

import io.devconsole.remoteconfig.RemoteConfigEntry
import io.devconsole.remoteconfig.RemoteConfigFetchInfo
import io.devconsole.remoteconfig.RemoteConfigFetchStatus
import io.devconsole.remoteconfig.RemoteConfigProvider
import io.devconsole.remoteconfig.RemoteConfigSnapshot
import io.devconsole.remoteconfig.RemoteConfigSource
import java.lang.reflect.Method

// Deliberately not the exception's message: the throw is swallowed inside `call`. Kept a private
// top-level const rather than a companion one, which would compile to a public static field.
private const val UNREADABLE_REASON = "Firebase Remote Config could not be read reflectively"

/**
 * Optional Firebase bridge with no Firebase compile-time dependency. Consumers pass a
 * `com.google.firebase.remoteconfig.FirebaseRemoteConfig`; reflection keeps Firebase absent
 * everywhere else, exactly as `FirebaseRemoteMessageAdapter` does for Cloud Messaging.
 */
class FirebaseRemoteConfigAdapter
    @JvmOverloads
    constructor(
        private val remoteConfig: Any,
        override val id: String = "firebase",
    ) : RemoteConfigProvider {
        override fun snapshot(): RemoteConfigSnapshot {
            // `getAll` returning a non-Map is how a non-Firebase object, or a getAll that blew up
            // (R8, a renamed method), reaches us. Reporting that as an empty-but-fine snapshot would
            // make both surfaces say "this provider has not completed a fetch yet" -- a confident
            // claim about a provider we never got an answer out of, and exactly the confusion the
            // no-op twin exists to avoid. An unavailableReason says we could not read it instead.
            val all =
                remoteConfig.call("getAll") as? Map<*, *>
                    ?: return RemoteConfigSnapshot(
                        providerId = id,
                        entries = emptyList(),
                        fetchInfo = RemoteConfigFetchInfo.unknown(),
                        unavailableReason = UNREADABLE_REASON,
                    )
            return RemoteConfigSnapshot(
                providerId = id,
                entries = readEntries(all),
                fetchInfo = readFetchInfo(),
            )
        }

        /**
         * Sorted by key: Firebase hands back an unordered map, and an inspector whose rows reshuffle
         * on every refresh is unreadable when you are watching one key.
         */
        private fun readEntries(all: Map<*, *>): List<RemoteConfigEntry> =
            all.entries
                .mapNotNull { (key, value) ->
                    val name = key as? String ?: return@mapNotNull null
                    RemoteConfigEntry(
                        key = name,
                        value = value?.call("asString") as? String ?: "",
                        source = sourceOf(value),
                    )
                }.sortedBy(RemoteConfigEntry::key)

        private fun sourceOf(value: Any?): RemoteConfigSource =
            when ((value?.call("getSource") as? Number)?.toInt()) {
                sourceRemote -> RemoteConfigSource.REMOTE
                sourceDefault -> RemoteConfigSource.DEFAULT
                sourceStatic -> RemoteConfigSource.STATIC
                else -> RemoteConfigSource.UNKNOWN
            }

        private fun readFetchInfo(): RemoteConfigFetchInfo {
            val info = remoteConfig.call("getInfo") ?: return RemoteConfigFetchInfo.unknown()
            return RemoteConfigFetchInfo(
                lastFetchEpochMs = (info.call("getFetchTimeMillis") as? Number)?.toLong()?.takeIf { it > 0 },
                status = statusOf(info),
                minimumFetchIntervalSeconds =
                    (
                        info
                            .call("getConfigSettings")
                            ?.call("getMinimumFetchIntervalInSeconds") as? Number
                    )?.toLong(),
            )
        }

        private fun statusOf(info: Any): RemoteConfigFetchStatus =
            when ((info.call("getLastFetchStatus") as? Number)?.toInt()) {
                statusSuccess -> RemoteConfigFetchStatus.SUCCESS
                statusNoFetchYet -> RemoteConfigFetchStatus.NO_FETCH_YET
                statusFailure -> RemoteConfigFetchStatus.FAILURE
                statusThrottled -> RemoteConfigFetchStatus.THROTTLED
                else -> RemoteConfigFetchStatus.UNKNOWN
            }

        /**
         * Resolved once per adapter rather than per `when` evaluation: these sat inside the branch
         * conditions, so every entry's source and every fetch-status read re-ran a `getField`
         * lookup. Still prefers the constant declared on the supplied Firebase class over the
         * literal fallback, so a future renumbering in the Firebase SDK cannot silently mis-badge
         * every row.
         */
        private fun constant(
            name: String,
            fallback: Int,
        ): Int = runCatching { remoteConfig.javaClass.getField(name).getInt(null) }.getOrDefault(fallback)

        private val sourceStatic by lazy { constant("VALUE_SOURCE_STATIC", VALUE_SOURCE_STATIC) }
        private val sourceDefault by lazy { constant("VALUE_SOURCE_DEFAULT", VALUE_SOURCE_DEFAULT) }
        private val sourceRemote by lazy { constant("VALUE_SOURCE_REMOTE", VALUE_SOURCE_REMOTE) }
        private val statusSuccess by lazy { constant("LAST_FETCH_STATUS_SUCCESS", LAST_FETCH_STATUS_SUCCESS) }
        private val statusNoFetchYet by lazy {
            constant("LAST_FETCH_STATUS_NO_FETCH_YET", LAST_FETCH_STATUS_NO_FETCH_YET)
        }
        private val statusFailure by lazy { constant("LAST_FETCH_STATUS_FAILURE", LAST_FETCH_STATUS_FAILURE) }
        private val statusThrottled by lazy { constant("LAST_FETCH_STATUS_THROTTLED", LAST_FETCH_STATUS_THROTTLED) }

        /**
         * `Class.getMethods()` allocates a fresh defensive copy on every call and this then linear-
         * scans it, which at a few hundred keys ran twice per entry (`asString`, `getSource`) on
         * every inspector refresh and every dashboard poll. Cached per receiver *class*, not per
         * receiver: the map's values are `FirebaseRemoteConfigValue` instances, one per key, all of
         * the same class. A null result is cached too -- a name that is absent stays absent.
         */
        private val methodCache = HashMap<Pair<Class<*>, String>, Method?>()

        private fun Any.call(name: String): Any? =
            runCatching {
                val key = javaClass to name
                val method =
                    synchronized(methodCache) {
                        if (methodCache.containsKey(key)) {
                            methodCache[key]
                        } else {
                            javaClass.methods
                                .firstOrNull { it.name == name && it.parameterCount == 0 }
                                .also { methodCache[key] = it }
                        }
                    }
                method?.invoke(this)
            }.getOrNull()

        private companion object {
            // Verified against firebase-android-sdk's FirebaseRemoteConfig.java. LAST_FETCH_STATUS_SUCCESS
            // is -1, not 0 -- treating "negative" as unknown would mislabel every successful fetch.
            const val VALUE_SOURCE_STATIC = 0
            const val VALUE_SOURCE_DEFAULT = 1
            const val VALUE_SOURCE_REMOTE = 2
            const val LAST_FETCH_STATUS_SUCCESS = -1
            const val LAST_FETCH_STATUS_NO_FETCH_YET = 0
            const val LAST_FETCH_STATUS_FAILURE = 1
            const val LAST_FETCH_STATUS_THROTTLED = 2
        }
    }
