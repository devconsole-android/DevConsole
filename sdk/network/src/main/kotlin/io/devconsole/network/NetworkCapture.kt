package io.devconsole.network

import io.devconsole.security.RedactionEngine
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

data class CaptureBodyMetadata(
    val declaredLength: Long? = null,
    val capturedBytes: Int = 0,
    val truncated: Boolean = false,
    val omittedReason: String? = null,
)

data class NetworkTimingPhases(
    val dnsMs: Long? = null,
    val connectMs: Long? = null,
    val tlsMs: Long? = null,
    val sendMs: Long? = null,
    val waitMs: Long? = null,
    val receiveMs: Long? = null,
)

data class NetworkRequestMetadata(
    val threadName: String? = null,
    val bodyLength: Long? = null,
    val bodyOmittedReason: String? = null,
    val tags: Map<String, String> = emptyMap(),
)

/** Adapter-neutral metadata that integrations attach without mutating outgoing HTTP headers. */
data class NetworkCaptureContext(
    val tags: Map<String, String> = emptyMap(),
)

data class NetworkResponseMetadata(
    val bodyLength: Long? = null,
    val bodyOmittedReason: String? = null,
    val timings: NetworkTimingPhases = NetworkTimingPhases(),
    val fromCache: Boolean = false,
    val exceptionClass: String? = null,
)

data class NetworkRequestInput(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
    val correlationId: String? = null,
    val pluginId: String = "network",
) {
    var metadata: NetworkRequestMetadata = NetworkRequestMetadata()
        private set

    fun withMetadata(metadata: NetworkRequestMetadata): NetworkRequestInput = copy().also { it.metadata = metadata }
}

data class NetworkResponseInput(
    val statusCode: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
    val protocol: String? = null,
    val error: String? = null,
) {
    var metadata: NetworkResponseMetadata = NetworkResponseMetadata()
        private set

    fun withMetadata(metadata: NetworkResponseMetadata): NetworkResponseInput = copy().also { it.metadata = metadata }
}

data class NetworkUrl(
    val scheme: String,
    val host: String,
    val path: String,
    val query: Map<String, String>,
) {
    val display: String get() =
        buildString {
            append("$scheme://$host$path")
            if (query.isNotEmpty()) append('?').append(query.entries.joinToString("&") { "${it.key}=${it.value}" })
        }
}

sealed interface BodyPreview {
    data class Text(
        val value: String,
        val truncated: Boolean,
    ) : BodyPreview

    data class Binary(
        val length: Long,
        val truncated: Boolean,
    ) : BodyPreview

    data object Absent : BodyPreview
}

data class CapturedRequest(
    val method: String,
    val url: NetworkUrl,
    val headers: Map<String, String>,
    val body: BodyPreview,
    val contentType: String?,
    val correlationId: String?,
    val pluginId: String,
) {
    var metadata: CapturedRequestMetadata =
        CapturedRequestMetadata(body = CaptureBodyMetadata())
        internal set

    /** ID of the bounded, redacted body attachment, when one was persisted. */
    var attachmentId: String? = null
        internal set
}

data class CapturedRequestMetadata(
    val threadName: String? = null,
    val body: CaptureBodyMetadata,
    val tags: Map<String, String> = emptyMap(),
)

data class CapturedResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: BodyPreview,
    val contentType: String?,
    val protocol: String?,
    val error: String?,
) {
    var metadata: CapturedResponseMetadata =
        CapturedResponseMetadata(body = CaptureBodyMetadata())
        internal set

    /** ID of the bounded, redacted body attachment, when one was persisted. */
    var attachmentId: String? = null
        internal set
}

data class CapturedResponseMetadata(
    val body: CaptureBodyMetadata,
    val timings: NetworkTimingPhases = NetworkTimingPhases(),
    val fromCache: Boolean = false,
    val exceptionClass: String? = null,
)

