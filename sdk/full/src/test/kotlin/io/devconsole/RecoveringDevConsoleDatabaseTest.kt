package io.devconsole

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.devconsole.storage.room.EventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveringDevConsoleDatabaseTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val databaseName = "devconsole-recovery-${UUID.randomUUID()}.db"

    @After
    fun cleanUp() {
        application.deleteDatabase(databaseName)
        File(application.noBackupFilesDir, "devconsole/corrupt")
            .listFiles()
            .orEmpty()
            .filter { quarantine -> quarantine.listFiles().orEmpty().any { it.name == databaseName } }
            .forEach(File::deleteRecursively)
    }

    @Test
    fun `backs up corrupt files before replacing database and reports recovery`() =
        runBlocking {
            var recoveries = 0
            val manager =
                RecoveringDevConsoleDatabase(
                    application = application,
                    databaseName = databaseName,
                    onRecovered = { recoveries++ },
                )
            withContext(Dispatchers.IO) {
                manager.current().eventDao().insertAll(listOf(event("before-recovery")))
                assertEquals(1, manager.current().eventDao().eventCount())

                manager.recover(IllegalStateException("database disk image is malformed"))

                assertEquals(0, manager.current().eventDao().eventCount())
            }

            val quarantines =
                manager
                    .quarantineRoot()
                    .listFiles()
                    .orEmpty()
                    .filter { it.isDirectory }
            assertTrue(
                quarantines.any { quarantine ->
                    quarantine.listFiles().orEmpty().any { it.name == databaseName }
                },
            )
            assertEquals(1, recoveries)
        }

    @Test(expected = IllegalArgumentException::class)
    fun `does not replace database for disk full failures`() {
        RecoveringDevConsoleDatabase(application, databaseName)
            .recover(IllegalStateException("database or disk is full"))
    }

    private fun event(id: String) =
        EventEntity(
            id,
            "session",
            1,
            "system",
            "test",
            1,
            1,
            1,
            "summary",
            null,
            "{}",
            null,
            null,
            1,
        )
}
