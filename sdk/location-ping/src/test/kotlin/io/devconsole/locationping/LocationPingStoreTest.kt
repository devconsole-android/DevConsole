/**
 * @author Zakaria
 * @since 26/10/26
 */
package io.devconsole.locationping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.TimeZone

class LocationPingStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val ridePings = LocationPingStream("ride", "/v2/trips/locations")
    private val idlePings = LocationPingStream("idle", "/v2/driver/locations")
    private val batchSchema =
        LocationPingSchema(
            pingListKey = "pings",
            latitudeKey = "lat",
            longitudeKey = "lng",
            deviceTimeKey = "device_time",
            fixTimeKey = "fix_time",
            groupIdKey = "trip_ids",
            appStateKey = "process",
        )

    @Test
    fun `a stream matches on the request path whatever the host or query`() {
        val tracking = LocationPingTracking(listOf(idlePings, ridePings))

        assertEquals(idlePings, tracking.streamFor("https://staging.example.com/v2/driver/locations?qa=1"))
        assertEquals(ridePings, tracking.streamFor("http://10.0.2.2:8080/api/v2/trips/locations/"))
        assertNull(tracking.streamFor("https://example.com/v2/driver/profile"))
    }

    @Test
    fun `nothing is written while the stream switch is off`() {
        val store = store()

        val accepted =
            store.record(
                "https://api.test/v2/trips/locations",
                ridePing(1_000),
                LocationPingOutcome.Answered(200),
            )

        assertFalse(accepted)
        assertTrue(store.tracks().isEmpty())
    }

    @Test
    fun `an enabled stream writes one file per group and day`() {
        val store = store().also { it.setEnabled("ride", true) }

        store.record(
            "https://api.test/v2/trips/locations",
            ridePing(1_000, orderId = "order-7"),
            LocationPingOutcome.Answered(200),
        )
        store.record(
            "https://api.test/v2/trips/locations",
            ridePing(2_000, orderId = "order-7"),
            LocationPingOutcome.Answered(200),
        )
        store.record(
            "https://api.test/v2/trips/locations",
            ridePing(3_000, orderId = "order 9/x"),
            LocationPingOutcome.Answered(200),
        )

        val files = store.tracks().map { it.file }.sorted()
        assertEquals(listOf("order-7-2026-10-26.jsonl", "order_9_x-2026-10-26.jsonl"), files)
        assertEquals("order-7", store.tracks().first { it.file.startsWith("order-7") }.groupId)
    }

    @Test
    fun `a ping without a group id is filed under the stream name`() {
        val store = store().also { it.setEnabled("idle", true) }

        store.record(
            "https://api.test/v2/driver/locations",
            ridePing(1_000, orderId = null),
            LocationPingOutcome.Answered(200),
        )

        assertEquals(listOf("idle-2026-10-26.jsonl"), store.tracks().map { it.file })
    }

    @Test
    fun `network pings map a missing or failed response to no connection`() {
        val store = store().also { it.setEnabled("ride", true) }

        store.recordNetworkPing("https://api.test/v2/trips/locations", ridePing(1_000).toByteArray(), 503)
        store.recordNetworkPing("https://api.test/v2/trips/locations", ridePing(2_000).toByteArray(), 0)
        store.recordNetworkPing("https://api.test/v2/trips/locations", ridePing(3_000).toByteArray(), null)

        val outcomes = store.onlyTrack().points.map { it.outcome }
        assertEquals(
            listOf(
                LocationPingOutcome.Answered(503),
                LocationPingOutcome.NoConnection,
                LocationPingOutcome.NoConnection,
            ),
            outcomes,
        )
    }

    @Test
    fun `points come back in device time order with gaps since the previous point`() {
        val store = store().also { it.setEnabled("ride", true) }

        store.record(
            "https://api.test/v2/trips/locations",
            ridePing(5_000, fixTime = 4_000),
            LocationPingOutcome.DroppedOnDevice,
        )
        store.record(
            "https://api.test/v2/trips/locations",
            ridePing(1_000, fixTime = 500),
            LocationPingOutcome.Answered(200),
        )
        store.record(
            "https://api.test/v2/trips/locations",
            ridePing(2_500, fixTime = 2_000),
            LocationPingOutcome.NoConnection,
        )

        val points = store.onlyTrack().points
        assertEquals(listOf(1_000L, 2_500L, 5_000L), points.map { it.deviceTimeEpochMs - OCTOBER_26_2026_NOON_UTC })
        assertEquals(listOf(0.0, 1.5, 2.5), points.map { it.deviceTimeGapSeconds })
        assertEquals(listOf(null, 1.5, 2.0), points.map { it.fixTimeGapSeconds })
        assertEquals(LocationPingOutcome.DroppedOnDevice, points.last().outcome)
        assertEquals("FOREGROUND", points.first().appState)
    }

    @Test
    fun `every ping in a batch becomes its own point and shares the body fields`() {
        val store = store().also { it.setEnabled("ride", true) }
        val body =
            """
            {"trip_ids":["o1"],"battery":81,"pings":[
              {"lat":23.79,"lng":90.41,"device_time":1000},
              {"lat":"23.80","lng":"90.42","device_time":2000,"speed":"4.2"},
              {"lat":123.0,"lng":90.42,"device_time":3000}
            ]}
            """.trimIndent()

        store.record("https://api.test/v2/trips/locations", body, LocationPingOutcome.Answered(200))

        val points = store.onlyTrack().points
        assertEquals(2, points.size)
        assertEquals(23.80, points[1].latitude, 0.0)
        assertEquals("81", points[1].fields["battery"])
        assertEquals("4.2", points[1].fields["speed"])
        assertFalse(points[1].fields.containsKey("lat"))
    }

    @Test
    fun `a schema without a ping list treats the body as one ping and accepts seconds`() {
        val store = store(LocationPingSchema()).also { it.setEnabled("idle", true) }

        store.record(
            "https://api.test/v2/driver/locations",
            """{"latitude":1.5,"longitude":2.5,"timestamp":1793022273}""",
            LocationPingOutcome.Answered(204),
        )

        val point = store.onlyTrack().points.single()
        assertEquals(1_793_022_273_000L, point.deviceTimeEpochMs)
    }

    @Test
    fun `fields pass through the redactor before they are returned`() {
        val store =
            store(redact = { fields ->
                fields.mapValues { (key, value) ->
                    if (key ==
                        "token"
                    ) {
                        "<redacted>"
                    } else {
                        value
                    }
                }
            })
                .also { it.setEnabled("ride", true) }

        store.record(
            "https://api.test/v2/trips/locations",
            ridePing(1_000, extra = ""","token":"secret""""),
            LocationPingOutcome.Answered(200),
        )

        assertEquals(
            "<redacted>",
            store
                .onlyTrack()
                .points
                .single()
                .fields["token"],
        )
    }

    @Test
    fun `a body that is not a JSON object is skipped`() {
        val store = store().also { it.setEnabled("ride", true) }

        store.record("https://api.test/v2/trips/locations", "lat=1&lng=2", LocationPingOutcome.Answered(200))

        assertTrue(store.tracks().isEmpty())
    }

    @Test
    fun `recording stops at the storage cap and reports why`() {
        val store = store(maxStorageBytes = 400).also { it.setEnabled("ride", true) }

        repeat(10) {
            store.record(
                "https://api.test/v2/trips/locations",
                ridePing(1_000L + it),
                LocationPingOutcome.Answered(200),
            )
        }

        val storage = store.storage()
        assertTrue(storage.usedBytes <= 400)
        assertNotNull(storage.lastError)
        assertTrue(storage.lastError!!.contains("full"))
    }

    @Test
    fun `track lookups refuse names that could leave the stream folder`() {
        val store = store().also { it.setEnabled("ride", true) }
        store.record("https://api.test/v2/trips/locations", ridePing(1_000), LocationPingOutcome.Answered(200))

        assertNull(store.track("..", "order-1-2026-10-26.jsonl"))
        assertNull(store.track("ride", "../idle/order-1-2026-10-26.jsonl"))
        assertNull(store.track("ride", "notes.txt"))
        assertNotNull(store.track("ride", "order-1-2026-10-26.jsonl"))
    }

    @Test
    fun `deleting a stream leaves the other streams alone`() {
        val store =
            store().also {
                it.setEnabled("ride", true)
                it.setEnabled("idle", true)
            }
        store.record("https://api.test/v2/trips/locations", ridePing(1_000), LocationPingOutcome.Answered(200))
        store.record(
            "https://api.test/v2/driver/locations",
            ridePing(1_000, orderId = null),
            LocationPingOutcome.Answered(200),
        )

        val result = store.delete(setOf("ride"))

        assertEquals(1, result.filesDeleted)
        assertEquals(listOf("idle"), store.tracks().map { it.stream })
        assertEquals(store.tracks().sumOf { it.sizeBytes }, store.storage().usedBytes)
    }

    @Test
    fun `switches only apply to configured streams`() {
        val store = store()

        assertFalse(store.setEnabled("unknown", true))
        assertTrue(store.setEnabled("idle", true))
        assertTrue(store.isEnabled())
        assertTrue(store.isEnabled("idle"))
        assertFalse(store.isEnabled("ride"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a stream name that is only dots is rejected`() {
        LocationPingStream("..", "/v1/pings")
    }

    private fun store(
        schema: LocationPingSchema = batchSchema,
        maxStorageBytes: Long = LocationPingTracking.DEFAULT_MAX_STORAGE_BYTES,
        redact: (Map<String, String>) -> Map<String, String> = { it },
    ): LocationPingStore =
        LocationPingStore(
            rootDirectory = folder.newFolder("pings"),
            redactFields = redact,
            writer = { it.run() },
            clock = { OCTOBER_26_2026_NOON_UTC },
            timeZone = TimeZone.getTimeZone("UTC"),
        ).also { it.configure(LocationPingTracking(listOf(idlePings, ridePings), schema, maxStorageBytes)) }

    private fun LocationPingStore.onlyTrack(): LocationPingTrackDetail {
        val track = tracks().single()
        return checkNotNull(track(track.stream, track.file))
    }

    private fun ridePing(
        deviceTime: Long,
        orderId: String? = "order-1",
        fixTime: Long = deviceTime - 100,
        extra: String = "",
    ): String {
        val orders = orderId?.let { """"trip_ids":["$it"],""" }.orEmpty()
        val deviceTimeField = """"device_time":${OCTOBER_26_2026_NOON_UTC + deviceTime}"""
        val times = """$deviceTimeField,"fix_time":${OCTOBER_26_2026_NOON_UTC + fixTime}"""
        return """{$orders"process":"FOREGROUND"$extra,"pings":[{"lat":23.79,"lng":90.41,$times}]}"""
    }

    private companion object {
        const val OCTOBER_26_2026_NOON_UTC = 1_793_016_000_000L
    }
}
