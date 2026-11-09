/**
 * @author Shakib
 * @since 03/11/26
 */
@file:Suppress("TooManyFunctions")

package io.devconsole.ui.compose

import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val JWT_SEGMENT_COUNT = 3
private const val MS_PER_SECOND_LONG = 1000L
private const val SECONDS_PER_MINUTE_LONG = 60L
private const val JWT_TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss 'UTC'"
private const val FORM_URLENCODED_MEDIA_TYPE = "application/x-www-form-urlencoded"
private const val BEARER_PREFIX = "Bearer "
private const val GRAPHQL_PATH_SUFFIX = "/graphql"

private val RESPONSE_JWT_FIELD_NAMES = setOf("access_token", "id_token", "token")
private val FORM_LOOKALIKE_REGEX = Regex("^[A-Za-z0-9_.~%+\\-]+=[^&\\s]*(&[A-Za-z0-9_.~%+\\-]+=[^&\\s]*)*$")
private val GRAPHQL_OPERATION_REGEX =
    Regex("^(query|mutation|subscription)\\b(?:\\s+([_A-Za-z][_0-9A-Za-z]*))?")

/** A decoded numeric timestamp claim (`exp`, `iat`, `nbf`) from a JWT payload. */
internal data class DecodedJwtTimestampClaim(
    val claim: String,
    val epochSeconds: Long,
    val humanTime: String,
    val relativeStatus: String,
    val isExpired: Boolean = false,
)

/** One decoded JWT discovered in a network transaction's request or response. */
internal data class DecodedJwt(
    val source: String,
    val headerJson: String,
    val payloadJson: String,
    val headerRoot: JsonValue.Obj,
    val payloadRoot: JsonValue.Obj,
    val timestamps: List<DecodedJwtTimestampClaim> = emptyList(),
)

/** One URL-decoded key/value pair from an `application/x-www-form-urlencoded` request body. */
internal data class DecodedFormField(
    val key: String,
    val value: String,
)

/** Decoded GraphQL operation details extracted from a network request. */
internal data class DecodedGraphQlRequest(
    val operationType: String,
    val operationName: String?,
    val query: String,
    val variablesJson: String?,
    val variablesRoot: JsonValue? = null,
)

/** Structured representations decoded from an [InspectorTransactionUi]. */
internal data class DecodedTransactionContent(
    val jwts: List<DecodedJwt> = emptyList(),
    val formFields: List<DecodedFormField>? = null,
    val graphQl: DecodedGraphQlRequest? = null,
) {
    val hasAny: Boolean
        get() = jwts.isNotEmpty() || !formFields.isNullOrEmpty() || graphQl != null
}

/**
 * True when [this] carries a redaction replacement marker (`<redacted>` or `***`) and therefore
 * must never be decoded.
 */
internal fun String.isRedactedValue(): Boolean = contains("<redacted>", ignoreCase = true) || contains("***")

/**
 * Decodes all structured payloads (JWTs, form-urlencoded fields, GraphQL operation) applicable to
 * [transaction], using [nowEpochMs] to evaluate relative JWT claim validity.
 */
