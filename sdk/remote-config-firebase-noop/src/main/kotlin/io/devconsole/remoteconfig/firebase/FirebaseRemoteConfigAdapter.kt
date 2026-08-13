/**
 * @author Md. Asaduzzaman Nur
 * @since 13/08/26
 */
package io.devconsole.remoteconfig.firebase

import io.devconsole.remoteconfig.RemoteConfigFetchInfo
import io.devconsole.remoteconfig.RemoteConfigProvider
import io.devconsole.remoteconfig.RemoteConfigSnapshot

/**
 * Protected-build adapter: does not reflect on or read the supplied Firebase instance.
 *
 * It reports `disabled-build` rather than an empty config, because "this build cannot show you
 * Remote Config" and "this app has no Remote Config values" are different answers, and silently
 * returning the second would be a misleading debugging tool.
 */
class FirebaseRemoteConfigAdapter
    @JvmOverloads
    constructor(
        @Suppress("UNUSED_PARAMETER") remoteConfig: Any,
        override val id: String = "firebase",
    ) : RemoteConfigProvider {
        override fun snapshot(): RemoteConfigSnapshot =
            RemoteConfigSnapshot(
                providerId = id,
                entries = emptyList(),
                fetchInfo = RemoteConfigFetchInfo.unknown(),
                unavailableReason = "disabled-build",
            )
    }
