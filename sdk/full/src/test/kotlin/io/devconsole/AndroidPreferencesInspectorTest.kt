/**
 * @author Shakib
 * @since 25/07/26
 */
package io.devconsole

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidPreferencesInspectorTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val inspector =
        AndroidPreferencesInspector(application, RedactionEngine(RedactionPolicy.default()))

    @Test
    fun `files lists typed entries from the app shared preferences`() {
        application
            .getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("theme", "dark")
            .putBoolean("onboarded", true)
            .putInt("launches", 7)
            .commit()

        val file = inspector.files().first { it.name == "user_prefs" }

        assertEquals("dark", file.entries.first { it.key == "theme" }.value)
        assertEquals("STRING", file.entries.first { it.key == "theme" }.type)
        assertEquals("BOOLEAN", file.entries.first { it.key == "onboarded" }.type)
        assertEquals("7", file.entries.first { it.key == "launches" }.value)
        assertEquals("INT", file.entries.first { it.key == "launches" }.type)
    }

    @Test
    fun `string values are redacted while primitives are shown verbatim`() {
        application
            .getSharedPreferences("secret_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("auth_token", "Bearer sk-super-secret-value-1234567890")
            .putInt("count", 3)
            .commit()

        val file = inspector.files().first { it.name == "secret_prefs" }
        val tokenEntry = file.entries.first { it.key == "auth_token" }

        assertFalse(tokenEntry.value.contains("sk-super-secret-value-1234567890"))
        assertTrue(tokenEntry.redacted)
        val countEntry = file.entries.first { it.key == "count" }
        assertEquals("3", countEntry.value)
        assertFalse(countEntry.redacted)
    }

    @Test
    fun `put writes a typed value that is then reflected in the store`() {
        application
            .getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("theme", "dark")
            .commit()

        assertTrue(inspector.put("user_prefs", "theme", "light", "STRING"))

        assertEquals(
            "light",
            application.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).getString("theme", null),
        )
    }

    @Test
    fun `put rejects a value that cannot be coerced to the declared type`() {
        application
            .getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("launches", 1)
            .commit()

        assertFalse(inspector.put("user_prefs", "launches", "not-a-number", "INT"))

        assertEquals(1, application.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).getInt("launches", -1))
    }

    @Test
    fun `remove deletes an entry from the store`() {
        application
            .getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("theme", "dark")
            .commit()

        assertTrue(inspector.remove("user_prefs", "theme"))

        assertNull(application.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).getString("theme", null))
    }

    @Test
    fun `put and remove refuse a file that is not a listed shared-prefs file`() {
        assertFalse(inspector.put("../../evil", "k", "v", "STRING"))
        assertFalse(inspector.remove("does_not_exist", "k"))
    }
}
