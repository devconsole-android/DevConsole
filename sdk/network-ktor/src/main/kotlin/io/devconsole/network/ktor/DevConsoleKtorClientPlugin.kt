package io.devconsole.network.ktor

import io.devconsole.network.NetworkRequestInput
import io.devconsole.network.NetworkRequestMetadata
import io.devconsole.network.NetworkResponseInput
import io.devconsole.network.NetworkResponseMetadata
import io.devconsole.network.NetworkTransactionRecorder
import io.devconsole.network.isTextualContentType
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.content.OutgoingContent
import io.ktor.util.AttributeKey

val DevConsoleKtorClientPlugin =
    createClientPlugin("DevConsoleKtorClientPlugin", ::DevConsoleKtorClientConfig) {
        val recorder = pluginConfig.recorder ?: return@createClientPlugin

        onRequest { request, _ ->
            // Never let attaching the start timestamp -- or any future work added to this hook --
            // break the host's request. Missing timing is far better than a broken call.
            runCatching { request.attributes.put(startedAtKey, System.currentTimeMillis()) }
        }

        onResponse { response ->
            // The whole capture body runs inside one non-rethrowing runCatching: unlike the OkHttp
            // interceptor, nothing here can affect a response the host has already been handed --
            // this hook only ever *observes* it -- but a plugin hook throwing is still something Ktor
            // itself is free to propagate into the caller's client.get(...), so it must never escape.
            runCatching {
                val startedAt = response.call.attributes.getOrNull(startedAtKey) ?: System.currentTimeMillis()
                val completedAt = System.currentTimeMillis()

                val request = response.call.request
                val headersMap = request.headers.entries().associate { (k, v) -> k to v.joinToString(",") }
                // The request-level Content-Type *header* is stripped from HttpRequestBuilder.headers
                // by Ktor's own default request transform once it has folded the value into the
                // OutgoingContent it builds (see DefaultTransform.kt's `context.headers.remove(...)`
                // right after wrapping a String/ByteArray body) -- so for a body-bearing request, the
                // content's own declared type is the reliable source; the header is only a fallback for
                // an OutgoingContent that doesn't declare one itself.
                val requestContentType =
                    request.content.contentType?.toString() ?: request.headers[io.ktor.http.HttpHeaders.ContentType]
                val capturedRequestBody = request.content.captureTextualBodyOrNull(requestContentType)
                val requestInput =
                    NetworkRequestInput(
                        method = request.method.value,
                        url = request.url.toString(),
                        headers = headersMap,
                        body = capturedRequestBody,
                        contentType = requestContentType,
                    ).withMetadata(
                        NetworkRequestMetadata(
                            threadName = Thread.currentThread().name,
                            bodyLength = request.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLongOrNull(),
                            bodyOmittedReason =
                                if (capturedRequestBody != null) null else "ktor-pipeline-metadata-only",
                        ),
                    )

                val responseHeadersMap = response.headers.entries().associate { (k, v) -> k to v.joinToString(",") }
                val responseInput =
                    NetworkResponseInput(
                        statusCode = response.status.value,
                        headers = responseHeadersMap,
                        contentType = response.headers[io.ktor.http.HttpHeaders.ContentType],
                        protocol = response.version.toString(),
                    ).withMetadata(
                        NetworkResponseMetadata(
                            bodyLength = response.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLongOrNull(),
                        ),
                    )

                recorder.record(
                    request = requestInput,
                    response = responseInput,
                    startedAtEpochMs = startedAt,
                    completedAtEpochMs = completedAt,
                )
            }
        }
    }

class DevConsoleKtorClientConfig {
    var recorder: NetworkTransactionRecorder? = null
}

private val startedAtKey = AttributeKey<Long>("DevConsoleStartedAt")

/**
 * Captures a bounded copy of a textual [OutgoingContent.ByteArrayContent] (Ktor's `TextContent` is a
 * subtype of this) request body. `bytes()` on this content type returns an already-materialized array
 * Ktor itself holds for the actual send, so reading it here never consumes or disturbs anything the
 * engine still needs to write. Other [OutgoingContent] variants ([OutgoingContent.ReadChannelContent],
 * [OutgoingContent.WriteChannelContent], etc.) stream from a single-use source and are left
 * metadata-only, same as before this capture existed.
 *
 * Response bodies are not captured here: at this pipeline stage (`onResponse`, before
 * `HttpResponsePipeline.Transform`) Ktor exposes no non-consuming ("peek") read of the response body
 * -- unlike OkHttp's `peekBody` -- so reading it would steal bytes the host's own
 * `response.body<T>()`/`bodyAsText()` call still needs. Response bodies stay metadata-only for the
 * Ktor integration until Ktor offers such a path.
 *
 * Deliberately not wrapped in its own `runCatching`: a misbehaving `bytes()` override propagates to
 * the single `runCatching` around the whole [onResponse] body above, same tradeoff
 * `DevConsoleOkHttpInterceptor` makes for `RequestBody.writeTo`.
 */
private fun OutgoingContent.captureTextualBodyOrNull(contentType: String?): ByteArray? {
    val byteArrayContent = this as? OutgoingContent.ByteArrayContent ?: return null
    val length = byteArrayContent.contentLength
    val eligible = isTextualContentType(contentType) && length != null && length <= MAX_KTOR_REQUEST_BODY_CAPTURE_BYTES
    return if (eligible) byteArrayContent.bytes() else null
}

/** Mirrors DevConsoleOkHttpInterceptor's request-body capture bound. */
private const val MAX_KTOR_REQUEST_BODY_CAPTURE_BYTES: Long = 256L * 1024L
