/**
 * @author Shakib
 * @since 02/08/26
 */
package io.devconsole.server.api

data class PreferencesEntryData(
    val key: String,
    val value: String,
    val type: String,
    /** True when redaction changed the shown value, so it must never be written back verbatim. */
    val redacted: Boolean = false,
)

data class PreferencesFileData(
    val name: String,
    val entries: List<PreferencesEntryData>,
)

/**
 * Reads and edits an app's `SharedPreferences`. Reads are redacted; editing is ungated here -- every
 * caller (the Compose adapter in `sdk:full`, and the browser server routes in `sdk:server-ktor`)
 * enforces the `EditingCapabilities.preferences` opt-in before calling [put]/[remove]. Declared in
 * `sdk:server-api` -- a plain boundary module with no Android dependency -- so both the Android engine
 * implementation and the platform-independent Ktor routes can share this one contract without a
 * circular module dependency, the same way `io.devconsole.state.SessionFeatureFlags` reaches
 * `GET/POST /api/v1/flags`.
 */
interface PreferencesInspector {
    fun files(): List<PreferencesFileData>

    fun put(
        file: String,
        key: String,
        value: String,
        type: String,
    ): Boolean

    fun remove(
        file: String,
        key: String,
    ): Boolean
}
