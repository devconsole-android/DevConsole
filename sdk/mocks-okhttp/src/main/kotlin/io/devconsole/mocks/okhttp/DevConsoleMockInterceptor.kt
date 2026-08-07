package io.devconsole.mocks.okhttp

import io.devconsole.mocks.MockAction
import io.devconsole.mocks.MockDecision
import io.devconsole.mocks.MockEngine
import io.devconsole.mocks.MockRequest
import io.devconsole.network.NetworkCaptureContext
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.net.SocketTimeoutException

class DevConsoleMockInterceptor(
    private val engine: MockEngine,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
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
        return applyAction(action, taggedRequest, chain)
    }

    private fun applyAction(
        action: MockAction,
        request: okhttp3.Request,
        chain: Interceptor.Chain,
    ): Response =
        when (action) {
            is MockAction.StaticResponse ->
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(action.statusCode)
                    .message("DevConsole mock")
                    .apply { action.headers.forEach { (name, value) -> header(name, value) } }
                    .body(action.body.toResponseBody())
                    .build()

            is MockAction.Delay -> {
                Thread.sleep(action.durationMs)
                applyAction(action.next, request, chain)
            }

            is MockAction.ConnectionFailure -> throw IOException(action.message)

            is MockAction.Timeout -> {
                Thread.sleep(action.durationMs)
                throw SocketTimeoutException("DevConsole simulated timeout after ${action.durationMs}ms")
            }

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

            is MockAction.TemplateResponse -> chain.proceed(request) // Template actions are resolved by MockEngine.
            MockAction.Passthrough -> chain.proceed(request)
        }

    /** `Headers.get` returns only the last value for a repeated name; fold repeats like HTTP does. */
    private fun Headers.toFoldedMap(): Map<String, String> = names().associateWith { values(it).joinToString(", ") }
}
