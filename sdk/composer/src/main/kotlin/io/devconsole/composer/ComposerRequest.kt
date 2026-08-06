package io.devconsole.composer

import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import java.net.URI
import java.net.URLEncoder

enum class ComposerBodyType { NONE, TEXT, JSON, FORM_URL_ENCODED, MULTIPART, BINARY_FILE }

data class ComposerQueryParameter(
    val name: String,
    val value: String,
)

data class ComposerMultipartPart(
    val name: String,
    val value: String,
    val fileName: String? = null,
    val contentType: String? = null,
)

data class ComposerBinaryBody(
    val fileName: String,
    val contentType: String,
    val bytes: ByteArray,
)

data class ComposerVariable(
    val name: String,
    val value: String,
    val secret: Boolean = false,
)

data class ComposerRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val query: List<ComposerQueryParameter> = emptyList(),
    val bodyType: ComposerBodyType = if (body == null) ComposerBodyType.NONE else ComposerBodyType.TEXT,
    val formFields: Map<String, String> = emptyMap(),
    val multipartParts: List<ComposerMultipartPart> = emptyList(),
    val binaryBody: ComposerBinaryBody? = null,
    val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    val followRedirects: Boolean = true,
    val variables: List<ComposerVariable> = emptyList(),
) {
    fun resolve(): ResolvedComposerRequest {
        require(timeoutMs > 0) { "Composer timeout must be positive" }
        val values = variables.associate { it.name to it.value }
        val resolvedQuery = query.map { ComposerQueryParameter(it.name.resolve(values), it.value.resolve(values)) }
        val resolvedForm = formFields.map { (name, value) -> name.resolve(values) to value.resolve(values) }.toMap()
        val resolvedMultipart =
            multipartParts.map { part ->
                part.copy(name = part.name.resolve(values), value = part.value.resolve(values))
            }
        val resolvedBody =
            when (bodyType) {
                ComposerBodyType.NONE -> null
                ComposerBodyType.TEXT, ComposerBodyType.JSON -> body?.resolve(values)
                ComposerBodyType.FORM_URL_ENCODED ->
                    resolvedForm.entries.joinToString(
                        "&",
                    ) { (name, value) -> "${name.urlEncode()}=${value.urlEncode()}" }
                ComposerBodyType.MULTIPART, ComposerBodyType.BINARY_FILE -> null
            }
        return ResolvedComposerRequest(
            method = method,
            url = url.resolve(values).appendQuery(resolvedQuery),
            headers = headers.mapValues { (_, value) -> value.resolve(values) },
            body = resolvedBody,
            bodyType = bodyType,
            multipartParts = resolvedMultipart,
            binaryBody = binaryBody,
            timeoutMs = timeoutMs,
            followRedirects = followRedirects,
        )
    }

    /**
     * Returns the only representation that may be persisted or sent to a browser. Binary bytes and
     * secret variable values are dropped; every remaining string crosses the active redaction
     * boundary before it is copied.
     */
    fun redacted(redaction: RedactionEngine): ComposerRequest =
        copy(
            method = redaction.redactText(method),
            url = redaction.redactText(url),
            headers = redaction.redactFields(headers),
            body = body?.let(redaction::redactText),
            query =
                query.map { parameter ->
                    val value =
                        redaction
                            .redactFields(mapOf(parameter.name to parameter.value))
                            .getValue(parameter.name)
                    parameter.copy(
                        name = redaction.redactText(parameter.name),
                        value = value,
                    )
                },
            formFields = redaction.redactFields(formFields),
            multipartParts =
                multipartParts.map { part ->
                    val value = redaction.redactFields(mapOf(part.name to part.value)).getValue(part.name)
                    part.copy(
                        name = redaction.redactText(part.name),
                        value = value,
                        fileName = part.fileName?.let(redaction::redactText),
                        contentType = part.contentType?.let(redaction::redactText),
                    )
                },
            binaryBody =
                binaryBody?.copy(
                    fileName = redaction.redactText(binaryBody.fileName),
                    contentType = redaction.redactText(binaryBody.contentType),
                    bytes = byteArrayOf(),
                ),
            variables =
                variables.map { variable ->
                    variable.copy(
                        name = redaction.redactText(variable.name),
                        value = if (variable.secret) redaction.replacement() else redaction.redactText(variable.value),
                    )
                },
        )

    /** Safe for UI/state transfer and syntactically valid for arbitrary user-controlled strings. */
    fun toRedactedMetadata(redaction: RedactionEngine): String {
        val safe = redacted(redaction)
        return safe.metadataJson()
    }

    /** Uses the default policy for source compatibility; runtime routes pass the host policy. */
    fun toRedactedMetadata(): String = toRedactedMetadata(DEFAULT_REDACTION)

    private fun metadataJson(): String =
        buildString {
            append("{\"method\":")
                .append(method.jsonQuoted())
                .append(",\"url\":")
                .append(url.jsonQuoted())
                .append(",\"bodyType\":")
                .append(bodyType.name.jsonQuoted())
                .append(",\"timeoutMs\":")
                .append(timeoutMs)
                .append(",\"followRedirects\":")
                .append(followRedirects)
                .append(",\"variables\":[")
            variables.forEachIndexed { index, variable ->
                if (index > 0) append(',')
                append("{\"name\":")
                    .append(variable.name.jsonQuoted())
                    .append(",\"secret\":")
                    .append(variable.secret)
                    .append('}')
            }
            append("]}")
        }

    private fun String.resolve(values: Map<String, String>): String =
        values.entries.fold(this) { current, (name, value) ->
            current.replace("\${$name}", value)
        }

    private fun String.appendQuery(parameters: List<ComposerQueryParameter>): String {
        if (parameters.isEmpty()) return this
        val separator = if ('?' in this) '&' else '?'
        return this + separator + parameters.joinToString("&") { "${it.name.urlEncode()}=${it.value.urlEncode()}" }
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    private fun String.jsonQuoted(): String =
        buildString(length + 2) {
            append('"')
            for (character in this@jsonQuoted) {
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else ->
                        if (character.code < CONTROL_CHARACTER_LIMIT) {
                            append("\\u").append(
                                character.code
                                    .toString(HEX_RADIX)
                                    .padStart(UNICODE_ESCAPE_WIDTH, UNICODE_ESCAPE_PADDING),
                            )
                        } else {
                            append(character)
                        }
                }
            }
            append('"')
        }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000
        const val CONTROL_CHARACTER_LIMIT = 0x20
        const val HEX_RADIX = 16
        const val UNICODE_ESCAPE_WIDTH = 4
        const val UNICODE_ESCAPE_PADDING = '0'
        val DEFAULT_REDACTION: RedactionEngine by lazy { RedactionEngine(RedactionPolicy.default()) }
    }
}

