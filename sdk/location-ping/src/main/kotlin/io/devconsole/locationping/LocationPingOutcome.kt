/**
 * @author Zakaria
 * @since 26/10/26
 */
package io.devconsole.locationping

sealed interface LocationPingOutcome {
    data class Answered(
        val statusCode: Int,
    ) : LocationPingOutcome {
        init {
            require(statusCode in HTTP_STATUS_RANGE) { "statusCode must be an HTTP status, was $statusCode" }
        }
    }

    data object DroppedOnDevice : LocationPingOutcome

    data object NoConnection : LocationPingOutcome

    companion object {
        @JvmStatic
        fun answered(statusCode: Int): LocationPingOutcome = Answered(statusCode)

        @JvmStatic
        fun droppedOnDevice(): LocationPingOutcome = DroppedOnDevice

        @JvmStatic
        fun noConnection(): LocationPingOutcome = NoConnection
    }
}

internal const val DROPPED_ON_DEVICE_STATUS = 0
internal const val NO_CONNECTION_STATUS = -1
private const val LOWEST_HTTP_STATUS = 100
private const val HIGHEST_HTTP_STATUS = 599
internal val HTTP_STATUS_RANGE = LOWEST_HTTP_STATUS..HIGHEST_HTTP_STATUS

internal fun LocationPingOutcome.storedStatus(): Int =
    when (this) {
        is LocationPingOutcome.Answered -> statusCode
        LocationPingOutcome.DroppedOnDevice -> DROPPED_ON_DEVICE_STATUS
        LocationPingOutcome.NoConnection -> NO_CONNECTION_STATUS
    }

internal fun outcomeOfStoredStatus(status: Int): LocationPingOutcome =
    when {
        status == DROPPED_ON_DEVICE_STATUS -> LocationPingOutcome.DroppedOnDevice
        status in HTTP_STATUS_RANGE -> LocationPingOutcome.Answered(status)
        else -> LocationPingOutcome.NoConnection
    }