data class NetworkCapture(
    val request: CapturedRequest,
    val response: CapturedResponse?,
) {
    fun estimatedSizeBytes(): Int = request.estimatedSizeBytes() + (response?.estimatedSizeBytes() ?: 0)
}

/**
 * Whether [contentType] identifies a body worth treating as text. Shared with the OkHttp/Ktor
 * adapter modules so a response (or request) body is never buffered into memory on the host's
 * calling thread only to be discarded here as binary -- adapters call this *before* reading any
 * bytes to decide whether reading is worth doing at all. [NetworkCaptureFactory] itself still runs
 * the same check (plus a UTF-8 decodability fallback for a body an adapter did read despite an
 * absent content-type) once bytes are actually in hand.
 */
fun isTextualContentType(contentType: String?): Boolean =
    contentType?.lowercase()?.let { type ->
        type.startsWith("text/") ||
            type.contains("json") ||
            type.contains("xml") ||
            type.contains("x-www-form-urlencoded")
    } ?: false

class NetworkCaptureFactory(
    private val redaction: RedactionEngine,
    private val limits: NetworkCaptureLimits = NetworkCaptureLimits(),
) {
    fun capture(
        request: NetworkRequestInput,
        response: NetworkResponseInput?,
    ): NetworkCapture {
        val requestBody = request.body.preview(request.contentType, limits.requestBodyPreviewBytes)
        val capturedRequest =
            CapturedRequest(
                method = request.method,
                url = request.url.toRedactedUrl(),
                headers = request.headers.redactHeaders(),
                body = requestBody,
                contentType = request.contentType,
                correlationId = request.correlationId?.let(redaction::redactText),
                pluginId = request.pluginId,
            ).also {
                it.metadata =
                    CapturedRequestMetadata(
                        threadName =
                            request.metadata.threadName
                                ?.let(redaction::redactText)
                                ?.take(MAX_THREAD_NAME_CHARS),
                        body =
                            requestBody.metadata(
                                declaredLength = request.metadata.bodyLength ?: request.body?.size?.toLong(),
                                omittedReason = request.metadata.bodyOmittedReason,
                            ),
                        tags = request.metadata.tags.redactTags(),
                    )
            }
        val capturedResponse =
            response?.let {
                val responseBody = it.body.preview(it.contentType, limits.responseBodyPreviewBytes)
                CapturedResponse(
                    statusCode = it.statusCode,
                    headers = it.headers.redactHeaders(),
                    body = responseBody,
                    contentType = it.contentType,
                    protocol = it.protocol,
                    error = it.error?.let(redaction::redactText),
                ).also { captured ->
                    captured.metadata =
                        CapturedResponseMetadata(
                            body =
                                responseBody.metadata(
                                    declaredLength = it.metadata.bodyLength ?: it.body?.size?.toLong(),
                                    omittedReason = it.metadata.bodyOmittedReason,
                                ),
                            timings = it.metadata.timings.nonNegative(),
                            fromCache = it.metadata.fromCache,
                            exceptionClass =
                                it.metadata.exceptionClass
                                    ?.let(redaction::redactText)
                                    ?.take(MAX_EXCEPTION_CLASS_CHARS),
                        )
                }
            }
        return NetworkCapture(capturedRequest, capturedResponse).constrainedTo(limits.totalCaptureBytes)
    }

    private fun String.toRedactedUrl(): NetworkUrl {
        val uri = URI(this)
        val query =
            uri.rawQuery
                .orEmpty()
                .split('&')
                .asSequence()
                .filter(String::isNotBlank)
                .take(MAX_QUERY_FIELDS)
                .associate { part ->
                    val index = part.indexOf('=')
                    val name = (if (index < 0) part else part.substring(0, index)).take(MAX_QUERY_FIELD_CHARS)
                    name to (if (index < 0) "" else part.substring(index + 1).take(MAX_QUERY_FIELD_CHARS))
                }
        return NetworkUrl(
            uri.scheme.orEmpty().take(MAX_SCHEME_CHARS),
            uri.host.orEmpty().take(MAX_HOST_CHARS),
            (uri.rawPath ?: "/").take(MAX_PATH_CHARS),
            redaction.redactFields(query),
        )
    }

    private fun Map<String, String>.redactHeaders(): Map<String, String> =
        entries.take(limits.maxHeaderCount).associate { (name, value) ->
            name to redaction.redactFields(mapOf(name to value)).getValue(name).take(limits.maxHeaderValueChars)
        }

    private fun Map<String, String>.redactTags(): Map<String, String> =
        entries.take(MAX_TAG_COUNT).associate { (name, value) ->
            name.take(MAX_TAG_CHARS) to
                redaction
                    .redactFields(mapOf(name to value))
                    .getValue(name)
                    .take(MAX_TAG_CHARS)
        }

    private fun ByteArray?.preview(
        contentType: String?,
        limit: Int,
    ): BodyPreview {
        if (this == null) return BodyPreview.Absent
        val previewSlice = copyOf(minOf(size, limit))
        // No content-type (e.g. a mock rule that doesn't set one) shouldn't force a UTF-8-decodable
        // body into the binary placeholder -- fall back to sniffing the bytes themselves. A declared,
        // non-textual content-type is still trusted as-is.
        val decodable = runCatching { previewSlice.decodeToString(throwOnInvalidSequence = true) }.isSuccess
        val decodableFallback = contentType.isNullOrBlank() && decodable
        if (!contentType.isTextual() && !decodableFallback) return BodyPreview.Binary(size.toLong(), size > limit)
        val value = previewSlice.decodeToString()
        val redacted = redaction.redactText(value, limit)
        return BodyPreview.Text(redacted.encodeToByteArray().copyOfAtMost(limit).decodeToString(), size > limit)
    }

    private fun String?.isTextual(): Boolean = isTextualContentType(this)

    private fun NetworkTimingPhases.nonNegative(): NetworkTimingPhases =
        copy(
            dnsMs = dnsMs?.coerceAtLeast(0),
            connectMs = connectMs?.coerceAtLeast(0),
            tlsMs = tlsMs?.coerceAtLeast(0),
            sendMs = sendMs?.coerceAtLeast(0),
            waitMs = waitMs?.coerceAtLeast(0),
            receiveMs = receiveMs?.coerceAtLeast(0),
        )

    private fun NetworkCapture.constrainedTo(maxBytes: Int): NetworkCapture {
        if (estimatedSizeBytes() <= maxBytes) return this
        var constrainedResponse = response?.withoutBodyForTotalLimit()
        var constrainedRequest = request
        var constrained = NetworkCapture(constrainedRequest, constrainedResponse)
        if (constrained.estimatedSizeBytes() <= maxBytes) return constrained

        constrainedRequest = constrainedRequest.withoutBodyForTotalLimit()
        constrained = NetworkCapture(constrainedRequest, constrainedResponse)
        if (constrained.estimatedSizeBytes() <= maxBytes) return constrained

        constrainedResponse = constrainedResponse?.withoutHeaders()
        constrained = NetworkCapture(constrainedRequest, constrainedResponse)
        if (constrained.estimatedSizeBytes() <= maxBytes) return constrained

        constrainedRequest = constrainedRequest.withoutHeaders()
        constrained = NetworkCapture(constrainedRequest, constrainedResponse)
        if (constrained.estimatedSizeBytes() <= maxBytes) return constrained

        constrainedRequest = constrainedRequest.withoutQuery()
        return NetworkCapture(constrainedRequest, constrainedResponse)
    }

    internal fun attachmentPayloads(
        transactionId: String,
        request: NetworkRequestInput,
        response: NetworkResponseInput?,
        capture: NetworkCapture,
    ): List<NetworkAttachmentPayload> {
        var remainingBytes = (limits.totalCaptureBytes - capture.estimatedSizeBytes()).coerceAtLeast(0)
        if (remainingBytes == 0) return emptyList()
        return buildList {
            request.body
                ?.takeIf {
                    !request.contentType.isTextual() ||
                        it.size > limits.requestBodyPreviewBytes ||
                        (request.metadata.bodyLength ?: it.size.toLong()) > limits.requestBodyPreviewBytes
                }?.let { body ->
                    val maxBytes = minOf(NetworkAttachmentPayload.MAX_BYTES, remainingBytes)
                    if (maxBytes > 0) {
                        val bytes = body.redactedAttachmentBytes(maxBytes)
                        if (bytes.isNotEmpty()) {
                            add(
                                NetworkAttachmentPayload(
                                    transactionId = transactionId,
                                    role = NetworkAttachmentRole.REQUEST,
                                    contentType = request.contentType ?: "application/octet-stream",
                                    bytes = bytes,
                                    originalLength = request.metadata.bodyLength ?: body.size.toLong(),
                                    truncated =
                                        (request.metadata.bodyLength ?: body.size.toLong()) >
                                            minOf(body.size, maxBytes),
                                ),
                            )
                            remainingBytes -= bytes.size
                        }
                    }
                }
            response
                ?.body
                ?.takeIf {
                    !response.contentType.isTextual() ||
                        it.size > limits.responseBodyPreviewBytes ||
                        (response.metadata.bodyLength ?: it.size.toLong()) > limits.responseBodyPreviewBytes
                }?.let { body ->
                    val maxBytes = minOf(NetworkAttachmentPayload.MAX_BYTES, remainingBytes)
                    if (maxBytes > 0) {
                        val bytes = body.redactedAttachmentBytes(maxBytes)
                        if (bytes.isNotEmpty()) {
                            add(
                                NetworkAttachmentPayload(
                                    transactionId = transactionId,
                                    role = NetworkAttachmentRole.RESPONSE,
                                    contentType = response.contentType ?: "application/octet-stream",
                                    bytes = bytes,
                                    originalLength = response.metadata.bodyLength ?: body.size.toLong(),
                                    truncated =
                                        (response.metadata.bodyLength ?: body.size.toLong()) >
                                            minOf(body.size, maxBytes),
                                ),
                            )
                        }
                    }
                }
        }
    }

    private fun ByteArray.redactedAttachmentBytes(maxBytes: Int): ByteArray {
        val boundedInput = copyOf(minOf(size, maxBytes))
        val redacted = redaction.redactText(boundedInput.decodeToString(), maxBytes).encodeToByteArray()
        return redacted.copyOfAtMost(maxBytes)
    }

    private companion object {
        const val MAX_SCHEME_CHARS = 32
        const val MAX_HOST_CHARS = 1_024
        const val MAX_PATH_CHARS = 64 * 1024
        const val MAX_QUERY_FIELDS = 100
        const val MAX_QUERY_FIELD_CHARS = 16 * 1024
        const val MAX_THREAD_NAME_CHARS = 256
        const val MAX_EXCEPTION_CLASS_CHARS = 512
        const val MAX_TAG_COUNT = 100
        const val MAX_TAG_CHARS = 16 * 1024
    }
}

/** Which side of an HTTP exchange owns a persisted payload attachment. */
enum class NetworkAttachmentRole {
    REQUEST,
    RESPONSE,
}

/**
 * A bounded body copy that has already passed through capture-time redaction. The byte array
 * returned by [bytes] is defensive so an integration cannot mutate another sink's payload.
 */
class NetworkAttachmentPayload(
    val transactionId: String,
    val role: NetworkAttachmentRole,
    val contentType: String,
    bytes: ByteArray,
    val originalLength: Long,
    val truncated: Boolean,
) {
    private val content = bytes.copyOf()

    val bytes: ByteArray
        get() = content.copyOf()

    var sessionId: String? = null
        private set

    fun withSessionId(value: String?): NetworkAttachmentPayload = apply { sessionId = value }

    init {
        require(transactionId.isNotBlank()) { "transactionId must not be blank" }
        require(contentType.isNotBlank()) { "contentType must not be blank" }
        require(content.size <= MAX_BYTES) { "attachment exceeds $MAX_BYTES bytes" }
        require(originalLength >= 0) { "originalLength must not be negative" }
    }

    companion object {
        const val MAX_BYTES: Int = 2 * 1024 * 1024
    }
}

/** Persists already-redacted payloads on the recorder's background worker. */
fun interface NetworkAttachmentSink {
    fun write(payload: NetworkAttachmentPayload): String?
}

