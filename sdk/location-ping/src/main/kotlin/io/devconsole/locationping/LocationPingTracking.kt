/**
 * @author Zakaria
 * @since 26/10/26
 */
package io.devconsole.locationping

import java.net.URI

data class LocationPingStream(
    val name: String,
    val path: String,
) {
    init {
        require(STREAM_NAME.matches(name)) { "Stream name must be 1-64 letters, digits, '.', '_' or '-': $name" }
        require(comparablePathOf(path).length > 1) { "Stream path must name an endpoint, e.g. /v1/pings: $path" }
    }

    internal val comparablePath: String get() = comparablePathOf(path)

    internal companion object {
        val STREAM_NAME = Regex("(?!\\.+$)[A-Za-z0-9._-]{1,64}")
    }
}

data class LocationPingSchema(
    val pingListKey: String? = null,
    val latitudeKey: String = "latitude",
    val longitudeKey: String = "longitude",
    val deviceTimeKey: String = "timestamp",
    val fixTimeKey: String? = null,
    val groupIdKey: String? = null,
    val appStateKey: String? = null,
    val groupLabel: String = "Group",
)

data class LocationPingTracking(
    val streams: List<LocationPingStream> = emptyList(),
    val schema: LocationPingSchema = LocationPingSchema(),
    val maxStorageBytes: Long = DEFAULT_MAX_STORAGE_BYTES,
) {
    init {
        require(streams.distinctBy { it.name }.size == streams.size) { "Location ping stream names must be unique" }
        require(maxStorageBytes > 0) { "maxStorageBytes must be positive" }
    }

    val isConfigured: Boolean get() = streams.isNotEmpty()

    fun stream(name: String): LocationPingStream? = streams.firstOrNull { it.name == name }

    fun streamFor(url: String): LocationPingStream? {
        val requestPath = comparablePathOf(url)
        if (requestPath.length <= 1) return null
        return streams
            .sortedByDescending { it.comparablePath.length }
            .firstOrNull { requestPath == it.comparablePath || requestPath.endsWith(it.comparablePath) }
    }

    companion object {
        const val DEFAULT_MAX_STORAGE_BYTES: Long = 64L * 1024L * 1024L
    }
}

private fun comparablePathOf(urlOrPath: String): String {
    val withoutQuery = urlOrPath.substringBefore('#').substringBefore('?').trim()
    val path = runCatching { URI(withoutQuery).path }.getOrNull() ?: withoutQuery
    val trimmed = path.trimEnd('/')
    return if (trimmed.startsWith('/')) trimmed else "/$trimmed"
}
