/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole.network.ktor

import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.Sender
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.cancel

/**
 * How a passthrough response is rewritten. The rewrite is not applied here: it rides on the request
 * and [rewriteMockedResponse] applies it between the engine and the receive pipeline, so capture
 * records the response the app actually gets rather than the one the server sent.
 */
internal sealed interface MockResponseRewrite {
    data class Status(
        val statusCode: Int,
    ) : MockResponseRewrite

    data class Body(
        val body: String,
    ) : MockResponseRewrite
}

internal val mockResponseRewriteKey = AttributeKey<MockResponseRewrite>("DevConsoleMockResponseRewrite")

internal suspend fun Sender.executeRewritten(
    request: HttpRequestBuilder,
    rewrite: MockResponseRewrite,
): HttpClientCall {
    request.attributes.put(mockResponseRewriteKey, rewrite)
    return execute(request)
}

/**
 * Applies a pending [MockResponseRewrite] to the engine's call. Runs in the send pipeline after
 * the engine and before `Receive`; any failure keeps the real call untouched.
 */
@OptIn(InternalAPI::class)
internal fun HttpClientCall.rewriteMockedResponse(
    client: HttpClient,
    request: HttpRequestBuilder,
    rewrite: MockResponseRewrite,
): HttpClientCall =
    runCatching {
        when (rewrite) {
            is MockResponseRewrite.Status ->
                replaceResponse(client, request, status = HttpStatusCode.fromValue(rewrite.statusCode))

            is MockResponseRewrite.Body -> {
                val bytes = rewrite.body.encodeToByteArray()
                // The server's body is discarded, so release it; and its length and encoding no
                // longer describe the replacement (a gzip Content-Encoding would make the
                // ContentEncoding plugin try to inflate plain text).
                response.rawContent.cancel(null)
                replaceResponse(
                    client = client,
                    request = request,
                    headers = response.headers.withReplacedBodyLength(bytes.size),
                    body = ByteReadChannel(bytes),
                )
            }
        }
    }.getOrDefault(this)

private fun Headers.withReplacedBodyLength(length: Int): Headers =
    HeadersBuilder()
        .apply {
            appendAll(this@withReplacedBodyLength)
            remove(HttpHeaders.ContentLength)
            remove(HttpHeaders.ContentEncoding)
            append(HttpHeaders.ContentLength, length.toString())
        }.build()

/**
 * Rebuilds a real call with an overridden status or body. The original call's coroutine context is
 * reused so completing the rebuilt response also completes the call it replaces.
 */
@OptIn(InternalAPI::class)
private fun HttpClientCall.replaceResponse(
    client: HttpClient,
    request: HttpRequestBuilder,
    status: HttpStatusCode = response.status,
    headers: Headers = response.headers,
    body: ByteReadChannel = response.rawContent,
): HttpClientCall =
    HttpClientCall(
        client = client,
        requestData = request.toMockRequestData(),
        responseData =
            HttpResponseData(
                statusCode = status,
                requestTime = response.requestTime,
                headers = headers,
                version = response.version,
                body = body,
                callContext = response.coroutineContext,
            ),
    )