data class NetworkCaptureLimits(
    val requestBodyPreviewBytes: Int = 256 * 1024,
    val responseBodyPreviewBytes: Int = 512 * 1024,
    val maxHeaderCount: Int = 100,
    val maxHeaderValueChars: Int = 16 * 1024,
) {
    var totalCaptureBytes: Int = DEFAULT_TOTAL_CAPTURE_BYTES
        private set

    init {
        require(requestBodyPreviewBytes >= 0) { "requestBodyPreviewBytes must not be negative" }
        require(responseBodyPreviewBytes >= 0) { "responseBodyPreviewBytes must not be negative" }
        require(maxHeaderCount >= 0) { "maxHeaderCount must not be negative" }
        require(maxHeaderValueChars >= 0) { "maxHeaderValueChars must not be negative" }
    }

    /**
     * Additive constructor retaining the original four-field data-class ABI while permitting an
     * aggregate bound in Kotlin source.
     */
    constructor(
        requestBodyPreviewBytes: Int = 256 * 1024,
        responseBodyPreviewBytes: Int = 512 * 1024,
        maxHeaderCount: Int = 100,
        maxHeaderValueChars: Int = 16 * 1024,
        totalCaptureBytes: Int,
    ) : this(requestBodyPreviewBytes, responseBodyPreviewBytes, maxHeaderCount, maxHeaderValueChars) {
        require(totalCaptureBytes > 0) { "totalCaptureBytes must be positive" }
        this.totalCaptureBytes = totalCaptureBytes
    }

    fun withTotalCaptureBytes(value: Int): NetworkCaptureLimits =
        NetworkCaptureLimits(
            requestBodyPreviewBytes,
            responseBodyPreviewBytes,
            maxHeaderCount,
            maxHeaderValueChars,
            value,
        )

    companion object {
        const val DEFAULT_TOTAL_CAPTURE_BYTES: Int = 3 * 1024 * 1024
    }
}

