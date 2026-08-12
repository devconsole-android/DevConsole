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
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.buffer

/**
 * OkHttp application interceptor that only ever captures a bounded copy of request/response bodies
 * and never lets a capture failure change what the host call sees. All capture errors are isolated
 * from the host call; `chain.proceed`'s result (success or thrown exception) is always exactly what
 * reaches the caller.
 *
 * A response with a declared `Content-Length` is captured eagerly via [Response.peekBody] before the
 * host sees it. A response *without* one (chunked transfer and transparent-gzip responses, most
 * commonly) whose content type is not known-binary and not `text/event-stream` is instead returned
 * with its body wrapped in a bounded tee ([TeeCapturingSource]): bytes are copied as the host
 * consumes them -- up to [MAX_RESPONSE_PEEK_BYTES], pass-through-only past it -- and the transaction
 * is recorded once the body reaches EOF or is closed, whichever comes first, with `completedAt` set
 * to that moment. A host that closes the body without draining it (reading only the status code, or
 * a parser that stops at the end of the value it wanted) does not get a truncated capture: the tee
 * drains what is left within a bounded budget on close, so a recorded body does not depend on how
 * much of it the host read. INVARIANT: every response `chain.proceed` returns is recorded. The tee upholds it
 * for bodies that stay open past a short grace period -- a long-lived stream (NDJSON, long-poll) or
 * a body the host never reads and never closes -- with a provisional metadata-only `"streaming"`
 * record that the completion record later replaces in place; see [DeferredResponseBodyCapture].
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
                // including building the request/response inputs -- runs inside a runCatching so a
                // capture bug can never turn this successful response into a rethrown exception or a
                // reported failure. A tee-setup failure degrades to the immediate-record path below,
                // which for an unknown-length body is today's metadata-only "streaming" record.
                runCatching { response.deferredBodyCaptureOrNull(call, startedAtEpochMs) }
                    .getOrNull()
                    ?.let { teedResponse -> return teedResponse }
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
            val responseContentType = body?.contentType()?.toString()
            val declaredLength = body?.contentLength()?.takeIf { it >= 0 }
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
         * Wraps [this] response's body in a bounded tee when -- and only when -- deferred capture is
         * the right policy: the body's length is unknown (so an eager [Response.peekBody] would block
         * inside [intercept], potentially forever on a long-lived connection) yet its content type is
         * neither known-binary (nothing textual to capture) nor `text/event-stream` (a declared SSE
         * stream is for all practical purposes endless, so teeing it would pin its capture buffer for
         * nothing; it keeps the immediate metadata-only path). Returns `null` when the
         * immediate-record path should run instead. Other long-lived unknown-length bodies -- NDJSON,
         * long-poll, a live `text/plain` feed, an SSE response whose server omitted its content
         * type -- do take the tee, and stay visible while open through the capture's provisional
         * `"streaming"` record; recording never waits for a stream's end.
         *
         * The request input is built here, eagerly, so [NetworkRequestMetadata.threadName] still
         * names the calling thread and a broken [RequestBody.writeTo] still fails this transaction's
         * capture the same way it does on the immediate path. Uses [Response.request] -- the final
         * post-redirect request -- matching the immediate success path. The live
         * [CallPhaseTimestamps] reference is grabbed now, while the factory still maps this call;
         * see [DevConsoleOkHttpEventListenerFactory.phaseTimestampsFor] for why the deferred record
         * can still snapshot it after eviction.
         */
        private fun Response.deferredBodyCaptureOrNull(
            call: Call,
            startedAtEpochMs: Long,
        ): Response? {
            val responseBody = body
            val responseContentType = responseBody?.contentType()?.toString()
            val declaredLength = responseBody?.contentLength()?.takeIf { it >= 0 }
            val knownBinary = responseContentType != null && !isTextualContentType(responseContentType)
            val eagerPathHandlesIt = declaredLength != null || knownBinary || isEventStream(responseContentType)
            if (responseBody == null || eagerPathHandlesIt) {
                return null
            }
            val capture =
                DeferredResponseBodyCapture(
                    recorder = recorder,
                    requestInput = request.toInput(),
                    responseTemplate =
                        NetworkResponseInput(
                            statusCode = code,
                            headers = headers.toSingleValueMap(),
                            contentType = responseContentType,
                            protocol = protocol.toString(),
                        ).withMetadata(NetworkResponseMetadata(fromCache = cacheResponse != null)),
                    startedAtEpochMs = startedAtEpochMs,
                    phaseTimestamps = timingsProvider?.phaseTimestampsFor(call),
                    maxCapturedBytes = MAX_RESPONSE_PEEK_BYTES,
                )
            val teedBody =
                TeeCapturingSource(responseBody.source(), capture)
                    .buffer()
                    .asResponseBody(responseBody.contentType(), responseBody.contentLength())
            val teedResponse = newBuilder().body(teedBody).build()
            // Scheduled last, once nothing on this path can fail anymore: a provisional record must
            // only ever exist for a response the host actually received in teed form.
            capture.recordProvisionallyWhileBodyStaysOpen()
            return teedResponse
        }

        /**
         * Classifies bodies for the immediate-record path. `text/event-stream` is always streaming
         * even when a length happens to be declared, since a real SSE response is for all practical
         * purposes never a small fixed-length body. An unknown-length body is streaming too, but by
         * the time this runs, [deferredBodyCaptureOrNull] has already routed every unknown-length
         * non-binary, non-SSE body to the tee -- so the `declaredLength == null` case only still
         * applies to known-binary bodies (and to any body whose tee setup failed, degrading it to
         * today's metadata-only record). Any body with a known, bounded length is safe to
         * [Response.peekBody] eagerly and is not considered streaming.
         */
        private fun isStreamingBody(
            contentType: String?,
            declaredLength: Long?,
        ): Boolean = isEventStream(contentType) || declaredLength == null

        private fun isEventStream(contentType: String?): Boolean =
            contentType?.lowercase().orEmpty().startsWith("text/event-stream")

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
