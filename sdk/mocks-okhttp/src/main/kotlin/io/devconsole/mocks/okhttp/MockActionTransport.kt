/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole.mocks.okhttp

import io.devconsole.mocks.MockAction
import io.devconsole.network.NetworkCaptureContext
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.io.IOException
import java.net.SocketTimeoutException

// Stateless OkHttp plumbing behind the timing and rewrite mock actions of [DevConsoleMockInterceptor].

private const val CALL_DEADLINE_NANOS_PER_MILLISECOND = 1_000_000L
private const val SLEEP_SLICE_MS = 50L
private const val MILLIS_PER_SECOND = 1_000L

internal fun simulateTimeout(
    action: MockAction.Timeout,
    chain: Interceptor.Chain,
): Nothing {
    // Never outlive the call's own deadline: OkHttp would report that as its own timeout instead.
    val callTimeout = chain.call().timeout()
    val untilCallDeadlineMs =
        if (callTimeout.hasDeadline()) {
            (callTimeout.deadlineNanoTime() - System.nanoTime()).coerceAtLeast(0L) /
                CALL_DEADLINE_NANOS_PER_MILLISECOND
        } else {
            action.durationMs
        }
    cancellableSleep(minOf(action.durationMs, untilCallDeadlineMs), chain)
    throw SocketTimeoutException("DevConsole simulated timeout after ${action.durationMs}ms")
}

internal fun proceedToRemappedUrl(
    action: MockAction.MapRemote,
    request: okhttp3.Request,
    chain: Interceptor.Chain,
): Response {
    val newUrl =
        request.url
            .newBuilder()
            .apply {
                action.scheme?.let(::scheme)
                action.host?.let(::host)
                action.port?.let(::port)
                action.path?.let(::encodedPath)
            }.build()
    val existing = request.tag(NetworkCaptureContext::class.java)?.tags.orEmpty()
    val rewrittenRequest =
        request
            .newBuilder()
            .url(newUrl)
            .tag(
                NetworkCaptureContext::class.java,
                NetworkCaptureContext(existing + ("mapRemote" to newUrl.toString())),
            ).build()
    return chain
        .proceed(rewrittenRequest)
        .newBuilder()
        .request(rewrittenRequest)
        .build()
}

internal fun Response.withThrottledBody(
    bytesPerSecond: Long,
    chain: Interceptor.Chain,
): Response {
    val originalBody = body ?: return this
    return newBuilder().body(throttledBody(originalBody, bytesPerSecond, chain)).build()
}

internal fun throttledBody(
    originalBody: ResponseBody,
    bytesPerSecond: Long,
    chain: Interceptor.Chain,
): ResponseBody {
    val throttledSource = ThrottledSource(originalBody.source(), bytesPerSecond, chain).buffer()
    return ThrottledResponseBody(originalBody, throttledSource)
}

/** Keeps the declared Content-Type verbatim (String.toResponseBody would append a charset). */
internal fun mockResponseBody(
    body: String,
    headers: Map<String, String>,
): ResponseBody {
    val mediaType = headers.declaredMediaType()
    return body.toByteArray(mediaType?.charset() ?: Charsets.UTF_8).toResponseBody(mediaType)
}

private fun Map<String, String>.declaredMediaType(): MediaType? =
    entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.toMediaTypeOrNull()

internal fun cancellableSleep(
    durationMs: Long,
    chain: Interceptor.Chain,
) {
    var remaining = durationMs
    while (remaining > 0) {
        if (chain.call().isCanceled()) throw IOException("Canceled")
        val slice = minOf(remaining, SLEEP_SLICE_MS)
        try {
            Thread.sleep(slice)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Canceled", error)
        }
        remaining -= slice
    }
    ensureCallActive(chain)
}

internal fun ensureCallActive(chain: Interceptor.Chain) {
    if (chain.call().isCanceled()) throw IOException("Canceled")
}

/** Paces reads to [bytesPerSecond], in slices short enough to notice a cancelled call promptly. */
private class ThrottledSource(
    delegate: Source,
    private val bytesPerSecond: Long,
    private val chain: Interceptor.Chain,
) : ForwardingSource(delegate) {
    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        ensureCallActive(chain)
        val maxChunk = (bytesPerSecond * SLEEP_SLICE_MS / MILLIS_PER_SECOND).coerceAtLeast(1L)
        val bytesRead = super.read(sink, minOf(byteCount, maxChunk))
        if (bytesRead > 0L) {
            val sleepMs = (bytesRead * MILLIS_PER_SECOND + bytesPerSecond - 1L) / bytesPerSecond
            cancellableSleep(sleepMs, chain)
        }
        return bytesRead
    }
}

private class ThrottledResponseBody(
    private val original: ResponseBody,
    private val throttledSource: BufferedSource,
) : ResponseBody() {
    override fun contentType(): MediaType? = original.contentType()

    override fun contentLength(): Long = original.contentLength()

    override fun source(): BufferedSource = throttledSource
}
