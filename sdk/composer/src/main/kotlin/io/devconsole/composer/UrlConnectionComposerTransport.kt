package io.devconsole.composer

import java.net.HttpURLConnection
import java.net.URL

/**
 * SDK-owned transport for internal builds. It creates a fresh connection for every composer
 * execution and never reads host application cookies, interceptors, or authentication state.
 */
class UrlConnectionComposerTransport(
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
) : ComposerTransport {
    init {
        require(maxResponseBytes > 0) { "Maximum response bytes must be positive" }
    }

    override fun execute(request: ResolvedComposerRequest): ComposerResponse {
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
    }
}
