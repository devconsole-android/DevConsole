/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole.server.ktor

/**
 * Host-side capture pause/resume and purge, exposed by the HTTP module without depending on the
 * facade. `:sdk:full` wires its `PlatformFacadeProvider` implementation in at engine construction;
 * keeping the contract this small means the server never has to know what a capture buffer is.
 */
interface CaptureControl {
    fun isPaused(): Boolean

    /** Applies [paused]; false means this build/session cannot honour the change right now. */
    fun setPaused(paused: Boolean): Boolean

    /** Discards this run's buffered captures plus old, unpinned retained runs. */
    suspend fun purge(): CapturePurgeSummary
}

/** What one [CaptureControl.purge] removed, as counts so the dashboard can report it honestly. */
data class CapturePurgeSummary(
    val networkTransactions: Int,
    val socketConnections: Int,
    val pushEvents: Int,
    val retainedRunsDeleted: Int,
)
