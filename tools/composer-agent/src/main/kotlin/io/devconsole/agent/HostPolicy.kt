package io.devconsole.agent

import java.net.InetAddress
import java.net.URI

internal class HostPolicy(
    allowedHosts: Set<String> = emptySet(),
    private val blockPrivateNetworks: Boolean = false,
) {
    private val allowedHosts = allowedHosts.map(String::lowercase).toSet()

    fun rejectionFor(url: String): String? {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase()
        return when {
            host.isNullOrBlank() -> "URL has no host"
            allowedHosts.isNotEmpty() && host !in allowedHosts ->
                "host $host is not in this agent's --allow-host list"
            blockPrivateNetworks && resolvesToPrivateAddress(host) ->
                "host $host resolves to a private network address"
            else -> null
        }
    }

    private fun resolvesToPrivateAddress(host: String): Boolean {
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return true
        return addresses.any { it.isPrivate() }
    }

    private fun InetAddress.isPrivate(): Boolean =
        listOf(isLoopbackAddress, isLinkLocalAddress, isSiteLocalAddress, isAnyLocalAddress, isUniqueLocalIpv6())
            .any { it }

    private fun InetAddress.isUniqueLocalIpv6(): Boolean {
        val first = address.firstOrNull()?.toInt()?.and(BYTE_MASK) ?: return false
        return address.size == IPV6_BYTES && (first and UNIQUE_LOCAL_MASK) == UNIQUE_LOCAL_PREFIX
    }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val IPV6_BYTES = 16
        const val UNIQUE_LOCAL_MASK = 0xFE
        const val UNIQUE_LOCAL_PREFIX = 0xFC
    }
}
