package io.devconsole.composer

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL

/**
 * SDK-owned transport for internal builds. It creates a fresh connection for every composer
 * execution and never reads host application cookies, interceptors, or authentication state.
 */
class UrlConnectionComposerTransport(
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    private val permitPrivateNetworkTargets: Boolean = false,
) : ComposerTransport {
    init {
        require(maxResponseBytes > 0) { "Maximum response bytes must be positive" }
    }

    override fun execute(request: ResolvedComposerRequest): ComposerResponse {
        rejectUnsafeDestination(request.url)
        val startedAt = System.nanoTime()
        val connection =
            (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = request.method.uppercase()
                connectTimeout = request.timeoutMs
                readTimeout = request.timeoutMs
                // Redirects are traversed by ComposerExecutor so every destination is authorized
                // before this transport performs network I/O.
                instanceFollowRedirects = false
                request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
        return try {
            connection.writeBody(request)
            val statusCode = connection.responseCode
            val body =
                (if (statusCode >= 400) connection.errorStream else connection.inputStream)
                    ?.use { input -> input.readBounded(maxResponseBytes).decodeToString() }
            ComposerResponse(
                statusCode = statusCode,
                headers =
                    connection.headerFields.filterKeys { it != null }.mapValues { (_, values) ->
                        values?.joinToString(",").orEmpty()
                    },
                body = body,
                durationMs = (System.nanoTime() - startedAt) / 1_000_000,
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Defence in depth on top of the hostname allowlist: resolve the host and fail closed if any
     * resolved address is loopback, link-local (incl. the 169.254.169.254 cloud metadata endpoint),
     * site-local, or IPv6 unique-local. This blocks the common case of an allowlisted host that
     * simply resolves to a private address. It does NOT fully close a live DNS-rebinding attack: the
     * underlying [HttpURLConnection] re-resolves the host independently at connect time, so a
     * TTL-0 resolver could still answer public here and private there. Pinning the connection to the
     * vetted address would break TLS SNI/cert validation, so it is not attempted; the residual risk
     * is bounded by the allowlist (the attacker must already own DNS for an allowlisted host).
     * [ComposerExecutor] re-invokes this transport once per redirect hop, so every hop is re-checked.
     */
    private fun rejectUnsafeDestination(url: String) {
        if (permitPrivateNetworkTargets) return
        val host = runCatching { URI(url).host }.getOrNull()
        if (host.isNullOrBlank()) throw ComposerDestinationRejectedException(url)
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull()
        if (addresses.isNullOrEmpty() || addresses.any { it.isPrivateNetworkAddress() }) {
            throw ComposerDestinationRejectedException(url)
        }
    }

    private fun InetAddress.isPrivateNetworkAddress(): Boolean {
        val flaggedPrivate =
            listOf(isLoopbackAddress, isLinkLocalAddress, isSiteLocalAddress, isAnyLocalAddress)
        return flaggedPrivate.any { it } || isUniqueLocalIpv6()
    }

    /** IPv6 unique local addresses (`fc00::/7`) are not covered by [InetAddress.isSiteLocalAddress]. */
    private fun InetAddress.isUniqueLocalIpv6(): Boolean {
        val firstByte = address.firstOrNull()?.toInt()?.and(BYTE_MASK) ?: return false
        return address.size == IPV6_ADDRESS_BYTES && (firstByte and UNIQUE_LOCAL_PREFIX_MASK) == UNIQUE_LOCAL_PREFIX
    }

    private fun HttpURLConnection.writeBody(request: ResolvedComposerRequest) {
        val bytes =
            when (request.bodyType) {
                ComposerBodyType.NONE -> return
                ComposerBodyType.TEXT ->
                    request.body.orEmpty().encodeToByteArray().also {
                        setIfAbsent(
                            "Content-Type",
                            "text/plain; charset=utf-8",
                        )
                    }
                ComposerBodyType.JSON ->
                    request.body.orEmpty().encodeToByteArray().also {
                        setIfAbsent(
                            "Content-Type",
                            "application/json; charset=utf-8",
                        )
                    }
                ComposerBodyType.FORM_URL_ENCODED ->
                    request.body.orEmpty().encodeToByteArray().also {
                        setIfAbsent("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                    }
                ComposerBodyType.BINARY_FILE -> {
                    setIfAbsent("Content-Type", request.binaryBody?.contentType ?: "application/octet-stream")
                    request.binaryBody?.bytes ?: ByteArray(0)
                }
                ComposerBodyType.MULTIPART -> multipartBytes(request)
            }
        doOutput = true
        setFixedLengthStreamingMode(bytes.size)
        outputStream.use { it.write(bytes) }
    }

    private fun HttpURLConnection.multipartBytes(request: ResolvedComposerRequest): ByteArray {
        val boundary = "DevConsole-${System.nanoTime()}"
        setIfAbsent("Content-Type", "multipart/form-data; boundary=$boundary")
        return buildString {
            request.multipartParts.forEach { part ->
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"").append(part.name).append('"')
                part.fileName?.let { append("; filename=\"").append(it).append('"') }
                append("\r\n")
                part.contentType?.let { append("Content-Type: ").append(it).append("\r\n") }
                append("\r\n").append(part.value).append("\r\n")
            }
            append("--$boundary--\r\n")
        }.encodeToByteArray()
    }

    private fun HttpURLConnection.setIfAbsent(
        name: String,
        value: String,
    ) {
        val exists = requestProperties.keys.any { it?.equals(name, ignoreCase = true) == true }
        if (!exists && getRequestProperty(name) == null) setRequestProperty(name, value)
    }

    private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (output.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES = 1_024 * 1_024

        /** Low byte of a raw IPv6 address, masked to an unsigned value. */
        const val BYTE_MASK = 0xFF
        const val IPV6_ADDRESS_BYTES = 16

        /** `fc00::/7`: match the top 7 bits of the first byte against the unique-local prefix. */
        const val UNIQUE_LOCAL_PREFIX_MASK = 0xFE
        const val UNIQUE_LOCAL_PREFIX = 0xFC
    }
}
