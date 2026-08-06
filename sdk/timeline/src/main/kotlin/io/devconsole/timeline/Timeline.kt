@file:Suppress("ReturnCount") // Guard-clause early returns are the clearest form for these cursor decode checks.

package io.devconsole.timeline

import io.devconsole.storage.api.StoredEvent
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

enum class TimelineSort { ASC, DESC }

data class TimelineQuery(
    val limit: Int = DEFAULT_PAGE_LIMIT,
    val cursor: String? = null,
    val pluginIds: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val severities: Set<Int> = emptySet(),
    val correlationId: String? = null,
    val query: String? = null,
    val sort: TimelineSort = TimelineSort.ASC,
) {
    var fromEpochMs: Long? = null
        private set

    var toEpochMs: Long? = null
        private set

    init {
        require(limit in 1..MAX_PAGE_LIMIT) { "limit must be between 1 and $MAX_PAGE_LIMIT" }
    }

    fun withTimeRange(
        fromEpochMs: Long? = null,
        toEpochMs: Long? = null,
    ): TimelineQuery {
        require(fromEpochMs == null || fromEpochMs >= 0) { "fromEpochMs must not be negative" }
        require(toEpochMs == null || toEpochMs >= 0) { "toEpochMs must not be negative" }
        require(fromEpochMs == null || toEpochMs == null || fromEpochMs <= toEpochMs) {
            "fromEpochMs must not be after toEpochMs"
        }
        return copy().also {
            it.fromEpochMs = fromEpochMs
            it.toEpochMs = toEpochMs
        }
    }

    fun withCursor(value: String?): TimelineQuery =
        copy(cursor = value).also {
            it.fromEpochMs = fromEpochMs
            it.toEpochMs = toEpochMs
        }

    internal fun scope(): String =
        listOf(
            sort.name,
            pluginIds.sorted().joinToString(","),
            types.sorted().joinToString(","),
            severities.sorted().joinToString(","),
            correlationId.orEmpty(),
            query.orEmpty().lowercase(),
            fromEpochMs?.toString().orEmpty(),
            toEpochMs?.toString().orEmpty(),
        ).joinToString("|")

    companion object {
        const val DEFAULT_PAGE_LIMIT = 100
        const val MAX_PAGE_LIMIT = 500
    }
}

sealed interface TimelinePage {
    data class Success(
        val events: List<StoredEvent>,
        val nextCursor: String?,
        val hasMore: Boolean,
    ) : TimelinePage

    data object InvalidCursor : TimelinePage
}

interface Timeline {
    fun page(query: TimelineQuery): TimelinePage

    fun contains(eventId: String): Boolean
}

/** Optional mutable sink used by server-side system events such as audited flag changes. */
interface TimelineAppender {
    fun append(event: StoredEvent)
}

/** Reference timeline query implementation used by tests and by the future Room adapter. */
class InMemoryTimeline(
    initialEvents: List<StoredEvent>,
    private val cursors: CursorCodec,
) : Timeline,
    TimelineAppender {
    private val events = initialEvents.toMutableList()

    // Chained plugin/type/severity/correlation/query/time-range/cursor filters inflate the McCabe
    // count; splitting them would fragment one cohesive paging pass across multiple functions.
    @Suppress("CyclomaticComplexMethod")
    @Synchronized
    override fun page(query: TimelineQuery): TimelinePage {
        val cursor = query.cursor?.let { cursors.decode(it, query.scope()) } ?: CursorDecodeResult.Missing
        if (cursor is CursorDecodeResult.Invalid) return TimelinePage.InvalidCursor
        val marker = (cursor as? CursorDecodeResult.Valid)?.marker
        val comparator =
            compareBy<StoredEvent> { it.monoTimeNs }
                .thenBy { it.sequence }
                .thenBy { it.id }
        val matching =
            events
                .asSequence()
                .filter { event -> query.pluginIds.isEmpty() || event.pluginId in query.pluginIds }
                .filter { event -> query.types.isEmpty() || event.type in query.types }
                .filter { event -> query.severities.isEmpty() || event.severity in query.severities }
                .filter { event -> query.correlationId == null || event.correlationId == query.correlationId }
                .filter { event -> query.query == null || event.summary.contains(query.query, ignoreCase = true) }
                .filter { event -> query.fromEpochMs == null || event.wallTimeMs >= query.fromEpochMs!! }
                .filter { event -> query.toEpochMs == null || event.wallTimeMs <= query.toEpochMs!! }
                .sortedWith(if (query.sort == TimelineSort.ASC) comparator else comparator.reversed())
                .filter { event -> marker == null || event.isAfter(marker, query.sort) }
                .take(query.limit + 1)
                .toList()
        val pageEvents = matching.take(query.limit)
        val hasMore = matching.size > query.limit
        val nextCursor =
            if (hasMore) {
                pageEvents.lastOrNull()?.let {
                    cursors.encode(
                        CursorMarker(it.sequence, it.id).withMonotonicNanos(it.monoTimeNs),
                        query.scope(),
                    )
                }
            } else {
                null
            }
        return TimelinePage.Success(pageEvents, nextCursor, hasMore)
    }

    @Synchronized
    override fun contains(eventId: String): Boolean = events.any { it.id == eventId }

    @Synchronized
    override fun append(event: StoredEvent) {
        events += event
    }

    /** Atomically replaces hydrated history when the active app-run changes. */
    @Synchronized
    fun replace(events: List<StoredEvent>) {
        this.events.clear()
        this.events.addAll(events)
    }

    /** Replaces one app-run while retaining events appended for that same run during hydration. */
    @Synchronized
    fun replaceSession(
        sessionId: String,
        persisted: List<StoredEvent>,
    ) {
        val merged = LinkedHashMap<String, StoredEvent>()
        persisted.filter { it.sessionId == sessionId }.forEach { merged[it.id] = it }
        events.filter { it.sessionId == sessionId }.forEach { merged.putIfAbsent(it.id, it) }
        events.clear()
        events.addAll(merged.values)
    }

    private fun StoredEvent.isAfter(
        marker: CursorMarker,
        sort: TimelineSort,
    ): Boolean {
        val compared =
            if (marker.monotonicNanos == LEGACY_MONOTONIC_NANOS) {
                sequence.compareTo(marker.sequence).takeIf { it != 0 } ?: id.compareTo(marker.id)
            } else {
                monoTimeNs
                    .compareTo(marker.monotonicNanos)
                    .takeIf { it != 0 }
                    ?: sequence.compareTo(marker.sequence).takeIf { it != 0 }
                    ?: id.compareTo(marker.id)
            }
        return if (sort == TimelineSort.ASC) compared > 0 else compared < 0
    }

    private companion object {
        const val LEGACY_MONOTONIC_NANOS = Long.MIN_VALUE
    }
}