private fun BodyPreview.metadata(
    declaredLength: Long?,
    omittedReason: String?,
): CaptureBodyMetadata =
    CaptureBodyMetadata(
        declaredLength = declaredLength,
        capturedBytes =
            when (this) {
                is BodyPreview.Text -> value.encodeToByteArray().size
                is BodyPreview.Binary, BodyPreview.Absent -> 0
            },
        truncated =
            when (this) {
                is BodyPreview.Text -> truncated
                is BodyPreview.Binary -> declaredLength != null && declaredLength > 0
                BodyPreview.Absent -> declaredLength != null && declaredLength > 0
            },
        omittedReason =
            omittedReason ?: when {
                this is BodyPreview.Binary && (declaredLength ?: 0) > 0 -> "binary-metadata-only"
                this is BodyPreview.Absent && (declaredLength ?: 0) > 0 -> "metadata-only"
                else -> null
            },
    )

private fun CapturedRequest.estimatedSizeBytes(): Int =
    method.byteSize() +
        url.scheme.byteSize() +
        url.host.byteSize() +
        url.path.byteSize() +
        url.query.entries.sumOf { it.key.byteSize() + it.value.byteSize() } +
        headers.entries.sumOf { it.key.byteSize() + it.value.byteSize() } +
        body.estimatedSizeBytes() +
        contentType.orEmpty().byteSize() +
        correlationId.orEmpty().byteSize() +
        pluginId.byteSize() +
        metadata.threadName.orEmpty().byteSize() +
        metadata.tags.entries.sumOf { it.key.byteSize() + it.value.byteSize() } +
        metadata.body.omittedReason
            .orEmpty()
            .byteSize()

