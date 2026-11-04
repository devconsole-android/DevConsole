/**
 * @author Shakib
 * @since 03/11/26
 *
 * One small to/from pair per composer type keeps the codec readable; splitting it would scatter the format.
 */
@file:Suppress("TooManyFunctions")

package io.devconsole

import io.devconsole.composer.ComposerBinaryBody
import io.devconsole.composer.ComposerBodyType
import io.devconsole.composer.ComposerCollection
import io.devconsole.composer.ComposerMultipartPart
import io.devconsole.composer.ComposerQueryParameter
import io.devconsole.composer.ComposerRequest
import io.devconsole.composer.ComposerVariable
import org.json.JSONArray
import org.json.JSONObject

// JSON codec for persisted composer collections; secret variable values are blanked on write.

private const val DEFAULT_TIMEOUT_MS = 15_000
private const val HEX_RADIX = 16
private const val NIBBLE_BITS = 4

internal fun ComposerCollection.toJson(): JSONObject =
    JSONObject()
        .put("name", name)
        .put("request", request.toJson())

private fun ComposerRequest.toJson(): JSONObject {
    val json = JSONObject()
    json.put("method", method)
    json.put("url", url)
    json.put("headers", headers.toJsonObject())
    json.put("body", body ?: JSONObject.NULL)
    json.put("bodyType", bodyType.name)
    json.put("timeoutMs", timeoutMs)
    json.put("followRedirects", followRedirects)
    json.put("query", query.map(ComposerQueryParameter::toJson).toJsonArray())
    json.put("formFields", formFields.toJsonObject())
    json.put("multipartParts", multipartParts.map(ComposerMultipartPart::toJson).toJsonArray())
    json.put("variables", variables.map(ComposerVariable::toJson).toJsonArray())
    binaryBody?.let { json.put("binaryBody", it.toJson()) }
    return json
}

private fun ComposerQueryParameter.toJson(): JSONObject =
    JSONObject()
        .put("name", name)
        .put("value", value)

private fun ComposerMultipartPart.toJson(): JSONObject {
    val json = JSONObject()
    json.put("name", name)
    json.put("value", value)
    fileName?.let { json.put("fileName", it) }
    contentType?.let { json.put("contentType", it) }
    return json
}

private fun ComposerBinaryBody.toJson(): JSONObject =
    JSONObject()
        .put("fileName", fileName)
        .put("contentType", contentType)
        .put("bytes", byteArrayToHex(bytes))

private fun ComposerVariable.toJson(): JSONObject =
    JSONObject()
        .put("name", name)
        .put("value", if (secret) "" else value)
        .put("secret", secret)

private fun Map<String, String>.toJsonObject(): JSONObject =
    JSONObject().apply {
        forEach { (key, value) -> put(key, value) }
    }

private fun <T> List<T>.toJsonArray(): JSONArray =
    JSONArray().apply {
        forEach { put(it) }
    }

internal fun JSONObject.toComposerRequest(): ComposerRequest {
    val method = getString("method")
    val url = getString("url")
    val headers = optJSONObject("headers")?.toStringMap() ?: emptyMap()
    val body = if (isNull("body")) null else optString("body", "")
    val bodyTypeName = optString("bodyType", ComposerBodyType.NONE.name)
    val bodyType = runCatching { ComposerBodyType.valueOf(bodyTypeName) }.getOrDefault(ComposerBodyType.NONE)
    val timeoutMs = optInt("timeoutMs", DEFAULT_TIMEOUT_MS)
    val followRedirects = optBoolean("followRedirects", true)
    val query = optJSONArray("query")?.toQueryList() ?: emptyList()
    val formFields = optJSONObject("formFields")?.toStringMap() ?: emptyMap()
    val multipartParts = optJSONArray("multipartParts")?.toMultipartList() ?: emptyList()
    val variables = optJSONArray("variables")?.toVariableList() ?: emptyList()
    val binaryBody = optJSONObject("binaryBody")?.toBinaryBody()
    return ComposerRequest(
        method = method,
        url = url,
        headers = headers,
        body = body,
        query = query,
        bodyType = bodyType,
        formFields = formFields,
        multipartParts = multipartParts,
        binaryBody = binaryBody,
        timeoutMs = timeoutMs,
        followRedirects = followRedirects,
        variables = variables,
    )
}

private fun JSONObject.toStringMap(): Map<String, String> =
    buildMap {
        keys().forEach { key -> put(key, getString(key)) }
    }

private fun JSONArray.toQueryList(): List<ComposerQueryParameter> =
    List(length()) { index ->
        val item = getJSONObject(index)
        ComposerQueryParameter(item.getString("name"), item.getString("value"))
    }

private fun JSONArray.toMultipartList(): List<ComposerMultipartPart> =
    List(length()) { index ->
        val item = getJSONObject(index)
        ComposerMultipartPart(
            name = item.getString("name"),
            value = item.getString("value"),
            fileName = if (item.isNull("fileName")) null else item.optString("fileName"),
            contentType = if (item.isNull("contentType")) null else item.optString("contentType"),
        )
    }

private fun JSONObject.toBinaryBody(): ComposerBinaryBody =
    ComposerBinaryBody(
        fileName = getString("fileName"),
        contentType = getString("contentType"),
        bytes = hexToByteArray(optString("bytes", "")),
    )

private fun JSONArray.toVariableList(): List<ComposerVariable> =
    List(length()) { index ->
        val item = getJSONObject(index)
        ComposerVariable(
            name = item.getString("name"),
            value = item.optString("value", ""),
            secret = item.optBoolean("secret", false),
        )
    }

private fun byteArrayToHex(bytes: ByteArray): String =
    buildString(bytes.size * 2) {
        for (byte in bytes) {
            append("%02x".format(byte))
        }
    }

private fun hexToByteArray(hex: String): ByteArray {
    val digits = hex.map { Character.digit(it, HEX_RADIX) }
    val valid = digits.size % 2 == 0 && digits.none { it == -1 }
    return if (valid) {
        ByteArray(digits.size / 2) { index ->
            ((digits[index * 2] shl NIBBLE_BITS) + digits[index * 2 + 1]).toByte()
        }
    } else {
        byteArrayOf()
    }
}
