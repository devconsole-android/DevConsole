/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared in-memory pause/resume gate consulted by live capture paths.
 *
 * When paused, new network transactions (those whose ID has not already been recorded), new socket
 * connections, push events, and log lines are dropped before reaching any store or the timeline.
 * Updates to an already-recorded network transaction still apply so in-flight requests finish cleanly.
 */
internal class CaptureGate(
    initialPaused: Boolean = false,
) {
    private val paused = AtomicBoolean(initialPaused)

    fun isPaused(): Boolean = paused.get()

    /**
     * Updates the pause state and returns `true` when this call changed the state from its previous
     * value, or `false` when it was already set to [value].
     */
    fun setPaused(value: Boolean): Boolean = paused.getAndSet(value) != value

    fun allowsNewCapture(): Boolean = !paused.get()

    fun allowsNetworkTransaction(
        transactionId: String,
        alreadyRecorded: (String) -> Boolean,
    ): Boolean = !paused.get() || alreadyRecorded(transactionId)
}
