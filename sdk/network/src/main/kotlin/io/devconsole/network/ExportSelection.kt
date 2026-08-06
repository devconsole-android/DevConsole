/**
 * @author Shakib
 * @since 02/08/26
 */
package io.devconsole.network

/**
 * Shared selection of which captured network transactions an export (HAR, Postman) should include.
 * Declared once here -- rather than separately in `sdk:server-ktor` and `sdk:full` -- so the browser
 * export routes and the Android in-app exporter can never select a different set of rows for what a
 * caller intends to be the same export. Mirrors the shape of `io.devconsole.export.ExportScope`
 * (whole/time-range/explicit-ids), which plays the same role for timeline-event ZIP exports; the two
 * are kept as separate types because they select over different id spaces (transaction ids here,
 * event ids there), not because the selection dimensions differ.
 */
sealed interface ExportSelection {
    /** Every transaction the store currently holds, subject to whatever query filters accompany it. */
    data object All : ExportSelection

    /** Exactly the transactions named by id; unknown ids are silently dropped, never an error. */
    data class Ids(
        val ids: Set<String>,
    ) : ExportSelection {
        init {
            require(ids.isNotEmpty()) { "Ids selection must not be empty" }
            require(ids.size <= NetworkTransactionQuery.MAX_PAGE_LIMIT) {
                "Ids selection exceeds ${NetworkTransactionQuery.MAX_PAGE_LIMIT} ids"
            }
            require(ids.all { it.isNotBlank() }) { "Ids selection contains a blank id" }
        }
    }

    /** Transactions started within the inclusive [fromEpochMs]..[toEpochMs] window. */
    data class TimeRange(
        val fromEpochMs: Long,
        val toEpochMs: Long,
    ) : ExportSelection {
        init {
            require(fromEpochMs >= 0) { "fromEpochMs must not be negative" }
            require(toEpochMs >= fromEpochMs) { "toEpochMs must not precede fromEpochMs" }
        }
    }
}

/**
 * Resolves [selection] against this store, bounded to [NetworkTransactionQuery.MAX_PAGE_LIMIT] rows.
 * [baseQuery] supplies any additional filters (method, host, status, free-text search, ...) layered
 * on top of [ExportSelection.All] or [ExportSelection.TimeRange]; it is ignored for
 * [ExportSelection.Ids], which is always an exact match regardless of filters -- a caller who picked
 * specific rows expects exactly those rows back, not a filtered subset of them.
 *
 * Returns `null` -- rather than an empty list -- when the underlying [NetworkTransactionStore.page]
 * call reports an invalid cursor (an out-of-range `limit`, or a cursor that fails to decode against
 * the resolved query's scope). `null` here means "this selection could not be resolved", which
 * callers must surface as a failure; treating it the same as a genuinely-empty result would silently
 * write an empty export instead. [ExportSelection.Ids] never triggers this path, since it resolves
 * by direct lookup rather than through `page`.
 */
fun NetworkTransactionStore.resolveExportSelection(
    selection: ExportSelection,
    baseQuery: NetworkTransactionQuery = NetworkTransactionQuery(limit = NetworkTransactionQuery.MAX_PAGE_LIMIT),
): List<NetworkTransaction>? =
    when (selection) {
        ExportSelection.All -> page(baseQuery).let { if (it.invalidCursor) null else it.transactions }
        is ExportSelection.Ids -> selection.ids.mapNotNull(::find)
        is ExportSelection.TimeRange ->
            page(
                baseQuery.withFilters(
                    baseQuery.filters.copy(fromEpochMs = selection.fromEpochMs, toEpochMs = selection.toEpochMs),
                ),
            ).let { if (it.invalidCursor) null else it.transactions }
    }
