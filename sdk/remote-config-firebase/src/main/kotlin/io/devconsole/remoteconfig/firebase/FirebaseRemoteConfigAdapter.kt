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
        override fun snapshot(): RemoteConfigSnapshot =
            RemoteConfigSnapshot(
                providerId = id,
                entries = readEntries(),
                fetchInfo = readFetchInfo(),
            )

        /**
         * Sorted by key: Firebase hands back an unordered map, and an inspector whose rows reshuffle
         * on every refresh is unreadable when you are watching one key.
         */
        private fun readEntries(): List<RemoteConfigEntry> {
            val all = remoteConfig.call("getAll") as? Map<*, *> ?: return emptyList()
            return all.entries
                .mapNotNull { (key, value) ->
                    val name = key as? String ?: return@mapNotNull null
                    RemoteConfigEntry(
                        key = name,
                        value = value?.call("asString") as? String ?: "",
                        source = sourceOf(value),
                    )
                }.sortedBy(RemoteConfigEntry::key)
        }

        private fun sourceOf(value: Any?): RemoteConfigSource =
            when ((value?.call("getSource") as? Number)?.toInt()) {
                constant("VALUE_SOURCE_REMOTE", VALUE_SOURCE_REMOTE) -> RemoteConfigSource.REMOTE
                constant("VALUE_SOURCE_DEFAULT", VALUE_SOURCE_DEFAULT) -> RemoteConfigSource.DEFAULT
                constant("VALUE_SOURCE_STATIC", VALUE_SOURCE_STATIC) -> RemoteConfigSource.STATIC
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
                constant("LAST_FETCH_STATUS_SUCCESS", LAST_FETCH_STATUS_SUCCESS) -> RemoteConfigFetchStatus.SUCCESS
                constant("LAST_FETCH_STATUS_NO_FETCH_YET", LAST_FETCH_STATUS_NO_FETCH_YET) ->
                    RemoteConfigFetchStatus.NO_FETCH_YET
                constant("LAST_FETCH_STATUS_FAILURE", LAST_FETCH_STATUS_FAILURE) -> RemoteConfigFetchStatus.FAILURE
                constant(
                    "LAST_FETCH_STATUS_THROTTLED",
                    LAST_FETCH_STATUS_THROTTLED,
                ),
                -> RemoteConfigFetchStatus.THROTTLED
                else -> RemoteConfigFetchStatus.UNKNOWN
            }

        /**
         * Prefers the constant declared on the supplied Firebase class over the literal below, so a
         * future renumbering in the Firebase SDK cannot silently mis-badge every row.
         */
        private fun constant(
            name: String,
            fallback: Int,
        ): Int = runCatching { remoteConfig.javaClass.getField(name).getInt(null) }.getOrDefault(fallback)

        private fun Any.call(name: String): Any? =
            runCatching {
                javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(this)
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
