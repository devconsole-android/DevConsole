/**
 * @author Md. Asaduzzaman Nur
 * @since 13/08/26
 */
package io.devconsole.remoteconfig

/**
 * Where a Remote Config value actually came from. This -- not the value itself -- is what makes a
 * config problem diagnosable: a key serving an in-app [DEFAULT] because the fetch was throttled
 * looks identical to one the server published, until you can see the source.
 */
enum class RemoteConfigSource(
    val wireName: String,
) {
    /** Fetched from the server and activated. */
    REMOTE("remote"),

    /** An in-app default (e.g. Firebase's `setDefaultsAsync`); no server value is active. */
    DEFAULT("default"),

    /** Set nowhere -- the provider's static fallback for an unknown key. */
    STATIC("static"),

    /** A locally applied override sitting on top of whatever the provider resolved. */
    OVERRIDE("override"),

    /** The provider could not attribute the value. Never guessed at. */
    UNKNOWN("unknown"),
    ;

    companion object {
        @JvmStatic
        fun fromWireName(value: String?): RemoteConfigSource? =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) }
    }
}

/**
 * One Remote Config key as it stands on the device right now.
 *
 * [value] is a `String` because Remote Config is string-typed at the source -- reading a key as a
 * long or a boolean is an interpretation the *reader* applies, so storing a parsed type here would
 * record information the server never sent.
 */
data class RemoteConfigEntry(
    val key: String,
    val value: String,
    val source: RemoteConfigSource,
    /** True when [value] was withheld by redaction, so a surface can badge it rather than show it. */
    val redacted: Boolean = false,
    /** True when [value] was cut to [RemoteConfigRegistry.MAX_VALUE_LENGTH]. */
    val truncated: Boolean = false,
)

/** Outcome of the provider's most recent fetch attempt. */
enum class RemoteConfigFetchStatus(
    val wireName: String,
) {
    SUCCESS("success"),
    NO_FETCH_YET("no_fetch_yet"),
    FAILURE("failure"),
    THROTTLED("throttled"),
    UNKNOWN("unknown"),
}

/**
 * Fetch metadata for a provider. [lastFetchEpochMs] is null -- never a sentinel like `-1` or `0` --
 * when no fetch has happened, so a surface cannot render "never fetched" as a 1970 timestamp.
 */
data class RemoteConfigFetchInfo(
    val lastFetchEpochMs: Long?,
    val status: RemoteConfigFetchStatus,
    val minimumFetchIntervalSeconds: Long?,
) {
    companion object {
        /** What a provider that failed or cannot report reads as; never a confident status. */
        @JvmStatic
        fun unknown(): RemoteConfigFetchInfo =
            RemoteConfigFetchInfo(
                lastFetchEpochMs = null,
                status = RemoteConfigFetchStatus.UNKNOWN,
                minimumFetchIntervalSeconds = null,
            )
    }
}

/**
 * Everything one provider can currently report. A non-null [unavailableReason] means [entries] is
 * empty because the provider could not be read -- a state the surfaces show explicitly rather than
 * rendering as "no config".
 */
data class RemoteConfigSnapshot(
    val providerId: String,
    val entries: List<RemoteConfigEntry>,
    val fetchInfo: RemoteConfigFetchInfo,
    val unavailableReason: String? = null,
)

/**
 * A host-facing source of Remote Config values. Implement this to inspect a provider DevConsole
 * ships no adapter for; [snapshot] is called on demand, when a surface asks, and is never polled.
 */
interface RemoteConfigProvider {
    val id: String

    fun snapshot(): RemoteConfigSnapshot
}
