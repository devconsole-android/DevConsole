package io.devconsole.network

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class NetworkTransaction(
    val id: String,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
    val capture: NetworkCapture,
) {
    /** Captured when recording is accepted, so async work cannot cross app-run boundaries. */
    var sessionId: String? = null
        private set

    fun withSessionId(value: String?): NetworkTransaction = apply { sessionId = value }

    val durationMs: Long? get() = completedAtEpochMs?.minus(startedAtEpochMs)?.coerceAtLeast(0)
}

data class NetworkTransactionQuery(
    val limit: Int = DEFAULT_PAGE_LIMIT,
    val cursor: String? = null,
    val methods: Set<String> = emptySet(),
    val hosts: Set<String> = emptySet(),
    val statuses: Set<Int> = emptySet(),
    val correlationId: String? = null,
) {
    var filters: NetworkTransactionFilters = NetworkTransactionFilters()
        private set

    fun withFilters(filters: NetworkTransactionFilters): NetworkTransactionQuery = copy().also { it.filters = filters }

    internal fun scope(): String =
        listOf(
            methods.map(String::uppercase).sorted().joinToString(","),
            hosts.map(String::lowercase).sorted().joinToString(","),
            statuses.sorted().joinToString(","),
            correlationId.orEmpty(),
            filters.scope(),
        ).joinToString("|")

    companion object {
        const val DEFAULT_PAGE_LIMIT = 100
        const val MAX_PAGE_LIMIT = 500
    }
}

data class NetworkTransactionFilters(
    val fromEpochMs: Long? = null,
    val toEpochMs: Long? = null,
    val paths: Set<String> = emptySet(),
    val contentTypes: Set<String> = emptySet(),
    val minDurationMs: Long? = null,
    val maxDurationMs: Long? = null,
    val statusFrom: Int? = null,
    val statusTo: Int? = null,
    val hasError: Boolean? = null,
    val tags: Map<String, String> = emptyMap(),
    val query: String? = null,
) {
    internal fun scope(): String =
        listOf(
            fromEpochMs?.toString().orEmpty(),
            toEpochMs?.toString().orEmpty(),
            paths.sorted().joinToString(","),
            contentTypes.map(String::lowercase).sorted().joinToString(","),
            minDurationMs?.toString().orEmpty(),
            maxDurationMs?.toString().orEmpty(),
            statusFrom?.toString().orEmpty(),
            statusTo?.toString().orEmpty(),
            hasError?.toString().orEmpty(),
            tags.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" },
            query.orEmpty().lowercase(),
        ).joinToString(";")
}

data class NetworkTransactionPage(
    val transactions: List<NetworkTransaction>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val invalidCursor: Boolean = false,
)

interface NetworkTransactionStore {
    fun record(transaction: NetworkTransaction)

    fun page(query: NetworkTransactionQuery): NetworkTransactionPage

    fun find(id: String): NetworkTransaction?

    fun statusDistribution(): Map<String, Int>
}

/** Bounded, thread-safe transaction store for the live network inspector. */
class InMemoryNetworkTransactionStore(
    private val cursorCodec: NetworkCursorCodec,
    private val maxTransactions: Int = DEFAULT_MAX_TRANSACTIONS,
) : NetworkTransactionStore {
    private val lock = Any()
    private val transactions = mutableListOf<NetworkTransaction>()
    private var capacity = maxTransactions
    private var byteCapacity = DEFAULT_MAX_BYTES
    private var storedBytes = 0L

    init {
        require(maxTransactions > 0) { "maxTransactions must be positive" }
    }

    fun withCapacity(maxTransactions: Int): InMemoryNetworkTransactionStore {
        require(maxTransactions > 0) { "maxTransactions must be positive" }
        synchronized(lock) {
            capacity = maxTransactions
            pruneToCapacity()
        }
        return this
    }

    fun withByteCapacity(maxBytes: Long): InMemoryNetworkTransactionStore {
        require(maxBytes > 0) { "maxBytes must be positive" }
        synchronized(lock) {
            byteCapacity = maxBytes
            pruneToCapacity()
        }
        return this
    }

    fun clear() =
        synchronized(lock) {
            transactions.clear()
            storedBytes = 0
        }

    override fun record(transaction: NetworkTransaction) =
        synchronized(lock) {
            val duplicate = transactions.firstOrNull { it.id == transaction.id }
            if (duplicate != null) {
                transactions.remove(duplicate)
                storedBytes -= duplicate.capture.estimatedSizeBytes()
            }
            transactions += transaction
            storedBytes += transaction.capture.estimatedSizeBytes()
            pruneToCapacity()
        }

    override fun page(query: NetworkTransactionQuery): NetworkTransactionPage =
        synchronized(lock) {
            if (query.limit !in
                1..NetworkTransactionQuery.MAX_PAGE_LIMIT
            ) {
                return@synchronized NetworkTransactionPage(emptyList(), null, false, true)
            }
            val offset =
                if (query.cursor == null) {
                    0
                } else {
                    cursorCodec.decode(query.cursor, query.scope())
                        ?: return@synchronized NetworkTransactionPage(emptyList(), null, false, true)
                }
            val matching =
                transactions
                    .asSequence()
                    .filter { transaction ->
                        query.methods.isEmpty() ||
                            transaction.capture.request.method
                                .uppercase() in query.methods.map(String::uppercase)
                    }.filter { transaction ->
                        query.hosts.isEmpty() ||
                            transaction.capture.request.url.host
                                .lowercase() in query.hosts.map(String::lowercase)
                    }.filter { transaction ->
                        query.statuses.isEmpty() ||
                            transaction.capture.response?.statusCode in query.statuses
                    }.filter { transaction ->
                        query.correlationId == null || transaction.capture.request.correlationId == query.correlationId
                    }.filter { transaction -> transaction.matches(query.filters) }
                    .sortedWith(
                        compareByDescending<NetworkTransaction> { it.startedAtEpochMs }.thenByDescending { it.id },
                    ).toList()
            if (offset > matching.size) return@synchronized NetworkTransactionPage(emptyList(), null, false, true)
            val page = matching.drop(offset).take(query.limit)
            val nextOffset = offset + page.size
            val hasMore = nextOffset < matching.size
            NetworkTransactionPage(
                transactions = page,
                nextCursor = if (hasMore) cursorCodec.encode(nextOffset, query.scope()) else null,
                hasMore = hasMore,
            )
        }

    override fun find(id: String): NetworkTransaction? = synchronized(lock) { transactions.firstOrNull { it.id == id } }

    override fun statusDistribution(): Map<String, Int> =
        synchronized(lock) {
            transactions
                .groupingBy { transaction ->
                    transaction.capture.response
                        ?.statusCode
                        ?.let { code -> "${code / 100}xx" } ?: "pending"
                }.eachCount()
        }

    private fun pruneToCapacity() {
        while (transactions.size > capacity || storedBytes > byteCapacity) {
            val removed = transactions.removeAt(transactions.indices.minBy { transactions[it].startedAtEpochMs })
            storedBytes -= removed.capture.estimatedSizeBytes()
        }
    }

    companion object {
        const val DEFAULT_MAX_TRANSACTIONS = 2_000
        const val DEFAULT_MAX_BYTES: Long = 8L * 1024L * 1024L
    }
}

