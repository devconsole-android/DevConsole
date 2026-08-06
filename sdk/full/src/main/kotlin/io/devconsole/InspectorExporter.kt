/**
 * @author Shakib
 * @since 25/07/26
 */
package io.devconsole

import android.content.Context
import io.devconsole.export.DEFAULT_EXPORT_LIMIT_BYTES
import io.devconsole.export.EventExportWriter
import io.devconsole.export.ExportRequest
import io.devconsole.export.ExportResult
import io.devconsole.network.ExportSelection
import io.devconsole.network.NetworkExport
import io.devconsole.network.NetworkTransaction
import io.devconsole.network.NetworkTransactionStore
import io.devconsole.network.resolveExportSelection
import io.devconsole.server.api.ServerMetadata
import io.devconsole.storage.api.StoredEvent
import io.devconsole.timeline.InMemoryTimelineAnnotations
import io.devconsole.timeline.Timeline
import io.devconsole.timeline.TimelineAnnotations
import io.devconsole.timeline.TimelinePage
import io.devconsole.timeline.TimelineQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Exports already-captured, already-redacted network traffic to the app's own storage. HAR and
 * Postman both read the same bounded transaction page the server's `/api/v1/network/har` and
 * `/api/v1/network/postman` endpoints use, resolved through the same [ExportSelection] so the
 * in-app exports and the browser exports can never disagree about which rows a given selection
 * means.
 */
interface InspectorExporter {
    fun exportHar(selection: ExportSelection = ExportSelection.All): ExportOutcome

    fun exportPostman(selection: ExportSelection = ExportSelection.All): ExportOutcome

    /**
     * Bundles the whole current session -- every timeline event (via [EventExportWriter], the same
     * redaction-and-manifest engine `/api/v1/exports` uses, paginated until exhausted so a session
     * with more than one page of events is never truncated), every bookmark/note and attachment on
     * those events, every captured network transaction *for this session* as both HAR and Postman,
     * and non-sensitive app metadata -- into one ZIP.
     */
    fun exportSessionZip(): ExportOutcome
}

sealed interface ExportOutcome {
    data class Written(
        val path: String,
        val sizeBytes: Long,
    ) : ExportOutcome

    data class Failed(
        val message: String,
    ) : ExportOutcome
}

/**
 * The bookmark/note and attachment sources [AndroidInspectorExporter.exportSessionZip] enriches the
 * bundle with -- grouped into one value so the constructor doesn't grow a parameter per enrichment
 * source. Both default to "nothing to add", matching the exporter's pre-existing behavior for
 * callers that don't wire real ones.
 */
internal data class SessionExportSources(
    val annotationsSupplier: () -> TimelineAnnotations = { InMemoryTimelineAnnotations() },
    val attachmentReader: suspend (String) -> ByteArray? = { null },
)