private fun CapturedResponse.estimatedSizeBytes(): Int =
    headers.entries.sumOf { it.key.byteSize() + it.value.byteSize() } +
        body.estimatedSizeBytes() +
        contentType.orEmpty().byteSize() +
        protocol.orEmpty().byteSize() +
        error.orEmpty().byteSize() +
        metadata.exceptionClass.orEmpty().byteSize() +
        metadata.body.omittedReason
            .orEmpty()
            .byteSize()

private fun BodyPreview.estimatedSizeBytes(): Int =
    when (this) {
        is BodyPreview.Text -> value.byteSize()
        is BodyPreview.Binary, BodyPreview.Absent -> 0
    }

private fun String.byteSize(): Int = encodeToByteArray().size

private fun CapturedRequest.withoutBodyForTotalLimit(): CapturedRequest =
    CapturedRequest(method, url, headers, BodyPreview.Absent, contentType, correlationId, pluginId).also {
        it.metadata =
            metadata.copy(
                body =
                    metadata.body.copy(
                        capturedBytes = 0,
                        truncated = (metadata.body.declaredLength ?: 0) > 0,
                        omittedReason = "total-event-limit",
                    ),
            )
    }

private fun CapturedResponse.withoutBodyForTotalLimit(): CapturedResponse =
    CapturedResponse(statusCode, headers, BodyPreview.Absent, contentType, protocol, error).also {
        it.metadata =
            metadata.copy(
                body =
                    metadata.body.copy(
                        capturedBytes = 0,
                        truncated = (metadata.body.declaredLength ?: 0) > 0,
                        omittedReason = "total-event-limit",
                    ),
            )
    }

private fun CapturedRequest.withoutHeaders(): CapturedRequest =
    CapturedRequest(method, url, emptyMap(), body, contentType, correlationId, pluginId).also { it.metadata = metadata }

private fun CapturedResponse.withoutHeaders(): CapturedResponse =
    CapturedResponse(statusCode, emptyMap(), body, contentType, protocol, error).also { it.metadata = metadata }

private fun CapturedRequest.withoutQuery(): CapturedRequest =
    CapturedRequest(method, url.copy(query = emptyMap()), headers, body, contentType, correlationId, pluginId).also {
        it.metadata = metadata
    }

/** Generic manual recorder. Capture failures are swallowed so host network behavior is untouched. */
class NetworkRecorder(
    private val factory: NetworkCaptureFactory,
    private val sink: (NetworkCapture) -> Unit,
) {
    fun record(
        request: NetworkRequestInput,
        response: NetworkResponseInput?,
    ) {
        runCatching { sink(factory.capture(request, response)) }
    }
}

/**
 * Consulted before a transaction is queued for capture, so an excluded request is never redacted,
 * serialized, persisted, or exported. Implementations must be cheap and side-effect free: they run
 * on the caller's (host application) thread.
 */
fun interface NetworkCaptureGate {
    fun allowsCapture(
        method: String,
        url: String,
    ): Boolean
}

/**
 * Bridges redacted capture to the bounded transaction store without affecting host behavior.
 * When [enabled] is false, [record] returns immediately without building a capture or touching
 * [store] — the disabled build never redacts, serializes, or stores a payload.
 * Processing (URL parsing, redaction, and store persistence) is offloaded asynchronously from
 * caller threads via a bounded queue (drop-oldest strategy on overflow).
 */
