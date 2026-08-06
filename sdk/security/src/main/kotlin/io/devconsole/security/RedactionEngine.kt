package io.devconsole.security

import com.google.re2j.Matcher
import com.google.re2j.Pattern

/**
 * Field matching is case-insensitive: [sensitiveFieldNames] is lower-cased on construction, so a
 * caller-supplied policy naming `"Authorization"` behaves the same as `"authorization"`.
 */
data class RedactionPolicy(
    val sensitiveFieldNames: Set<String>,
    val replacement: String = "<redacted>",
    val textPatterns: List<Regex>,
) {
    init {
        require(sensitiveFieldNames.all { it.isNotBlank() }) { "sensitiveFieldNames must not contain blank entries" }
    }

    /** Normalized once here so every lookup can compare against a lower-cased name. */
    internal val normalizedFieldNames: Set<String> = sensitiveFieldNames.mapTo(mutableSetOf(), String::lowercase)

    companion object {
        /**
         * Memoized so every `default()` caller shares one instance. [textPatterns] holds [Regex]
         * values, which compare by reference, so a fresh policy per call would make two otherwise
         * identical configs (e.g. [io.devconsole.api.DevConsoleConfig]) unequal.
         */
        private val DEFAULT: RedactionPolicy by lazy { buildDefault() }

        fun default(): RedactionPolicy = DEFAULT

        private fun buildDefault(): RedactionPolicy =
            RedactionPolicy(
                sensitiveFieldNames =
                    setOf(
                        "authorization",
                        "proxy-authorization",
                        "www-authenticate",
                        "authentication",
                        "cookie",
                        "set-cookie",
                        "x-api-key",
                        "api-key",
                        "apikey",
                        "x-auth-token",
                        "x-access-token",
                        "x-csrf-token",
                        "x-xsrf-token",
                        "access_token",
                        "refresh_token",
                        "id_token",
                        "token",
                        "jwt",
                        "password",
                        "passcode",
                        "passphrase",
                        "secret",
                        "client_secret",
                        "private_key",
                        "session_id",
                    ),
                textPatterns = listOf(Regex("Bearer\\s+[A-Za-z0-9._~-]+", RegexOption.IGNORE_CASE)),
            )
    }
}

class RedactionEngine(
    policy: RedactionPolicy,
) {
    /**
     * Swappable so a host-supplied policy can take effect at the capture boundary after the engine
     * has been constructed: every recorder shares one engine instance, so replacing the policy here
     * updates redaction everywhere at once rather than requiring the recorders to be rebuilt.
     */
    @Volatile
    private var policy: RedactionPolicy = policy

    @Volatile
    private var textPatterns: List<Pattern> = compileTextPatterns(policy)

    @Volatile
    private var jsonSecretField: Regex = jsonSecretFieldFor(policy)

    private val formField = Regex("(^|[?&;])([^=?&;]+)=([^&;]*)")

    /** Replaces the active policy; the derived JSON field matcher is recomputed to match. */
    fun updatePolicy(policy: RedactionPolicy) {
        val compiledPatterns = compileTextPatterns(policy)
        val compiledJsonSecretField = jsonSecretFieldFor(policy)
        this.policy = policy
        textPatterns = compiledPatterns
        jsonSecretField = compiledJsonSecretField
    }

    /** The active policy's marker for a value that must never cross a redaction boundary. */
    fun replacement(): String = policy.replacement

    fun redactFields(fields: Map<String, String>): Map<String, String> =
        fields.mapValues { (name, value) ->
            if (name.lowercase() in policy.normalizedFieldNames) policy.replacement else redactText(value)
        }

    fun redactText(
        value: String,
        maxLength: Int = DEFAULT_TEXT_LIMIT,
    ): String {
        require(maxLength > 0) { "maxLength must be positive" }
        val bounded = value.take(maxLength)
        val replacement = Matcher.quoteReplacement(policy.replacement)
        val patternRedacted =
            textPatterns.fold(bounded) { redacted, pattern ->
                pattern.matcher(redacted).replaceAll(replacement)
            }
        val jsonRedacted =
            jsonSecretField.replace(patternRedacted) { match ->
                "${match.groupValues[1]}\"${policy.replacement}\""
            }
        return formField
            .replace(jsonRedacted) { match ->
                val field = match.groupValues[2]
                if (field.lowercase() in policy.normalizedFieldNames) {
                    "${match.groupValues[1]}$field=${policy.replacement}"
                } else {
                    match.value
                }
            }.take(maxLength)
    }

    companion object {
        const val DEFAULT_TEXT_LIMIT: Int = 64 * 1024

        private fun compileTextPatterns(policy: RedactionPolicy): List<Pattern> =
            policy.textPatterns.map { expression ->
                require(expression.pattern.length <= MAX_PATTERN_LENGTH) {
                    "Redaction pattern exceeds $MAX_PATTERN_LENGTH characters"
                }
                val unsupported = expression.options - SUPPORTED_OPTIONS
                require(unsupported.isEmpty()) { "Unsupported redaction regex options: $unsupported" }
                val flags =
                    buildString {
                        if (RegexOption.IGNORE_CASE in expression.options) append('i')
                        if (RegexOption.MULTILINE in expression.options) append('m')
                        if (RegexOption.DOT_MATCHES_ALL in expression.options) append('s')
                    }
                Pattern.compile(if (flags.isEmpty()) expression.pattern else "(?$flags)${expression.pattern}")
            }

        private fun jsonSecretFieldFor(policy: RedactionPolicy): Regex {
            val sensitiveNamePattern = policy.normalizedFieldNames.joinToString("|") { Regex.escape(it) }
            return Regex(
                "(\\\"(?:$sensitiveNamePattern)\\\"\\s*:\\s*)(?:\\\"(?:\\\\.|[^\\\"])*\\\"|[^,}\\s]+)",
                RegexOption.IGNORE_CASE,
            )
        }

        private const val MAX_PATTERN_LENGTH = 1_024
        private val SUPPORTED_OPTIONS =
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
    }
}
