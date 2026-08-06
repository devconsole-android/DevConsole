@file:Suppress("ReturnCount") // Guard-clause early returns are the clearest form for this recovery boundary.

package io.devconsole.storage.room

import kotlinx.coroutines.CancellationException

/**
 * Runs an operation once, recreates the backing resource only for confirmed SQLite corruption,
 * then retries exactly once against the resource returned by [resourceProvider].
 *
 * Fail-open recovery boundary: any non-cancellation failure must trigger the recreate-and-retry
 * path below rather than crash the host, so it deliberately catches Exception broadly and has
 * more return/throw exits than the default guard-clause thresholds allow.
 */
@Suppress("TooGenericExceptionCaught", "ThrowsCount")
internal suspend inline fun <Resource, Result> executeWithSqliteRecovery(
    unavailable: Result,
    resourceProvider: () -> Resource,
    noinline recover: ((Throwable) -> Unit)?,
    operation: (Resource) -> Result,
): Result {
    val firstFailure =
        try {
            return operation(resourceProvider())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure
        }

    if (!firstFailure.isSqliteCorruption()) return unavailable
    val recovery = recover ?: return unavailable
    try {
        recovery(firstFailure)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return unavailable
    }

    return try {
        operation(resourceProvider())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        unavailable
    }
}
