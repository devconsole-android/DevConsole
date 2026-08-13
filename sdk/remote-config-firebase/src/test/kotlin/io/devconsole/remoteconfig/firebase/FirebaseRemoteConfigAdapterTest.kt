/**
 * @author Md. Asaduzzaman Nur
 * @since 13/08/26
 */
package io.devconsole.remoteconfig.firebase

import io.devconsole.remoteconfig.RemoteConfigFetchStatus
import io.devconsole.remoteconfig.RemoteConfigSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the adapter with plain Kotlin stand-ins whose method names match Firebase's, the same
 * technique `FirebaseRemoteMessageAdapterTest` uses -- so the real Firebase SDK never enters this
 * module's test classpath, which is the whole point of the reflection bridge.
 */
class FirebaseRemoteConfigAdapterTest {
    private class FakeValue(
        private val value: String,
        val source: Int,
    ) {
        fun asString(): String = value
    }

    private class FakeSettings(
        val minimumFetchIntervalInSeconds: Long,
    )

    private class FakeInfo(
        val fetchTimeMillis: Long,
        val lastFetchStatus: Int,
        val configSettings: FakeSettings = FakeSettings(3600L),
    )

    private class FakeRemoteConfig(
        private val all: Map<String, FakeValue>,
        private val info: FakeInfo = FakeInfo(FETCH_TIME, SUCCESS),
    ) {
        fun getAll(): Map<String, FakeValue> = all

        fun getInfo(): FakeInfo = info
    }

    @Test
    fun `maps firebase value sources onto the vendor-neutral enum`() {
        val adapter =
            FirebaseRemoteConfigAdapter(
                FakeRemoteConfig(
                    mapOf(
                        "fromServer" to FakeValue("a", VALUE_SOURCE_REMOTE),
                        "fromDefaults" to FakeValue("b", VALUE_SOURCE_DEFAULT),
                        "fromNowhere" to FakeValue("c", VALUE_SOURCE_STATIC),
                    ),
                ),
            )

        val bySource = adapter.snapshot().entries.associate { it.key to it.source }

        assertEquals(RemoteConfigSource.REMOTE, bySource["fromServer"])
        assertEquals(RemoteConfigSource.DEFAULT, bySource["fromDefaults"])
        assertEquals(RemoteConfigSource.STATIC, bySource["fromNowhere"])
    }

    @Test
    fun `an unrecognised source integer reads as unknown rather than being guessed`() {
        val adapter = FirebaseRemoteConfigAdapter(FakeRemoteConfig(mapOf("k" to FakeValue("v", 99))))

        assertEquals(
            RemoteConfigSource.UNKNOWN,
            adapter
                .snapshot()
                .entries
                .single()
                .source,
        )
    }

    @Test
    fun `reads the value through asString`() {
        val adapter =
            FirebaseRemoteConfigAdapter(FakeRemoteConfig(mapOf("k" to FakeValue("hello", VALUE_SOURCE_REMOTE))))

        assertEquals(
            "hello",
            adapter
                .snapshot()
                .entries
                .single()
                .value,
        )
    }

    /** Firebase's LAST_FETCH_STATUS_SUCCESS is -1, so a "negative means unknown" guess breaks every success. */
    @Test
    fun `fetch status success is the negative constant`() {
        val adapter = fetchInfoAdapter(FakeInfo(FETCH_TIME, SUCCESS))

        assertEquals(RemoteConfigFetchStatus.SUCCESS, adapter.snapshot().fetchInfo.status)
    }

    @Test
    fun `maps the remaining fetch statuses`() {
        assertEquals(
            RemoteConfigFetchStatus.NO_FETCH_YET,
            fetchInfoAdapter(FakeInfo(FETCH_TIME, NO_FETCH_YET)).snapshot().fetchInfo.status,
        )
        assertEquals(
            RemoteConfigFetchStatus.FAILURE,
            fetchInfoAdapter(FakeInfo(FETCH_TIME, FAILURE)).snapshot().fetchInfo.status,
        )
        assertEquals(
            RemoteConfigFetchStatus.THROTTLED,
            fetchInfoAdapter(FakeInfo(FETCH_TIME, THROTTLED)).snapshot().fetchInfo.status,
        )
    }

