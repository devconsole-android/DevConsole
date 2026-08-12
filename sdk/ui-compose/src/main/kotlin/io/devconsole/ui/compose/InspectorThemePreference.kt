package io.devconsole.ui.compose

import android.content.Context

internal interface InspectorThemePreferenceStore {
    fun readOverride(): Boolean?

    fun writeOverride(dark: Boolean)
}

internal class SharedPreferencesInspectorThemeStore(
    context: Context,
) : InspectorThemePreferenceStore {
    private val preferences =
        context.getSharedPreferences("devconsole.ui", Context.MODE_PRIVATE)

    override fun readOverride(): Boolean? =
        if (preferences.contains(KEY_DARK_THEME)) preferences.getBoolean(KEY_DARK_THEME, false) else null

    override fun writeOverride(dark: Boolean) {
        preferences.edit().putBoolean(KEY_DARK_THEME, dark).apply()
    }

    private companion object {
        const val KEY_DARK_THEME = "dark_theme_override"
    }
}

internal fun resolveDarkTheme(
    override: Boolean?,
    systemDark: Boolean,
): Boolean = override ?: systemDark