internal fun decodeTransactionStructuredContent(
    transaction: InspectorTransactionUi,
    nowEpochMs: Long = System.currentTimeMillis(),
): DecodedTransactionContent {
    val requestBinary = transaction.requestBodyKind == InspectorBodyKind.BINARY
    val requestBody = transaction.requestPreview?.takeUnless { requestBinary }
    val jwts = extractTransactionJwts(transaction, nowEpochMs)
    val formFields =
        if (isFormUrlEncodedRequest(transaction.requestHeaders, requestBody)) {
            requestBody?.let(::parseFormUrlEncoded)?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
    val graphQl = detectGraphQlRequest(transaction.method, transaction.url, transaction.path, requestBody)
    return DecodedTransactionContent(
        jwts = jwts,
        formFields = formFields,
        graphQl = graphQl,
    )
}

/**
 * Extracts and decodes JWT tokens from [transaction]'s request `Authorization` header (`Bearer <jwt>`,
 * case-insensitive) and from any `access_token`, `id_token`, or `token` fields in a JSON response
 * preview.
 */
internal fun extractTransactionJwts(
    transaction: InspectorTransactionUi,
    nowEpochMs: Long = System.currentTimeMillis(),
): List<DecodedJwt> =
    buildList {
        transaction.requestHeaders.entries
            .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
            ?.value
            ?.let { authHeader -> extractBearerToken(authHeader) }
            ?.let { token -> decodeJwt(token, nowEpochMs = nowEpochMs, source = "Authorization") }
            ?.let(::add)

        val responsePreview = transaction.responsePreview
        if (!responsePreview.isNullOrBlank() && sniffBodyFormat(responsePreview) == SniffedBodyFormat.JSON) {
            val root = runCatching { MinimalJsonParser(responsePreview).parseDocument() }.getOrNull()
            if (root != null) {
                val candidates = mutableListOf<Pair<String, String>>()
                collectResponseJwtCandidates(root, candidates)
                candidates.forEach { (fieldName, rawToken) ->
                    decodeJwt(rawToken, nowEpochMs = nowEpochMs, source = "response.$fieldName")?.let(::add)
                }
            }
        }
    }

private fun extractBearerToken(headerValue: String): String? =
    headerValue
        .trim()
        .takeIf { !it.isRedactedValue() && it.startsWith(BEARER_PREFIX, ignoreCase = true) }
        ?.substring(BEARER_PREFIX.length)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.isRedactedValue() }

private fun collectResponseJwtCandidates(
    node: JsonValue,
    out: MutableList<Pair<String, String>>,
) {
    when (node) {
        is JsonValue.Obj ->
            node.entries.forEach { (key, value) ->
                if (key.lowercase(Locale.US) in RESPONSE_JWT_FIELD_NAMES && value is JsonValue.Str) {
                    out += key to value.value
                } else {
                    collectResponseJwtCandidates(value, out)
                }
            }
        is JsonValue.Arr -> node.items.forEach { item -> collectResponseJwtCandidates(item, out) }
        else -> Unit
    }
}

/**
 * Decodes a single [rawToken] as a 3-segment base64url JWT whose first two segments parse as JSON
 * objects. Returns `null` when [rawToken] is redacted, does not have 3 segments, fails base64url
 * decoding, or has non-object JSON in its header or payload.
 */
@Suppress("ReturnCount")
internal fun decodeJwt(
    rawToken: String,
    nowEpochMs: Long = System.currentTimeMillis(),
    source: String = "JWT",
): DecodedJwt? {
    val trimmed = rawToken.trim().removeSurrounding("\"")
    val token =
        if (trimmed.startsWith(BEARER_PREFIX, ignoreCase = true)) {
            trimmed.substring(BEARER_PREFIX.length).trim()
        } else {
            trimmed
        }
    if (token.isEmpty() || token.isRedactedValue()) return null
    val segments = token.split('.')
    if (segments.size != JWT_SEGMENT_COUNT || segments.any { it.isEmpty() }) return null

    val headerText = decodeBase64UrlUtf8(segments[0]) ?: return null
    val payloadText = decodeBase64UrlUtf8(segments[1]) ?: return null
    if (headerText.isRedactedValue() || payloadText.isRedactedValue()) return null

    val headerObj =
        runCatching { MinimalJsonParser(headerText).parseDocument() }.getOrNull() as? JsonValue.Obj
            ?: return null
    val payloadObj =
        runCatching { MinimalJsonParser(payloadText).parseDocument() }.getOrNull() as? JsonValue.Obj
            ?: return null

    return DecodedJwt(
        source = source,
        headerJson = headerObj.prettyPrint(),
        payloadJson = payloadObj.prettyPrint(),
        headerRoot = headerObj,
        payloadRoot = payloadObj,
        timestamps = extractJwtTimestamps(payloadObj, nowEpochMs),
    )
}

private fun decodeBase64UrlUtf8(segment: String): String? =
    runCatching {
        // Base64url drops '=' padding; the JDK decoder accepts unpadded input, and rejects a
        // length that is 1 mod 4 (never valid base64) by throwing, which runCatching maps to null.
        String(Base64.getUrlDecoder().decode(segment), Charsets.UTF_8)
    }.getOrNull()

