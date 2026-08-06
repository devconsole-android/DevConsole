package io.devconsole.core

import io.devconsole.api.EventEnvelope
import io.devconsole.storage.api.EventStore
import io.devconsole.storage.api.EventStoreWriteResult
import io.devconsole.storage.api.StoredEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bounded persistence worker. It receives only [EventEnvelope] values emitted after the redaction
 * boundary and commits 100 events or the pending batch every 250 ms.
 */
class EventBatchWriter(
    private val store: EventStore,
    private val scope: CoroutineScope,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxBatchSize: Int = DEFAULT_BATCH_SIZE,
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
    private val onDrop: (StoredEvent, EventBatchDropReason) -> Unit = { _, _ -> },
    private val onStored: suspend (List<StoredEvent>) -> Unit = {},
) {
    private val lifecycleLock = Any()
    private var queueState = QueueState(capacity)
    private var worker: Job? = null
    private var maxQueuedBytes = DEFAULT_MAX_QUEUED_BYTES
    private var pluginPolicies: Map<String, PluginOverflowPolicy> = emptyMap()

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(maxBatchSize > 0) { "maxBatchSize must be positive" }
        require(flushIntervalMs > 0) { "flushIntervalMs must be positive" }
    }

    fun withByteCapacity(maxBytes: Long): EventBatchWriter {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val dropped =
            synchronized(lifecycleLock) {
                maxQueuedBytes = maxBytes
                pruneExistingQueue(queueState)
            }
        dropped.forEach { notifyDrop(it, EventBatchDropReason.QUEUE_FULL) }
        return this
    }

    fun withPluginOverflowPolicies(policies: Map<String, PluginOverflowPolicy>): EventBatchWriter =
        apply {
            require(policies.keys.all(String::isNotBlank)) { "plugin policy ids must not be blank" }
            synchronized(lifecycleLock) { pluginPolicies = policies.toMap() }
        }

    fun start() {
        synchronized(lifecycleLock) {
            if (worker != null) return
            if (!queueState.accepting) {
                queueState = QueueState(capacity)
            }
            val activeState = queueState
            worker = scope.launch { process(activeState) }
        }
    }

    fun submit(event: EventEnvelope): Boolean = submit(event.toStoredEvent())

    /** For callers that already hold a redacted [StoredEvent], such as the timeline appender. */
    fun submit(event: StoredEvent): Boolean {
        val dropped = mutableListOf<StoredEvent>()
        lateinit var activeState: QueueState
        var closed = false
        val accepted =
            synchronized(lifecycleLock) {
                activeState = queueState
                if (!activeState.accepting) {
                    closed = true
                    false
                } else {
                    enqueue(activeState, event, dropped)
                }
            }
        if (closed) {
            notifyDrop(event, EventBatchDropReason.WRITER_CLOSED)
            return false
        }
        dropped.forEach { notifyDrop(it, EventBatchDropReason.QUEUE_FULL) }
        if (accepted) activeState.wakeup.trySend(Unit)
        return accepted
    }

    fun stop() {
        val (activeState, activeWorker) =
            synchronized(lifecycleLock) {
                queueState.accepting = false
                queueState.wakeup.close()
                queueState to worker.also { worker = null }
            }
        activeWorker?.cancel()
        if (activeWorker == null) {
            synchronized(lifecycleLock) {
                activeState.events.clear()
                activeState.queuedBytes = 0
            }
        }
    }

    /** Gracefully drains the current queue and persists its final partial batch. */
    suspend fun flushAndStop() {
        val (activeState, activeWorker) =
            synchronized(lifecycleLock) {
                queueState.accepting = false
                queueState.wakeup.close()
                queueState to worker.also { worker = null }
            }
        if (activeWorker != null) {
            activeWorker.join()
        } else {
            withContext(NonCancellable) { drainAll(activeState) }
        }
    }

    private suspend fun process(state: QueueState) {
        try {
            while (true) {
                val received =
                    withTimeoutOrNull(flushIntervalMs) {
                        state.wakeup.receiveCatching()
                    }
                val batch = drainBatch(state)
                if (batch.isNotEmpty()) flush(batch)
                if (received?.isClosed == true && isEmpty(state)) break
            }
        } finally {
            withContext(NonCancellable) { drainAll(state) }
        }
    }

    private suspend fun drainAll(state: QueueState) {
        while (true) {
            val batch = drainBatch(state)
            if (batch.isEmpty()) return
            flush(batch)
        }
    }

    private fun drainBatch(state: QueueState): List<StoredEvent> =
        synchronized(lifecycleLock) {
            buildList {
                repeat(minOf(maxBatchSize, state.events.size)) {
                    val event = state.events.removeFirst()
                    state.queuedBytes -= event.estimatedSizeBytes()
                    add(event)
                }
            }
        }

    private fun isEmpty(state: QueueState): Boolean = synchronized(lifecycleLock) { state.events.isEmpty() }

    private suspend fun flush(pending: List<StoredEvent>) {
        if (store.insert(pending) is EventStoreWriteResult.Success) {
            onStored(pending)
        } else {
            // Persistence failures are deliberately non-blocking; runtime health observes drops separately.
        }
    }

    private fun enqueue(
        state: QueueState,
        incoming: StoredEvent,
        dropped: MutableList<StoredEvent>,
    ): Boolean {
        val incomingBytes = incoming.estimatedSizeBytes()
        if (incomingBytes > maxQueuedBytes) {
            dropped += incoming
            return false
        }
        while (true) {
            val policy = pluginPolicies[incoming.pluginId]
            val pluginIndices = state.events.indices.filter { state.events[it].pluginId == incoming.pluginId }
            val candidatesAndStrategy =
                when {
                    policy != null && pluginIndices.size >= policy.maxBufferedEvents ->
                        pluginIndices to policy.strategy
                    state.events.size >= capacity || state.queuedBytes + incomingBytes > maxQueuedBytes ->
                        state.events.indices.toList() to
                            (policy?.strategy ?: EventOverflowStrategy.DROP_OLDEST_LOWEST_SEVERITY)
                    else -> null
                }
            if (candidatesAndStrategy == null) {
                state.events.addLast(incoming)
                state.queuedBytes += incomingBytes
                return true
            }
            when (
                val victim =
                    selectVictim(
                        state,
                        candidatesAndStrategy.first,
                        incoming,
                        candidatesAndStrategy.second,
                    )
            ) {
                QueueVictim.Incoming -> {
                    dropped += incoming
                    return false
                }
                is QueueVictim.Buffered -> {
                    val removed = state.events.removeAt(victim.index)
                    state.queuedBytes -= removed.estimatedSizeBytes()
                    dropped += removed
                }
            }
        }
    }

    private fun pruneExistingQueue(state: QueueState): List<StoredEvent> =
        buildList {
            while (state.queuedBytes > maxQueuedBytes && state.events.isNotEmpty()) {
                val lowestSeverity = state.events.minOf(StoredEvent::severity)
                val index = state.events.indexOfFirst { it.severity == lowestSeverity }
                val removed = state.events.removeAt(index)
                state.queuedBytes -= removed.estimatedSizeBytes()
                add(removed)
            }
        }

    private fun selectVictim(
        state: QueueState,
        bufferedIndices: List<Int>,
        incoming: StoredEvent,
        strategy: EventOverflowStrategy,
    ): QueueVictim =
        when (strategy) {
            EventOverflowStrategy.DROP_LATEST -> QueueVictim.Incoming
            EventOverflowStrategy.DROP_OLDEST ->
                bufferedIndices.firstOrNull()?.let(QueueVictim::Buffered) ?: QueueVictim.Incoming
            EventOverflowStrategy.DROP_OLDEST_LOWEST_SEVERITY -> {
                val lowestSeverity =
                    (bufferedIndices.map { state.events[it].severity } + incoming.severity).min()
                bufferedIndices
                    .firstOrNull { state.events[it].severity == lowestSeverity }
                    ?.let(QueueVictim::Buffered)
                    ?: QueueVictim.Incoming
            }
        }

    private fun notifyDrop(
        event: StoredEvent,
        reason: EventBatchDropReason,
    ) {
        runCatching { onDrop(event, reason) }
    }

    private class QueueState(
        capacity: Int,
    ) {
        val events = ArrayDeque<StoredEvent>(capacity)
        val wakeup = Channel<Unit>(Channel.CONFLATED)
        var queuedBytes = 0L
        var accepting = true
    }

    private sealed interface QueueVictim {
        data class Buffered(
            val index: Int,
        ) : QueueVictim

        data object Incoming : QueueVictim
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 2_000
        const val DEFAULT_BATCH_SIZE: Int = 100
        const val DEFAULT_FLUSH_INTERVAL_MS: Long = 250
        const val DEFAULT_MAX_QUEUED_BYTES: Long = 16L * 1024L * 1024L
    }
}

