/**
 * @author Shakib
 * @since 25/07/26
 */
@file:Suppress("ReturnCount") // Guard-clause early returns are the clearest form for these SQL/state checks.

package io.devconsole

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.devconsole.security.RedactionEngine
import io.devconsole.server.api.DatabaseExecResult
import io.devconsole.server.api.DatabaseInspector
import io.devconsole.server.api.DatabaseListingData
import io.devconsole.server.api.DatabaseQueryData
import io.devconsole.server.api.DatabaseTableData

@Suppress("TooManyFunctions") // Small private cursor/SQL helpers kept beside the four interface methods.
internal class AndroidDatabaseInspector(
    private val context: Context,
    private val redaction: RedactionEngine,
    private val maxRows: Int = DEFAULT_MAX_ROWS,
) : DatabaseInspector {
    override fun databases(): List<String> = context.databaseList().filterNot { it.isAuxiliaryFile() }.sorted()

    override fun tables(database: String): DatabaseListingData? =
        withDatabase(database, writable = false) { db ->
            val tables =
                db.rawQuery(TABLE_LIST_SQL, null).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(0)
                            add(DatabaseTableData(name = name, rowCount = db.rowCountOf(name)))
                        }
                    }
                }
            DatabaseListingData(
                name = database,
                tables = tables.sortedBy { it.name },
                sizeBytes = context.getDatabasePath(database).length(),
            )
        }

    override fun query(
        database: String,
        table: String,
    ): DatabaseQueryData? {
        // sqlite_% internals are excluded from tables() on purpose; the row route must not be a
        // way around that listing filter.
        if (!table.isSafeIdentifier() || table.startsWith("sqlite_", ignoreCase = true)) return null
        return withDatabase(database, writable = false) { db ->
            val quoted = table.replace("\"", "\"\"")
            // rowid is aliased first so a safe-per-row WHERE can be built for edits; a WITHOUT ROWID
            // table (or any other reason the alias fails) falls back to the plain, rowid-less query.
            runCatching {
                db
                    .rawQuery("SELECT rowid AS \"$ROWID_ALIAS\", * FROM \"$quoted\" LIMIT ${maxRows + 1}", null)
                    .use { it.toQueryData(hasRowId = true) }
            }.getOrElse {
                db.rawQuery("SELECT * FROM \"$quoted\" LIMIT ${maxRows + 1}", null).use { it.toQueryData() }
            }
        }
    }

    override fun execute(
        database: String,
        sql: String,
        writeEnabled: Boolean,
    ): DatabaseExecResult {
        val kind = sql.statementKind()
        if (kind == StatementKind.WRITE && !writeEnabled) return DatabaseExecResult.WriteBlocked
        val writable = kind == StatementKind.WRITE
        return withDatabase(database, writable = writable) { db ->
            when (kind) {
                StatementKind.READ ->
                    db.rawQuery(sql, null).use { DatabaseExecResult.Query(it.toQueryData()) }
                StatementKind.WRITE -> {
                    db.execSQL(sql)
                    DatabaseExecResult.Write(affectedRows = db.changesCount())
                }
            }
        } ?: DatabaseExecResult.Failed("Database is not accessible")
    }

    /** Opens an app database read-only unless [writable]; failures return null rather than throwing out. */
    private fun <T> withDatabase(
        database: String,
        writable: Boolean,
        block: (SQLiteDatabase) -> T,
    ): T? {
        if (database !in databases()) return null
        val path = context.getDatabasePath(database)
        if (!path.exists()) return null
        val flags = if (writable) SQLiteDatabase.OPEN_READWRITE else SQLiteDatabase.OPEN_READONLY
        return runCatching {
            SQLiteDatabase.openDatabase(path.absolutePath, null, flags).use(block)
        }.getOrNull()
    }

    private fun SQLiteDatabase.rowCountOf(table: String): Long =
        runCatching {
            rawQuery("SELECT COUNT(*) FROM \"${table.replace("\"", "\"\"")}\"", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        }.getOrDefault(0L)

    private fun SQLiteDatabase.changesCount(): Int =
        runCatching {
            rawQuery("SELECT changes()", null).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        }.getOrDefault(0)

    /**
     * [hasRowId] means this cursor's first column is the `rowid` alias appended by [query]; it is
     * split off into [DatabaseQueryData.rowIds] rather than shown as an ordinary, maskable column.
     */
    private fun android.database.Cursor.toQueryData(hasRowId: Boolean = false): DatabaseQueryData {
        val columns = if (hasRowId) columnNames.drop(1) else columnNames.toList()
        val rows = mutableListOf<List<String>>()
        val rowIds = mutableListOf<Long?>()
        var truncated = false
        while (moveToNext()) {
            if (rows.size >= maxRows) {
                truncated = true
                break
            }
            if (hasRowId) rowIds.add(if (isNull(0)) null else getLong(0))
            rows.add(
                columns.indices.map { index ->
                    val cursorIndex = if (hasRowId) index + 1 else index
                    cellText(cursorIndex, columns[index])
                },
            )
        }
        return DatabaseQueryData(columns = columns, rows = rows, truncated = truncated, rowIds = rowIds)
    }

    private fun android.database.Cursor.cellText(
        index: Int,
        column: String,
    ): String {
        if (isNull(index)) return "NULL"
        val raw =
            when (getType(index)) {
                android.database.Cursor.FIELD_TYPE_BLOB -> "[blob ${getBlob(index).size} bytes]"
                else -> getString(index).orEmpty()
            }
        if (column.looksSensitiveColumn()) return redaction.replacement()
        return redaction.redactFields(mapOf(column to raw)).getValue(column)
    }

    /**
     * The shared redaction policy matches HTTP field names exactly, which never fires for
     * application table columns like `email` or `user_phone`. Application schemas name columns
     * freely, so this substring pass covers the common personal-data columns before a row is shown.
     * It is a safety net, not a guarantee -- hosts should still treat this browser as sensitive.
     */
    private fun String.looksSensitiveColumn(): Boolean {
        val normalized = lowercase()
        return SENSITIVE_COLUMN_FRAGMENTS.any { normalized.contains(it) }
    }

    private enum class StatementKind { READ, WRITE }

    /**
     * A statement is READ only when its leading keyword is SELECT/PRAGMA/EXPLAIN, or it is a `WITH`
     * clause whose statement after the CTE bodies is itself a SELECT. SQLite allows a CTE to prefix
     * DELETE/INSERT/UPDATE, so a bare `WITH` must not be trusted as read-only.
     */
    private fun String.statementKind(): StatementKind {
        val leading = firstKeyword()
        val keyword = if (leading == WITH_KEYWORD) afterCteKeyword() else leading
        return if (keyword in READ_ONLY_KEYWORDS) StatementKind.READ else StatementKind.WRITE
    }

    private fun String.firstKeyword(): String = trim().takeWhile { !it.isWhitespace() && it != '(' }.uppercase()

    /**
     * Skips balanced-parenthesis CTE bodies after `WITH` and returns the keyword that follows the
     * last one. An unbalanced/garbled clause returns an empty string, which classifies as a WRITE.
     */
    private fun String.afterCteKeyword(): String {
        var depth = 0
        forEachIndexed { index, character ->
            if (character == '(') depth++
            if (character != ')') return@forEachIndexed
            depth--
            if (depth != 0) return@forEachIndexed
            val rest = substring(index + 1).trimStart()
            // A comma continues the CTE list; anything else is the payload statement.
            if (!rest.startsWith(",")) return rest.firstKeyword()
        }
        return ""
    }

    private fun String.isAuxiliaryFile(): Boolean = endsWith("-journal") || endsWith("-wal") || endsWith("-shm")

    /** Table names come from the on-disk schema, but this rejects anything that could break quoting. */
    private fun String.isSafeIdentifier(): Boolean = isNotBlank() && none { it == '"' || it == ' ' }

    private companion object {
        const val DEFAULT_MAX_ROWS = 200
        const val TABLE_LIST_SQL =
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"

        // Unlikely to collide with a real application column; if it ever does, the SELECT below
        // simply fails and query() falls back to the plain, rowid-less form.
        const val ROWID_ALIAS = "__devconsole_rowid__"
        const val WITH_KEYWORD = "WITH"
        val READ_ONLY_KEYWORDS = setOf("SELECT", "PRAGMA", "EXPLAIN")
        val SENSITIVE_COLUMN_FRAGMENTS =
            setOf(
                "password",
                "passwd",
                "secret",
                "token",
                "auth",
                "credential",
                "session",
                "email",
                "phone",
                "mobile",
                "address",
                "postcode",
                "zip",
                "ssn",
                "national_id",
                "nid",
                "passport",
                "dob",
                "birth",
                "card",
                "iban",
                "account_number",
                "latitude",
                "longitude",
                "location",
            )
    }
}