private fun extractJwtTimestamps(
    payload: JsonValue.Obj,
    nowEpochMs: Long,
): List<DecodedJwtTimestampClaim> {
    val claimNames = listOf("exp", "iat", "nbf")
    val nowSeconds = nowEpochMs / MS_PER_SECOND_LONG
    return claimNames.mapNotNull { claim ->
        val rawNum =
            payload.entries
                .firstOrNull { (key, _) -> key == claim }
                ?.second as? JsonValue.Num
                ?: return@mapNotNull null
        val epochSeconds = rawNum.raw.toLongOrNull() ?: rawNum.raw.toDoubleOrNull()?.toLong() ?: return@mapNotNull null
        val humanTime = formatJwtUtcTimestamp(epochSeconds * MS_PER_SECOND_LONG)
        val (relativeStatus, isExpired) = formatJwtRelativeStatus(claim, epochSeconds, nowSeconds)
        DecodedJwtTimestampClaim(
            claim = claim,
            epochSeconds = epochSeconds,
            humanTime = humanTime,
            relativeStatus = relativeStatus,
            isExpired = isExpired,
        )
    }
}

private fun formatJwtUtcTimestamp(epochMs: Long): String =
    SimpleDateFormat(JWT_TIMESTAMP_PATTERN, Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(epochMs))

private fun formatJwtRelativeStatus(
    claim: String,
    claimSeconds: Long,
    nowSeconds: Long,
): Pair<String, Boolean> {
    val span = formatJwtSpan(kotlin.math.abs(claimSeconds - nowSeconds))
    val inPast = claimSeconds <= nowSeconds
    return when (claim) {
        "exp" -> if (inPast) "expired $span ago" to true else "valid for $span" to false
        "nbf" -> if (inPast) "active since $span ago" to false else "not valid yet, starts in $span" to false
        else -> if (inPast) "issued $span ago" to false else "issued $span in the future (clock skew?)" to false
    }
}

/**
 * A claim distance at the coarsest unit that still reads precisely: `45 s`, `15 min`, `2 h 5 min`,
 * `3 days`, `about 5 months`, `about 3 years` -- never `1662228 min`. Partial minutes round up so a
 * token with 30 s left never reads as `0 min`.
 */
internal fun formatJwtSpan(seconds: Long): String {
    val minutes = (seconds + SECONDS_PER_MINUTE_LONG - 1) / SECONDS_PER_MINUTE_LONG
    val hours = minutes / MINUTES_PER_HOUR
    val days = hours / HOURS_PER_DAY
    return when {
        seconds < SECONDS_PER_MINUTE_LONG -> "$seconds s"
        minutes < MINUTES_PER_HOUR -> "$minutes min"
        hours < HOURS_PER_DAY ->
            "$hours h" + (minutes % MINUTES_PER_HOUR).let { rest -> if (rest > 0) " $rest min" else "" }
        days < DAYS_PER_MONTH * 2 -> "$days day${if (days == 1L) "" else "s"}"
        days < DAYS_PER_YEAR -> "about ${days / DAYS_PER_MONTH} months"
        else -> (days / DAYS_PER_YEAR).let { years -> "about $years year${if (years == 1L) "" else "s"}" }
    }
}

private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L
private const val DAYS_PER_MONTH = 30L
private const val DAYS_PER_YEAR = 365L

/**
 * True when [headers] declare `Content-Type: application/x-www-form-urlencoded` (ignoring parameters
 * like `; charset=utf-8` and case) or when that content-type is present and [body] looks like
 * `k=v&k2=v2`.
 */
internal fun isFormUrlEncodedRequest(
    headers: Map<String, String>,
    body: String?,
): Boolean {
    if (body.isNullOrBlank() || body.trim().isRedactedValue()) return false
    val contentType =
        headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?.substringBefore(';')
            ?.trim()
    val hasFormContentType = contentType.equals(FORM_URLENCODED_MEDIA_TYPE, ignoreCase = true)
    return hasFormContentType || (contentType == null && FORM_LOOKALIKE_REGEX.matches(body.trim()))
}

/**
 * Parses an `application/x-www-form-urlencoded` payload into ordered key/value pairs, preserving
 * repeated keys and empty values while URL-decoding keys and values using UTF-8.
 */
