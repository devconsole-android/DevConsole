package io.devconsole.network

import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

/**
 * Exports captured network transactions. Inputs are [NetworkCapture] values rather than raw
 * requests, so all output is constrained to the capture pipeline's redacted fields.
 *
 * One small StringBuilder-append helper per HAR/Postman/cURL fragment (including cookie parsing);
 * splitting the object further would fragment the format logic rather than simplify it.
 */
@Suppress("TooManyFunctions")
object NetworkExport {
    fun toCurl(capture: NetworkCapture): String =
        buildString {
            val request = capture.request
            append("curl -X ").append(request.method.shellQuoted())
            request.headers.entries.sortedBy { it.key.lowercase() }.forEach { (name, value) ->
                append(" -H ").append("$name: $value".shellQuoted())
            }
            if (request.contentType != null &&
                request.headers.keys.none { it.equals("Content-Type", ignoreCase = true) }
            ) {
                append(" -H ").append("Content-Type: ${request.contentType}".shellQuoted())
            }
            (request.body as? BodyPreview.Text)?.let { body ->
                append(" --data-raw ").append(body.value.shellQuoted())
            }
            append(' ').append(request.url.display.shellQuoted())
        }

    fun toHar(captures: List<NetworkCapture>): String =
        buildString {
            append(
                "{\"log\":{\"version\":\"1.2\",\"creator\":{\"name\":\"DevConsole\",\"version\":\"1\"},\"entries\":[",
            )
            captures.forEachIndexed { index, capture ->
                if (index > 0) append(',')
                appendHarEntry(capture, startedAtEpochMs = 0, durationMs = 0, timings = NetworkTimingPhases())
            }
            append("]}}")
        }

    /**
     * A minimal, valid Postman Collection v2.1 built from the same redacted capture data as
     * [toHarTransactions]: headers and bodies come straight off [NetworkCapture], which has already
     * been through [NetworkCaptureFactory]'s redaction, so nothing here needs a second redaction pass.
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
    fun toPostman(transactions: List<NetworkTransaction>): String =
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
                appendPostmanItem(transaction)
            }
            append("]}")
        }

    private fun StringBuilder.appendPostmanItem(transaction: NetworkTransaction) {
        val request = transaction.capture.request
        val response = transaction.capture.response
        append("{\"name\":").append("${request.method} ${request.url.display}".jsonQuoted()).append(',')
        append("\"request\":{")
        appendJsonProperty("method", request.method).append(',')
        append("\"header\":").appendHeaders(request.headers, keyField = "key")
        (request.body as? BodyPreview.Text)?.let { body ->
            append(",\"body\":{\"mode\":\"raw\",\"raw\":").append(body.value.jsonQuoted()).append('}')
        }
        append(",\"url\":{\"raw\":").append(request.url.display.jsonQuoted()).append("}}")
        append(",\"response\":[")
        if (response != null) {
            append("{\"name\":").append("Response".jsonQuoted()).append(",\"originalRequest\":{")
            appendJsonProperty("method", request.method).append(',')
            append("\"url\":{\"raw\":").append(request.url.display.jsonQuoted()).append("}},")
            appendJsonProperty("status", response.error.orEmpty()).append(',')
            append("\"code\":").append(response.statusCode).append(',')
            append("\"header\":").appendHeaders(response.headers, keyField = "key")
            (response.body as? BodyPreview.Text)?.let { body ->
                append(",\"body\":").append(body.value.jsonQuoted())
            }
            append('}')
        }
        append("]}")
    }

    fun toHarTransactions(transactions: List<NetworkTransaction>): String =
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
                )
            }
            append("]}}")
        }

    private fun StringBuilder.appendHarEntry(
        capture: NetworkCapture,
        startedAtEpochMs: Long,
        durationMs: Long,
        timings: NetworkTimingPhases,
    ) {
        val request = capture.request
        val response = capture.response
        append("{\"startedDateTime\":")
            .append(HAR_TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(startedAtEpochMs)).jsonQuoted())
            .append(",\"time\":")
            .append(durationMs)
            .append(",\"request\":{")
        appendJsonProperty("method", request.method).append(',')
        appendJsonProperty("url", request.url.display).append(',')
        appendJsonProperty("httpVersion", "HTTP/1.1").append(',')
        append("\"cookies\":").appendCookies(request.headers.requestCookies())
        append(",\"headers\":").appendHeaders(request.headers)
        append(",\"queryString\":").appendHeaders(request.url.query, sorted = false)
        append(",\"headersSize\":-1,\"bodySize\":-1")
        (request.body as? BodyPreview.Text)?.let { body ->
            append(",\"postData\":{")
            appendJsonProperty("mimeType", request.contentType.orEmpty()).append(',')
            appendJsonProperty("text", body.value)
            append('}')
        }
        append("},\"response\":{")
        append("\"status\":").append(response?.statusCode ?: 0).append(',')
        appendJsonProperty("statusText", response?.error.orEmpty()).append(',')
        appendJsonProperty("httpVersion", response?.protocol ?: "HTTP/1.1").append(',')
        append("\"cookies\":").appendCookies(response?.headers.orEmpty().responseCookies())
        append(",\"headers\":").appendHeaders(response?.headers.orEmpty())
        append(",\"content\":{")
        appendJsonProperty("mimeType", response?.contentType.orEmpty())
        (response?.body as? BodyPreview.Text)?.let { body ->
            append(',').appendJsonProperty("text", body.value)
        }
        append("},\"redirectURL\":\"\",\"headersSize\":-1,\"bodySize\":-1}")
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
     */
    private fun Map<String, String>.requestCookies(): List<HarCookie> =
        headerValue("Cookie")
            .orEmpty()
            .split(';')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { pair ->
                val index = pair.indexOf('=')
                if (index < 0) {
                    HarCookie(name = pair, value = "")
                } else {
                    HarCookie(pair.take(index), pair.substring(index + 1))
                }
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

    private val HAR_TIMESTAMP_FORMAT =
        DateTimeFormatterBuilder().appendInstant(HAR_TIMESTAMP_FRACTIONAL_DIGITS).toFormatter()

    private const val CONTROL_CHAR_MAX = 0x20
    private const val HAR_TIMESTAMP_FRACTIONAL_DIGITS = 3
}
