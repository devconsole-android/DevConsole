/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole

import io.devconsole.network.BodyPreview
import io.devconsole.network.CaptureBodyMetadata
import io.devconsole.network.CapturedRequest
import io.devconsole.network.CapturedRequestMetadata
import io.devconsole.network.CapturedResponse
import io.devconsole.network.CapturedResponseMetadata
import io.devconsole.network.NetworkCapture
import io.devconsole.network.NetworkTimingPhases
import io.devconsole.network.NetworkTransaction
import io.devconsole.network.NetworkUrl
import io.devconsole.push.PushEvent
import io.devconsole.socket.SocketMessage
import org.json.JSONObject

private const val SUPPORTED_VERSION = 1
private const val KIND_NETWORK = "network"
private const val KIND_SOCKET_MESSAGE = "socket.message"
private const val KIND_SOCKET_LIFECYCLE = "socket.lifecycle"
private const val KIND_PUSH = "push"

/**
 * Decodes persisted timeline payloads originally encoded by [CapturePayloadCodec].
 */
internal object RetainedCaptureDecoder {
    fun network(payloadJson: String?): NetworkTransaction? =
        safeDecode(payloadJson, KIND_NETWORK, ::decodeNetworkTransaction)

    fun socketMessage(payloadJson: String?): SocketMessage? =
        safeDecode(payloadJson, KIND_SOCKET_MESSAGE, ::decodeSocketMessage)

    fun socketLifecycle(payloadJson: String?): RetainedSocketLifecycle? =
        safeDecode(payloadJson, KIND_SOCKET_LIFECYCLE, ::decodeSocketLifecycle)

    fun push(payloadJson: String?): PushEvent? = safeDecode(payloadJson, KIND_PUSH, ::decodePushEvent)
}

private inline fun <T> safeDecode(
    payloadJson: String?,
    expectedKind: String,
    decode: (JSONObject) -> T,
): T? =
    if (payloadJson.isNullOrBlank()) {
        null
    } else {
        runCatching {
            val root = JSONObject(payloadJson)
            val version = root.optInt("v", -1)
            val kind = root.optString("kind", "")
            if (version in 1..SUPPORTED_VERSION && kind == expectedKind) {
                root.optJSONObject("data")?.let(decode)
            } else {
                null
            }
        }.getOrNull()
    }

private fun decodeNetworkTransaction(data: JSONObject): NetworkTransaction {
    val id = data.getString("id")
    val startedAtEpochMs = data.getLong("startedAtEpochMs")
    val completedAtEpochMs = data.optLongOrNull("completedAtEpochMs")
    val sessionId = data.optStringOrNull("sessionId")
    val reqJson = data.getJSONObject("request")
    val resJson = data.optJSONObject("response")

    val request = decodeRequest(reqJson)
    val response = resJson?.let(::decodeResponse)
    val capture = NetworkCapture(request, response)

    return NetworkTransaction(
        id = id,
        startedAtEpochMs = startedAtEpochMs,
        completedAtEpochMs = completedAtEpochMs,
        capture = capture,
    ).withSessionId(sessionId)
}

private fun decodeRequest(json: JSONObject): CapturedRequest {
    val method = json.getString("method")
    val urlJson = json.getJSONObject("url")
    val url =
        NetworkUrl(
            scheme = urlJson.optString("scheme", ""),
            host = urlJson.optString("host", ""),
            path = urlJson.optString("path", ""),
            query = urlJson.optJSONObject("query")?.toRetainedStringMap().orEmpty(),
        )
    val headers = json.optJSONObject("headers")?.toRetainedStringMap().orEmpty()
    val (bodyPreview, bodyMeta) = decodeBody(json.optJSONObject("body"))
    val contentType = json.optStringOrNull("contentType")
    val correlationId = json.optStringOrNull("correlationId")
    val pluginId = json.optString("pluginId", "network")
    val threadName = json.optStringOrNull("threadName")
    val tags = json.optJSONObject("tags")?.toRetainedStringMap().orEmpty()
    val attachmentId = json.optStringOrNull("attachmentId")

    return CapturedRequest(
        method = method,
        url = url,
        headers = headers,
        body = bodyPreview,
        contentType = contentType,
        correlationId = correlationId,
        pluginId = pluginId,
    ).withAttachmentId(attachmentId)
        .withMetadata(
            CapturedRequestMetadata(
                threadName = threadName,
                body = bodyMeta,
                tags = tags,
            ),
        )
}

private fun decodeResponse(json: JSONObject): CapturedResponse {
    val statusCode = json.getInt("statusCode")
    val headers = json.optJSONObject("headers")?.toRetainedStringMap().orEmpty()
    val (bodyPreview, bodyMeta) = decodeBody(json.optJSONObject("body"))
    val contentType = json.optStringOrNull("contentType")
    val protocol = json.optStringOrNull("protocol")
    val error = json.optStringOrNull("error")
    val fromCache = json.optBoolean("fromCache", false)
    val exceptionClass = json.optStringOrNull("exceptionClass")
    val timings = decodeTimings(json.optJSONObject("timings"))
    val attachmentId = json.optStringOrNull("attachmentId")

    return CapturedResponse(
        statusCode = statusCode,
        headers = headers,
        body = bodyPreview,
        contentType = contentType,
        protocol = protocol,
        error = error,
    ).withAttachmentId(attachmentId)
        .withMetadata(
            CapturedResponseMetadata(
                body = bodyMeta,
                timings = timings,
                fromCache = fromCache,
                exceptionClass = exceptionClass,
            ),
        )
}

private fun decodeBody(json: JSONObject?): Pair<BodyPreview, CaptureBodyMetadata> {
    if (json == null) return BodyPreview.Absent to CaptureBodyMetadata()
    val kind = json.optString("kind", "absent")
    val truncated = json.optBoolean("truncated", false)
    val preview =
        when (kind) {
            "text" ->
                BodyPreview.Text(
                    value = json.optString("preview", ""),
                    truncated = truncated,
                )
            "binary" ->
                BodyPreview.Binary(
                    length = json.optLong("length", 0L),
                    truncated = truncated,
                )
            else -> BodyPreview.Absent
        }
    val metadata =
        CaptureBodyMetadata(
            declaredLength = json.optLongOrNull("declaredLength"),
            capturedBytes = json.optInt("capturedBytes", 0),
            truncated = json.optBoolean("metadataTruncated", false),
            omittedReason = json.optStringOrNull("omittedReason"),
        )
    return preview to metadata
}

private fun decodeTimings(json: JSONObject?): NetworkTimingPhases =
    if (json == null) {
        NetworkTimingPhases()
    } else {
        NetworkTimingPhases(
            dnsMs = json.optLongOrNull("dnsMs"),
            connectMs = json.optLongOrNull("connectMs"),
            tlsMs = json.optLongOrNull("tlsMs"),
            sendMs = json.optLongOrNull("sendMs"),
            waitMs = json.optLongOrNull("waitMs"),
            receiveMs = json.optLongOrNull("receiveMs"),
        )
    }
