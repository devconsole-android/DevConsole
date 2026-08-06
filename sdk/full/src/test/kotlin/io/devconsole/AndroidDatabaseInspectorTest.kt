/**
 * @author Shakib
 * @since 25/07/26
 */
package io.devconsole

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.server.api.DatabaseExecResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidDatabaseInspectorTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val inspector =
        AndroidDatabaseInspector(application, RedactionEngine(RedactionPolicy.default()))

    @Before
    fun createDemoDatabase() {
        val db = application.openOrCreateDatabase("demo.db", Context.MODE_PRIVATE, null)
        db.execSQL("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT)")
        db.execSQL("INSERT INTO users (id, email) VALUES (1, 'a@example.test')")
        db.close()
    }

    @Test
    fun `databases lists app databases and excludes auxiliary journal files`() {
        val names = inspector.databases()

        assertTrue(names.contains("demo.db"))
        assertFalse(names.any { it.endsWith("-journal") })
        assertFalse(names.any { it.endsWith("-wal") })
        assertFalse(names.any { it.endsWith("-shm") })
    }

    @Test
    fun `tables lists user tables with row counts and excludes sqlite internal tables`() {
        val listing = requireNotNull(inspector.tables("demo.db"))

        assertEquals("demo.db", listing.name)
        val users = listing.tables.single { it.name == "users" }
        assertEquals(1L, users.rowCount)
        assertFalse(listing.tables.any { it.name.startsWith("sqlite_") })
    }

    @Test
    fun `query returns columns and rows for a table`() {
        val result = requireNotNull(inspector.query("demo.db", "users"))

        assertEquals(listOf("id", "email"), result.columns)
        assertEquals(1, result.rows.size)
        assertEquals("1", result.rows.single()[0])
        // `email` is a personal-data column, so the cell is redacted rather than shown verbatim.
        assertFalse(result.rows.single()[1].contains("a@example.test"))
        assertFalse(result.truncated)
    }

    @Test
    fun `query includes each row's rowid for safe per-row edits`() {
        val db = application.openOrCreateDatabase("demo.db", Context.MODE_PRIVATE, null)
        db.execSQL("INSERT INTO users (id, email) VALUES (2, 'b@example.test')")
        db.close()

        val result = requireNotNull(inspector.query("demo.db", "users"))

        assertEquals(2, result.rowIds.size)
        // users.id is declared INTEGER PRIMARY KEY, which SQLite makes an alias for rowid itself.
        assertEquals(setOf(1L, 2L), result.rowIds.filterNotNull().toSet())
        // rowid is split into its own field rather than shown as an ordinary, maskable column.
        assertFalse(result.columns.any { it.contains("rowid", ignoreCase = true) })
    }

    @Test
    fun `execute's raw sql results report no rowid since no single table is guaranteed`() {
        val result = inspector.execute("demo.db", "SELECT * FROM users", writeEnabled = false)

        assertTrue(result is DatabaseExecResult.Query)
        assertTrue((result as DatabaseExecResult.Query).result.rowIds.isEmpty())
    }

    @Test
    fun `tables reports the real on-disk database file size`() {
        val listing = requireNotNull(inspector.tables("demo.db"))

        assertEquals(application.getDatabasePath("demo.db").length(), listing.sizeBytes)
        assertTrue(listing.sizeBytes > 0)
    }

    @Test
    fun `query refuses sqlite internal tables that the listing already excludes`() {
        assertEquals(null, inspector.query("demo.db", "sqlite_master"))
        assertEquals(null, inspector.query("demo.db", "SQLITE_MASTER"))
    }

    @Test
    fun `a select statement is always allowed even when writeEnabled is false`() {
        val result = inspector.execute("demo.db", "SELECT * FROM users", writeEnabled = false)

        assertTrue(result is DatabaseExecResult.Query)
        val query = result as DatabaseExecResult.Query
        assertEquals(1, query.result.rows.size)
    }

    @Test
    fun `pragma, explain, and with statements are treated as reads and not blocked`() {
        val pragma = inspector.execute("demo.db", "PRAGMA table_info(users)", writeEnabled = false)
        val explain = inspector.execute("demo.db", "EXPLAIN SELECT * FROM users", writeEnabled = false)
        val with =
            inspector.execute(
                "demo.db",
                "WITH t AS (SELECT * FROM users) SELECT * FROM t",
                writeEnabled = false,
            )

        assertTrue(pragma is DatabaseExecResult.Query)
        assertTrue(explain is DatabaseExecResult.Query)
        assertTrue(with is DatabaseExecResult.Query)
    }

    @Test
    fun `a CTE-prefixed delete is classified as a write and blocked`() {
        // SQLite allows WITH ... DELETE/INSERT/UPDATE, so a bare leading WITH must not be trusted.
        val result = inspector.execute("demo.db", "WITH x AS (SELECT 1) DELETE FROM users", writeEnabled = false)

        assertEquals(DatabaseExecResult.WriteBlocked, result)
        assertEquals(1, requireNotNull(inspector.query("demo.db", "users")).rows.size)
    }

    @Test
    fun `a CTE-prefixed select is still allowed as a read`() {
        val result = inspector.execute("demo.db", "WITH x AS (SELECT 1) SELECT * FROM users", writeEnabled = false)

        assertTrue(result is DatabaseExecResult.Query)
    }

    @Test
    fun `a chain of multiple CTEs prefixing a write is still classified as a write and blocked`() {
        // afterCteKeyword() skips one balanced-parenthesis CTE body at a time and only stops
        // classifying once a comma no longer follows -- this exercises that continuation logic
        // across more than one CTE body rather than just the single-CTE case above.
        val sql = "WITH a AS (SELECT 1), b AS (SELECT 2), c AS (SELECT 3) DELETE FROM users"

        val result = inspector.execute("demo.db", sql, writeEnabled = false)

        assertEquals(DatabaseExecResult.WriteBlocked, result)
        assertEquals(1, requireNotNull(inspector.query("demo.db", "users")).rows.size)
    }

    @Test
    fun `a chain of multiple CTEs prefixing a select is still allowed as a read`() {
        val sql = "WITH a AS (SELECT 1), b AS (SELECT 2), c AS (SELECT 3) SELECT * FROM users"

        val result = inspector.execute("demo.db", sql, writeEnabled = false)

        assertTrue(result is DatabaseExecResult.Query)
    }

    @Test
    fun `execute can read sqlite_master directly, unlike the table listing route`() {
        // query(database, table) deliberately refuses "sqlite_master" (it must not become a
        // back door around the tables() listing filter), but the raw SQL console is a different,
        // more privileged surface: enabling writeEnabled means full raw database access was
        // explicitly granted, so reading schema metadata through a hand-written SELECT is allowed.
        val result =
            inspector.execute(
                "demo.db",
                "SELECT name FROM sqlite_master WHERE type='table'",
                writeEnabled = false,
            )

        assertTrue(result is DatabaseExecResult.Query)
        val names = (result as DatabaseExecResult.Query).result.rows.map { it.single() }
        assertTrue(names.contains("users"))
    }

    @Test
    fun `a multi-statement smuggling attempt does not mutate data`() {
        // Android's SQLite compiles a single statement per call, so the trailing DELETE never runs.
        // The security property under test is that the row survives, whatever the reported result.
        inspector.execute("demo.db", "SELECT 1; DELETE FROM users", writeEnabled = false)

        assertEquals(1, requireNotNull(inspector.query("demo.db", "users")).rows.size)
    }

    @Test
    fun `a mutating pragma cannot persist when writes are disabled`() {
        inspector.execute("demo.db", "PRAGMA user_version=42", writeEnabled = false)

        val readBack = inspector.execute("demo.db", "PRAGMA user_version", writeEnabled = false)
        val rows = (readBack as DatabaseExecResult.Query).result.rows
        assertFalse(rows.any { row -> row.any { it == "42" } })
    }

    @Test
    fun `personal-data columns are redacted even though the shared policy targets http fields`() {
        val db = application.openOrCreateDatabase("pii.db", Context.MODE_PRIVATE, null)
        db.execSQL("CREATE TABLE people (id INTEGER PRIMARY KEY, email TEXT, user_phone TEXT, nickname TEXT)")
        db.execSQL("INSERT INTO people VALUES (1, 'a@example.test', '+8801700000000', 'shakib')")
        db.close()

        val rows = requireNotNull(inspector.query("pii.db", "people")).let { it.columns.zip(it.rows.single()) }.toMap()

        assertFalse(rows.getValue("email").contains("a@example.test"))
        assertFalse(rows.getValue("user_phone").contains("8801700000000"))
        assertEquals("shakib", rows.getValue("nickname"))
    }

    @Test
    fun `a write statement is blocked and leaves data untouched when writeEnabled is false`() {
        val result = inspector.execute("demo.db", "DELETE FROM users", writeEnabled = false)

        assertEquals(DatabaseExecResult.WriteBlocked, result)

        val remaining = requireNotNull(inspector.query("demo.db", "users"))
        assertEquals(1, remaining.rows.size)
    }

    @Test
    fun `a write statement mutates data when writeEnabled is true`() {
        val result = inspector.execute("demo.db", "DELETE FROM users", writeEnabled = true)

        assertTrue(result is DatabaseExecResult.Write)
        assertEquals(1, (result as DatabaseExecResult.Write).affectedRows)

        val remaining = requireNotNull(inspector.query("demo.db", "users"))
        assertTrue(remaining.rows.isEmpty())
    }

    @Test
    fun `an unknown database name returns null or Failed rather than throwing`() {
        assertNull(inspector.tables("does-not-exist.db"))
        assertNull(inspector.query("does-not-exist.db", "users"))
        val result = inspector.execute("does-not-exist.db", "SELECT 1", writeEnabled = false)
        assertTrue(result is DatabaseExecResult.Failed)
    }

    @Test
    fun `a malformed statement returns Failed rather than throwing`() {
        // Malformed but still classified as a read (starts with SELECT) so the failure comes from
        // SQLite itself, not from the write gate.
        val result = inspector.execute("demo.db", "SELECT * FRM users", writeEnabled = false)

        assertTrue(result is DatabaseExecResult.Failed)
    }
}
