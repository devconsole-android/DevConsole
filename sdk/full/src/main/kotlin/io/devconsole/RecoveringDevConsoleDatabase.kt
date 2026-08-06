package io.devconsole

import android.app.Application
import androidx.room.Room
import io.devconsole.storage.room.DevConsoleDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * Owns the process-local Room instance and replaces it only after preserving corrupt database
 * artifacts in app-private storage. Recovery is deliberately limited to explicit corruption
 * signatures; disk-full, permission, and programming failures must never destroy durable data.
 */
internal class RecoveringDevConsoleDatabase(
    private val application: Application,
    private val databaseName: String,
    private val maxQuarantines: Int = DEFAULT_MAX_QUARANTINES,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onRecovered: () -> Unit = {},
) {
    init {
        require(databaseName.isNotBlank()) { "databaseName must not be blank" }
        require(maxQuarantines > 0) { "maxQuarantines must be positive" }
    }

    @Volatile
    private var database: DevConsoleDatabase = buildDatabase()

    fun current(): DevConsoleDatabase = database

    @Synchronized
    fun recover(cause: Throwable) {
        require(cause.hasExplicitSqliteCorruptionSignature()) {
            "Database replacement requires an explicit SQLite corruption signature"
        }

        database.close()
        val sourceFiles = databaseFiles().filter(File::exists)
        val quarantine =
            if (sourceFiles.isEmpty()) {
                null
            } else {
                quarantine(sourceFiles)
            }

        val deleted = application.deleteDatabase(databaseName)
        check(deleted || databaseFiles().none(File::exists)) {
            "Corrupt database was preserved${quarantine?.let { " at ${it.absolutePath}" }.orEmpty()}, " +
                "but Android could not remove the active files"
        }

        database = buildDatabase()
        pruneOldQuarantines()
        runCatching(onRecovered)
    }

    internal fun quarantineRoot(): File = File(application.noBackupFilesDir, QUARANTINE_DIRECTORY)

    private fun buildDatabase(): DevConsoleDatabase =
        Room
            .databaseBuilder(application, DevConsoleDatabase::class.java, databaseName)
            .addMigrations(DevConsoleDatabase.MIGRATION_1_2)
            .addMigrations(DevConsoleDatabase.MIGRATION_2_3)
            .addMigrations(DevConsoleDatabase.MIGRATION_3_4)
            .addMigrations(DevConsoleDatabase.MIGRATION_4_5)
            .build()

    private fun databaseFiles(): List<File> {
        val databaseFile = application.getDatabasePath(databaseName)
        return listOf(
            databaseFile,
            File(databaseFile.path + "-wal"),
            File(databaseFile.path + "-shm"),
        )
    }

    private fun quarantine(sourceFiles: List<File>): File {
        val root = quarantineRoot().also { check(it.mkdirs() || it.isDirectory) }
        val identifier = "${clock()}-${UUID.randomUUID()}"
        val staging = File(root, ".$identifier.staging")
        val destination = File(root, identifier)
        check(staging.mkdir()) { "Could not create database quarantine staging directory" }

        try {
            sourceFiles.forEach { source ->
                copyAndSync(source, File(staging, source.name))
            }
            check(staging.renameTo(destination)) {
                "Could not publish database quarantine atomically"
            }
        } catch (failure: Throwable) {
            staging.deleteRecursively()
            throw failure
        }
        return destination
    }

    private fun copyAndSync(
        source: File,
        destination: File,
    ) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        check(destination.length() == source.length()) {
            "Database quarantine copy was truncated for ${source.name}"
        }
    }

    private fun pruneOldQuarantines() {
        quarantineRoot()
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedByDescending(File::lastModified)
            .drop(maxQuarantines)
            .forEach(File::deleteRecursively)
    }

    companion object {
        private const val QUARANTINE_DIRECTORY = "devconsole/corrupt"
        private const val DEFAULT_MAX_QUARANTINES = 3
    }
}

private fun Throwable.hasExplicitSqliteCorruptionSignature(): Boolean =
    generateSequence(this) { it.cause }
        .any { failure ->
            failure.javaClass.simpleName.contains("SQLiteDatabaseCorruptException") ||
                failure.message.orEmpty().contains("database disk image is malformed", ignoreCase = true) ||
                failure.message.orEmpty().contains("file is not a database", ignoreCase = true)
        }
