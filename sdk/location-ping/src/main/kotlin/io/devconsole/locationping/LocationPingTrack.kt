/**
 * @author Zakaria
 * @since 26/10/26
 */
package io.devconsole.locationping

data class LocationPingStreamStatus(
    val name: String,
    val path: String,
    val enabled: Boolean,
)

data class LocationPingTrack(
    val stream: String,
    val file: String,
    val groupId: String,
    val capturedDate: String,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
)

data class LocationPingPoint(
    val index: Int,
    val latitude: Double,
    val longitude: Double,
    val deviceTimeEpochMs: Long,
    val deviceTimeGapSeconds: Double,
    val fixTimeEpochMs: Long?,
    val fixTimeGapSeconds: Double?,
    val outcome: LocationPingOutcome,
    val appState: String?,
    val fields: Map<String, String>,
)

data class LocationPingTrackDetail(
    val track: LocationPingTrack,
    val points: List<LocationPingPoint>,
    val truncated: Boolean,
)

data class LocationPingStorage(
    val usedBytes: Long,
    val maxBytes: Long,
    val lastError: String?,
)

data class LocationPingDeleteResult(
    val filesDeleted: Int,
    val filesLeft: Int,
)

interface LocationPingSwitches {
    fun isEnabled(streamName: String): Boolean

    fun setEnabled(
        streamName: String,
        enabled: Boolean,
    )
}

class InMemoryLocationPingSwitches : LocationPingSwitches {
    private val enabledStreams =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    override fun isEnabled(streamName: String): Boolean = streamName in enabledStreams

    override fun setEnabled(
        streamName: String,
        enabled: Boolean,
    ) {
        if (enabled) enabledStreams += streamName else enabledStreams -= streamName
    }
}