private fun NetworkTransaction.matches(filters: NetworkTransactionFilters): Boolean {
    if (filters.fromEpochMs != null && startedAtEpochMs < filters.fromEpochMs) return false
    if (filters.toEpochMs != null && startedAtEpochMs > filters.toEpochMs) return false
    if (filters.paths.isNotEmpty() && capture.request.url.path !in filters.paths) return false
    val contentTypes = filters.contentTypes.map(String::lowercase)
    if (
        contentTypes.isNotEmpty() &&
        capture.request.contentType?.lowercase() !in contentTypes &&
        capture.response?.contentType?.lowercase() !in contentTypes
    ) {
        return false
    }
    if (filters.minDurationMs != null && durationMs?.let { it >= filters.minDurationMs } != true) return false
    if (filters.maxDurationMs != null && durationMs?.let { it <= filters.maxDurationMs } != true) return false
    val status = capture.response?.statusCode
    if (filters.statusFrom != null && status?.let { it >= filters.statusFrom } != true) return false
    if (filters.statusTo != null && status?.let { it <= filters.statusTo } != true) return false
    if (filters.hasError != null && (capture.response?.error != null) != filters.hasError) return false
    if (filters.tags.any { (name, value) -> capture.request.metadata.tags[name] != value }) return false
    if (filters.query != null && !searchableText().contains(filters.query, ignoreCase = true)) return false
    return true
}

private fun NetworkTransaction.searchableText(): String =
    buildString {
        append(capture.request.method).append(' ')
        append(capture.request.url.display).append(' ')
        append(capture.request.contentType.orEmpty()).append(' ')
        capture.request.headers.forEach { (name, value) -> append(name).append(' ').append(value).append(' ') }
        capture.request.metadata.tags
            .forEach { (name, value) -> append(name).append(' ').append(value).append(' ') }
        append(capture.response?.statusCode ?: "").append(' ')
        append(capture.response?.contentType.orEmpty()).append(' ')
        append(capture.response?.error.orEmpty())
    }

/** Signed, scope-bound cursor so a cursor cannot be reused with different filters. */
class NetworkCursorCodec(
    secret: ByteArray,
) {
    private val key = secret.copyOf()

    init {
        require(key.size >= 16) { "Cursor signing key must be at least 128 bits" }
    }

    fun encode(
        offset: Int,
        scope: String,
    ): String {
        val payload = "$offset:${scope.sha256()}".encodeToByteArray()
        return "${payload.base64Url()}.${sign(payload).base64Url()}"
    }

    fun decode(
        cursor: String,
        scope: String,
    ): Int? {
        val (encodedPayload, encodedSignature) = cursor.split('.', limit = 2).takeIf { it.size == 2 } ?: return null
        val payload = encodedPayload.base64UrlDecoded() ?: return null
        val signature = encodedSignature.base64UrlDecoded() ?: return null
        if (!MessageDigest.isEqual(sign(payload), signature)) return null
        val (rawOffset, scopeHash) =
            payload.decodeToString().split(':', limit = 2).takeIf { it.size == 2 }
                ?: return null
        return rawOffset.toIntOrNull()?.takeIf { it >= 0 && scopeHash == scope.sha256() }
    }

    private fun sign(payload: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(payload)
        }

    @OptIn(ExperimentalEncodingApi::class)
    private fun ByteArray.base64Url(): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(this)

    @OptIn(ExperimentalEncodingApi::class)
    private fun String.base64UrlDecoded(): ByteArray? =
        runCatching { Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(this) }.getOrNull()

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(encodeToByteArray()).joinToString("") {
            "%02x".format(it)
        }
}