internal class AndroidInspectorExporter(
    private val context: Context,
    private val networkTransactionStore: NetworkTransactionStore,
    private val timelineSupplier: () -> Timeline? = { null },
    private val sessionIdSupplier: () -> String? = { null },
    private val metadataSupplier: () -> ServerMetadata = { ServerMetadata() },
    private val sessionExportSources: SessionExportSources = SessionExportSources(),
) : InspectorExporter {
    /** Disambiguates filenames generated within the same millisecond; see [nextExportSuffix]. */
    private val exportSequence = AtomicLong(0)

    /**
     * Ceiling for the merged session ZIP, matching [EventExportWriter]'s own default. A `var`
     * rather than a constructor parameter so the constructor doesn't grow past the parameter-count
     * threshold just to make this one knob test-overridable.
     */
    internal var maxSessionZipBytes: Long = DEFAULT_EXPORT_LIMIT_BYTES

    override fun exportHar(selection: ExportSelection): ExportOutcome {
        val transactions =
            networkTransactionStore.resolveExportSelection(selection)
                ?: return ExportOutcome.Failed(INVALID_SELECTION_MESSAGE)
        return write("devconsole-${System.currentTimeMillis()}-${nextExportSuffix()}.har") {
            NetworkExport.toHarTransactions(transactions)
        }
    }

    override fun exportPostman(selection: ExportSelection): ExportOutcome {
        val transactions =
            networkTransactionStore.resolveExportSelection(selection)
                ?: return ExportOutcome.Failed(INVALID_SELECTION_MESSAGE)
        return write("devconsole-${System.currentTimeMillis()}-${nextExportSuffix()}.postman_collection.json") {
            NetworkExport.toPostman(transactions)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun exportSessionZip(): ExportOutcome {
        val exportDir = File(context.filesDir, EXPORT_DIR_NAME).apply { mkdirs() }
        pruneOldExports(exportDir)
        val timestamp = System.currentTimeMillis()
        val suffix = nextExportSuffix()
        val timelinePart = File(exportDir, "devconsole-session-$timestamp-$suffix.timeline.zip.tmp")
        val events = timelineEvents()
        val sessionId = sessionIdSupplier() ?: events.lastOrNull()?.sessionId ?: "current"
        var bundleTemp: File? = null
        return try {
            // writeAsync, not write: write() refuses to run on Android's main thread, and this method
            // is already expected to run off it (the ViewModel dispatches every export on Dispatchers.IO),
            // so writeAsync's own withContext(Dispatchers.IO) is a same-thread no-op in production while
            // still being the entry point EventExportWriter documents for Android callers.
            val timelineResult =
                runBlocking {
                    val annotations = sessionExportSources.annotationsSupplier()
                    EventExportWriter().writeAsync(
                        ExportRequest(sessionId = sessionId, events = events, destination = timelinePart)
                            .withAnnotations(events.associate { it.id to annotations.get(it.id) })
                            .withAttachments(collectAttachments(events)),
                    )
                }
            when (timelineResult) {
                is ExportResult.Success -> {
                    val transactions =
                        networkTransactionStore
                            .resolveExportSelection(ExportSelection.All)
                            ?.filter { it.sessionId == sessionId }
                            ?: return ExportOutcome.Failed(INVALID_SELECTION_MESSAGE)
                    val finalFile = File(exportDir, "devconsole-session-$timestamp-$suffix.zip")
                    val temp = File(exportDir, "devconsole-session-$timestamp-$suffix.zip.tmp")
                    bundleTemp = temp
                    bundleSessionZip(timelineResult.file, temp, transactions)
                    publishAtomically(temp, finalFile)
                    bundleTemp = null
                    if (finalFile.length() > maxSessionZipBytes) {
                        finalFile.delete()
                        ExportOutcome.Failed(ExportResult.ExceedsSizeLimit.GUIDANCE)
                    } else {
                        ExportOutcome.Written(path = finalFile.absolutePath, sizeBytes = finalFile.length())
                    }
                }
                ExportResult.ExceedsSizeLimit -> ExportOutcome.Failed(ExportResult.ExceedsSizeLimit.GUIDANCE)
                ExportResult.Unavailable -> ExportOutcome.Failed("Session export is unavailable")
            }
        } catch (failure: Exception) {
            ExportOutcome.Failed(failure.message ?: failure.javaClass.simpleName)
        } finally {
            // timelinePart is EventExportWriter's own atomically-published destination -- it only
            // exists on the Success path, but deleting an absent file is a no-op. bundleTemp is the
            // in-progress merged ZIP; non-null here only if the merge/atomic-move step never
            // completed (an exception, or the invalid-selection early return above), so cleaning it
            // up here means a failed export never leaves a `.zip.tmp` behind in the FileProvider-
            // covered export directory.
            timelinePart.delete()
            bundleTemp?.delete()
        }
    }

    private fun timelineEvents(): List<StoredEvent> {
        val timeline = timelineSupplier() ?: return emptyList()
        val events = mutableListOf<StoredEvent>()
        var cursor: String? = null
        var hasMore = true
        while (hasMore) {
            val query = TimelineQuery(limit = TimelineQuery.MAX_PAGE_LIMIT, cursor = cursor)
            val success = timeline.page(query) as? TimelinePage.Success
            events += success?.events.orEmpty()
            hasMore = success?.hasMore ?: false
            cursor = success?.nextCursor
        }
        return events
    }

    /**
     * Reads every attachment referenced by [events], the same way `/api/v1/exports` does: best
     * effort, skipping (never failing the whole export over) an id the reader can't produce bytes
     * for.
     */
    private suspend fun collectAttachments(events: List<StoredEvent>): Map<String, ByteArray> =
        buildMap {
            events
                .mapNotNull(StoredEvent::attachmentId)
                .distinct()
                .forEach { attachmentId ->
                    try {
                        sessionExportSources.attachmentReader(attachmentId)?.let { put(attachmentId, it) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // A missing/corrupt attachment degrades this export, not the host app.
                    }
                }
        }

    /**
     * Copies every entry [EventExportWriter] already wrote into [destination], then adds the
     * network HAR/Postman exports (already narrowed to the current session by the caller) and app
     * metadata alongside them. A straight entry-by-entry copy rather than a nested zip-in-zip: a
     * diagnostic bundle should unzip to one flat, browsable tree.
     */
    private fun bundleSessionZip(
        timelinePart: File,
        destination: File,
        transactions: List<NetworkTransaction>,
    ) {
        ZipOutputStream(destination.outputStream().buffered()).use { output ->
            ZipInputStream(timelinePart.inputStream().buffered()).use { input ->
                generateSequence(input.nextEntry) { input.nextEntry }.forEach { entry ->
                    output.putNextEntry(ZipEntry(entry.name))
                    input.copyTo(output)
                    output.closeEntry()
                }
            }
            output.writeText("network.har", NetworkExport.toHarTransactions(transactions))
            output.writeText("network.postman_collection.json", NetworkExport.toPostman(transactions))
            output.writeText("metadata.json", metadataSupplier().toJson())
        }
    }

    /**
     * File I/O is arbitrary storage failure (full disk, permission changes); any failure must
     * surface as [ExportOutcome.Failed] rather than crash the inspector UI.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun write(
        fileName: String,
        render: () -> String,
    ): ExportOutcome =
        runCatching {
            val exportDir = File(context.filesDir, EXPORT_DIR_NAME).apply { mkdirs() }
            pruneOldExports(exportDir)
            val file = File(exportDir, fileName)
            file.writeText(render())
            ExportOutcome.Written(path = file.absolutePath, sizeBytes = file.length())
        }.getOrElse { failure -> ExportOutcome.Failed(failure.message ?: failure.javaClass.simpleName) }

    /**
     * `filesDir/devconsole-exports` is user-triggered but never emptied on its own, so a host that
     * exports often would otherwise accumulate artifacts (and Android auto-backup weight) forever.
     * Run before every new export, keeping only the [MAX_RETAINED_EXPORTS] - 1 most recently
     * modified existing files so the directory settles at [MAX_RETAINED_EXPORTS] once the new export
     * lands.
     */
    private fun pruneOldExports(exportDir: File) {
        val files = exportDir.listFiles()?.filter(File::isFile) ?: return
        if (files.size < MAX_RETAINED_EXPORTS) return
        files
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .drop(MAX_RETAINED_EXPORTS - 1)
            .forEach { it.delete() }
    }

    /** Monotonically increasing, so two exports started in the same millisecond never collide. */
    private fun nextExportSuffix(): String = exportSequence.incrementAndGet().toString()

    private companion object {
        const val EXPORT_DIR_NAME = "devconsole-exports"
        const val MAX_RETAINED_EXPORTS = 5
        const val INVALID_SELECTION_MESSAGE = "Export selection could not be resolved"

        /**
         * `java.nio.file` is API 26 and this SDK's `minSdk` is 24, so the previous `Files.move`
         * crashed on Android 7 the first time an export was published. `renameTo` is the API-1
         * equivalent and is atomic here for the same reason the old call was: source and
         * destination always live in the same export directory, so the rename never crosses a
         * filesystem boundary.
         *
         * `renameTo` will not overwrite on every platform, hence the delete-then-retry. The copy
         * is the last resort and is deliberately not atomic — a reader that catches a partial file
         * is still better than an export that silently never appears.
         */
        fun publishAtomically(
            source: File,
            destination: File,
        ) {
            if (source.renameTo(destination)) return
            destination.delete()
            if (source.renameTo(destination)) return
            source.copyTo(destination, overwrite = true)
            source.delete()
        }
    }
}

private fun ZipOutputStream.writeText(
    name: String,
    content: String,
) {
    putNextEntry(ZipEntry(name))
    write(content.encodeToByteArray())
    closeEntry()
}

private fun ServerMetadata.toJson(): String =
    "{\"appDisplayName\":\"${appDisplayName.escapeJson()}\"," +
        "\"appPackageName\":\"${appPackageName.escapeJson()}\"," +
        "\"appVersionName\":\"${appVersionName.escapeJson()}\"," +
        "\"buildVariant\":\"${buildVariant.escapeJson()}\"}"
