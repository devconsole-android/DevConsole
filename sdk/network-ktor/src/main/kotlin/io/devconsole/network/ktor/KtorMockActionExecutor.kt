/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole.network.ktor

import io.devconsole.mocks.MockAction
import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.Sender
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.encodedPath
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketTimeoutException

private const val THROTTLE_CHUNKS_PER_SECOND = 10L
private const val MILLIS_PER_SECOND = 1_000L

/**
 * Applies one already-resolved [MockAction] to [request]. `MockEngine.decide` resolves templates
 * and sequence steps before returning, so those branches are defensive passthroughs only.
 */
internal suspend fun Sender.applyMockAction(
    client: HttpClient,
    action: MockAction,
    request: HttpRequestBuilder,
): HttpClientCall =
    when (action) {
        is MockAction.StaticResponse -> staticResponseOrProceed(client, action, request)

        is MockAction.Delay -> {
            delay(action.durationMs)
            applyMockAction(client, action.next, request)
        }

        is MockAction.ConnectionFailure -> throw IOException(action.message)

        is MockAction.Timeout -> simulateTimeout(action)

        is MockAction.StatusOverride -> executeRewritten(request, MockResponseRewrite.Status(action.statusCode))

        is MockAction.BodyReplacement -> executeRewritten(request, MockResponseRewrite.Body(action.body))

        is MockAction.MapRemote -> proceedToRemappedUrl(action, request)

        is MockAction.Throttle -> applyThrottle(client, action, request)

        is MockAction.TemplateResponse, is MockAction.Sequence, MockAction.Passthrough -> execute(request)
    }

private suspend fun Sender.staticResponseOrProceed(
    client: HttpClient,
    action: MockAction.StaticResponse,
    request: HttpRequestBuilder,
): HttpClientCall =
    runCatching {
        buildMockCall(
            client = client,
            requestData = request.toMockRequestData(),
            statusCode = action.statusCode,
            headers = action.headers.toKtorHeaders(),
            body = ByteReadChannel(action.body.encodeToByteArray()),
        )
    }.getOrNull()?.received(client) ?: execute(request)

private suspend fun simulateTimeout(action: MockAction.Timeout): Nothing {
    delay(action.durationMs)
    // SocketTimeoutException is an IOException and is what OkHttp's adapter throws, so a host's
    // existing timeout handling sees the same failure class it would from a real call.
    throw SocketTimeoutException("DevConsole simulated timeout after ${action.durationMs}ms")
}

private suspend fun Sender.proceedToRemappedUrl(
    action: MockAction.MapRemote,
    request: HttpRequestBuilder,
): HttpClientCall {
    action.scheme?.let { request.url.protocol = URLProtocol.createOrDefault(it) }
    action.host?.let { request.url.host = it }
    action.port?.let { request.url.port = it }
    action.path?.let { request.url.encodedPath = it }
    request.markMappedRemote(request.url.build().toString())
    return execute(request)
}

/**
 * Latency is applied for every throttle rule. A static payload is also paced at
 * [MockAction.Throttle.bytesPerSecond] through a channel that emits bounded chunks. Pacing an
 * engine-produced body (a throttle wrapping passthrough) is left unsimulated: the response channel
 * belongs to the engine and there is no supported point in the client pipeline to re-wrap it
 * without buffering the host's stream, so that case keeps only the latency.
 */
private suspend fun Sender.applyThrottle(
    client: HttpClient,
    action: MockAction.Throttle,
    request: HttpRequestBuilder,
): HttpClientCall {
    delay(action.latencyMs)
    val next = action.next
    val throttledStatic =
        (next as? MockAction.StaticResponse)?.let { static ->
            runCatching {
                buildMockCall(
                    client = client,
                    requestData = request.toMockRequestData(),
                    statusCode = static.statusCode,
                    headers = static.headers.toKtorHeaders(),
                    body = client.pacedBodyChannel(static.body, action.bytesPerSecond),
                )
            }.getOrNull()
        }
    return throttledStatic?.received(client) ?: applyMockAction(client, next, request)
}

private fun HttpClient.pacedBodyChannel(
    body: String,
    bytesPerSecond: Long,
): ByteReadChannel {
    val channel = ByteChannel(autoFlush = true)
    launch {
        try {
            val bytes = body.encodeToByteArray()
            var offset = 0
            while (offset < bytes.size) {
                val remaining = (bytes.size - offset).toLong()
                val chunkLength =
                    (bytesPerSecond / THROTTLE_CHUNKS_PER_SECOND)
                        .coerceAtLeast(1L)
                        .coerceAtMost(remaining)
                        .toInt()
                channel.writeFully(bytes.copyOfRange(offset, offset + chunkLength))
                offset += chunkLength
                delay((chunkLength * MILLIS_PER_SECOND + bytesPerSecond - 1L) / bytesPerSecond)
            }
        } finally {
            channel.flushAndClose()
        }
    }
    return channel
}

/**
 * Builds a synthetic call around [requestData]. Ktor 3 has no public constructor for a response
 * that never touched an engine: [HttpResponse] is abstract, and the concrete call/response pair is
 * assembled by the `@InternalAPI` [HttpClientCall] constructor from a public [HttpResponseData].
 * This is the smallest surface that produces one. Returned from `HttpSend`, it never reaches the
 * send pipeline's `Receive` phase, so [MockHttpClientCall.received] runs the receive pipeline
 * itself -- that is where capture and body saving hook in.
 *
 * [HttpResponseData.callContext] gets its own child job: the response completes that job when its
 * body drains, and reusing the client's job would complete the whole client.
 */
@OptIn(InternalAPI::class)
private fun buildMockCall(
    client: HttpClient,
    requestData: HttpRequestData,
    statusCode: Int,
    headers: Headers,
    body: ByteReadChannel,
): MockHttpClientCall =
    MockHttpClientCall(
        client = client,
        requestData = requestData,
        responseData =
            HttpResponseData(
                statusCode = HttpStatusCode.fromValue(statusCode),
                requestTime = GMTDate(),
                headers = headers,
                version = HttpProtocolVersion.HTTP_1_1,
                body = body,
                callContext = client.coroutineContext + Job(client.coroutineContext[Job]),
            ),
    )

/**
 * A call that never went through the send pipeline. [received] does what `HttpClient`'s own
 * `Receive` interceptor does for engine calls: runs the receive pipeline and keeps its response.
 */
@OptIn(InternalAPI::class)
private class MockHttpClientCall(
    client: HttpClient,
    requestData: HttpRequestData,
    responseData: HttpResponseData,
) : HttpClientCall(client, requestData, responseData) {
    suspend fun received(client: HttpClient): HttpClientCall =
        apply { response = client.receivePipeline.execute(Unit, response) }
}
