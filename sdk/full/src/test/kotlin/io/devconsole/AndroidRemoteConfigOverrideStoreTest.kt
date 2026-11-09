/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidRemoteConfigOverrideStoreTest {
    private val application: Application = ApplicationProvider.getApplicationContext()

    private fun preferences() =
        application.getSharedPreferences("devconsole_remote_config_overrides", Context.MODE_PRIVATE)

    @Before
    fun clearPersistedOverrides() {
        preferences().edit().clear().commit()
    }

    @Test
    fun `persists overrides and reloads them into a fresh store`() {
        val store = AndroidRemoteConfigOverrideStore()
        store.attach(application)
        store.setOverride("firebase", "flag", "true")
        store.setOverride("firebase", "other", "1")

        val reloaded = AndroidRemoteConfigOverrideStore()
        reloaded.attach(application)

        assertEquals(mapOf("firebase" to mapOf("flag" to "true", "other" to "1")), reloaded.overrides())
    }

    @Test
    fun `an override set before attach wins over the persisted one`() {
        val first = AndroidRemoteConfigOverrideStore()
        first.attach(application)
        first.setOverride("firebase", "flag", "persisted")

        val second = AndroidRemoteConfigOverrideStore()
        second.setOverride("firebase", "flag", "in-memory")
        second.attach(application)

        assertEquals("in-memory", second.overrides().getValue("firebase").getValue("flag"))
        val reread = AndroidRemoteConfigOverrideStore()
        reread.attach(application)
        assertEquals("in-memory", reread.overrides().getValue("firebase").getValue("flag"))
    }

    @Test
    fun `clearOverride and clearAll remove the persisted entries`() {
        val store = AndroidRemoteConfigOverrideStore()
        store.attach(application)
        store.setOverride("firebase", "flag", "true")
        store.setOverride("firebase", "other", "1")

        store.clearOverride("firebase", "flag")

        assertFalse(preferences().all.containsValue("true"))
        store.clearAll()
        assertTrue(preferences().all.isEmpty())
        assertTrue(store.overrides().isEmpty())
    }

    @Test
    fun `provider ids containing a colon round-trip through the pref encoding`() {
        val store = AndroidRemoteConfigOverrideStore()
        store.attach(application)
        store.setOverride("firebase:prod", "flag:sub", "value")

        val reloaded = AndroidRemoteConfigOverrideStore()
        reloaded.attach(application)

        assertEquals("value", reloaded.overrides().getValue("firebase:prod").getValue("flag:sub"))
    }

    @Test
    fun `corrupt pref entries are ignored rather than failing attach`() {
        preferences().edit().putString("not-a-valid-key", "value").commit()
        preferences().edit().putString("99:short", "value").commit()

        val store = AndroidRemoteConfigOverrideStore()
        store.attach(application)

        assertTrue(store.overrides().isEmpty())
    }
}
