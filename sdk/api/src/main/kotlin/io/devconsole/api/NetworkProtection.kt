/**
 * @author Shakib
 * @since 19/10/26
 */
package io.devconsole.api

/** Optional request budgets and Host/Origin validation for the embedded dashboard server. */
data class NetworkProtection(
    val rateLimiting: Boolean = false,
    val hostAndOriginChecks: Boolean = false,
) {
    companion object {
        @JvmStatic
        fun recommended(): NetworkProtection = NetworkProtection(true, true)
    }
}
