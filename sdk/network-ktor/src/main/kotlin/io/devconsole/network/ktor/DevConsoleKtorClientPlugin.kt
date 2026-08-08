package io.devconsole.network.ktor

import io.devconsole.network.NetworkRequestInput
import io.devconsole.network.NetworkRequestMetadata
import io.devconsole.network.NetworkResponseInput
import io.devconsole.network.NetworkResponseMetadata
import io.devconsole.network.NetworkTransactionRecorder
import io.devconsole.network.isTextualContentType
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.observer.wrapWithContent
import io.ktor.client.statement.HttpReceivePipeline
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.util.AttributeKey
import io.ktor.util.split
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.discard
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.readByteArray
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records every completed call -- headers, metadata, and a bounded copy of textual request and
 * response bodies -- without changing anything the host application observes.
 *
 * Response bodies are captured at `HttpReceivePipeline.After`, the same stage Ktor's own
 * `ResponseObserver`/`Logging` plugins use, which is the earliest point where the raw body channel
 * can be duplicated without stealing bytes from the host's later `body<T>()`/`bodyAsText()` read.
 * Every captured body takes the same path: the raw channel is duplicated with
 * `ByteReadChannel.split` and the host proceeds with a rebuilt response around one half, exactly
 * like `ResponseObserver` -- deliberately including responses `SaveBodyPlugin` has already saved.
 * A saved-body special path (reading a private replay and leaving the host's response untouched)
 * is a trap, twice over:
 *
 * - [io.ktor.client.plugins.isSaved] is a request-attribute flag that survives every later
 *   re-wrap of the response, and this plugin is not necessarily the last wrapper before us:
 *   Ktor's default-installed `BodyProgress` (whenever an `onDownload` listener is present) and
 *   plugins like `ResponseObserver`/`Logging` registered earlier at this same phase replace the
 *   response with a *fixed single-shot channel* while `isSaved` stays true. Reading -- or worse,
 *   cancelling -- `rawContent` outside a split then consumes or destroys the host's only body.
 *
 * - Even when `rawContent` really is the replay-minting wrapper, `ByteChannelReplay` streams only
 *   to its *first* caller; every later caller waits for the whole origin to close before seeing a
 *   byte. A capture-side replay therefore either steals the streamed first read from the host --
 *   buffering its body until the origin closes, which for a never-closing textual stream means
 *   forever -- or races it for that slot. Splitting the first access instead keeps the host's
 *   bytes flowing chunk-by-chunk as they arrive.
 *
 * INVARIANT: the capture half of the split must always be *fully drained and never cancelled*.
 * `split`'s copier writes each chunk to both halves and awaits both writes before reading the next
 * chunk from the origin, so an undrained capture half stalls -- and a cancelled one errors -- the
 * host's half too. [launchResponseBodyCapture] upholds this by draining the remainder in a
 * `finally`.
 *
 * Re-readability is preserved without touching replay internals: the rebuilt response hands the
 * host half to the first `rawContent` access and delegates every later access back to the wrapped
 * response. On a saved body a second `body<T>()` therefore mints a fresh replay served from the
 * saved copy -- complete by then, because the first read drained the origin -- exactly as it would
 * without this plugin. With body saving off there is no replay to delegate to and a double
 * `body<T>()` fails with `DoubleReceiveException`, again exactly as it would without this plugin.
 *
 * The capture coroutine is launched on the call's own scope (`response.call`), not the client's --
 * a deliberate deviation from `ResponseObserver`'s client-scoped launch: it dies with the
 * individual call and can never outlive or leak past it. Exactly one transaction is recorded per
 * received response, from a single site ([PendingKtorTransaction.recordTo]): immediately for
 * metadata-only bodies, at the capture cap or channel close for captured ones, and from the
 * observer's `finally` (metadata-only) if the call is torn down mid-body -- recording is never
 * held hostage by a slow or never-ending stream. `completedAtEpochMs` remains the headers-received
 * time, matching the OkHttp interceptor's timing semantics.
 *
 * As before, every fallible step runs inside `runCatching` before the body channel is touched, so
 * a capture bug can only ever lose a recording -- never break, replace, or delay what the host
 * receives.
 */
@OptIn(InternalAPI::class)
val DevConsoleKtorClientPlugin =
    createClientPlugin("DevConsoleKtorClientPlugin", ::DevConsoleKtorClientConfig) {
        val recorder = pluginConfig.recorder ?: return@createClientPlugin

        onRequest { request, _ ->
            // Never let attaching the start timestamp -- or any future work added to this hook --
            // break the host's request. Missing timing is far better than a broken call.
            runCatching { request.attributes.put(startedAtKey, System.currentTimeMillis()) }
        }

        client.receivePipeline.intercept(HttpReceivePipeline.After) { response ->
            // Everything fallible (request-body re-read, header assembly, capture-mode decision)
            // happens here, before the body channel is touched in any way. A failure records
            // nothing and leaves the original response to proceed untouched -- the same
            // swallow-the-whole-capture contract the previous onResponse implementation had.
            val pending = runCatching { prepareTransaction(response) }.getOrNull() ?: return@intercept

            if (pending.responseBodyOmittedReason != null) {
                runCatching { pending.recordTo(recorder, body = null, pending.responseBodyOmittedReason) }
                return@intercept
            }

            // Between split() and proceedWith the origin channel is already being pumped into both
            // halves, so nothing fallible may run in between: this block is only channel
            // bookkeeping and a coroutine launch, neither of which can throw.
            val rebuiltResponse =
                runCatching {
                    val (captureContent, hostContent) = response.rawContent.split(response)
                    val hostContentDelivered = AtomicBoolean(false)
                    val rebuiltCall =
                        response.call.wrapWithContent {
                            if (hostContentDelivered.compareAndSet(false, true)) hostContent else response.rawContent
                        }
                    val rebuilt = rebuiltCall.response
                    launchResponseBodyCapture(
                        scope = response.call,
                        pending = pending,
                        recorder = recorder,
                        captureContent = captureContent,
                    )
                    rebuilt
                }.getOrNull() ?: return@intercept
            proceedWith(rebuiltResponse)
        }
    }

class DevConsoleKtorClientConfig {
    var recorder: NetworkTransactionRecorder? = null
}

private val startedAtKey = AttributeKey<Long>("DevConsoleStartedAt")

/**
 * Reads up to [MAX_KTOR_RESPONSE_BODY_CAPTURE_BYTES] (+1 to detect overflow without guessing at
 * `isClosedForRead` edge states) from the capture half of the split and records the transaction
 * exactly once.
 *
 * Recording happens at whichever comes first -- the cap is reached (`"too-large"`, matching the
 * request-side vocabulary) or the channel closes (body captured whole) -- and, as a last resort,
 * from the `finally` as metadata-only if the coroutine is cancelled or fails mid-read. That
 * `finally` also releases the channel by *draining* it via `discard()`, which is the load-bearing
 * part of the whole design: `split`'s documented contract is "cancel of one channel in split
 * cancels other channels", so cancelling the capture half would destroy the host's half, and an
 * undrained half stalls the copier's `awaitAll` and with it the host's body. If this coroutine is
 * itself cancelled, the copier shares the same call scope and dies with it, so no stall can
 * outlive the call.
 *
 * [recordTo][PendingKtorTransaction.recordTo] is wrapped in its own `runCatching` so a recorder
 * failure can never skip the drain, and the coroutine runs on the call's scope so a crash or hang
 * in here can neither escape into nor outlive the host's call.
 */
private fun launchResponseBodyCapture(
    scope: CoroutineScope,
    pending: PendingKtorTransaction,
    recorder: NetworkTransactionRecorder,
    captureContent: ByteReadChannel,
) {
    scope.launch {
        var recorded = false

        fun recordOnce(
            body: ByteArray?,
            omittedReason: String?,
        ) {
            if (recorded) return
            recorded = true
            runCatching { pending.recordTo(recorder, body, omittedReason) }
        }

        try {
            val bytes = captureContent.readRemaining(MAX_KTOR_RESPONSE_BODY_CAPTURE_BYTES + 1).readByteArray()
            if (bytes.size > MAX_KTOR_RESPONSE_BODY_CAPTURE_BYTES) {
                recordOnce(body = null, omittedReason = "too-large")
            } else {
                recordOnce(body = bytes.takeIf { it.isNotEmpty() }, omittedReason = null)
            }
        } finally {
            recordOnce(body = null, omittedReason = null)
            runCatching { captureContent.discard() }
        }
    }
}

/**
 * Everything needed to record the transaction later, assembled eagerly at intercept time so the
 * asynchronous body capture holds plain values -- never the live response -- and so any failure in
 * building it aborts the whole capture *before* the body channel has been touched.
 */
private class PendingKtorTransaction(
    val request: NetworkRequestInput,
    private val responseTemplate: NetworkResponseInput,
    private val responseDeclaredLength: Long?,
    val responseBodyOmittedReason: String?,
    private val startedAtEpochMs: Long,
    private val completedAtEpochMs: Long,
) {
    /** The single record site for this integration; every capture path funnels through here. */
    fun recordTo(
        recorder: NetworkTransactionRecorder,
        body: ByteArray?,
        omittedReason: String?,
    ) {
        recorder.record(
            request = request,
            response =
                responseTemplate.copy(body = body).withMetadata(
                    NetworkResponseMetadata(
                        bodyLength = responseDeclaredLength,
                        bodyOmittedReason = omittedReason,
                    ),
                ),
            startedAtEpochMs = startedAtEpochMs,
            completedAtEpochMs = completedAtEpochMs,
        )
    }
}

private fun prepareTransaction(response: HttpResponse): PendingKtorTransaction {
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
    val requestContentType = request.content.contentType?.toString() ?: request.headers[HttpHeaders.ContentType]
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
                bodyLength = request.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
                bodyOmittedReason = if (capturedRequestBody != null) null else "ktor-pipeline-metadata-only",
            ),
        )

    val responseContentType = response.headers[HttpHeaders.ContentType]
    val responseDeclaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    return PendingKtorTransaction(
        request = requestInput,
        responseTemplate =
            NetworkResponseInput(
                statusCode = response.status.value,
                headers = response.headers.entries().associate { (k, v) -> k to v.joinToString(",") },
                contentType = responseContentType,
                protocol = response.version.toString(),
            ),
        responseDeclaredLength = responseDeclaredLength,
        responseBodyOmittedReason =
            responseBodyOmittedReasonOrNull(response.status.value, responseContentType, responseDeclaredLength),
        startedAtEpochMs = startedAt,
        completedAtEpochMs = completedAt,
    )
}