@Suppress("LongParameterList") // All test/tuning seams (queue size, byte budget, executor, id/session providers).
class NetworkTransactionRecorder(
    private val factory: NetworkCaptureFactory,
    private val store: NetworkTransactionStore,
    private val enabled: Boolean = true,
    private val idProvider: () -> String = { UUID.randomUUID().toString() },
    private val maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE,
    private val executor: Executor = DEFAULT_EXECUTOR,
    private val maxQueuedBytes: Long = DEFAULT_MAX_QUEUED_BYTES,
) {
    private val droppedCount = AtomicLong(0)

    /**
     * Mirrors [io.devconsole.core.EventBatchWriter]'s dual count+byte queue budget: a raw peeked
     * response body can be ~512KB and a captured request body up to ~256KB, so the count-only bound
     * ([maxQueueSize], historically 1000) admits a multi-hundred-MB transient worst case on its own.
     * Best-effort under concurrent producers -- see [record]'s evict loop -- same tradeoff the
     * pre-existing count-based drop-oldest logic already made.
     */
    private val queuedBytes = AtomicLong(0)

    @Volatile
    private var attachmentSink: NetworkAttachmentSink? = null

    @Volatile private var sessionIdProvider: () -> String? = { null }

    @Volatile private var captureGate: NetworkCaptureGate? = null

    /** Lazy so a disabled recorder never allocates the buffer; [record] returns before touching it. */
    private val queue: LinkedBlockingQueue<PendingRecord> by lazy { LinkedBlockingQueue(maxQueueSize) }

    @Volatile
    private var isProcessing = false

    fun droppedCount(): Long = droppedCount.get()

    fun withAttachmentSink(sink: NetworkAttachmentSink): NetworkTransactionRecorder = apply { attachmentSink = sink }

    fun withSessionIdProvider(provider: () -> String?): NetworkTransactionRecorder =
        apply {
            sessionIdProvider =
                provider
        }

    /** Installs the capture-exclusion gate; without one every enabled recording is captured. */
    fun withCaptureGate(gate: NetworkCaptureGate): NetworkTransactionRecorder = apply { captureGate = gate }

    // Each early return below is a distinct drop reason (gate rejection, single item over budget,
    // still-full queue after one eviction) that reads far more clearly as a guard clause than folded
    // into one boolean expression.
    @Suppress("ReturnCount")
    fun record(
        request: NetworkRequestInput,
        response: NetworkResponseInput?,
        startedAtEpochMs: Long,
        completedAtEpochMs: Long?,
    ) {
        val gate = captureGate
        // A gate failure must never suppress capture silently, so an exception means "capture".
        val allowed =
            enabled &&
                (gate == null || runCatching { gate.allowsCapture(request.method, request.url) }.getOrDefault(true))
        if (!allowed) return
        val item =
            PendingRecord(
                request,
                response,
                startedAtEpochMs,
                completedAtEpochMs,
                runCatching(sessionIdProvider).getOrNull(),
            )
        val itemBytes = item.estimatedSizeBytes()
        if (itemBytes > maxQueuedBytes) {
            // Bigger than the entire budget by itself -- could never fit even in an empty queue.
            droppedCount.incrementAndGet()
            return
        }
        if (!queue.offer(item)) {
            queue.poll()?.let { queuedBytes.addAndGet(-it.estimatedSizeBytes()) }
            droppedCount.incrementAndGet()
            if (!queue.offer(item)) {
                droppedCount.incrementAndGet()
                return
            }
        }
        queuedBytes.addAndGet(itemBytes)
        while (queuedBytes.get() > maxQueuedBytes) {
            val evicted = queue.poll() ?: break
            queuedBytes.addAndGet(-evicted.estimatedSizeBytes())
            droppedCount.incrementAndGet()
        }
        scheduleProcessing()
    }

    private fun scheduleProcessing() {
        if (isProcessing) return
        synchronized(this) {
            if (isProcessing) return
            isProcessing = true
        }
        executor.execute {
            try {
                while (true) {
                    val item = queue.poll() ?: break
                    queuedBytes.addAndGet(-item.estimatedSizeBytes())
                    runCatching {
                        val transactionId = idProvider()
                        val capture = factory.capture(item.request, item.response)
                        attachmentSink?.let { sink ->
                            factory
                                .attachmentPayloads(transactionId, item.request, item.response, capture)
                                .onEach { payload -> payload.withSessionId(item.sessionId) }
                                .forEach { payload ->
                                    val attachmentId =
                                        runCatching { sink.write(payload) }
                                            .getOrNull()
                                            ?.takeIf(String::isNotBlank)
                                    if (attachmentId != null) capture.attach(payload, attachmentId)
                                }
                        }
                        store.record(
                            NetworkTransaction(
                                id = transactionId,
                                startedAtEpochMs = item.startedAtEpochMs,
                                completedAtEpochMs = item.completedAtEpochMs,
                                capture = capture,
                            ).withSessionId(item.sessionId),
                        )
                    }
                }
            } finally {
                synchronized(this) {
                    isProcessing = false
                }
                if (!queue.isEmpty()) {
                    scheduleProcessing()
                }
            }
        }
    }

    private data class PendingRecord(
        val request: NetworkRequestInput,
        val response: NetworkResponseInput?,
        val startedAtEpochMs: Long,
        val completedAtEpochMs: Long?,
        val sessionId: String?,
    )

    /** Rough estimate of what [item] holds in memory while queued; body bytes dominate. */
    private fun PendingRecord.estimatedSizeBytes(): Long =
        PENDING_RECORD_BASE_OVERHEAD_BYTES +
            request.url.byteSize() +
            request.headers.entries.sumOf { it.key.byteSize() + it.value.byteSize() } +
            (request.body?.size ?: 0) +
            (response?.headers?.entries?.sumOf { it.key.byteSize() + it.value.byteSize() } ?: 0) +
            (response?.body?.size ?: 0)

    companion object {
        const val DEFAULT_MAX_QUEUE_SIZE = 1000

        /** e.g. a ~512KB peeked response body plus a ~256KB captured request body, times a healthy margin. */
        const val DEFAULT_MAX_QUEUED_BYTES: Long = 32L * 1024L * 1024L

        private const val PENDING_RECORD_BASE_OVERHEAD_BYTES = 128L

        /**
         * Indirection so that merely constructing a recorder never starts a thread. A default
         * argument is evaluated at construction, so referencing the pool directly would spawn a
         * worker even in a disabled (no-op) build that can never enqueue anything. The pool is
         * created on the first [Executor.execute], which only an enabled recorder reaches.
         */
        private val DEFAULT_EXECUTOR = Executor { runnable -> sharedPool.execute(runnable) }

        private val sharedPool: ExecutorService by lazy {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "devconsole-network-recorder").apply { isDaemon = true }
            }
        }
    }
}

private fun NetworkCapture.attach(
    payload: NetworkAttachmentPayload,
    attachmentId: String,
) {
    when (payload.role) {
        NetworkAttachmentRole.REQUEST -> {
            request.attachmentId = attachmentId
            request.metadata =
                request.metadata.copy(
                    body =
                        request.metadata.body.copy(
                            capturedBytes = maxOf(request.metadata.body.capturedBytes, payload.bytes.size),
                            truncated = payload.truncated,
                            omittedReason = if (payload.truncated) "attachment-truncated" else null,
                        ),
                )
        }
        NetworkAttachmentRole.RESPONSE -> {
            response?.let { captured ->
                captured.attachmentId = attachmentId
                captured.metadata =
                    captured.metadata.copy(
                        body =
                            captured.metadata.body.copy(
                                capturedBytes = maxOf(captured.metadata.body.capturedBytes, payload.bytes.size),
                                truncated = payload.truncated,
                                omittedReason = if (payload.truncated) "attachment-truncated" else null,
                            ),
                    )
            }
        }
    }
}

private fun ByteArray.copyOfAtMost(maxBytes: Int): ByteArray = if (size <= maxBytes) this else copyOf(maxBytes)