    @Test
    fun `an unrecognised fetch status reads as unknown`() {
        assertEquals(
            RemoteConfigFetchStatus.UNKNOWN,
            fetchInfoAdapter(FakeInfo(FETCH_TIME, 77)).snapshot().fetchInfo.status,
        )
    }

    /** Firebase reports -1 for "no fetch yet"; rendering that raw would date the fetch to 1969. */
    @Test
    fun `a negative fetch time reads as never fetched rather than an epoch sentinel`() {
        val adapter = fetchInfoAdapter(FakeInfo(-1L, NO_FETCH_YET))

        assertNull(adapter.snapshot().fetchInfo.lastFetchEpochMs)
    }

    @Test
    fun `a real fetch time is carried through`() {
        val adapter = fetchInfoAdapter(FakeInfo(FETCH_TIME, SUCCESS))

        assertEquals(FETCH_TIME, adapter.snapshot().fetchInfo.lastFetchEpochMs)
    }

    @Test
    fun `reads the minimum fetch interval from the nested config settings`() {
        val adapter = fetchInfoAdapter(FakeInfo(FETCH_TIME, SUCCESS, FakeSettings(43_200L)))

        assertEquals(43_200L, adapter.snapshot().fetchInfo.minimumFetchIntervalSeconds)
    }

    @Test
    fun `entries are sorted by key so a refresh does not reshuffle the table`() {
        val adapter =
            FirebaseRemoteConfigAdapter(
                FakeRemoteConfig(
                    mapOf(
                        "zeta" to FakeValue("z", VALUE_SOURCE_REMOTE),
                        "alpha" to FakeValue("a", VALUE_SOURCE_REMOTE),
                        "mid" to FakeValue("m", VALUE_SOURCE_REMOTE),
                    ),
                ),
            )

        assertEquals(listOf("alpha", "mid", "zeta"), adapter.snapshot().entries.map { it.key })
    }

    @Test
    fun `an object that is not firebase shaped yields an unavailable snapshot instead of throwing`() {
        val snapshot = FirebaseRemoteConfigAdapter(Any()).snapshot()

        assertTrue(snapshot.entries.isEmpty())
        assertEquals(RemoteConfigFetchStatus.UNKNOWN, snapshot.fetchInfo.status)
        assertNull(snapshot.fetchInfo.lastFetchEpochMs)
    }

    @Test
    fun `a remote config whose getAll throws does not propagate`() {
        val exploding =
            object {
                fun getAll(): Map<String, Any> = error("firebase exploded")

                fun getInfo(): FakeInfo = FakeInfo(FETCH_TIME, SUCCESS)
            }

        assertTrue(FirebaseRemoteConfigAdapter(exploding).snapshot().entries.isEmpty())
    }

    @Test
    fun `defaults its provider id to firebase and honours an explicit one`() {
        assertEquals("firebase", FirebaseRemoteConfigAdapter(FakeRemoteConfig(emptyMap())).id)
        assertEquals("rc-staging", FirebaseRemoteConfigAdapter(FakeRemoteConfig(emptyMap()), "rc-staging").id)
        assertEquals(
            "rc-staging",
            FirebaseRemoteConfigAdapter(FakeRemoteConfig(emptyMap()), "rc-staging").snapshot().providerId,
        )
    }

    private fun fetchInfoAdapter(info: FakeInfo) = FirebaseRemoteConfigAdapter(FakeRemoteConfig(emptyMap(), info))

    private companion object {
        const val VALUE_SOURCE_STATIC = 0
        const val VALUE_SOURCE_DEFAULT = 1
        const val VALUE_SOURCE_REMOTE = 2
        const val SUCCESS = -1
        const val NO_FETCH_YET = 0
        const val FAILURE = 1
        const val THROTTLED = 2
        const val FETCH_TIME = 1_755_043_200_000L
    }
}
