package io.devconsole.network

import io.devconsole.security.RedactionEngine
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

/**
 * Exports captured network transactions. Inputs are [NetworkCapture] values rather than raw
 * requests, so all output is constrained to the capture pipeline's redacted fields.
 *
 * Capture-time redaction runs against the policy in effect when the request/response was
 * recorded. Since a policy can change afterwards (e.g. a host adds a new sensitive field name),
 * every export function also accepts an optional [RedactionEngine] to re-run redaction against
 * the *current* policy at export time; omitting it preserves the previous capture-only behavior.
 *
 * One small StringBuilder-append helper per HAR/Postman/cURL fragment (including cookie parsing);
 * splitting the object further would fragment the format logic rather than simplify it.
 */
@Suppress("TooManyFunctions")
object NetworkExport {
    @JvmOverloads
    fun toCurl(
        capture: NetworkCapture,
        redaction: RedactionEngine? = null,
    ): String =
        buildString {
            val request = capture.request
            val headers = request.headers.redactedWith(redaction)
            val body = request.body.redactedWith(redaction)
            append("curl -X ").append(request.method.shellQuoted())
            headers.entries.sortedBy { it.key.lowercase() }.forEach { (name, value) ->
                append(" -H ").append("$name: $value".shellQuoted())
            }
            if (request.contentType != null &&
                headers.keys.none { it.equals("Content-Type", ignoreCase = true) }
            ) {
                append(" -H ").append("Content-Type: ${request.contentType}".shellQuoted())
            }
            (body as? BodyPreview.Text)?.let { text ->
                append(" --data-raw ").append(text.value.shellQuoted())
            }
            append(' ').append(request.url.display.shellQuoted())
        }

    /**
     * Internal: emits epoch-0 timestamps and zero durations because [NetworkCapture] alone carries
     * neither, and has no production caller -- [toHarTransactions] is the real HAR export path since
     * it exports from [NetworkTransaction], which does carry real timing. Kept for capture-level HAR
     * shape/redaction coverage in tests.
     */
    internal fun toHar(
        captures: List<NetworkCapture>,
        redaction: RedactionEngine? = null,
    ): String =
        buildString {
            append(
                "{\"log\":{\"version\":\"1.2\",\"creator\":{\"name\":\"DevConsole\",\"version\":\"1\"},\"entries\":[",
            )
            captures.forEachIndexed { index, capture ->
                if (index > 0) append(',')
                appendHarEntry(
                    capture,
                    startedAtEpochMs = 0,
                    durationMs = 0,
                    timings = NetworkTimingPhases(),
                    redaction = redaction,
                )
            }
            append("]}}")
        }

