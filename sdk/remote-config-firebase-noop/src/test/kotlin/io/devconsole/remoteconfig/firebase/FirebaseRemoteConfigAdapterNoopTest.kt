/**
 * @author Md. Asaduzzaman Nur
 * @since 13/08/26
 */
package io.devconsole.remoteconfig.firebase

import io.devconsole.remoteconfig.RemoteConfigFetchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseRemoteConfigAdapterNoopTest {
    private class SpyRemoteConfig {
        var touched: Boolean = false

        fun getAll(): Map<String, Any> {
            touched = true
            return emptyMap()
        }

        fun getInfo(): Any {
            touched = true
            return Any()
        }
    }

    @Test
    fun `reports no entries in a protected build`() {
        assertTrue(FirebaseRemoteConfigAdapter(SpyRemoteConfig()).snapshot().entries.isEmpty())
    }

    @Test
    fun `labels itself a disabled build rather than pretending config is simply empty`() {
        assertEquals("disabled-build", FirebaseRemoteConfigAdapter(SpyRemoteConfig()).snapshot().unavailableReason)
    }

    @Test
    fun `never reflects on the supplied firebase instance`() {
        val spy = SpyRemoteConfig()

        FirebaseRemoteConfigAdapter(spy).snapshot()

        assertFalse(spy.touched)
    }

    @Test
    fun `reports no fetch metadata`() {
        val fetch = FirebaseRemoteConfigAdapter(SpyRemoteConfig()).snapshot().fetchInfo

        assertEquals(RemoteConfigFetchStatus.UNKNOWN, fetch.status)
        assertNull(fetch.lastFetchEpochMs)
        assertNull(fetch.minimumFetchIntervalSeconds)
    }

    @Test
    fun `keeps the same id contract as the enabled adapter`() {
        assertEquals("firebase", FirebaseRemoteConfigAdapter(SpyRemoteConfig()).id)
        assertEquals("rc-staging", FirebaseRemoteConfigAdapter(SpyRemoteConfig(), "rc-staging").id)
    }
}