/** Ephemeral request handed to the SDK-owned transport. Do not persist or expose it to the dashboard. */
data class ResolvedComposerRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
    val bodyType: ComposerBodyType,
    val multipartParts: List<ComposerMultipartPart>,
    val binaryBody: ComposerBinaryBody?,
    val timeoutMs: Int,
    val followRedirects: Boolean,
)

data class ComposerResponse(
    val statusCode: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val durationMs: Long? = null,
)

fun interface ComposerTransport {
    fun execute(request: ResolvedComposerRequest): ComposerResponse
}

data class ComposerExecution(
    val requestMetadata: String,
    val response: ComposerResponse,
)

class ComposerExecutor(
    private val transport: ComposerTransport,
) {
    fun execute(request: ComposerRequest): ComposerExecution =
        execute(
            request = request,
            permitsDestination = { true },
        )

    fun execute(
        request: ComposerRequest,
        permitsDestination: (String) -> Boolean,
    ): ComposerExecution {
        var current = request.resolve()
        var redirectCount = 0
        while (true) {
            if (!permitsDestination(current.url)) throw ComposerDestinationRejectedException(current.url)
            val response = transport.execute(current.copy(followRedirects = false))
            val redirected = current.redirectedBy(response)
            if (redirected == null) {
                return ComposerExecution(request.toRedactedMetadata(), response)
            }
            if (redirectCount++ >= MAX_REDIRECTS) error("Composer redirect limit exceeded")
            current = redirected
        }
    }

    private fun ResolvedComposerRequest.redirectedBy(response: ComposerResponse): ResolvedComposerRequest? {
        val location =
            response.headers.entries
                .firstOrNull { it.key.equals(LOCATION_HEADER, ignoreCase = true) }
                ?.value
        if (!followRedirects || response.statusCode !in REDIRECT_STATUSES || location.isNullOrBlank()) {
            return null
        }
        val nextUrl = URI(url).resolve(location).toString()
        val nextMethod = redirectedMethod(response.statusCode)
        val dropsBody = nextMethod != method
        val crossOrigin = URI(url).origin() != URI(nextUrl).origin()
        return copy(
            method = nextMethod,
            url = nextUrl,
            headers = if (crossOrigin) headers.withoutCredentials() else headers,
            body = if (dropsBody) null else body,
            bodyType = if (dropsBody) ComposerBodyType.NONE else bodyType,
            multipartParts = if (dropsBody) emptyList() else multipartParts,
            binaryBody = if (dropsBody) null else binaryBody,
        )
    }

    private fun ResolvedComposerRequest.redirectedMethod(statusCode: Int): String =
        when {
            statusCode == STATUS_SEE_OTHER && method != HEAD_METHOD -> GET_METHOD
            statusCode in POST_TO_GET_REDIRECTS && method == POST_METHOD -> GET_METHOD
            else -> method
        }

    private fun URI.origin(): String {
        val effectivePort = port.takeIf { it >= 0 } ?: if (scheme == HTTPS_SCHEME) HTTPS_PORT else HTTP_PORT
        return "${scheme.lowercase()}://${host.lowercase()}:$effectivePort"
    }

    private fun Map<String, String>.withoutCredentials(): Map<String, String> =
        filterKeys { name -> name.lowercase() !in CREDENTIAL_HEADERS }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val STATUS_MOVED_PERMANENTLY = 301
        const val STATUS_FOUND = 302
        const val STATUS_SEE_OTHER = 303
        const val STATUS_TEMPORARY_REDIRECT = 307
        const val STATUS_PERMANENT_REDIRECT = 308
        const val HTTP_PORT = 80
        const val HTTPS_PORT = 443
        const val HTTPS_SCHEME = "https"
        const val LOCATION_HEADER = "Location"
        const val HEAD_METHOD = "HEAD"
        const val GET_METHOD = "GET"
        const val POST_METHOD = "POST"
        val POST_TO_GET_REDIRECTS = setOf(STATUS_MOVED_PERMANENTLY, STATUS_FOUND)
        val REDIRECT_STATUSES =
            setOf(
                STATUS_MOVED_PERMANENTLY,
                STATUS_FOUND,
                STATUS_SEE_OTHER,
                STATUS_TEMPORARY_REDIRECT,
                STATUS_PERMANENT_REDIRECT,
            )
        val CREDENTIAL_HEADERS = setOf("authorization", "proxy-authorization", "cookie")
    }
}

class ComposerDestinationRejectedException(
    val destination: String,
) : SecurityException("Composer destination is not permitted")
