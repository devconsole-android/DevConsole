/**
 * @author Zakaria
 * @since 26/10/26
 */
package io.devconsole.locationping

import java.io.File

internal class LocationPingTrackReader(
    private val schema: LocationPingSchema,
    private val redactFields: (Map<String, String>) -> Map<String, String>,
    private val maxPoints: Int,
) {
    fun read(
        track: LocationPingTrack,
        file: File,
    ): LocationPingTrackDetail {
        val points = ArrayList<LocationPingPoint>()
        var truncated = false
        file.useLines { lines ->
            for (line in lines) {
                if (points.size >= maxPoints) {
                    truncated = true
                    break
                }
                pointsOfLine(line).forEach { if (points.size < maxPoints) points += it else truncated = true }
            }
        }
        return LocationPingTrackDetail(track, inDeviceTimeOrderWithGaps(points), truncated)
    }

    private fun pointsOfLine(line: String): List<LocationPingPoint> {
        val record = PingJson.parseOrNull(line) as? PingJson.Obj
        val body = record?.get(RECORD_BODY) as? PingJson.Obj ?: return emptyList()
        val status = record[RECORD_STATUS]?.asDouble()?.toInt() ?: NO_CONNECTION_STATUS
        val pingObjects =
            when (val listKey = schema.pingListKey) {
                null -> listOf(body)
                else -> (body[listKey] as? PingJson.Arr)?.items?.filterIsInstance<PingJson.Obj>().orEmpty()
            }
        return pingObjects.mapNotNull { ping -> pointOrNull(ping, body, outcomeOfStoredStatus(status)) }
    }

    private fun pointOrNull(
        ping: PingJson.Obj,
        body: PingJson.Obj,
        outcome: LocationPingOutcome,
    ): LocationPingPoint? {
        val latitude = ping.lookup(schema.latitudeKey, body)?.asDouble()?.takeIf { it in -MAX_LATITUDE..MAX_LATITUDE }
        val longitude =
            ping
                .lookup(
                    schema.longitudeKey,
                    body,
                )?.asDouble()
                ?.takeIf { it in -MAX_LONGITUDE..MAX_LONGITUDE }
        val deviceTime = ping.lookup(schema.deviceTimeKey, body)?.asEpochMillis()
        if (latitude == null || longitude == null || deviceTime == null) return null
        return LocationPingPoint(
            index = 0,
            latitude = latitude,
            longitude = longitude,
            deviceTimeEpochMs = deviceTime,
            deviceTimeGapSeconds = 0.0,
            fixTimeEpochMs = schema.fixTimeKey?.let { ping.lookup(it, body)?.asEpochMillis() },
            fixTimeGapSeconds = null,
            outcome = outcome,
            appState = schema.appStateKey?.let { ping.lookup(it, body)?.asReadableText() }?.takeIf(String::isNotBlank),
            fields = redactFields(readableFieldsOf(ping, body)),
        )
    }

    private fun readableFieldsOf(
        ping: PingJson.Obj,
        body: PingJson.Obj,
    ): Map<String, String> {
        val shownElsewhere =
            setOfNotNull(schema.pingListKey, schema.latitudeKey, schema.longitudeKey, schema.deviceTimeKey)
        val fields = LinkedHashMap<String, String>()
        ping.fields.forEach { (key, value) -> if (key !in shownElsewhere) fields[key] = value.asReadableText() }
        body.fields.forEach { (key, value) ->
            if (key !in shownElsewhere &&
                key !in fields
            ) {
                fields[key] = value.asReadableText()
            }
        }
        return fields
    }

    private fun inDeviceTimeOrderWithGaps(points: List<LocationPingPoint>): List<LocationPingPoint> {
        var previousDeviceTime: Long? = null
        var previousFixTime: Long? = null
        return points.sortedBy { it.deviceTimeEpochMs }.mapIndexed { index, point ->
            val deviceGap = previousDeviceTime?.let { (point.deviceTimeEpochMs - it) / MILLIS_PER_SECOND } ?: 0.0
            val fixGap = point.fixTimeEpochMs?.let { fix -> previousFixTime?.let { (fix - it) / MILLIS_PER_SECOND } }
            previousDeviceTime = point.deviceTimeEpochMs
            point.fixTimeEpochMs?.let { previousFixTime = it }
            point.copy(index = index, deviceTimeGapSeconds = deviceGap, fixTimeGapSeconds = fixGap)
        }
    }
}

internal const val RECORD_STATUS = "status"
internal const val RECORD_RECORDED_AT = "recordedAt"
internal const val RECORD_BODY = "body"

private const val MILLIS_PER_SECOND = 1000.0
private const val SMALLEST_EPOCH_MILLIS = 100_000_000_000.0
private const val MILLIS_PER_SECOND_LONG = 1000L
private const val MAX_LATITUDE = 90.0
private const val MAX_LONGITUDE = 180.0

private fun PingJson.Obj.lookup(
    key: String,
    body: PingJson.Obj,
): PingJson? = this[key] ?: body[key]

private fun PingJson.asEpochMillis(): Long? {
    val value = asDouble()?.takeIf { it > 0 } ?: return null
    return if (value < SMALLEST_EPOCH_MILLIS) (value * MILLIS_PER_SECOND_LONG).toLong() else value.toLong()
}
