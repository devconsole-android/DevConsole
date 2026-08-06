package io.devconsole

import io.devconsole.network.BodyPreview
import io.devconsole.network.CaptureBodyMetadata
import io.devconsole.network.CapturedResponse
import io.devconsole.network.NetworkTimingPhases
import io.devconsole.network.NetworkTransaction
import io.devconsole.push.PushEvent
import io.devconsole.socket.SocketConnection
import io.devconsole.socket.SocketLifecycleEvent
import io.devconsole.socket.SocketMessage
import io.devconsole.socket.SocketPayload

/**
 * Stable, dependency-free payload envelopes for captures mirrored to the persistent timeline.
 * Inputs are capture models, which have already passed their owning recorder's redaction boundary.
 * A malformed future model must never make a host capture fail, so callers use [runCatching].
 */
internal object CapturePayloadCodec {
    private const val VERSION = 1

    fun network(transaction: NetworkTransaction): String? =
        safe {
            envelope(
                "network",
                obj(
                    "id" to str(transaction.id),
                    "startedAtEpochMs" to num(transaction.startedAtEpochMs),
                    "completedAtEpochMs" to num(transaction.completedAtEpochMs),
                    "sessionId" to str(transaction.sessionId),
                    "request" to request(transaction),
                    "response" to transaction.capture.response?.let(::response),
                ),
            )
        }

    fun socketMessage(message: SocketMessage): String? =
        safe {
            val payload = message.payload
            envelope(
                "socket.message",
                obj(
                    "connectionId" to str(message.connectionId),
                    "timestampEpochMs" to num(message.timestampEpochMs),
                    "direction" to str(message.direction.name),
                    "contentType" to str(message.contentType),
                    "frameType" to str(message.metadata.frameType.name),
                    "textFormat" to str(message.metadata.textFormat.name),
                    "payload" to
                        when (payload) {
                            is SocketPayload.Text ->
                                obj(
                                    "kind" to str("text"),
                                    "preview" to str(payload.preview),
                                    "truncated" to bool(payload.truncated),
                                )
                            is SocketPayload.Binary ->
                                obj(
                                    "kind" to str("binary"),
                                    "length" to num(payload.length),
                                    "truncated" to bool(payload.truncated),
                                    "preview" to str(payload.preview),
                                    "previewEncoding" to str(payload.previewEncoding?.name),
                                )
                        },
                ),
            )
        }

    fun socketLifecycle(
        event: SocketLifecycleEvent,
        connection: SocketConnection?,
    ): String? =
        safe {
            envelope(
                "socket.lifecycle",
                obj(
                    "connectionId" to str(event.connectionId),
                    "timestampEpochMs" to num(event.timestampEpochMs),
                    "type" to str(event.type.name),
                    "code" to num(event.code),
                    "reason" to str(event.reason),
                    "error" to str(event.error),
                    "url" to str(connection?.url),
                    "openedAtEpochMs" to num(connection?.openedAtEpochMs),
                    "reconnectAttempt" to num(connection?.reconnectAttempt),
                ),
            )
        }

    fun push(event: PushEvent): String? =
        safe {
            envelope(
                "push",
                obj(
                    "provider" to str(event.provider),
                    "data" to map(event.data),
                    "messageId" to str(event.messageId),
                    "source" to str(event.source),
                    "sentAtEpochMs" to num(event.sentAtEpochMs),
                    "receivedAtEpochMs" to num(event.receivedAtEpochMs),
                    "rawMetadata" to map(event.rawMetadata),
                    "lifecycle" to str(event.lifecycle.name),
                    "simulated" to bool(event.simulated),
                    "notification" to
                        event.notification?.let { notification ->
                            obj(
                                "title" to str(notification.title),
                                "body" to str(notification.body),
                                "channelId" to str(notification.channelId),
                                "imageUrl" to str(notification.imageUrl),
                            )
                        },
                ),
            )
        }

    private fun request(transaction: NetworkTransaction): String {
        val request = transaction.capture.request
        return obj(
            "method" to str(request.method),
            "url" to
                obj(
                    "scheme" to str(request.url.scheme),
                    "host" to str(request.url.host),
                    "path" to str(request.url.path),
                    "query" to map(request.url.query),
                ),
            "headers" to map(request.headers),
            "body" to body(request.body, request.metadata.body),
            "contentType" to str(request.contentType),
            "correlationId" to str(request.correlationId),
            "pluginId" to str(request.pluginId),
            "threadName" to str(request.metadata.threadName),
            "tags" to map(request.metadata.tags),
            "attachmentId" to str(request.attachmentId),
        )
    }

    private fun response(response: CapturedResponse): String =
        obj(
            "statusCode" to num(response.statusCode),
            "headers" to map(response.headers),
            "body" to body(response.body, response.metadata.body),
            "contentType" to str(response.contentType),
            "protocol" to str(response.protocol),
            "error" to str(response.error),
            "fromCache" to bool(response.metadata.fromCache),
            "exceptionClass" to str(response.metadata.exceptionClass),
            "timings" to timings(response.metadata.timings),
            "attachmentId" to str(response.attachmentId),
        )

    private fun body(
        preview: BodyPreview,
        metadata: CaptureBodyMetadata,
    ): String =
        obj(
            "kind" to
                str(
                    when (preview) {
                        is BodyPreview.Text -> "text"
                        is BodyPreview.Binary -> "binary"
                        BodyPreview.Absent -> "absent"
                    },
                ),
            "preview" to str((preview as? BodyPreview.Text)?.value),
            "length" to num((preview as? BodyPreview.Binary)?.length),
            "truncated" to
                bool(
                    when (preview) {
                        is BodyPreview.Text -> preview.truncated
                        is BodyPreview.Binary -> preview.truncated
                        BodyPreview.Absent -> false
                    },
                ),
            "declaredLength" to num(metadata.declaredLength),
            "capturedBytes" to num(metadata.capturedBytes),
            "metadataTruncated" to bool(metadata.truncated),
            "omittedReason" to str(metadata.omittedReason),
        )

    private fun timings(value: NetworkTimingPhases): String =
        obj(
            "dnsMs" to num(value.dnsMs),
            "connectMs" to num(value.connectMs),
            "tlsMs" to num(value.tlsMs),
            "sendMs" to num(value.sendMs),
            "waitMs" to num(value.waitMs),
            "receiveMs" to num(value.receiveMs),
        )

    private fun envelope(
        kind: String,
        data: String,
    ) = obj("v" to num(VERSION), "kind" to str(kind), "data" to data)

    private fun map(value: Map<String, String>): String =
        value.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
            "${str(key)}:${str(item)}"
        }

    private fun obj(vararg fields: Pair<String, String?>): String =
        fields
            .filter {
                it.second != null
            }.joinToString(prefix = "{", postfix = "}") { (key, value) -> "${str(key)}:$value" }

    private fun str(value: String?): String? = value?.let { "\"${it.escapeJson()}\"" }

    private fun num(value: Number?): String? = value?.toString()

    private fun bool(value: Boolean): String = value.toString()

    private inline fun safe(block: () -> String): String? = runCatching(block).getOrNull()
}
