/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.storage.room

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs [DevConsoleDatabase.MIGRATION_4_5] against a v4 database populated the way a real retained
 * app run would leave it -- a session, an event, an attachment, and a timeline annotation -- and
 * checks the three things a migration that corrupts retained data would get wrong: the new evidence
 * tables exist, every pre-existing attachment is honestly backfilled to `APPLIED` (every attachment
 * written before this version went through redaction), and no session/event/attachment row is lost.
 */
@RunWith(AndroidJUnit4::class)
class Migration4To5InstrumentedTest {
    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            DevConsoleDatabase::class.java,
        )

    @Test
    fun migratingAPopulatedV4DatabaseAddsEvidenceTablesAndBackfillsRedactionApplicabilityWithoutRowLoss() {
        val v4 = helper.createDatabase(TEST_DB_NAME, 4)
        v4.execSQL(
            "INSERT INTO sessions " +
                "(id, status, started_at_ms, started_at_monotonic_ns, record_count, estimated_bytes) " +
                "VALUES ('session-1', 'COMPLETED', 0, 0, 2, 100)",
        )
        v4.execSQL(
            "INSERT INTO events " +
                "(id, session_id, sequence, plugin_id, type, wall_time_ms, mono_time_ns, " +
                "severity, summary, tags_json, schema_version) " +
                "VALUES ('event-1', 'session-1', 0, 'network', 'network.request', 0, 0, 0, 'GET /orders', '{}', 1)",
        )
        v4.execSQL(
            "INSERT INTO attachments " +
                "(id, event_id, session_id, mime_type, original_length, stored_length, truncated, sha256, " +
                "is_redacted, relative_path, created_wall_time_ms, is_bookmarked, pending_deletion) " +
                "VALUES ('attachment-1', 'event-1', 'session-1', 'application/json', 4, 4, 0, " +
                "'${"a".repeat(64)}', 1, 'session-1/attachments/f.bin', 0, 0, 0)",
        )
        v4.execSQL("INSERT INTO timeline_annotations (event_id, bookmarked, note) VALUES ('event-1', 1, 'investigate')")
        v4.close()

        val v5 = helper.runMigrationsAndValidate(TEST_DB_NAME, 5, true, DevConsoleDatabase.MIGRATION_4_5)

        v5.query("SELECT COUNT(*) FROM evidence_items").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        v5.query("SELECT COUNT(*) FROM evidence_reports").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        v5.query("SELECT redaction_applicability FROM attachments WHERE id = 'attachment-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("APPLIED", cursor.getString(0))
        }
        v5.query("SELECT COUNT(*) FROM sessions WHERE id = 'session-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        v5.query("SELECT COUNT(*) FROM events WHERE id = 'event-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        v5.query("SELECT COUNT(*) FROM attachments WHERE id = 'attachment-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        v5.query("SELECT COUNT(*) FROM timeline_annotations WHERE event_id = 'event-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        v5.close()
    }

    @Test
    fun evidenceItemsEnforceOneFlagPerSubjectAndEvidenceReportsKeyOnSession() {
        helper.createDatabase(TEST_DB_NAME, 4).close()
        val v5 = helper.runMigrationsAndValidate(TEST_DB_NAME, 5, true, DevConsoleDatabase.MIGRATION_4_5)

        v5.execSQL(
            "INSERT INTO evidence_items (id, session_id, kind, subject_id, label, flagged_at_ms, snapshot_json) " +
                "VALUES ('evidence-1', 'session-1', 'NETWORK', 'network-1', 'GET /orders', 0, '{}')",
        )
        v5.execSQL(
            "INSERT INTO evidence_reports (session_id, severity, updated_at_ms) VALUES ('session-1', 'MAJOR', 0)",
        )

        var constraintViolated = false
        try {
            v5.execSQL(
                "INSERT INTO evidence_items (id, session_id, kind, subject_id, label, flagged_at_ms, snapshot_json) " +
                    "VALUES ('evidence-2', 'session-1', 'NETWORK', 'network-1', 'duplicate subject', 0, '{}')",
            )
        } catch (expected: android.database.sqlite.SQLiteConstraintException) {
            constraintViolated = true
        }

        assertTrue(constraintViolated)
        v5.close()
    }

    private companion object {
        const val TEST_DB_NAME = "migration-4-5-test.db"
    }
}
