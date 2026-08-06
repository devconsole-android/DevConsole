package io.devconsole

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import io.devconsole.api.DevConsoleState

/**
 * Auto-initializes DevConsole on app startup in debug builds so developers do not need
 * manual boilerplate in Application.onCreate.
 */
class DevConsoleInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application
        if (app != null && app.isDebuggable() && DevConsole.state().value is DevConsoleState.Uninitialized) {
            // Zero-config startup: the runtime is ready (state/timeline/capture) without the host
            // writing any Application.onCreate boilerplate. The browser server itself is never
            // auto-started -- call DevConsole.startBrowser()/startBrowserAsync() explicitly.
            DevConsole.initializeProvisionally(app)
        }
        return true
    }

    /**
     * Runtime backstop, independent of the Gradle variant policy: if `sdk:full` ever reaches a
     * non-debuggable build, auto-initialization must not happen anyway.
     */
    private fun Application.isDebuggable(): Boolean = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
