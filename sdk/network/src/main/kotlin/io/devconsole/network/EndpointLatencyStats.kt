/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole.network

import kotlin.math.ceil

data class EndpointLatency(
    val method: String,
    val host: String,
    val pathTemplate: String,
    val count: Int,
    val errorCount: Int,
    val p50Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
    val maxMs: Long,
)

/** Per-endpoint latency percentiles over completed transactions, with numeric/UUID/opaque-id path segments folded. */
object EndpointLatencyStats {
    private const val DEFAULT_LIMIT = 100
    private const val HTTP_ERROR_STATUS = 400
    private const val P50 = 0.50
    private const val P95 = 0.95
    private const val P99 = 0.99
    private const val OPAQUE_ID_MIN_LENGTH = 16

    private val UUID_REGEX =
        Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        )

    fun from(
        transactions: List<NetworkTransaction>,
        limit: Int = DEFAULT_LIMIT,
    ): List<EndpointLatency> =
        if (limit <= 0) {
            emptyList()
        } else {
            transactions
                .filter { it.completedAtEpochMs != null }
                .groupBy(::endpointKey)
                .map { (key, group) -> latencyOf(key, group) }
                .sortedWith(
                    compareByDescending<EndpointLatency> { it.p95Ms }
                        .thenByDescending { it.count }
                        .thenBy { it.host }
                        .thenBy { it.pathTemplate }
                        .thenBy { it.method },
                ).take(limit)
        }

    private fun endpointKey(transaction: NetworkTransaction): EndpointKey =
        EndpointKey(
            method =
                transaction.capture.request.method
                    .uppercase(),
            host =
                transaction.capture.request.url.host
                    .lowercase(),
            pathTemplate = templatePath(transaction.capture.request.url.path),
        )

    private fun latencyOf(
        key: EndpointKey,
        group: List<NetworkTransaction>,
    ): EndpointLatency {
        val durations =
            group
                .map { tx -> (checkNotNull(tx.completedAtEpochMs) - tx.startedAtEpochMs).coerceAtLeast(0L) }
                .sorted()
        val errors =
            group.count { tx ->
                val response = tx.capture.response
                response == null || response.error != null || response.statusCode >= HTTP_ERROR_STATUS
            }
        return EndpointLatency(
            method = key.method,
            host = key.host,
            pathTemplate = key.pathTemplate,
            count = group.size,
            errorCount = errors,
            p50Ms = nearestRank(durations, P50),
            p95Ms = nearestRank(durations, P95),
            p99Ms = nearestRank(durations, P99),
            maxMs = durations.last(),
        )
    }

    internal fun templatePath(rawPath: String): String {
        val path = rawPath.ifBlank { "/" }
        val normalized = if (path.startsWith('/')) path else "/$path"
        return normalized
            .split('/')
            .mapIndexed { index, segment ->
                if (index > 0 && segment.isNotEmpty() && isDynamicSegment(segment)) "{id}" else segment
            }.joinToString("/")
    }

    private fun isDynamicSegment(segment: String): Boolean =
        segment.all(Char::isDigit) || UUID_REGEX.matches(segment) || isOpaqueId(segment)

    private fun isOpaqueId(segment: String): Boolean =
        segment.length >= OPAQUE_ID_MIN_LENGTH &&
            segment.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' } &&
            segment.any(Char::isDigit)

    private fun nearestRank(
        sortedDurations: List<Long>,
        quantile: Double,
    ): Long {
        val n = sortedDurations.size
        val index = (ceil(quantile * n).toInt() - 1).coerceIn(0, n - 1)
        return sortedDurations[index]
    }

    private data class EndpointKey(
        val method: String,
        val host: String,
        val pathTemplate: String,
    )
}
