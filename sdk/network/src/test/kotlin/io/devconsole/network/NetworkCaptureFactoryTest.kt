package io.devconsole.network

import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkCaptureFactoryTest {
    private val factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default()))

    @Test
    fun `redacts headers query and textual body before producing capture`() {
        val capture =
            factory.capture(
                request =
                    NetworkRequestInput(
                        method = "POST",
                        url = "https://example.test/orders?access_token=raw-secret",
                        headers = mapOf("Authorization" to "Bearer header-secret"),
                        body = "Bearer body-secret".encodeToByteArray(),
                        contentType = "application/json",
                    ),
                response = null,
            )

        assertEquals("<redacted>", capture.request.headers.getValue("Authorization"))
        assertFalse(
            capture.request.url.display
                .contains("raw-secret"),
        )
        assertFalse((capture.request.body as BodyPreview.Text).value.contains("body-secret"))
    }

    @Test
    fun `represents binary bodies as bounded metadata only`() {
        val capture =
            factory.capture(
                NetworkRequestInput(
                    "POST",
                    "https://example.test/upload",
                    body = byteArrayOf(0, 1, 2),
                    contentType = "application/octet-stream",
                ),
                null,
            )

        assertEquals(3L, (capture.request.body as BodyPreview.Binary).length)
    }

    @Test
    fun `falls back to UTF-8 decodability when content type is absent`() {
        val capture =
            factory.capture(
                NetworkRequestInput(
                    "POST",
                    "https://example.test/orders",
                    body = "{\"mocked\":true,\"orders\":[]}".encodeToByteArray(),
                    contentType = null,
                ),
                null,
            )

        assertEquals(
            "{\"mocked\":true,\"orders\":[]}",
            (capture.request.body as BodyPreview.Text).value,
        )
    }

    @Test
    fun `keeps genuinely non-UTF-8 bytes binary when content type is absent`() {
        val capture =
            factory.capture(
                NetworkRequestInput(
                    "POST",
                    "https://example.test/upload",
                    body = byteArrayOf(-1, -2, -3),
                    contentType = null,
                ),
                null,
            )

        assertEquals(3L, (capture.request.body as BodyPreview.Binary).length)
    }

    @Test
    fun `preserves complete request and response metadata without consuming omitted bodies`() {
        val capture =
            factory.capture(
                NetworkRequestInput(
                    method = "POST",
                    url = "https://example.test/upload",
                    contentType = "application/octet-stream",
                ).withMetadata(
                    NetworkRequestMetadata(
                        threadName = "host-network-thread",
                        bodyLength = 4_096,
                        bodyOmittedReason = "one-shot",
                        tags = mapOf("client" to "primary"),
                    ),
                ),
                NetworkResponseInput(
                    statusCode = 200,
                    protocol = "h2",
                    error = "socket closed",
                ).withMetadata(
                    NetworkResponseMetadata(
                        bodyLength = 8_192,
                        bodyOmittedReason = "streaming",
                        timings = NetworkTimingPhases(dnsMs = 2, connectMs = 4, tlsMs = 7, waitMs = 11),
                        fromCache = true,
                        exceptionClass = "java.io.IOException",
                    ),
                ),
            )

        assertEquals("host-network-thread", capture.request.metadata.threadName)
        assertEquals(4_096L, capture.request.metadata.body.declaredLength)
        assertEquals("one-shot", capture.request.metadata.body.omittedReason)
        assertEquals(mapOf("client" to "primary"), capture.request.metadata.tags)
        assertEquals(
            8_192L,
            capture.response!!
                .metadata.body.declaredLength,
        )
        assertEquals(
            "streaming",
            capture.response!!
                .metadata.body.omittedReason,
        )
        assertEquals(
            7L,
            capture.response!!
                .metadata.timings.tlsMs,
        )
        assertTrue(capture.response!!.metadata.fromCache)
        assertEquals("java.io.IOException", capture.response!!.metadata.exceptionClass)
    }

    @Test
    fun `aggregate capture limit omits previews before exceeding the event budget`() {
        val constrained =
            NetworkCaptureFactory(
                RedactionEngine(RedactionPolicy.default()),
                NetworkCaptureLimits(totalCaptureBytes = 1_024),
            )

        val capture =
            constrained.capture(
                NetworkRequestInput(
                    "POST",
                    "https://example.test/upload",
                    headers = mapOf("X-Large" to "a".repeat(900)),
                    body = "b".repeat(900).encodeToByteArray(),
                    contentType = "text/plain",
                ),
                NetworkResponseInput(
                    200,
                    headers = mapOf("X-Large" to "c".repeat(900)),
                    body = "d".repeat(900).encodeToByteArray(),
                    contentType = "text/plain",
                ),
            )

        assertTrue(capture.estimatedSizeBytes() <= 1_024)
        assertTrue(
            capture.request.metadata.body.truncated ||
                capture.response!!
                    .metadata.body.truncated,
        )
    }

    @Test
    fun `redacts configured secret fields in json and form previews`() {
        val json =
            factory.capture(
                NetworkRequestInput(
                    "POST",
                    "https://example.test/",
                    body = "{\"password\":\"json-secret\",\"safe\":\"value\"}".encodeToByteArray(),
                    contentType = "application/json",
                ),
                null,
            )
        val form =
            factory.capture(
                NetworkRequestInput(
                    "POST",
                    "https://example.test/",
                    body = "password=form-secret&safe=value".encodeToByteArray(),
                    contentType = "application/x-www-form-urlencoded",
                ),
                null,
            )

        assertFalse((json.request.body as BodyPreview.Text).value.contains("json-secret"))
        assertFalse((form.request.body as BodyPreview.Text).value.contains("form-secret"))
        assertTrue((json.request.body as BodyPreview.Text).value.contains("<redacted>"))
        assertTrue((form.request.body as BodyPreview.Text).value.contains("<redacted>"))
    }

    @Test
    fun `PNG magic bytes survive attachment capture byte-for-byte instead of a lossy UTF-8 round trip`() {
        // 0x89 is not a valid UTF-8 lead byte on its own, so a decodeToString().encodeToByteArray()
        // round trip (the bug in redactedAttachmentBytes) would replace it -- and any other invalid
        // sequence -- with the U+FFFD replacement character, corrupting both the bytes and the length
        // of a genuinely binary attachment such as this PNG.
        val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val pngBody = pngSignature + ByteArray(256) { it.toByte() }
        val request = NetworkRequestInput(method = "GET", url = "https://example.test/avatar.png")
        val response = NetworkResponseInput(statusCode = 200, body = pngBody, contentType = "image/png")
        val capture = factory.capture(request, response)

        val attachments = factory.attachmentPayloads("tx-png", request, response, capture)

        val responseAttachment = attachments.single { it.role == NetworkAttachmentRole.RESPONSE }
        assertTrue(responseAttachment.bytes.contentEquals(pngBody))
    }

    @Test
    fun `oversized textual attachment bodies still get text redaction`() {
        val oversizedJson = "{\"password\":\"leak-me\",\"pad\":\"" + "a".repeat(300 * 1024) + "\"}"
        val request =
            NetworkRequestInput(
                method = "POST",
                url = "https://example.test/orders",
                body = oversizedJson.encodeToByteArray(),
                contentType = "application/json",
            )
        val capture = factory.capture(request, null)

        val attachments = factory.attachmentPayloads("tx-text", request, null, capture)

        val requestAttachment = attachments.single { it.role == NetworkAttachmentRole.REQUEST }
        assertFalse(requestAttachment.bytes.decodeToString().contains("leak-me"))
    }

    @Test
    fun `recorder fails open when capture sink throws`() {
        val recorder = NetworkRecorder(factory) { error("storage unavailable") }

        recorder.record(NetworkRequestInput("GET", "https://example.test/"), null)

        assertTrue(true)
    }
}
