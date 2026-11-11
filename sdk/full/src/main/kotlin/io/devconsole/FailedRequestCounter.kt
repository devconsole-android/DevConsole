/**
 * @author Shakib
 * @since 09/11/26
 */
package io.devconsole

import io.devconsole.network.NetworkTransaction

private const val DEFAULT_CAPACITY = 100
private const val HTTP_ERROR_FLOOR = 400

/** Matches the inspector's Traffic "Failed" filter: no status, or a status of 400 or above. */
internal fun NetworkTransaction.isUnmockedFailure(): Boolean {
    val status = capture.response?.statusCode
    val mocked = capture.request.metadata.tags["mocked"] == "true"
    return status != null && !mocked && (status <= 0 || status >= HTTP_ERROR_FLOOR)
}

/** Bounded tally of failed transactions, by id because a store records provisional then final. */
internal class FailedRequestCounter(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val failedIds = LinkedHashSet<String>()

    val count: Int
        @Synchronized get() = failedIds.size

    /** True only when this call raised the count. */
    @Synchronized
    fun record(transaction: NetworkTransaction): Boolean =
        transaction.isUnmockedFailure() && failedIds.size < capacity && failedIds.add(transaction.id)

    /** True only when there was something to clear. */
    @Synchronized
    fun reset(): Boolean = failedIds.isNotEmpty().also { failedIds.clear() }
}
