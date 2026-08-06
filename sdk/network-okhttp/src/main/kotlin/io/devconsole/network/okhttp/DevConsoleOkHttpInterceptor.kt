package io.devconsole.network.okhttp

import io.devconsole.network.NetworkCaptureContext
import io.devconsole.network.NetworkRequestInput
import io.devconsole.network.NetworkRequestMetadata
import io.devconsole.network.NetworkResponseInput
import io.devconsole.network.NetworkResponseMetadata
import io.devconsole.network.NetworkTimingPhases
import io.devconsole.network.NetworkTransactionRecorder
import io.devconsole.network.isTextualContentType
import okhttp3.Call
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer

/**
 * OkHttp application interceptor that only ever captures a bounded copy of request/response bodies
 * and never lets a capture failure change what the host call sees. All capture errors are isolated
 * from the host call; `chain.proceed`'s result (success or thrown exception) is always exactly what
 * reaches the caller.
 *
 * [timingsProvider], when supplied, must be the same [DevConsoleOkHttpEventListenerFactory] instance
 * installed as the client's `eventListenerFactory` -- see that class's kdoc for the wiring. Without
 * it, every recorded transaction's DNS/TCP/TLS/wait/download phases are left `null`, same as today.
 */
class DevConsoleOkHttpInterceptor
    @JvmOverloads
    constructor(
        private val recorder: NetworkTransactionRecorder,
        private val timingsProvider: DevConsoleOkHttpEventListenerFactory? = null,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val call = chain.call()
            val request = chain.request()
            val startedAtEpochMs = System.currentTimeMillis()
            try {
                // This inner try/catch is scoped *strictly* to chain.proceed -- nothing capture-related
                // runs inside it, so a capture bug can never masquerade as a network failure and discard
                // a response that actually succeeded.
                val response =
                    try {
                        chain.proceed(request)
                    } catch (error: Exception) {
                        // The real network exception must reach the host unconditionally. Recording the
                        // failure is best-effort and must never replace or suppress it.
                        runCatching {
                            val failureMessage = error.message ?: error.javaClass.simpleName
                            recorder.record(
                                request.toInput(),
                                NetworkResponseInput(statusCode = 0, error = failureMessage)
                                    .withMetadata(
                                        NetworkResponseMetadata(
                                            exceptionClass = error.javaClass.name,
                                            timings = call.timings(),
                                        ),
                                    ),
                                startedAtEpochMs,
                                System.currentTimeMillis(),
                            )
                        }
                        throw error
                    }
                // chain.proceed already succeeded: `response` is final from here on. Everything below --
                // including building the request/response inputs -- runs inside one runCatching so a
                // capture bug can never turn this successful response into a rethrown exception or a
                // reported failure.
                runCatching {
                    recorder.record(
                        response.request.toInput(),
                        response.toInput(call.timings()),
                        startedAtEpochMs,
                        System.currentTimeMillis(),
                    )
                }
                return response
            } finally {
                timingsProvider?.forget(call)
            }
        }

        private fun Call.timings(): NetworkTimingPhases = timingsProvider?.timingsFor(this) ?: NetworkTimingPhases()

        private fun Request.toInput(): NetworkRequestInput {
            val requestBody = body
            val declaredLength =
                requestBody
                    ?.let { runCatching { it.contentLength() }.getOrNull() }
                    ?.takeIf { it >= 0 }
            val (capturedBody, omittedReason) = requestBody?.captureOrReason(declaredLength) ?: (null to null)
            return NetworkRequestInput(
                method = method,
                url = url.toString(),
                headers = headers.toSingleValueMap(),
                body = capturedBody,
                contentType = requestBody?.contentType()?.toString(),
            ).withMetadata(
                NetworkRequestMetadata(
                    threadName = Thread.currentThread().name,
                    bodyLength = declaredLength,
                    bodyOmittedReason = omittedReason,
                    tags = tag(NetworkCaptureContext::class.java)?.tags.orEmpty(),
                ),
            )
        }

        /**
         * Captures a bounded copy of [this] request body into a fresh [Buffer], leaving the original
         * body untouched so OkHttp's real network write still sees the same bytes -- [Buffer.writeTo]
         * semantics never consume the source. Only attempted when [RequestBody.isOneShot] is false
         * (repeat reads are safe), [RequestBody.isDuplex] is false (not a streaming duplex body),
         * the content type is textual (or absent -- an absent type still gets read so
         * [io.devconsole.network.NetworkCaptureFactory]'s own UTF-8 sniff can decide, same leniency
         * as the response side below), and the declared length is known and within
         * [MAX_REQUEST_BODY_CAPTURE_BYTES]. Deliberately **not** wrapped in its own `runCatching`: a
         * [RequestBody.writeTo] failure here propagates to [toInput]'s caller (ultimately the single
         * `runCatching` around the whole capture in [intercept]), so a broken body implementation
         * fails the whole capture for this transaction rather than silently reporting a transaction
         * that never actually captured anything.
         */
        private fun RequestBody.captureOrReason(declaredLength: Long?): Pair<ByteArray?, String?> {
            val requestContentType = contentType()?.toString()
            return when {
                isDuplex() -> null to "duplex"
                isOneShot() -> null to "one-shot"
                declaredLength == null -> null to "unknown-length"
                declaredLength > MAX_REQUEST_BODY_CAPTURE_BYTES -> null to "too-large"
                requestContentType != null && !isTextualContentType(requestContentType) -> null to "binary"
                else -> {
                    val buffer = Buffer()
                    writeTo(buffer)
                    buffer.readByteArray() to null
                }
            }
        }

        private fun Response.toInput(timings: NetworkTimingPhases): NetworkResponseInput {
            val responseContentType = body.contentType()?.toString()
            val declaredLength = body.contentLength().takeIf { it >= 0 }
            val streaming = isStreamingBody(responseContentType, declaredLength)
            // A response with no declared content-type might still be decodable text --
            // NetworkCaptureFactory sniffs that from the actual bytes once it has them, so only a
            // content-type that is *known* non-textual skips the peek; an absent content-type is still
            // peeked to give that fallback a chance, same as before this change.
            val knownBinary = responseContentType != null && !isTextualContentType(responseContentType)
            val peekedBody =
                if (streaming || knownBinary) {
                    null
                } else {
                    // Known-length bodies only need peeking up to their own length; this also keeps a
                    // small known-length body from paying for a full 512KB peek buffer allocation.
                    val peekBytes = declaredLength?.coerceAtMost(MAX_RESPONSE_PEEK_BYTES) ?: MAX_RESPONSE_PEEK_BYTES
                    runCatching { peekBody(peekBytes).bytes() }.getOrNull()
                }
            return NetworkResponseInput(
                statusCode = code,
                headers = headers.toSingleValueMap(),
                body = peekedBody,
                contentType = responseContentType,
                protocol = protocol.toString(),
            ).withMetadata(
                NetworkResponseMetadata(
                    bodyLength = declaredLength,
                    bodyOmittedReason = if (streaming) "streaming" else null,
                    timings = timings,
                    fromCache = cacheResponse != null,
                ),
            )
        }

        /**
         * SSE (`text/event-stream`) and NDJSON bodies are effectively unbounded and are often held
         * open indefinitely; peeking one synchronously on the request thread would block until either
         * the peek bound is reached (which a live stream may never hit) or the read times out.
         * `text/event-stream` is always treated as streaming regardless of declared length (a real SSE
         * response is for all practical purposes never a small fixed-length body); NDJSON is only
         * treated as streaming when its length is unknown (chunked/unbounded) -- a length-bounded
         * NDJSON body is just a regular small body and is safe to peek like any other.
         */
        private fun isStreamingBody(
            contentType: String?,
            declaredLength: Long?,
        ): Boolean {
            val type = contentType?.lowercase().orEmpty()
            val isEventStream = type.startsWith("text/event-stream")
            val isNdjson = type.contains("ndjson")
            if (!isEventStream && !isNdjson) return false
            return isEventStream || declaredLength == null
        }

        /**
         * `Headers.get` returns only the last value for a repeated name, which silently drops all but
         * one `Set-Cookie`. Fold repeats the way HTTP itself does instead.
         */
        private fun Headers.toSingleValueMap(): Map<String, String> = names().associateWith { fold(it) }

        private fun Headers.fold(name: String): String = values(name).joinToString(", ")

        private companion object {
            const val MAX_RESPONSE_PEEK_BYTES: Long = 512L * 1024L

            /**
             * Upper bound for buffered request-body capture. Request bodies are typically small
             * (JSON/form payloads), so 256KB comfortably covers realistic cases while keeping the
             * worst-case synchronous buffering cost on the host's calling thread small; larger bodies
             * are reported as metadata-only with `bodyOmittedReason = "too-large"`. Matches
             * [io.devconsole.network.NetworkCaptureLimits.requestBodyPreviewBytes]'s default.
             */
            const val MAX_REQUEST_BODY_CAPTURE_BYTES: Long = 256L * 1024L
        }
    }
