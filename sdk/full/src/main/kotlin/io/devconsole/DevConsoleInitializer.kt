package io.devconsole

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import io.devconsole.api.DevConsoleState
import java.io.File

/**
 * Auto-initializes DevConsole on app startup in debug builds so developers do not need
 * manual boilerplate in Application.onCreate.
 */
class DevConsoleInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application
        if (app != null && shouldInitialize(app)) {
            // Zero-config startup: the runtime is ready (state/timeline/capture) without the host
            // writing any Application.onCreate boilerplate. The browser server itself is never
            // auto-started -- call DevConsole.startBrowser()/startBrowserAsync() explicitly.
            DevConsole.initializeProvisionally(app)
        }
        return true
    }

    private fun shouldInitialize(app: Application): Boolean =
        app.isDebuggable() && isMainProcess(app) && DevConsole.state().value is DevConsoleState.Uninitialized

    /**
     * Runtime backstop, independent of the Gradle variant policy: if `sdk:full` ever reaches a
     * non-debuggable build, auto-initialization must not happen anyway.
     */
    private fun Application.isDebuggable(): Boolean = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun isMainProcess(application: Application): Boolean =
        isMainProcessName(
            currentProcessName = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else readProcessName(),
            applicationProcessName = application.applicationInfo.processName,
            packageName = application.packageName,
        )

    private fun readProcessName(): String? =
        runCatching {
            File(
                "/proc/self/cmdline",
            ).readText().substringBefore('\u0000').trim().takeIf(String::isNotEmpty)
        }.getOrNull()

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

    internal companion object {
        /** Auto-init belongs to the app's main process only; `:remote`-style processes skip it. */
        fun isMainProcessName(
            currentProcessName: String?,
            applicationProcessName: String?,
            packageName: String,
        ): Boolean {
            val expected = applicationProcessName?.takeIf(String::isNotBlank) ?: packageName
            return currentProcessName == expected
        }
    }
}
