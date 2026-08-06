/**
 * @author Shakib
 * @since 02/08/26
 */
package io.devconsole.server.api

data class DatabaseTableData(
    val name: String,
    val rowCount: Long,
)

data class DatabaseListingData(
    val name: String,
    val tables: List<DatabaseTableData>,
    /** On-disk file size in bytes; 0 when the underlying platform cannot resolve a file (e.g. tests). */
    val sizeBytes: Long = 0,
)

data class DatabaseQueryData(
    val columns: List<String>,
    val rows: List<List<String>>,
    val truncated: Boolean,
    /**
     * Parallel to [rows] -- `rowIds[i]` is the SQLite `rowid` for `rows[i]`, letting a dashboard build
     * an unambiguous `WHERE rowid = ?` for a single-row edit. Empty when the underlying query has no
     * well-defined rowid (arbitrary SQL via the console, or a `WITHOUT ROWID` table); a present entry
     * may itself be null if the row's rowid was somehow NULL.
     */
    val rowIds: List<Long?> = emptyList(),
)

sealed interface DatabaseExecResult {
    data class Query(
        val result: DatabaseQueryData,
    ) : DatabaseExecResult

    data class Write(
        val affectedRows: Int,
    ) : DatabaseExecResult

    /** The statement is a write/DDL but the caller has not opted into database editing. */
    data object WriteBlocked : DatabaseExecResult

    data class Failed(
        val message: String,
    ) : DatabaseExecResult
}

/**
 * Reads and (opt-in) edits an app's own SQLite databases. Read-only `SELECT`/`PRAGMA` statements are
 * always allowed; any statement that can mutate data or schema is refused with [DatabaseExecResult.WriteBlocked]
 * unless [DatabaseInspector.execute]'s `writeEnabled` is true. Callers pass `writeEnabled` from
 * `EditingCapabilities.database`. Declared in `sdk:server-api` -- a plain boundary module with no
 * Android dependency -- so both the Android engine implementation (`sdk:full`) and the platform-independent
 * Ktor routes (`sdk:server-ktor`) can share this one contract without a circular module dependency, the
 * same way `io.devconsole.state.SessionFeatureFlags` reaches `GET/POST /api/v1/flags`.
 */
interface DatabaseInspector {
    fun databases(): List<String>

    fun tables(database: String): DatabaseListingData?

    fun query(
        database: String,
        table: String,
    ): DatabaseQueryData?

    fun execute(
        database: String,
        sql: String,
        writeEnabled: Boolean,
    ): DatabaseExecResult
}