/**
 * Non-null means the response body is deliberately left uncaptured (metadata-only) and the body
 * channel is never touched at all -- no split, zero overhead and zero risk for the host.
 * Vocabulary matches the rest of the codebase: `"streaming"` for `text/event-stream` and protocol
 * upgrades (a `101 Switching Protocols` channel is a live WebSocket connection, not a body --
 * splitting it would swallow frames), `"binary"` for a declared non-textual type, and `"too-large"`
 * when a declared Content-Length already exceeds the capture cap, mirroring the request side.
 *
 * A response with *no* declared content type is still captured so [io.devconsole.network
 * .NetworkCaptureFactory]'s UTF-8 sniff can decide from the actual bytes, and an unknown length
 * (chunked transfer) is still captured up to the cap -- unlike the OkHttp interceptor, the capture
 * here reads asynchronously as bytes arrive and can never block the host, so chunked textual
 * bodies no longer have to be treated as streaming.
 */
private fun responseBodyOmittedReasonOrNull(
    statusCode: Int,
    contentType: String?,
    declaredLength: Long?,
): String? {
    val isEventStream = contentType?.lowercase().orEmpty().startsWith("text/event-stream")
    return when {
        statusCode == HttpStatusCode.SwitchingProtocols.value -> "streaming"
        isEventStream -> "streaming"
        contentType != null && !isTextualContentType(contentType) -> "binary"
        declaredLength != null && declaredLength > MAX_KTOR_RESPONSE_BODY_CAPTURE_BYTES -> "too-large"
        else -> null
    }
}

