/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole.server.ktor

import io.devconsole.network.EndpointLatency
import io.devconsole.network.EndpointLatencyStats
import io.devconsole.network.NetworkTransaction
import io.devconsole.network.NetworkTransactionQuery
import io.devconsole.network.NetworkTransactionStore
import io.devconsole.server.api.SessionAuthority
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private const val MAX_ENDPOINT_STATS_ROWS = 5_000

internal fun Route.networkEndpointStatsRoutes(
    sessionAuthority: SessionAuthority,
    categoryEnabled: (String) -> Boolean,
    storeForSession: suspend (ApplicationCall) -> NetworkTransactionStore?,
) {
    get("/api/v1/network/endpoints") {
        if (sessionAuthority.bearerSession(call.request.headers[HttpHeaders.Authorization]) == null) {
            call.respondText("{\"code\":\"AUTH_REQUIRED\"}", status = HttpStatusCode.Unauthorized)
            return@get
        }
        if (!categoryEnabled("network")) {
            call.respondEndpointCategoryDisabled("network")
            return@get
        }
        val store = storeForSession(call) ?: return@get
        val sampled = collectSampledTransactions(store)
        val completedCount = sampled.count { it.completedAtEpochMs != null }
        val stats = EndpointLatencyStats.from(sampled)
        val dataJson = stats.joinToString(",") { it.json() }
        call.respondText(
            "{\"data\":[$dataJson],\"sampleSize\":$completedCount}",
            contentType = ContentType.Application.Json,
        )
    }
}

private suspend fun ApplicationCall.respondEndpointCategoryDisabled(name: String) {
    respondText(
        "{\"error\":{\"code\":\"CATEGORY_DISABLED\"," +
            "\"message\":\"The '${name.escapeJson()}' capture category is disabled for this app run\"}}",
        status = HttpStatusCode.Forbidden,
        contentType = ContentType.Application.Json,
    )
}

private fun collectSampledTransactions(store: NetworkTransactionStore): List<NetworkTransaction> {
    val collected = ArrayList<NetworkTransaction>()
    var cursor: String? = null
    var exhausted = false
    while (!exhausted && collected.size < MAX_ENDPOINT_STATS_ROWS) {
        val pageLimit = minOf(NetworkTransactionQuery.MAX_PAGE_LIMIT, MAX_ENDPOINT_STATS_ROWS - collected.size)
        val page = store.page(NetworkTransactionQuery(limit = pageLimit, cursor = cursor))
        if (!page.invalidCursor) collected.addAll(page.transactions)
        cursor = page.nextCursor
        exhausted = page.invalidCursor || page.transactions.isEmpty() || !page.hasMore || cursor == null
    }
    return collected
}

private fun EndpointLatency.json(): String =
    "{\"method\":\"${method.escapeJson()}\"," +
        "\"host\":\"${host.escapeJson()}\"," +
        "\"pathTemplate\":\"${pathTemplate.escapeJson()}\"," +
        "\"count\":$count," +
        "\"errorCount\":$errorCount," +
        "\"p50Ms\":$p50Ms," +
        "\"p95Ms\":$p95Ms," +
        "\"p99Ms\":$p99Ms," +
        "\"maxMs\":$maxMs}"
