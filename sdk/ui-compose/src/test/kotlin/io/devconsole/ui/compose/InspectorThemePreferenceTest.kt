package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InspectorThemePreferenceTest {
    @Test
    fun `missing override delegates to system theme`() {
        val store = FakeThemePreferenceStore()
        assertNull(store.readOverride())
        assertEquals(true, resolveDarkTheme(store.readOverride(), systemDark = true))
        assertEquals(false, resolveDarkTheme(store.readOverride(), systemDark = false))
    }

    @Test
    fun `explicit override wins and survives a new store read`() {
        val values = mutableMapOf<String, Boolean>()
        val store = FakeThemePreferenceStore(values)
        store.writeOverride(true)

        assertEquals(true, FakeThemePreferenceStore(values).readOverride())
        assertEquals(true, resolveDarkTheme(store.readOverride(), systemDark = false))
    }
}

private class FakeThemePreferenceStore(
    private val values: MutableMap<String, Boolean> = mutableMapOf(),
) : InspectorThemePreferenceStore {
    override fun readOverride(): Boolean? = values["dark"]
    override fun writeOverride(dark: Boolean) { values["dark"] = dark }
}