data class CursorMarker(
    val sequence: Long,
    val id: String,
) {
    var monotonicNanos: Long = Long.MIN_VALUE
        private set

    fun withMonotonicNanos(value: Long): CursorMarker = copy().also { it.monotonicNanos = value }
}

sealed interface CursorDecodeResult {
    data object Missing : CursorDecodeResult

    data class Valid(
        val marker: CursorMarker,
    ) : CursorDecodeResult

    data object Invalid : CursorDecodeResult
}

/** HMAC-signed, filter-scoped cursor codec; callers treat its output as opaque. */
class CursorCodec(
    secret: ByteArray,
) {
    private val key = secret.copyOf()

    init {
        require(key.size >= 16) { "cursor secret must be at least 128 bits" }
    }

    fun encode(
        marker: CursorMarker,
        scope: String,
    ): String {
        val payload =
            listOf(
                CURSOR_VERSION,
                marker.monotonicNanos.toString(),
                marker.sequence.toString(),
                encodePart(marker.id),
                encodePart(scope),
            ).joinToString(".")
        return "$payload.${sign(payload)}"
    }

    fun decode(
        cursor: String,
        expectedScope: String,
    ): CursorDecodeResult {
        val parts = cursor.split('.')
        return when {
            parts.size == CURRENT_CURSOR_PART_COUNT && parts[0] == CURSOR_VERSION -> decodeCurrent(parts, expectedScope)
            parts.size == 4 -> decodeLegacy(parts, expectedScope)
            else -> CursorDecodeResult.Invalid
        }
    }

    private fun decodeCurrent(
        parts: List<String>,
        expectedScope: String,
    ): CursorDecodeResult {
        val payload = parts.take(CURRENT_CURSOR_SIGNATURE_INDEX).joinToString(".")
        if (!constantTimeEquals(sign(payload), parts[CURRENT_CURSOR_SIGNATURE_INDEX])) return CursorDecodeResult.Invalid
        val monotonicNanos = parts[1].toLongOrNull() ?: return CursorDecodeResult.Invalid
        val sequence = parts[2].toLongOrNull() ?: return CursorDecodeResult.Invalid
        val id = decodePart(parts[3]) ?: return CursorDecodeResult.Invalid
        val scope = decodePart(parts[4]) ?: return CursorDecodeResult.Invalid
        return if (scope == expectedScope) {
            CursorDecodeResult.Valid(CursorMarker(sequence, id).withMonotonicNanos(monotonicNanos))
        } else {
            CursorDecodeResult.Invalid
        }
    }

    private fun decodeLegacy(
        parts: List<String>,
        expectedScope: String,
    ): CursorDecodeResult {
        val payload = parts.take(3).joinToString(".")
        if (!constantTimeEquals(sign(payload), parts[3])) return CursorDecodeResult.Invalid
        val sequence = parts[0].toLongOrNull() ?: return CursorDecodeResult.Invalid
        val id = decodePart(parts[1]) ?: return CursorDecodeResult.Invalid
        val scope = decodePart(parts[2]) ?: return CursorDecodeResult.Invalid
        return if (scope ==
            expectedScope
        ) {
            CursorDecodeResult.Valid(CursorMarker(sequence, id))
        } else {
            CursorDecodeResult.Invalid
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun sign(payload: String): String =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(doFinal(payload.encodeToByteArray()))
        }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encodePart(value: String): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(value.encodeToByteArray())

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodePart(value: String): String? =
        runCatching {
            Base64.UrlSafe
                .withPadding(Base64.PaddingOption.ABSENT)
                .decode(value)
                .decodeToString()
        }.getOrNull()

    private fun constantTimeEquals(
        left: String,
        right: String,
    ): Boolean = java.security.MessageDigest.isEqual(left.encodeToByteArray(), right.encodeToByteArray())

    private companion object {
        const val CURSOR_VERSION = "v2"

        // version.monotonicNanos.sequence.id.scope.signature
        const val CURRENT_CURSOR_PART_COUNT = 6

        // Index of both the signature part and the number of payload parts preceding it.
        const val CURRENT_CURSOR_SIGNATURE_INDEX = 5
    }
}
