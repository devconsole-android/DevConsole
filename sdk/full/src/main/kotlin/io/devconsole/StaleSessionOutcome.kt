/**
 * @author Shakib
 * @since 08/08/26
 */
package io.devconsole

import io.devconsole.storage.api.EventStore
import kotlinx.coroutines.CancellationException

private const val CRASH_PLUGIN_ID = "crash"
private const val UNCAUGHT_CRASH_TYPE = "uncaught"
private const val CRASH_PROBE_LIMIT = 5

/**
 * Whether a leftover `ACTIVE` row left behind by a process that is already gone belongs to a run
 * that genuinely crashed, and so must be closed `CRASHED` rather than `COMPLETED` (see
 * `PlatformFacadeProvider.bootstrapDurableSessionWithinTimeout`).
 *
 * A process death cannot run `stop()`, so *every* ordinary exit -- swiping the app away, the system
 * reclaiming the process, Android Studio's stop button -- leaves behind exactly the same leftover
 * `ACTIVE` row an uncaught exception does. Closing all of them as `CRASHED` is what made the Observe
 * "Previous run crashed" banner and the dashboard Overview banner (both read from this one stored
 * status) fire after runs that never crashed.
 *
 * The one durable thing that separates the two cases is the crash record itself: [CrashCapture]
 * writes a `"crash"` plugin event of type `"uncaught"` synchronously, from inside the
 * uncaught-exception handler, before the process dies. A run that has one crashed; a run that has
 * none exited some other way.
 *
 * Only `"uncaught"` counts. [CrashCapture.recordAnr] files main-thread stalls under the same plugin
 * id, and a stall the run survived is not a crash -- hence the small [CRASH_PROBE_LIMIT] window
 * rather than a single newest row, so a burst of trailing ANR records cannot hide the crash record
 * underneath them.
 *
 * Deaths this evidence genuinely cannot see -- native crashes, low-memory kills -- read as
 * `COMPLETED`. Under-reporting costs one banner; over-reporting is the bug this replaces, and it
 * cried wolf on every single launch.
 */
internal suspend fun EventStore.recordedUncaughtCrash(sessionId: String): Boolean =
    probeCrashEvents(sessionId).any { it.type == UNCAUGHT_CRASH_TYPE }

/**
 * Fails closed rather than failing the whole bootstrap: an unreadable event store must still let the
 * leftover row be closed, and without evidence `COMPLETED` is the honest answer. Cancellation is
 * rethrown so the caller's `withTimeoutOrNull` budget stays the single deadline authority.
 */
@Suppress("TooGenericExceptionCaught")
private suspend fun EventStore.probeCrashEvents(sessionId: String) =
    try {
        recentEventsForSession(sessionId, CRASH_PROBE_LIMIT, setOf(CRASH_PLUGIN_ID))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }
