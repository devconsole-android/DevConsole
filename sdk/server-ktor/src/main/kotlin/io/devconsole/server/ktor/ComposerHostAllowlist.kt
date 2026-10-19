package io.devconsole.server.ktor

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

internal class ComposerHostAllowlist(
    configuredHosts: Set<String>,
    val editable: Boolean,
) {
    private val appHosts: Set<String> =
        configuredHosts.map { it.trim().lowercase() }.filter(String::isNotEmpty).toSortedSet()
    private val sessionHosts: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    /** An app that configures no hosts opts out of the allowlist, so session hosts never narrow it. */
    val permitsAnyHost: Boolean = appHosts.isEmpty()

    enum class Source { APP, SESSION }

    data class Entry(
        val host: String,
        val source: Source,
    )

    sealed interface AddResult {
        data class Added(
            val host: String,
        ) : AddResult

        data class AlreadyAllowed(
            val host: String,
        ) : AddResult

        data object Invalid : AddResult

        data object LimitReached : AddResult
    }

    sealed interface RemoveResult {
        data object Removed : RemoveResult

        data object NotFound : RemoveResult

        data object SetByApp : RemoveResult
    }

    fun hasConfiguration(
        configuredHosts: Set<String>,
        editable: Boolean,
    ): Boolean = editable == this.editable && ComposerHostAllowlist(configuredHosts, editable).appHosts == appHosts

    fun permits(host: String): Boolean {
        val name = host.lowercase()
        return name.isNotEmpty() && (permitsAnyHost || name in appHosts || name in sessionHosts)
    }

    fun entries(): List<Entry> =
        appHosts.map { Entry(it, Source.APP) } +
            sessionHosts.sorted().filterNot { it in appHosts }.map { Entry(it, Source.SESSION) }

    fun add(input: String): AddResult {
        val host = hostnameFrom(input)
        return when {
            host == null -> AddResult.Invalid
            permits(host) -> AddResult.AlreadyAllowed(host)
            sessionHosts.size >= MAX_SESSION_HOSTS -> AddResult.LimitReached
            else -> {
                sessionHosts += host
                AddResult.Added(host)
            }
        }
    }

    fun remove(input: String): RemoveResult {
        val host = input.trim().lowercase()
        return when {
            host in appHosts -> RemoveResult.SetByApp
            sessionHosts.remove(host) -> RemoveResult.Removed
            else -> RemoveResult.NotFound
        }
    }

    companion object {
        const val MAX_SESSION_HOSTS = 50
        private const val MAX_HOSTNAME_LENGTH = 253
        private val URL_SCHEME = Regex("^[a-z][a-z0-9+.-]*://")
        private val TRAILING_PORT = Regex(":\\d{1,5}$")
        private val DNS_LABEL = Regex("^[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?$")

        fun hostnameFrom(input: String): String? {
            val withoutScheme = input.trim().lowercase().replace(URL_SCHEME, "")
            val authority = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            val host = authority.substringAfterLast('@').replace(TRAILING_PORT, "")
            val isHostname =
                host.isNotEmpty() &&
                    host.length <= MAX_HOSTNAME_LENGTH &&
                    host.split('.').all { DNS_LABEL.matches(it) }
            return host.takeIf { isHostname }
        }
    }
}