enum class EventBatchDropReason {
    QUEUE_FULL,
    WRITER_CLOSED,
}

private fun StoredEvent.estimatedSizeBytes(): Long =
    96L +
        id.byteSize() +
        sessionId.byteSize() +
        pluginId.byteSize() +
        type.byteSize() +
        summary.byteSize() +
        correlationId.orEmpty().byteSize() +
        tagsJson.byteSize() +
        payloadJson.orEmpty().byteSize() +
        attachmentId.orEmpty().byteSize()

private fun String.byteSize(): Int = encodeToByteArray().size

internal fun EventEnvelope.toStoredEvent(): StoredEvent =
    StoredEvent(
        id = id.toString(),
        sessionId = sessionId.toString(),
        sequence = sequence,
        pluginId = pluginId,
        type = type,
        wallTimeMs = timestampEpochMs,
        monoTimeNs = monotonicNanos,
        severity = severity.ordinal,
        summary = summary,
        correlationId = correlationId,
        tagsJson = tags.toStableJson(),
        payloadJson = null,
        attachmentId = payloadRef,
        schemaVersion = schemaVersion,
    )

private fun Map<String, String>.toStableJson(): String =
    entries
        .sortedBy { it.key }
        .joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${key.escapeJson()}\":\"${value.escapeJson()}\""
        }

private fun String.escapeJson(): String =
    buildString(length + 16) {
        for (char in this@escapeJson) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        append("\\u").append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
    }
