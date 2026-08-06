/**
 * @author Shakib
 * @since 02/08/26
 */
package io.devconsole.api

import java.net.URI

/**
 * A durable capture exclusion.
 *
 * A network transaction is excluded from capture when [host] matches the request host exactly
 * (case-insensitively, as hostnames are case-insensitive), [method] is absent or matches the
 * request method, and [pathPrefix] is absent or is a literal prefix of the request path. Paths
 * are compared case-sensitively because URI paths are case-sensitive.
 *
 * Exclusion happens before the transaction is redacted, stored, or exported, so an excluded
 * request never produces a captured payload at all.
 */
data class CaptureRule(
    val id: String,
    val host: String,
    val method: String? = null,
    val pathPrefix: String? = null,
    val enabled: Boolean = true,
) {
    init {
        require(id.matches(ID_PATTERN)) { "Capture rule id must match ${ID_PATTERN.pattern}" }
        require(host.isNotBlank() && host.length <= MAX_HOST_LENGTH) {
            "Capture rule host must be 1..$MAX_HOST_LENGTH characters"
        }
        require(host.none { it.isWhitespace() } && FORBIDDEN_HOST_CHARACTERS.none { it in host }) {
            "Capture rule host must be a bare hostname without scheme, port, path, or whitespace"
        }
        require(isRoundTrippableHost(host)) {
            "Capture rule host must be a hostname the request matcher can resolve from a request URL"
        }
        require(hasValidHostShape(host)) {
            "Capture rule host must consist of dot-separated labels of letters, digits, '_' or '-', " +
                "and no label may be empty or start/end with '-'"
        }
        require(method == null || method.matches(METHOD_PATTERN)) {
            "Capture rule method must match ${METHOD_PATTERN.pattern}"
        }
        require(pathPrefix == null || pathPrefix.startsWith('/')) { "Capture rule path prefix must start with '/'" }
        require(pathPrefix == null || pathPrefix.length <= MAX_PATH_PREFIX_LENGTH) {
            "Capture rule path prefix must be at most $MAX_PATH_PREFIX_LENGTH characters"
        }
    }

    /** Canonical storage form: lowercase host, uppercase method, blank optionals collapsed to null. */
    fun normalized(): CaptureRule =
        copy(
            host = host.lowercase(),
            method = method?.uppercase(),
        )

    /** True when this rule is active and excludes the given request coordinates. */
    fun matches(
        method: String,
        host: String,
        path: String,
    ): Boolean =
        enabled &&
            this.host.equals(host, ignoreCase = true) &&
            (this.method == null || this.method.equals(method, ignoreCase = true)) &&
            (pathPrefix == null || path.startsWith(pathPrefix))

    companion object {
        const val MAX_HOST_LENGTH = 253
        const val MAX_PATH_PREFIX_LENGTH = 1_024

        private val ID_PATTERN = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")
        private val METHOD_PATTERN = Regex("[A-Za-z]{1,16}")
        private val FORBIDDEN_HOST_CHARACTERS = charArrayOf('/', '\\', ':', '?', '#', '@')

        /**
         * A single dot-separated hostname label: starts and ends with a letter, digit, or
         * underscore (ASCII or Unicode, so IDN labels such as "müller" are accepted), with letters,
         * digits, underscores, or hyphens allowed in between. This rejects wildcards (`*`), bare
         * hyphen runs (`-bad-`), and punctuation (`host!name`) that [isRoundTrippableHost] alone
         * cannot catch, because the tolerant fallback parser echoes almost any non-whitespace,
         * non-delimiter string back unchanged.
         */
        private val HOST_LABEL_PATTERN = Regex("[\\p{L}\\p{N}_](?:[\\p{L}\\p{N}_-]*[\\p{L}\\p{N}_])?")

        /**
         * True when [extractRequestHost] -- the same host resolver [CaptureRuleEngine.allowsCapture]
         * uses on the hot path, including its manual scheme/authority fallback for hosts (e.g.
         * underscores, IDN/non-ASCII labels) that [java.net.URI] alone cannot parse -- recovers
         * [host] unchanged from a synthetic `http://` URL. This only proves [host] survives the
         * resolver's parsing round trip, not that it is a shape the matcher can ever equal a real
         * request host against; the tolerant fallback echoes back almost anything (`*.example.com`,
         * `host!name`, ...) unchanged, so this check must always be combined with
         * [hasValidHostShape], which is what actually constrains [host] to a real hostname.
         */
        private fun isRoundTrippableHost(host: String): Boolean =
            extractRequestHost("http://$host/")?.equals(host, ignoreCase = true) == true

        /**
         * True when every dot-separated label of [host] matches [HOST_LABEL_PATTERN]. Combined with
         * [isRoundTrippableHost], this is what keeps every saved rule enforceable: a host that
         * passes both checks is guaranteed to be a real hostname shape that [CaptureRule.matches]'s
         * exact, case-insensitive equality can actually match against a live request host, so
         * wildcards and other hosts the matcher could never equal are rejected here instead of
         * silently never firing.
         */
        private fun hasValidHostShape(host: String): Boolean {
            val labels = host.split('.')
            return labels.all { it.matches(HOST_LABEL_PATTERN) }
        }

        /**
         * Builds a rule from raw (browser or Compose form) input, trimming and collapsing empty
         * optional fields before validation so callers do not have to.
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            id: String,
            host: String,
            method: String? = null,
            pathPrefix: String? = null,
            enabled: Boolean = true,
        ): CaptureRule =
            CaptureRule(
                id = id.trim(),
                host = host.trim(),
                method = method?.trim()?.takeIf(String::isNotEmpty),
                pathPrefix = pathPrefix?.trim()?.takeIf(String::isNotEmpty),
                enabled = enabled,
            ).normalized()
    }
}

/** Durable backing store for [CaptureRule]s; implementations must survive process restart. */
interface CaptureRuleStore {
    fun load(): List<CaptureRule>

    fun save(rules: List<CaptureRule>)
}

/** Test and default implementation; deliberately not durable. */
class InMemoryCaptureRuleStore(
    initialRules: List<CaptureRule> = emptyList(),
) : CaptureRuleStore {
    private var rules = initialRules.toList()

    @Synchronized
    override fun load(): List<CaptureRule> = rules.toList()

    @Synchronized
    override fun save(rules: List<CaptureRule>) {
        this.rules = rules.toList()
    }
}

/**
 * Evaluates capture exclusions on the network capture hot path.
 *
 * Matching reads a volatile snapshot so a recorded request never blocks on CRUD or storage, while
 * every mutation writes through [bindPersistence]'s store *before* the active snapshot changes --
 * a rule the caller was told was saved is always durable.
 */
class CaptureRuleEngine(
    initialRules: List<CaptureRule> = emptyList(),
) {
    private val rules = initialRules.map(CaptureRule::normalized).toMutableList()

    @Volatile
    private var activeSnapshot: List<CaptureRule> = rules.filter(CaptureRule::enabled)

    @Volatile
    private var store: CaptureRuleStore? = null

    @Synchronized
    fun rules(): List<CaptureRule> = rules.toList()

    /**
     * Restores durable rules and makes subsequent CRUD write through [store]. Persistence is
     * unbound while restoring so replaying stored rules never rewrites them.
     */
    @Synchronized
    fun bindPersistence(store: CaptureRuleStore) {
        this.store = null
        restore(store)
        this.store = store
    }

    /** Replays stored rules into memory; rows that no longer validate are skipped, not fatal. */
    @Synchronized
    fun restore(store: CaptureRuleStore) {
        store.load().take(MAX_RULES).forEach { stored ->
            runCatching { stored.normalized() }.onSuccess(::applyUpsert)
        }
        publish()
    }

    @Synchronized
    fun upsert(rule: CaptureRule) {
        val normalized = rule.normalized()
        val candidate = rules.toMutableList().also { it.replaceOrAdd(normalized) }
        require(candidate.size <= MAX_RULES) { "At most $MAX_RULES capture rules may be configured" }
        store?.save(candidate)
        applyUpsert(normalized)
        publish()
    }

    @Synchronized
    fun remove(id: String): Boolean {
        if (rules.none { it.id == id }) return false
        store?.save(rules.filterNot { it.id == id })
        rules.removeAll { it.id == id }
        publish()
        return true
    }

    /** Enable/disable without losing the rule; returns false when [id] is unknown. */
    @Synchronized
    fun setEnabled(
        id: String,
        enabled: Boolean,
    ): Boolean {
        val index = rules.indexOfFirst { it.id == id }
        if (index < 0) return false
        val updated = rules[index].copy(enabled = enabled)
        store?.save(rules.toMutableList().also { it[index] = updated })
        rules[index] = updated
        publish()
        return true
    }

    /**
     * Hot-path gate. Every [CaptureRule.host] is validated at construction time to be recoverable
     * by [java.net.URI], but the request [url] itself is not -- callers (the Ktor client plugin,
     * manual `NetworkTransactionRecorder.record` use) may pass an unencoded URL that makes
     * [URI]'s constructor throw even though its host and path are perfectly ordinary.
     * [extractRequestHost]/[extractRequestPath] fall back to manual scheme/authority splitting in
     * that case, so a rule for a well-formed host is never defeated by an unrelated encoding
     * problem elsewhere in the URL. Returns true (capture) only when no host at all can be
     * recovered from [url].
     */
    fun allowsCapture(
        method: String,
        url: String,
    ): Boolean {
        val snapshot = activeSnapshot
        if (snapshot.isEmpty()) return true
        val host = extractRequestHost(url)
        return host == null || snapshot.none { it.matches(method, host, extractRequestPath(url)) }
    }

    private fun applyUpsert(rule: CaptureRule) {
        rules.replaceOrAdd(rule)
    }

    private fun publish() {
        activeSnapshot = rules.filter(CaptureRule::enabled)
    }

    private fun MutableList<CaptureRule>.replaceOrAdd(rule: CaptureRule) {
        val index = indexOfFirst { it.id == rule.id }
        if (index >= 0) set(index, rule) else add(rule)
    }

    companion object {
        const val MAX_RULES = 500
    }
}

/**
 * Recovers the request host even when [url] is not a strictly valid URI (an unencoded space or
 * `|` in the path/query makes [URI]'s constructor throw). Tries [URI] first and only falls back
 * to [fallbackHost]'s manual scheme/authority splitting when that fails or yields an empty host.
 */
private fun extractRequestHost(url: String): String? {
    val parsedHost = runCatching { URI(url) }.getOrNull()?.host?.takeIf(String::isNotEmpty)
    return parsedHost ?: fallbackHost(url)
}

private fun fallbackHost(url: String): String? {
    val authority =
        url
            .substringAfter("//", missingDelimiterValue = "")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfter('@')
    if (authority.isEmpty()) return null
    val host =
        if (authority.startsWith('[')) {
            authority.substringBefore(']').removePrefix("[")
        } else {
            authority.substringBefore(':')
        }
    return host.takeIf(String::isNotEmpty)
}

/** Companion to [extractRequestHost]: recovers the request path with the same tolerant fallback. */
private fun extractRequestPath(url: String): String {
    val parsedPath = runCatching { URI(url) }.getOrNull()?.path?.takeIf(String::isNotEmpty)
    return parsedPath ?: fallbackPath(url)
}

private fun fallbackPath(url: String): String {
    val afterAuthority =
        url
            .substringAfter("//", missingDelimiterValue = url)
            .substringAfter('/', missingDelimiterValue = "")
    if (afterAuthority.isEmpty()) return "/"
    return ("/" + afterAuthority).substringBefore('?').substringBefore('#').ifEmpty { "/" }
}