/**
 * Captures a bounded copy of a textual [OutgoingContent.ByteArrayContent] (Ktor's `TextContent` is a
 * subtype of this) request body. `bytes()` on this content type returns an already-materialized array
 * Ktor itself holds for the actual send, so reading it here never consumes or disturbs anything the
 * engine still needs to write. Other [OutgoingContent] variants ([OutgoingContent.ReadChannelContent],
 * [OutgoingContent.WriteChannelContent], etc.) stream from a single-use source and are left
 * metadata-only, same as before this capture existed.
 *
 * Deliberately not wrapped in its own `runCatching`: a misbehaving `bytes()` override propagates to
 * the single `runCatching` around [prepareTransaction], failing the whole capture for this
 * transaction -- before the response body channel has been touched -- rather than recording a
 * partial transaction, same tradeoff `DevConsoleOkHttpInterceptor` makes for `RequestBody.writeTo`.
 */
private fun OutgoingContent.captureTextualBodyOrNull(contentType: String?): ByteArray? {
    val byteArrayContent = this as? OutgoingContent.ByteArrayContent ?: return null
    val length = byteArrayContent.contentLength
    val eligible = isTextualContentType(contentType) && length != null && length <= MAX_KTOR_REQUEST_BODY_CAPTURE_BYTES
    return if (eligible) byteArrayContent.bytes() else null
}

/** Mirrors DevConsoleOkHttpInterceptor's request-body capture bound. */
private const val MAX_KTOR_REQUEST_BODY_CAPTURE_BYTES: Long = 256L * 1024L

/** Response-side twin of [MAX_KTOR_REQUEST_BODY_CAPTURE_BYTES]; bodies past this record as "too-large". */
private const val MAX_KTOR_RESPONSE_BODY_CAPTURE_BYTES: Long = 256L * 1024L
