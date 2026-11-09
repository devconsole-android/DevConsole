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
import io.devconsole.socket.SocketTextFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetainedCaptureDecoderTest {
    @Test
    fun `round trips text body GET with query, headers, and timings`() {
        val request = sampleGetRequest()
        val response = sampleGetResponse()

        val original =
            NetworkTransaction(
                id = "tx-get-1",
                startedAtEpochMs = 1_000L,
                completedAtEpochMs = 1_130L,
                capture = NetworkCapture(request, response),
            ).withSessionId("session-abc")

        val encoded = CapturePayloadCodec.network(original)
        assertNotNull(encoded)

        val decoded = RetainedCaptureDecoder.network(encoded)
        assertNotNull(decoded)

        assertEquals(original.id, decoded!!.id)
        assertEquals(original.startedAtEpochMs, decoded.startedAtEpochMs)
        assertEquals(original.completedAtEpochMs, decoded.completedAtEpochMs)
        assertEquals(original.sessionId, decoded.sessionId)

        val decReq = decoded.capture.request
        assertEquals(request.method, decReq.method)
        assertEquals(request.url, decReq.url)
        assertEquals(request.headers, decReq.headers)
        assertEquals(request.body, decReq.body)
        assertEquals(request.contentType, decReq.contentType)
        assertEquals(request.correlationId, decReq.correlationId)
        assertEquals(request.pluginId, decReq.pluginId)
        assertEquals(request.attachmentId, decReq.attachmentId)
        assertEquals(request.metadata, decReq.metadata)

        val decResp = decoded.capture.response
        assertNotNull(decResp)
        assertEquals(response.statusCode, decResp!!.statusCode)
        assertEquals(response.headers, decResp.headers)
        assertEquals(response.body, decResp.body)
        assertEquals(response.contentType, decResp.contentType)
        assertEquals(response.protocol, decResp.protocol)
        assertEquals(response.error, decResp.error)
        assertEquals(response.attachmentId, decResp.attachmentId)
        assertEquals(response.metadata, decResp.metadata)
    }

    @Test
    fun `round trips binary body POST`() {
        val request =
            CapturedRequest(
                method = "POST",
                url = NetworkUrl("https", "upload.example.com", "/files", emptyMap()),
                headers = mapOf("Content-Type" to "application/octet-stream"),
                body = BodyPreview.Binary(length = 2048L, truncated = true),
                contentType = "application/octet-stream",
                correlationId = "corr-post",
                pluginId = "network",
            ).withMetadata(
                CapturedRequestMetadata(
                    body = CaptureBodyMetadata(declaredLength = 2048L, capturedBytes = 512, truncated = true),
                ),
            )

        val response =
            CapturedResponse(
                statusCode = 201,
                headers = mapOf("Content-Type" to "application/octet-stream"),
                body = BodyPreview.Binary(length = 100L, truncated = false),
                contentType = "application/octet-stream",
                protocol = "HTTP/2",
                error = null,
            ).withMetadata(
                CapturedResponseMetadata(
                    body = CaptureBodyMetadata(declaredLength = 100L, capturedBytes = 100, truncated = false),
                ),
            )

        val original =
            NetworkTransaction(
                id = "tx-post-bin",
                startedAtEpochMs = 2_000L,
                completedAtEpochMs = 2_500L,
                capture = NetworkCapture(request, response),
            ).withSessionId("session-post")

        val encoded = CapturePayloadCodec.network(original)
        val decoded = RetainedCaptureDecoder.network(encoded)
        assertNotNull(decoded)

        assertEquals(original.id, decoded!!.id)
        assertEquals(original.capture.request.body, decoded.capture.request.body)
        assertEquals(original.capture.response?.body, decoded.capture.response?.body)
        assertEquals(original.capture.request.metadata.body, decoded.capture.request.metadata.body)
        assertEquals(
            original.capture.response
                ?.metadata
                ?.body,
            decoded.capture.response
                ?.metadata
                ?.body,
        )
    }

    @Test
    fun `round trips absent body failed request with error set and no response`() {
        val request =
            CapturedRequest(
                method = "GET",
                url = NetworkUrl("http", "broken.test", "/down", emptyMap()),
                headers = emptyMap(),
                body = BodyPreview.Absent,
                contentType = null,
                correlationId = null,
                pluginId = "network",
            )

        val original =
            NetworkTransaction(
                id = "tx-failed",
                startedAtEpochMs = 3_000L,
                completedAtEpochMs = null,
                capture = NetworkCapture(request, response = null),
            )

        val encoded = CapturePayloadCodec.network(original)
        val decoded = RetainedCaptureDecoder.network(encoded)
        assertNotNull(decoded)

        assertEquals("tx-failed", decoded!!.id)
        assertNull(decoded.completedAtEpochMs)
        assertNull(decoded.capture.response)
        assertEquals(BodyPreview.Absent, decoded.capture.request.body)
    }

    @Test
    fun `round trips socket text message`() {
        val message =
            SocketMessage(
                connectionId = "conn-1",
                direction = SocketDirection.SENT,
                timestampEpochMs = 12345L,
                payload = SocketPayload.Text("hello world", truncated = false),
                contentType = "application/json",
            ).withMetadata(
                SocketMessageMetadata(
                    frameType = SocketFrameType.TEXT,
                    textFormat = SocketTextFormat.JSON,
                ),
            )

        val encoded = CapturePayloadCodec.socketMessage(message)
        val decoded = RetainedCaptureDecoder.socketMessage(encoded)
        assertNotNull(decoded)

        assertEquals(message.connectionId, decoded!!.connectionId)
        assertEquals(message.direction, decoded.direction)
        assertEquals(message.timestampEpochMs, decoded.timestampEpochMs)
        assertEquals(message.contentType, decoded.contentType)
        assertEquals(message.payload, decoded.payload)
        assertEquals(message.metadata, decoded.metadata)
    }

    @Test
    fun `round trips socket binary message with preview`() {
        val binaryPayload =
            SocketPayload
                .Binary(length = 256L, truncated = true)
                .withPreview("deadbeef", BinaryPreviewEncoding.HEX)
        val message =
            SocketMessage(
                connectionId = "conn-bin",
                direction = SocketDirection.RECEIVED,
                timestampEpochMs = 54321L,
                payload = binaryPayload,
                contentType = "application/octet-stream",
            ).withMetadata(
                SocketMessageMetadata(
                    frameType = SocketFrameType.BINARY,
                    textFormat = SocketTextFormat.TEXT,
                ),
            )

        val encoded = CapturePayloadCodec.socketMessage(message)
        val decoded = RetainedCaptureDecoder.socketMessage(encoded)
        assertNotNull(decoded)

        assertEquals(message.connectionId, decoded!!.connectionId)
        val decPayload = decoded.payload as SocketPayload.Binary
        assertEquals(binaryPayload.length, decPayload.length)
        assertEquals(binaryPayload.truncated, decPayload.truncated)
        assertEquals(binaryPayload.preview, decPayload.preview)
        assertEquals(binaryPayload.previewEncoding, decPayload.previewEncoding)
        assertEquals(message.metadata, decoded.metadata)
    }

    @Test
    fun `round trips socket lifecycle event and url`() {
        val event =
            SocketLifecycleEvent(
                connectionId = "conn-lifecycle",
                type = SocketLifecycleType.CLOSED,
                timestampEpochMs = 99999L,
                code = 1000,
                reason = "Normal closure",
                error = null,
            )
        val connectionUrl = "wss://echo.websocket.org"

        val encoded =
            CapturePayloadCodec.socketLifecycle(
                event = event,
                connection =
                    io.devconsole.socket.SocketConnection(
                        id = event.connectionId,
                        url = connectionUrl,
                        openedAtEpochMs = 90000L,
                    ),
            )

        val decoded = RetainedCaptureDecoder.socketLifecycle(encoded)
        assertNotNull(decoded)
        val (decodedEvent, decodedUrl) = decoded!!

        assertEquals(event, decodedEvent)
        assertEquals(connectionUrl, decodedUrl)
    }

    @Test
    fun `round trips push event with notification`() {
        val event =
            PushEvent(
                provider = "fcm",
                data = mapOf("k1" to "v1", "k2" to "v2"),
                messageId = "msg-123",
                source = "network",
                sentAtEpochMs = 10000L,
                receivedAtEpochMs = 10050L,
                notification =
                    PushNotification(
                        title = "Alert",
                        body = "System warning",
                        channelId = "alerts_channel",
                        imageUrl = "https://img.test/pic.png",
                    ),
                rawMetadata = mapOf("ttl" to "60"),
                lifecycle = PushLifecycle.DISPLAYED,
                simulated = false,
            )

        val encoded = CapturePayloadCodec.push(event)
        val decoded = RetainedCaptureDecoder.push(encoded)
        assertNotNull(decoded)

        assertEquals(event, decoded)
    }

    @Test
    fun `round trips push event without notification`() {
        val event =
            PushEvent(
                provider = "local",
                data = emptyMap(),
                messageId = null,
                source = null,
                sentAtEpochMs = null,
                receivedAtEpochMs = 20000L,
                notification = null,
                rawMetadata = emptyMap(),
                lifecycle = PushLifecycle.RECEIVED,
                simulated = true,
            )

        val encoded = CapturePayloadCodec.push(event)
        val decoded = RetainedCaptureDecoder.push(encoded)
        assertNotNull(decoded)

        assertEquals(event, decoded)
    }

    @Test
    fun `corrupted and invalid payloads return null without throwing`() {
        assertNull(RetainedCaptureDecoder.network(null))
        assertNull(RetainedCaptureDecoder.network(""))
        assertNull(RetainedCaptureDecoder.network("{"))
        assertNull(RetainedCaptureDecoder.network("""{"v":1,"kind":"wrong_kind","data":{}}"""))
        assertNull(RetainedCaptureDecoder.network("""{"v":2,"kind":"network","data":{}}"""))

        assertNull(RetainedCaptureDecoder.socketMessage(null))
        assertNull(RetainedCaptureDecoder.socketMessage(""))
        assertNull(RetainedCaptureDecoder.socketMessage("{"))
        assertNull(RetainedCaptureDecoder.socketMessage("""{"v":1,"kind":"push","data":{}}"""))
        assertNull(RetainedCaptureDecoder.socketMessage("""{"v":2,"kind":"socket.message","data":{}}"""))

        assertNull(RetainedCaptureDecoder.socketLifecycle(null))
        assertNull(RetainedCaptureDecoder.socketLifecycle(""))
        assertNull(RetainedCaptureDecoder.socketLifecycle("{"))
        assertNull(RetainedCaptureDecoder.socketLifecycle("""{"v":1,"kind":"network","data":{}}"""))
        assertNull(RetainedCaptureDecoder.socketLifecycle("""{"v":2,"kind":"socket.lifecycle","data":{}}"""))

        assertNull(RetainedCaptureDecoder.push(null))
        assertNull(RetainedCaptureDecoder.push(""))
        assertNull(RetainedCaptureDecoder.push("{"))
        assertNull(RetainedCaptureDecoder.push("""{"v":1,"kind":"socket.message","data":{}}"""))
        assertNull(RetainedCaptureDecoder.push("""{"v":2,"kind":"push","data":{}}"""))
    }

    private fun sampleGetRequest(): CapturedRequest =
        CapturedRequest(
            method = "GET",
            url = NetworkUrl("https", "api.example.com", "/items", mapOf("page" to "1", "sort" to "desc")),
            headers = mapOf("Authorization" to "Bearer token", "Accept" to "application/json"),
            body = BodyPreview.Text("request text", truncated = false),
            contentType = "application/json",
            correlationId = "corr-123",
            pluginId = "network",
        ).withAttachmentId("att-req-1")
            .withMetadata(
                CapturedRequestMetadata(
                    threadName = "main-thread",
                    body = CaptureBodyMetadata(declaredLength = 12L, capturedBytes = 12, truncated = false),
                    tags = mapOf("tag1" to "val1"),
                ),
            )

    private fun sampleGetResponse(): CapturedResponse =
        CapturedResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = BodyPreview.Text("response text", truncated = true),
            contentType = "application/json",
            protocol = "HTTP/1.1",
            error = null,
        ).withAttachmentId("att-resp-1")
            .withMetadata(
                CapturedResponseMetadata(
                    body =
                        CaptureBodyMetadata(
                            declaredLength = 100L,
                            capturedBytes = 13,
                            truncated = true,
                            omittedReason = "truncated-body",
                        ),
                    timings =
                        NetworkTimingPhases(
                            dnsMs = 10L,
                            connectMs = 20L,
                            tlsMs = 30L,
                            sendMs = 5L,
                            waitMs = 50L,
                            receiveMs = 15L,
                        ),
                    fromCache = false,
                    exceptionClass = null,
                ),
            )
}
