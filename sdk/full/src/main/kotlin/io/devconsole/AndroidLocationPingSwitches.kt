/**
 * @author Zakaria
 * @since 26/10/26
 */
package io.devconsole

import android.content.Context
import android.content.SharedPreferences
import io.devconsole.locationping.LocationPingSwitches

internal class AndroidLocationPingSwitches(
    context: Context,
) : LocationPingSwitches {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun isEnabled(streamName: String): Boolean = preferences.getBoolean(keyOf(streamName), false)

    override fun setEnabled(
        streamName: String,
        enabled: Boolean,
    ) {
        preferences.edit().putBoolean(keyOf(streamName), enabled).apply()
    }

    private fun keyOf(streamName: String): String = "enabled.$streamName"

    private companion object {
        const val PREFERENCES_NAME = "devconsole_location_pings"
    }
}
