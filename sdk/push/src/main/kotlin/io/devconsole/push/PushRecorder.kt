package io.devconsole.push

import io.devconsole.security.RedactionEngine

enum class PushLifecycle {
    RECEIVED,
    DISPLAYED,
    SUPPRESSED,
    OPENED,
    ACTION_CLICKED,
    DEEP_LINK_RESOLVED,
    HANDLING_FAILED,
}

data class PushNotification(
    val title: String? = null,
    val body: String? = null,
    val channelId: String? = null,
    val imageUrl: String? = null,
)

data class PushInput(
    val provider: String,
    val data: Map<String, String>,
    val messageId: String? = null,
    val source: String? = null,
    val sentAtEpochMs: Long? = null,
    val receivedAtEpochMs: Long = System.currentTimeMillis(),
    val notification: PushNotification? = null,
    val rawMetadata: Map<String, String> = emptyMap(),
    val lifecycle: PushLifecycle = PushLifecycle.RECEIVED,
    val simulated: Boolean = false,
)

data class PushEvent(
    val provider: String,
    val data: Map<String, String>,
    val messageId: String? = null,
    val source: String? = null,
    val sentAtEpochMs: Long? = null,
    val receivedAtEpochMs: Long = System.currentTimeMillis(),
    val notification: PushNotification? = null,
    val rawMetadata: Map<String, String> = emptyMap(),
    val lifecycle: PushLifecycle = PushLifecycle.RECEIVED,
    val simulated: Boolean = false,
)

interface PushStore {
    fun append(event: PushEvent)

    fun events(): List<PushEvent>
}

fun interface PushSimulationCallback {
    /** Invokes an app-provided local handler. This never attempts provider transport delivery. */
    fun onSimulatedPush(input: PushInput): PushLifecycle
}

class PushSimulator(
    private val callback: PushSimulationCallback,
    private val recorder: PushRecorder,
) {
    fun simulate(input: PushInput): PushEvent {
        val localInput = input.copy(provider = input.provider.ifBlank { "local" }, simulated = true)
        val lifecycle =
            runCatching { callback.onSimulatedPush(localInput) }
                .getOrElse { PushLifecycle.HANDLING_FAILED }
        return recorder.record(localInput.copy(lifecycle = lifecycle))
    }
}

class InMemoryPushStore(
    private val capacity: Int = 200,
) : PushStore {
    private val stored = mutableListOf<PushEvent>()

    init {
        require(capacity > 0) { "Push store capacity must be positive" }
    }

    @Synchronized
    override fun append(event: PushEvent) {
        if (stored.size == capacity) stored.removeAt(0)
        stored += event
    }

    @Synchronized
    override fun events(): List<PushEvent> = stored.toList()

    @Synchronized
    fun clear() {
        stored.clear()
    }
}

/**
 * When [enabled] is false, [record] returns the event unredacted from input echo alone — it never
 * invokes redaction and never appends to [store]. Disabled callers should treat the returned event
 * as informational only, not as a stored/dashboard-visible record.
 */
class PushRecorder(
    private val redaction: RedactionEngine,
    private val store: PushStore? = null,
    private val enabled: Boolean = true,
) {
    fun record(input: PushInput): PushEvent {
        if (!enabled) {
            return PushEvent(
                provider = input.provider,
                data = emptyMap(),
                messageId = input.messageId,
                source = input.source,
                sentAtEpochMs = input.sentAtEpochMs,
                receivedAtEpochMs = input.receivedAtEpochMs,
                notification = input.notification,
                rawMetadata = emptyMap(),
                lifecycle = input.lifecycle,
                simulated = input.simulated,
            )
        }
        return PushEvent(
            provider = input.provider,
            data = redaction.redactFields(input.data),
            messageId = input.messageId,
            source = input.source,
            sentAtEpochMs = input.sentAtEpochMs,
            receivedAtEpochMs = input.receivedAtEpochMs,
            notification = input.notification?.redacted(),
            rawMetadata = redaction.redactFields(input.rawMetadata),
            lifecycle = input.lifecycle,
            simulated = input.simulated,
        ).also(store?.let { target -> { target.append(it) } } ?: {})
    }

    private fun PushNotification.redacted(): PushNotification =
        PushNotification(
            title = title?.let(redaction::redactText),
            body = body?.let(redaction::redactText),
            channelId = channelId?.let(redaction::redactText),
            imageUrl = imageUrl?.let(redaction::redactText),
        )
}