internal fun parseFormUrlEncoded(body: String): List<DecodedFormField> {
    val trimmed = body.trim()
    if (trimmed.isEmpty() || trimmed.isRedactedValue()) return emptyList()
    return trimmed
        .split('&')
        .filter { it.isNotEmpty() }
        .map { part ->
            val eqIndex = part.indexOf('=')
            val rawKey = if (eqIndex >= 0) part.substring(0, eqIndex) else part
            val rawValue = if (eqIndex >= 0) part.substring(eqIndex + 1) else ""
            DecodedFormField(
                key = urlDecodeFormComponent(rawKey),
                value = urlDecodeFormComponent(rawValue),
            )
        }
}

private fun urlDecodeFormComponent(value: String): String =
    runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)

/**
 * Detects and parses a GraphQL request when the request is `POST` with a JSON body containing a
 * string `"query"` field (and optional `"operationName"` / `"variables"`), or when the URL path
 * ends with `/graphql`.
 */
@Suppress("ReturnCount", "CyclomaticComplexMethod")
internal fun detectGraphQlRequest(
    method: String,
    url: String,
    path: String,
    body: String?,
): DecodedGraphQlRequest? {
    val rawBody = body?.trim().orEmpty()
    if (rawBody.isEmpty() || rawBody.isRedactedValue()) return null

    val isPost = method.equals("POST", ignoreCase = true)
    val isGraphQlPath = hasGraphQlPath(url, path)
    if (!isPost && !isGraphQlPath) return null

    if (sniffBodyFormat(rawBody) == SniffedBodyFormat.JSON) {
        val obj =
            runCatching { MinimalJsonParser(rawBody).parseDocument() }.getOrNull() as? JsonValue.Obj
                ?: return null
        val queryValue =
            (obj.entries.firstOrNull { (k, _) -> k == "query" }?.second as? JsonValue.Str)
                ?.value
                ?.takeIf { it.isNotBlank() && !it.isRedactedValue() }
                ?: return null
        val explicitOperationName =
            (obj.entries.firstOrNull { (k, _) -> k == "operationName" }?.second as? JsonValue.Str)
                ?.value
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !it.isRedactedValue() }
        val variablesNode =
            obj.entries
                .firstOrNull { (k, _) -> k == "variables" }
                ?.second
                ?.takeUnless { it is JsonValue.Null }
        val variablesJson =
            when (variablesNode) {
                null -> null
                is JsonValue.Str ->
                    runCatching { MinimalJsonParser(variablesNode.value).parseDocument().prettyPrint() }
                        .getOrDefault(variablesNode.value)
                else -> variablesNode.prettyPrint()
            }
        val (parsedType, parsedName) = parseGraphQlOperationHeader(queryValue)
        return DecodedGraphQlRequest(
            operationType = parsedType,
            operationName = explicitOperationName ?: parsedName,
            query = queryValue,
            variablesJson = variablesJson,
            variablesRoot = variablesNode,
        )
    }

    if (isGraphQlPath) {
        val (parsedType, parsedName) = parseGraphQlOperationHeader(rawBody)
        return DecodedGraphQlRequest(
            operationType = parsedType,
            operationName = parsedName,
            query = rawBody,
            variablesJson = null,
        )
    }

    return null
}

private fun hasGraphQlPath(
    url: String,
    path: String,
): Boolean {
    val cleanPath = path.substringBefore('?').trimEnd('/')
    if (cleanPath.endsWith(GRAPHQL_PATH_SUFFIX, ignoreCase = true)) return true
    val cleanUrlPath = url.substringBefore('?').trimEnd('/')
    return cleanUrlPath.endsWith(GRAPHQL_PATH_SUFFIX, ignoreCase = true)
}

/**
 * Extracts `(operationType, operationName)` from a GraphQL document string. Shorthand selection
 * sets (`{ viewer { id } }`) report `"query"` with a `null` operation name.
 */
internal fun parseGraphQlOperationHeader(query: String): Pair<String, String?> {
    val stripped =
        query
            .lineSequence()
            .map { line -> line.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .trim()
    val match = GRAPHQL_OPERATION_REGEX.find(stripped)
    return if (match != null) {
        val type = match.groupValues[1].lowercase(Locale.US)
        val name = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
        type to name
    } else {
        "query" to null
    }
}
