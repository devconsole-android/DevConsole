package io.devconsole.core

import io.devconsole.api.EventEnvelope
import io.devconsole.api.EventSeverity
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class EventDraft(
    val pluginId: String,
    val type: String,
    val severity: EventSeverity,
    val summary: String,
    val correlationId: String? = null,
    val tags: Map<String, String> = emptyMap(),
    val payloadRef: String? = null,
) {
    companion object {
        fun system(
            summary: String,
            severity: EventSeverity = EventSeverity.INFO,
        ): EventDraft =
            EventDraft(
                pluginId = "system",
                type = "system.event",
                severity = severity,
                summary = summary,
            )
    }
}

enum class EventOverflowStrategy {
    DROP_OLDEST_LOWEST_SEVERITY,
    DROP_OLDEST,
    DROP_LATEST,
}

data class PluginOverflowPolicy(
    val maxBufferedEvents: Int,
    val strategy: EventOverflowStrategy = EventOverflowStrategy.DROP_OLDEST_LOWEST_SEVERITY,
) {
    init {
        require(maxBufferedEvents > 0) { "maxBufferedEvents must be positive" }
    }
}

enum class EventDropReason { GLOBAL_CAPACITY, PLUGIN_CAPACITY }

data class EventDropNotice(
    val droppedEventId: UUID,
    val droppedPluginId: String,
    val droppedSeverity: EventSeverity,
    val incomingPluginId: String,
    val reason: EventDropReason,
    val totalDroppedCount: Long,
)

fun interface EventDropSink {
    fun record(notice: EventDropNotice)
}

class EventPipeline(
    private val sessionId: UUID,
    private val capacity: Int,
    private val redactionEngine: RedactionEngine = RedactionEngine(RedactionPolicy.default()),
) {
    private val sequence = AtomicLong(0)
    private val observationWindow = ArrayDeque<EventEnvelope>(capacity)
    private val mutableHealth = MutableStateFlow(SdkHealth())
    private var pluginPolicies: Map<String, PluginOverflowPolicy> = emptyMap()
    private var dropSink: EventDropSink = EventDropSink {}

    val health: StateFlow<SdkHealth> = mutableHealth.asStateFlow()

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    @Synchronized
    fun withPluginOverflowPolicies(policies: Map<String, PluginOverflowPolicy>): EventPipeline =
        apply {
            require(policies.keys.all(String::isNotBlank)) { "plugin policy ids must not be blank" }
            pluginPolicies = policies.toMap()
        }

    @Synchronized
    fun withDropSink(sink: EventDropSink): EventPipeline =
        apply {
            dropSink = sink
        }

    @Synchronized
    fun publish(draft: EventDraft): EventEnvelope {
        val envelope =
            EventEnvelope(
                id = UUID.randomUUID(),
                sessionId = sessionId,
                pluginId = draft.pluginId,
                type = draft.type,
                timestampEpochMs = System.currentTimeMillis(),
                monotonicNanos = System.nanoTime(),
                sequence = sequence.incrementAndGet(),
                severity = draft.severity,
                summary = redactionEngine.redactText(draft.summary),
                correlationId = draft.correlationId?.let(redactionEngine::redactText),
                tags = redactionEngine.redactFields(draft.tags),
                payloadRef = draft.payloadRef,
            )

        val pluginPolicy = pluginPolicies[draft.pluginId]
        val pluginIndices =
            observationWindow.indices.filter { observationWindow[it].pluginId == draft.pluginId }
        val overflow =
            when {
                pluginPolicy != null && pluginIndices.size >= pluginPolicy.maxBufferedEvents ->
                    selectVictim(pluginIndices, envelope, pluginPolicy.strategy) to EventDropReason.PLUGIN_CAPACITY
                observationWindow.size >= capacity ->
                    selectVictim(
                        observationWindow.indices.toList(),
                        envelope,
                        pluginPolicy?.strategy ?: EventOverflowStrategy.DROP_OLDEST_LOWEST_SEVERITY,
                    ) to EventDropReason.GLOBAL_CAPACITY
                else -> null
            }
        val droppedEvent =
            when (val victim = overflow?.first) {
                is OverflowVictim.Buffered -> observationWindow.removeAt(victim.index)
                OverflowVictim.Incoming -> envelope
                null -> null
            }
        if (droppedEvent !== envelope) observationWindow.addLast(envelope)
        val newDroppedCount = mutableHealth.value.droppedEventCount + if (droppedEvent != null) 1 else 0
        mutableHealth.value =
            mutableHealth.value.copy(
                publishedEventCount = mutableHealth.value.publishedEventCount + 1,
                droppedEventCount = newDroppedCount,
            )
        if (droppedEvent != null && overflow != null) {
            runCatching {
                dropSink.record(
                    EventDropNotice(
                        droppedEventId = droppedEvent.id,
                        droppedPluginId = droppedEvent.pluginId,
                        droppedSeverity = droppedEvent.severity,
                        incomingPluginId = envelope.pluginId,
                        reason = overflow.second,
                        totalDroppedCount = newDroppedCount,
                    ),
                )
            }
        }
        return envelope
    }

    @Synchronized
    fun snapshot(): List<EventEnvelope> = observationWindow.toList()

    private fun selectVictim(
        bufferedIndices: List<Int>,
        incoming: EventEnvelope,
        strategy: EventOverflowStrategy,
    ): OverflowVictim =
        when (strategy) {
            EventOverflowStrategy.DROP_LATEST -> OverflowVictim.Incoming
            EventOverflowStrategy.DROP_OLDEST ->
                bufferedIndices.firstOrNull()?.let(OverflowVictim::Buffered) ?: OverflowVictim.Incoming
            EventOverflowStrategy.DROP_OLDEST_LOWEST_SEVERITY -> {
                val lowestSeverity =
                    (
                        bufferedIndices.map { observationWindow[it].severity.ordinal } +
                            incoming.severity.ordinal
                    ).min()
                bufferedIndices
                    .firstOrNull { observationWindow[it].severity.ordinal == lowestSeverity }
                    ?.let(OverflowVictim::Buffered)
                    ?: OverflowVictim.Incoming
            }
        }

    private sealed interface OverflowVictim {
        data class Buffered(
            val index: Int,
        ) : OverflowVictim

        data object Incoming : OverflowVictim
    }
}
