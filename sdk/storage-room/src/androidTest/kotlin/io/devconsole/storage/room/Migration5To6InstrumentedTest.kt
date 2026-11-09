/**
 * @author Shakib
 * @since 03/11/26
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

/** [DevConsoleDatabase.MIGRATION_5_6] adds `sessions.pinned`, defaulting every existing run to unpinned. */
@RunWith(AndroidJUnit4::class)
class Migration5To6InstrumentedTest {
    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            DevConsoleDatabase::class.java,
        )

    @Test
    fun migratingAV5DatabaseKeepsEveryRunAndLeavesThemUnpinned() {
        val v5 = helper.createDatabase(TEST_DB_NAME, 5)
        v5.execSQL(
            "INSERT INTO sessions " +
                "(id, status, started_at_ms, started_at_monotonic_ns, record_count, estimated_bytes) " +
                "VALUES ('session-1', 'COMPLETED', 0, 0, 2, 100)",
        )
        v5.close()

        val v6 = helper.runMigrationsAndValidate(TEST_DB_NAME, 6, true, DevConsoleDatabase.MIGRATION_5_6)

        v6.query("SELECT pinned FROM sessions WHERE id = 'session-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        v6.close()
    }

    private companion object {
        const val TEST_DB_NAME = "migration-5-6-test"
    }
}
