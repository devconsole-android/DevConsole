/**
 * @author Md. Asaduzzaman Nur
 * @since 13/08/26
 */
package io.devconsole

import io.devconsole.remoteconfig.RemoteConfigEntry
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy

/**
 * The one redaction boundary for Remote Config values.
 *
 * It sits in `:sdk:full` rather than in `:sdk:server-ktor` on purpose: the Compose inspector reads
 * the registry in-process through [FullInspectorDataSource] and never crosses the HTTP boundary, so
 * redacting at the route would protect the browser and leave the on-device surface showing raw
 * secrets. Applying it here means the JSON serializer and the Compose renderer both receive
 * already-redacted entries and neither needs to know redaction exists.
 */
internal class RedactingRemoteConfig(
    private val redaction: RedactionEngine,
    policy: RedactionPolicy = RedactionPolicy.default(),
) {
    private val sensitiveNames: Set<String> = policy.sensitiveFieldNames.mapTo(mutableSetOf(), String::lowercase)

    private val collapsedNames: Set<String> = sensitiveNames.mapTo(mutableSetOf()) { it.collapseSeparators() }

    private val replacement: String = policy.replacement

    fun apply(entries: List<RemoteConfigEntry>): List<RemoteConfigEntry> = entries.map(::redact)

    /**
     * The key is deliberately never redacted: knowing *which* value was withheld is the point of the
     * view, and the key name is what the host typed into the Remote Config console, not a secret.
     */
    private fun redact(entry: RemoteConfigEntry): RemoteConfigEntry {
        val sensitive = isSensitive(entry.key)
        // Still run a non-sensitive key's value through the engine: it can hold a bearer token,
        // and the engine's text patterns are what catch that. providerValue is the value a surface
        // shows *behind* an active override, so it needs exactly the same treatment -- otherwise a
        // secret the provider resolved would be displayed raw the moment a developer overrides it.
        val value = if (sensitive) replacement else redaction.redactText(entry.value)
        val providerValue = entry.providerValue?.let { if (sensitive) replacement else redaction.redactText(it) }
        if (!sensitive && value == entry.value && providerValue == entry.providerValue) return entry
        return entry.copy(value = value, redacted = true, providerValue = providerValue)
    }

    /**
     * Matches separator-insensitively, which the raw policy does not.
     * [RedactionPolicy.sensitiveFieldNames] is an HTTP-header list (`api-key`, `apikey`) compared by
     * exact lowercased name, but Remote Config keys are written snake_case or camelCase. Without
     * this, `api_key` -- the likeliest name for a secret accidentally shipped through Remote Config
     * -- would not match the policy's `api-key` and would be displayed in full.
     */
    private fun isSensitive(key: String): Boolean {
        val lowered = key.lowercase()
        return lowered in sensitiveNames || lowered.collapseSeparators() in collapsedNames
    }

    private companion object {
        /**
         * `.` as well as `-`/`_`: dotted keys are an ordinary Remote Config naming style (the
         * sample's own flags use `compose_sample.show_order_history`), and without it `api.key`
         * matches neither `api-key` nor `apikey` and is shown in full.
         */
        private val SEPARATORS = Regex("[-_.]")

        fun String.collapseSeparators(): String = replace(SEPARATORS, "")
    }
}
