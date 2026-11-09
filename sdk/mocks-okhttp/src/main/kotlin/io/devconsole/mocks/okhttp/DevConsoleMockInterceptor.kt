package io.devconsole.mocks.okhttp

import io.devconsole.mocks.MockAction
import io.devconsole.mocks.MockDecision
import io.devconsole.mocks.MockEngine
import io.devconsole.mocks.MockRequest
import io.devconsole.network.NetworkCaptureContext
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class DevConsoleMockInterceptor(
    private val engine: MockEngine,
) : Interceptor {
    private val rejectedHeaderLogged = AtomicBoolean(false)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // `installDevConsole` wires one of these by default, so a host that also kept its own manual
        // `.addInterceptor(DevConsoleMockInterceptor(...))` line now has two in the chain. Deciding
        // twice is not merely redundant: a Delay rule would sleep in both passes, silently doubling
        // every simulated latency the moment the host upgraded. Whoever got there first owns the call.
        if (request.tag(NetworkCaptureContext::class.java)?.tags?.containsKey("mocked") == true) {
            return chain.proceed(request)
        }
        val decision =
            engine.decide(
                MockRequest(
                    method = request.method,
                    scheme = request.url.scheme,
                    host = request.url.host,
                    path = request.url.encodedPath,
                    query =
                        request.url.queryParameterNames.associateWith { name ->
                            request.url.queryParameter(name).orEmpty()
                        },
                    headers = request.headers.toFoldedMap(),
                    body = request.bodyPreviewForMatching(engine.requestBodyLimitBytes()),
                ),
            )
        val matched = decision as? MockDecision.Matched
        val action = matched?.action ?: MockAction.Passthrough
        val taggedRequest =
            matched
                ?.let {
                    val existing = request.tag(NetworkCaptureContext::class.java)?.tags.orEmpty()
                    request
                        .newBuilder()
                        .tag(
                            NetworkCaptureContext::class.java,
                            NetworkCaptureContext(existing + mapOf("mocked" to "true", "mockRuleId" to it.rule.id)),
                        ).build()
                } ?: request
        return try {
            applyAction(action, taggedRequest, chain)
        } catch (error: IOException) {
            // Only failures the mock itself simulated are re-tagged; a real network failure from
            // chain.proceed propagates untouched (and is never retried here).
            // The host must still see the exact exception type it would get (e.g. SocketTimeoutException),
            // so the mock tags ride along as a suppressed marker instead of a wrapper.
            if (matched != null && action.simulatesFailure()) {
                error.addSuppressed(
                    MockFailureMarker(taggedRequest.tag(NetworkCaptureContext::class.java)?.tags.orEmpty()),
                )
            }
            throw error
        }
    }

    /**
     * A UTF-8 copy of the request body for body predicates, or `null` when none is needed or it
     * cannot be read safely. One-shot and duplex bodies are never read (reading would consume them),
     * and a body of unknown or oversized length is skipped rather than buffered.
     */
    private fun Request.bodyPreviewForMatching(limitBytes: Int?): String? {
        val requestBody = body?.takeUnless { it.isOneShot() || it.isDuplex() }
        val length = requestBody?.let { runCatching { it.contentLength() }.getOrDefault(-1L) } ?: -1L
        val readable = requestBody != null && limitBytes != null && length in 0..limitBytes
        return if (readable) runCatching { Buffer().also(requestBody::writeTo).readUtf8() }.getOrNull() else null
    }

    private fun MockAction.simulatesFailure(): Boolean =
        when (this) {
            is MockAction.ConnectionFailure, is MockAction.Timeout -> true
            is MockAction.Delay -> next.simulatesFailure()
            is MockAction.Throttle -> next.simulatesFailure()
            is MockAction.Sequence -> steps.any { it.simulatesFailure() }
            else -> false
        }

    private fun applyAction(
        action: MockAction,
        request: okhttp3.Request,
        chain: Interceptor.Chain,
    ): Response =
        when (action) {
            is MockAction.StaticResponse -> buildStaticResponse(action, request) ?: chain.proceed(request)

            is MockAction.Delay -> {
                cancellableSleep(action.durationMs, chain)
                applyAction(action.next, request, chain)
            }

            is MockAction.ConnectionFailure -> throw IOException(action.message)

            is MockAction.Timeout -> simulateTimeout(action, chain)

            is MockAction.StatusOverride ->
                chain
                    .proceed(request)
                    .newBuilder()
                    .code(action.statusCode)
                    .message("DevConsole status override")
                    .build()

            is MockAction.BodyReplacement -> {
                val hostResponse = chain.proceed(request)
                val mediaType = hostResponse.body?.contentType()
                // Not Response.close(): in OkHttp 4 it throws IllegalStateException when body is null,
                // which is legal for a Response built by an upstream interceptor without a body.
                hostResponse.body?.close()
                hostResponse.newBuilder().body(action.body.toResponseBody(mediaType)).build()
            }

            is MockAction.MapRemote -> proceedToRemappedUrl(action, request, chain)

            is MockAction.Throttle -> {
                cancellableSleep(action.latencyMs, chain)
                applyAction(action.next, request, chain).withThrottledBody(action.bytesPerSecond, chain)
            }

            // Sequence steps and templates are resolved by MockEngine before they reach the interceptor.
            is MockAction.Sequence -> chain.proceed(request)
            is MockAction.TemplateResponse -> chain.proceed(request)
            MockAction.Passthrough -> chain.proceed(request)
        }

    @Suppress("TooGenericExceptionCaught") // A malformed mock response must not break the host's real network request.
    private fun buildStaticResponse(
        action: MockAction.StaticResponse,
        request: okhttp3.Request,
    ): Response? =
        try {
            Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(action.statusCode)
                .message("DevConsole mock")
                .apply {
                    action.headers.forEach { (name, value) ->
                        runCatching { header(name, value) }.onFailure(::logRejectedHeaderOnce)
                    }
                }.body(mockResponseBody(action.body, action.headers))
                .build()
        } catch (error: Exception) {
            logRejectedHeaderOnce(error)
            null
        }

    private fun logRejectedHeaderOnce(error: Throwable) {
        if (rejectedHeaderLogged.compareAndSet(false, true)) {
            runCatching {
                System.err.println(
                    "DevConsole skipped an invalid mock response header or response: ${error.message}",
                )
            }
        }
    }

    /** `Headers.get` returns only the last value for a repeated name; fold repeats like HTTP does. */
    private fun Headers.toFoldedMap(): Map<String, String> = names().associateWith { values(it).joinToString(", ") }
}

/** Attached (suppressed) to a failure a mock rule simulated, so capture can tag it as mocked. */
class MockFailureMarker(
    val tags: Map<String, String>,
) : RuntimeException("DevConsole mock failure", null, false, false)