    /**
     * A minimal, valid Postman Collection v2.1 built from the same redacted capture data as
     * [toHarTransactions]: headers and bodies come straight off [NetworkCapture], which has already
     * been through [NetworkCaptureFactory]'s redaction; [redaction] additionally re-applies the
     * current policy at export time.
     *
     * Requests identical in method, URL, headers, and request body **and** response status code and
     * error presence (e.g. a polling endpoint hit a dozen times, all 200s) collapse to one
     * representative item -- the newest one, by [NetworkTransaction.startedAtEpochMs] -- so a
     * collection with one entry per request/response shape reads far better than one with every
     * duplicate poll. The response *status* participates in the key so a 200 run and a 401 for the
     * same request never collapse together; the response *body* deliberately does not, since bodies
     * routinely carry timestamps or request-scoped ids that would otherwise defeat dedup entirely.
     * One consequence: an explicit `?id=` selection can still collapse rows the caller asked for by
     * id, if they happen to share a dedup key -- selecting specific ids narrows which candidates are
     * considered, not whether the dedup rule applies to them.
     */
    @JvmOverloads
    fun toPostman(
        transactions: List<NetworkTransaction>,
        redaction: RedactionEngine? = null,
    ): String =
        buildString {
            append("{\"info\":{\"name\":\"DevConsole Export\",\"schema\":")
            append("\"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"},\"item\":[")
            val deduplicated =
                transactions
                    .sortedByDescending { it.startedAtEpochMs }
                    .distinctBy { transaction ->
                        val request = transaction.capture.request
                        val response = transaction.capture.response
                        val headerKey =
                            request.headers.entries.sortedBy { it.key.lowercase() }.joinToString(";") { (name, value) ->
                                "${name.lowercase()}=$value"
                            }
                        val bodyKey = (request.body as? BodyPreview.Text)?.value.orEmpty()
                        val responseKey = "${response?.statusCode ?: -1}:${response?.error != null}"
                        "${request.method.uppercase()} ${request.url.display} $headerKey $bodyKey $responseKey"
                    }
            deduplicated.forEachIndexed { index, transaction ->
                if (index > 0) append(',')
                appendPostmanItem(transaction, redaction)
            }
            append("]}")
        }

    private fun StringBuilder.appendPostmanItem(
        transaction: NetworkTransaction,
        redaction: RedactionEngine?,
    ) {
        val request = transaction.capture.request
        val response = transaction.capture.response
        val requestHeaders = request.headers.redactedWith(redaction)
        val requestBody = request.body.redactedWith(redaction)
        append("{\"name\":").append("${request.method} ${request.url.display}".jsonQuoted()).append(',')
        append("\"request\":{")
        appendJsonProperty("method", request.method).append(',')
        append("\"header\":").appendHeaders(requestHeaders, keyField = "key")
        (requestBody as? BodyPreview.Text)?.let { body ->
            append(",\"body\":{\"mode\":\"raw\",\"raw\":").append(body.value.jsonQuoted()).append('}')
        }
        append(",\"url\":{\"raw\":").append(request.url.display.jsonQuoted()).append("}}")
        append(",\"response\":[")
        if (response != null) {
            val responseHeaders = response.headers.redactedWith(redaction)
            val responseBody = response.body.redactedWith(redaction)
            append("{\"name\":").append("Response".jsonQuoted()).append(",\"originalRequest\":{")
            appendJsonProperty("method", request.method).append(',')
            append("\"url\":{\"raw\":").append(request.url.display.jsonQuoted()).append("}},")
            // Postman's "status" is the human-readable reason phrase (e.g. "OK", "Not Found"), not
            // the captured transport/error message -- those are unrelated pieces of information.
            appendJsonProperty("status", reasonPhraseFor(response.statusCode)).append(',')
            append("\"code\":").append(response.statusCode).append(',')
            append("\"header\":").appendHeaders(responseHeaders, keyField = "key")
            (responseBody as? BodyPreview.Text)?.let { body ->
                append(",\"body\":").append(body.value.jsonQuoted())
            }
            append('}')
        }
        append("]}")
    }

    @JvmOverloads
    fun toHarTransactions(
        transactions: List<NetworkTransaction>,
        redaction: RedactionEngine? = null,
    ): String =
        buildString {
            append(
                "{\"log\":{\"version\":\"1.2\",\"creator\":{\"name\":\"DevConsole\",\"version\":\"1\"},\"entries\":[",
            )
            transactions.forEachIndexed { index, transaction ->
                if (index > 0) append(',')
                appendHarEntry(
                    capture = transaction.capture,
                    startedAtEpochMs = transaction.startedAtEpochMs,
                    durationMs = transaction.durationMs ?: 0,
                    timings =
                        transaction.capture.response
                            ?.metadata
                            ?.timings ?: NetworkTimingPhases(),
                    redaction = redaction,
                )
            }
            append("]}}")
        }

    private fun StringBuilder.appendHarEntry(
        capture: NetworkCapture,
        startedAtEpochMs: Long,
        durationMs: Long,
        timings: NetworkTimingPhases,
        redaction: RedactionEngine?,
    ) {
        val request = capture.request
        val response = capture.response
        val requestHeaders = request.headers.redactedWith(redaction)
        val requestBody = request.body.redactedWith(redaction)
        val responseHeaders = response?.headers.orEmpty().redactedWith(redaction)
        val responseBody = (response?.body ?: BodyPreview.Absent).redactedWith(redaction)
        append("{\"startedDateTime\":")
            .append(HAR_TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(startedAtEpochMs)).jsonQuoted())
            .append(",\"time\":")
            .append(durationMs)
            .append(",\"request\":{")
        appendJsonProperty("method", request.method).append(',')
        appendJsonProperty("url", request.url.display).append(',')
        appendJsonProperty("httpVersion", "HTTP/1.1").append(',')
        append("\"cookies\":").appendCookies(requestHeaders.requestCookies())
        append(",\"headers\":").appendHeaders(requestHeaders)
        append(",\"queryString\":").appendHeaders(request.url.query, sorted = false)
        append(",\"headersSize\":-1,\"bodySize\":-1")
        (requestBody as? BodyPreview.Text)?.let { body ->
            append(",\"postData\":{")
            appendJsonProperty("mimeType", request.contentType.orEmpty()).append(',')
            appendJsonProperty("text", body.value)
            append('}')
        }
        append("},\"response\":{")
        append("\"status\":").append(response?.statusCode ?: 0).append(',')
        // HAR's "statusText" is the HTTP reason phrase (e.g. "OK", "Not Found"), not the captured
        // transport/error message -- a failed request has no reason phrase, so it falls back to "".
        appendJsonProperty("statusText", reasonPhraseFor(response?.statusCode)).append(',')
        appendJsonProperty("httpVersion", response?.protocol ?: "HTTP/1.1").append(',')
        append("\"cookies\":").appendCookies(responseHeaders.responseCookies())
        append(",\"headers\":").appendHeaders(responseHeaders)
        append(",\"content\":{")
        appendJsonProperty("mimeType", response?.contentType.orEmpty()).append(',')
        append("\"size\":").append(responseBody.byteSize(response?.metadata?.body?.declaredLength))
        (responseBody as? BodyPreview.Text)?.let { body ->
            append(',').appendJsonProperty("text", body.value)
        }
        append("},\"redirectURL\":").append(response.redirectUrl(responseHeaders).jsonQuoted())
        append(",\"headersSize\":-1,\"bodySize\":-1}")
        append(",\"cache\":{},\"timings\":{")
        append("\"blocked\":-1")
        append(",\"dns\":").append(timings.dnsMs ?: -1)
        append(",\"connect\":").append(timings.connectMs ?: -1)
        append(",\"ssl\":").append(timings.tlsMs ?: -1)
        append(",\"send\":").append(timings.sendMs ?: -1)
        append(",\"wait\":").append(timings.waitMs ?: -1)
        append(",\"receive\":").append(timings.receiveMs ?: -1)
        append("}}")
    }

    /** Best-known response-body byte size: the host-declared length, else the captured length. */
    private fun BodyPreview?.byteSize(declaredLength: Long?): Long =
        declaredLength ?: when (this) {
            is BodyPreview.Text -> value.byteSize().toLong()
            is BodyPreview.Binary -> length
            is BodyPreview.Absent, null -> 0L
        }

    /** A 3xx response's redirect target, straight off the (already redacted) `Location` header. */
    private fun CapturedResponse?.redirectUrl(headers: Map<String, String>): String {
        val statusCode = this?.statusCode ?: return ""
        return if (statusCode in HTTP_REDIRECT_RANGE) headers.headerValue("Location").orEmpty() else ""
    }

    private fun StringBuilder.appendCookies(cookies: List<HarCookie>): StringBuilder =
        append('[')
            .also {
                cookies.forEachIndexed { index, cookie ->
                    if (index > 0) append(',')
                    append('{')
                    appendJsonProperty("name", cookie.name).append(',')
                    appendJsonProperty("value", cookie.value)
                    cookie.path?.let { append(',').appendJsonProperty("path", it) }
                    cookie.domain?.let { append(',').appendJsonProperty("domain", it) }
                    cookie.expires?.let { append(',').appendJsonProperty("expires", it) }
                    append('}')
                }
            }.append(']')

    private data class HarCookie(
        val name: String,
        val value: String,
        val path: String? = null,
        val domain: String? = null,
        val expires: String? = null,
    )

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /**
     * A `Cookie` request header is just `name=value` pairs separated by `;` -- no per-cookie
     * attributes travel with a request, only with the `Set-Cookie` response that created them.
     * A fully-redacted header (the whole value replaced by the policy's marker, since `cookie` is a
     * sensitive field name) has no `=` in it at all -- that pair is skipped rather than fabricating a
     * `HarCookie` whose "name" is the redaction marker itself.
     */
    private fun Map<String, String>.requestCookies(): List<HarCookie> =
        headerValue("Cookie")
            .orEmpty()
            .split(';')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull { pair ->
                val index = pair.indexOf('=')
                if (index < 0) null else HarCookie(pair.take(index), pair.substring(index + 1))
            }

    /**
     * Every `Set-Cookie` response header the redaction/capture pipeline saw is already folded into
     * one comma-joined value (see `Headers.fold` in the OkHttp/Ktor adapters), which is lossy for an
     * `Expires=<day-name>, <date>` attribute since that itself contains a comma. [SET_COOKIE_SPLIT_REGEX]
     * only splits at a comma that is immediately followed by another `name=value` pair -- never one
     * embedded inside an attribute value -- so a folded `Expires` attribute survives intact.
     */
    private fun Map<String, String>.responseCookies(): List<HarCookie> =
        headerValue("Set-Cookie")
            .orEmpty()
            .split(SET_COOKIE_SPLIT_REGEX)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull { it.toHarCookie() }

    private fun String.toHarCookie(): HarCookie? {
        val parts = split(';').map(String::trim).filter(String::isNotEmpty)
        val nameValue = parts.firstOrNull()
        val index = nameValue?.indexOf('=') ?: -1
        if (nameValue == null || index < 0) return null
        var path: String? = null
        var domain: String? = null
        var expires: String? = null
        parts.drop(1).forEach { attribute ->
            val attrIndex = attribute.indexOf('=')
            if (attrIndex < 0) return@forEach
            val attrName = attribute.take(attrIndex).trim()
            val attrValue = attribute.substring(attrIndex + 1).trim()
            when {
                attrName.equals("Path", ignoreCase = true) -> path = attrValue
                attrName.equals("Domain", ignoreCase = true) -> domain = attrValue
                attrName.equals("Expires", ignoreCase = true) -> expires = attrValue
            }
        }
        return HarCookie(nameValue.take(index), nameValue.substring(index + 1), path, domain, expires)
    }

    private val SET_COOKIE_SPLIT_REGEX = Regex(""",(?=\s*[^;=,\s]+=)""")

    /** [keyField] is `"name"` for HAR-shaped header arrays and `"key"` for Postman-shaped ones. */
    private fun StringBuilder.appendHeaders(
        headers: Map<String, String>,
        keyField: String = "name",
        sorted: Boolean = true,
    ): StringBuilder =
        append('[')
            .also {
                val entries =
                    if (sorted) {
                        headers.entries.sortedBy { entry -> entry.key.lowercase() }
                    } else {
                        headers.entries.toList()
                    }
                entries.forEachIndexed { index, (name, value) ->
                    if (index > 0) append(',')
                    append('{')
                        .appendJsonProperty(keyField, name)
                        .append(',')
                        .appendJsonProperty("value", value)
                        .append('}')
                }
            }.append(']')

    private fun StringBuilder.appendJsonProperty(
        name: String,
        value: String,
    ): StringBuilder = append(name.jsonQuoted()).append(':').append(value.jsonQuoted())

    private fun String.jsonQuoted(): String =
        buildString {
            append('"')
            this@jsonQuoted.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else ->
                        if (character.code < CONTROL_CHAR_MAX) {
                            append("\\u${character.code.toString(16).padStart(4, '0')}")
                        } else {
                            append(character)
                        }
                }
            }
            append('"')
        }

    // Standard POSIX single-quote escaping: close the quote, emit an escaped literal quote, reopen
    // the quote -- e.g. `it's` becomes `'it'\''s'`. The previous replacement text used an escaped
    // double-quote (`'\"'\"'`) instead of an escaped single-quote, which is not valid shell syntax
    // and could corrupt the exported command whenever a header, body, or URL contained an apostrophe.
    private fun String.shellQuoted(): String = "'${replace("'", "'\\''")}'"

    /** Re-applies [engine] (the *current* redaction policy) to already capture-time-redacted headers. */
    private fun Map<String, String>.redactedWith(engine: RedactionEngine?): Map<String, String> =
        if (engine == null) this else engine.redactFields(this)

    /** Re-applies [engine] to an already capture-time-redacted textual body; binary previews pass through untouched. */
    private fun BodyPreview.redactedWith(engine: RedactionEngine?): BodyPreview =
        if (engine == null || this !is BodyPreview.Text) this else copy(value = engine.redactText(value))

    private fun String.byteSize(): Int = encodeToByteArray().size

    /** The standard HTTP reason phrase for [statusCode], or "" for an absent/unrecognized code. */
    private fun reasonPhraseFor(statusCode: Int?): String = HTTP_REASON_PHRASES[statusCode] ?: ""

    @Suppress("MagicNumber") // HTTP status codes are self-documenting; naming each would only add noise.
    private val HTTP_REASON_PHRASES: Map<Int, String> =
        mapOf(
            100 to "Continue",
            101 to "Switching Protocols",
            200 to "OK",
            201 to "Created",
            202 to "Accepted",
            203 to "Non-Authoritative Information",
            204 to "No Content",
            205 to "Reset Content",
            206 to "Partial Content",
            300 to "Multiple Choices",
            301 to "Moved Permanently",
            302 to "Found",
            303 to "See Other",
            304 to "Not Modified",
            305 to "Use Proxy",
            307 to "Temporary Redirect",
            308 to "Permanent Redirect",
            400 to "Bad Request",
            401 to "Unauthorized",
            402 to "Payment Required",
            403 to "Forbidden",
            404 to "Not Found",
            405 to "Method Not Allowed",
            406 to "Not Acceptable",
            407 to "Proxy Authentication Required",
            408 to "Request Timeout",
            409 to "Conflict",
            410 to "Gone",
            411 to "Length Required",
            412 to "Precondition Failed",
            413 to "Payload Too Large",
            414 to "URI Too Long",
            415 to "Unsupported Media Type",
            416 to "Range Not Satisfiable",
            417 to "Expectation Failed",
            418 to "I'm a teapot",
            422 to "Unprocessable Entity",
            425 to "Too Early",
            426 to "Upgrade Required",
            428 to "Precondition Required",
            429 to "Too Many Requests",
            431 to "Request Header Fields Too Large",
            451 to "Unavailable For Legal Reasons",
            500 to "Internal Server Error",
            501 to "Not Implemented",
            502 to "Bad Gateway",
            503 to "Service Unavailable",
            504 to "Gateway Timeout",
            505 to "HTTP Version Not Supported",
            506 to "Variant Also Negotiates",
            507 to "Insufficient Storage",
            508 to "Loop Detected",
            510 to "Not Extended",
            511 to "Network Authentication Required",
        )

    private val HAR_TIMESTAMP_FORMAT =
        DateTimeFormatterBuilder().appendInstant(HAR_TIMESTAMP_FRACTIONAL_DIGITS).toFormatter()

    private const val HTTP_REDIRECT_MIN = 300
    private const val HTTP_REDIRECT_MAX = 399
    private val HTTP_REDIRECT_RANGE = HTTP_REDIRECT_MIN..HTTP_REDIRECT_MAX
    private const val CONTROL_CHAR_MAX = 0x20
    private const val HAR_TIMESTAMP_FRACTIONAL_DIGITS = 3
}
