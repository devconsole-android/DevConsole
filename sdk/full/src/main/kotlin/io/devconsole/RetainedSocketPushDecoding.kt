/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole

import io.devconsole.push.PushEvent
import io.devconsole.push.PushLifecycle
import io.devconsole.push.PushNotification
import io.devconsole.socket.BinaryPreviewEncoding
import io.devconsole.socket.SocketDirection
import io.devconsole.socket.SocketFrameType
import io.devconsole.socket.SocketLifecycleEvent
import io.devconsole.socket.SocketLifecycleType
import io.devconsole.socket.SocketMessage
import io.devconsole.socket.SocketMessageMetadata
import io.devconsole.socket.SocketPayload
import io.devconsole.socket.SocketProtocol
import io.devconsole.socket.SocketTextFormat
import org.json.JSONObject

// Socket and push halves of [RetainedCaptureDecoder], plus the null-tolerant JSON readers both halves share.

internal fun decodeSocketMessage(data: JSONObject): SocketMessage {
    val connectionId = data.getString("connectionId")
    val timestampEpochMs = data.getLong("timestampEpochMs")
    val direction = SocketDirection.valueOf(data.getString("direction"))
    val contentType = data.optStringOrNull("contentType")
    val frameType = data.optStringOrNull("frameType")?.let(SocketFrameType::valueOf) ?: SocketFrameType.TEXT
    val textFormat = data.optStringOrNull("textFormat")?.let(SocketTextFormat::valueOf) ?: SocketTextFormat.TEXT
    val payload = decodeSocketPayload(data.getJSONObject("payload"))

    return SocketMessage(
        connectionId = connectionId,
        direction = direction,
        timestampEpochMs = timestampEpochMs,
        payload = payload,
        contentType = contentType,
    ).withMetadata(
        SocketMessageMetadata(
            frameType = frameType,
            textFormat = textFormat,
        ),
    )
}

private fun decodeSocketPayload(payloadJson: JSONObject): SocketPayload {
    val kind = payloadJson.optString("kind")
    val truncated = payloadJson.optBoolean("truncated", false)
    return if (kind == "binary") {
        val binary =
            SocketPayload.Binary(
                length = payloadJson.optLong("length", 0L),
                truncated = truncated,
            )
        val preview = payloadJson.optStringOrNull("preview")
        val previewEncoding = payloadJson.optStringOrNull("previewEncoding")?.let(BinaryPreviewEncoding::valueOf)
        if (preview != null && previewEncoding != null) {
            binary.withPreview(preview, previewEncoding)
        } else {
            binary
        }
    } else {
        SocketPayload.Text(
            preview = payloadJson.optString("preview", ""),
            truncated = truncated,
        )
    }
}

/** A persisted socket lifecycle event plus the connection identity it was captured with. */
internal data class RetainedSocketLifecycle(
    val event: SocketLifecycleEvent,
    val url: String?,
    val protocol: SocketProtocol,
)

internal fun decodeSocketLifecycle(data: JSONObject): RetainedSocketLifecycle {
    val event =
        SocketLifecycleEvent(
            connectionId = data.getString("connectionId"),
            type = SocketLifecycleType.valueOf(data.getString("type")),
            timestampEpochMs = data.getLong("timestampEpochMs"),
            code = data.optIntOrNull("code"),
            reason = data.optStringOrNull("reason"),
            error = data.optStringOrNull("error"),
        )
    return RetainedSocketLifecycle(
        event = event,
        url = data.optStringOrNull("url"),
        protocol = SocketProtocol.fromWireName(data.optStringOrNull("protocol")),
    )
}

internal fun decodePushEvent(data: JSONObject): PushEvent {
    val provider = data.getString("provider")
    val eventData = data.optJSONObject("data")?.toRetainedStringMap().orEmpty()
    val messageId = data.optStringOrNull("messageId")
    val source = data.optStringOrNull("source")
    val sentAtEpochMs = data.optLongOrNull("sentAtEpochMs")
    val receivedAtEpochMs = data.optLong("receivedAtEpochMs", 0L)
    val rawMetadata = data.optJSONObject("rawMetadata")?.toRetainedStringMap().orEmpty()
    val lifecycle = data.optStringOrNull("lifecycle")?.let(PushLifecycle::valueOf) ?: PushLifecycle.RECEIVED
    val simulated = data.optBoolean("simulated", false)
    val notification =
        data.optJSONObject("notification")?.let { notifJson ->
            PushNotification(
                title = notifJson.optStringOrNull("title"),
                body = notifJson.optStringOrNull("body"),
                channelId = notifJson.optStringOrNull("channelId"),
                imageUrl = notifJson.optStringOrNull("imageUrl"),
            )
        }

    return PushEvent(
        provider = provider,
        data = eventData,
        messageId = messageId,
        source = source,
        sentAtEpochMs = sentAtEpochMs,
        receivedAtEpochMs = receivedAtEpochMs,
        notification = notification,
        rawMetadata = rawMetadata,
        lifecycle = lifecycle,
        simulated = simulated,
    )
}

internal fun JSONObject.optStringOrNull(name: String): String? =
    if (!has(name) || isNull(name)) null else getString(name)

internal fun JSONObject.optLongOrNull(name: String): Long? = if (!has(name) || isNull(name)) null else getLong(name)

internal fun JSONObject.optIntOrNull(name: String): Int? = if (!has(name) || isNull(name)) null else getInt(name)

internal fun JSONObject.toRetainedStringMap(): Map<String, String> =
    buildMap {
        keys().forEach { name ->
            val value = optStringOrNull(name)
            if (value != null) put(name, value)
        }
    }
