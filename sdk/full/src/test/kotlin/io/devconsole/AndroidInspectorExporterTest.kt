/**
 * @author Shakib
 * @since 25/07/26
 */
package io.devconsole

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.devconsole.network.ExportSelection
import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkRequestInput
import io.devconsole.network.NetworkResponseInput
import io.devconsole.network.NetworkTransaction
import io.devconsole.network.NetworkTransactionPage
import io.devconsole.network.NetworkTransactionQuery
import io.devconsole.network.NetworkTransactionStore
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.server.api.ServerMetadata
import io.devconsole.storage.api.StoredEvent
import io.devconsole.timeline.CursorCodec
import io.devconsole.timeline.InMemoryTimeline
import io.devconsole.timeline.InMemoryTimelineAnnotations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipInputStream

private const val SECRET_TOKEN = "sk-super-secret-value-1234567890"

/** Mirrors `AndroidInspectorExporter.EXPORT_DIR_NAME`, which is private to the class under test. */
private const val EXPORT_DIR_NAME_FOR_TESTS = "devconsole-exports"
private const val PRUNE_TEST_BASE_TIME_MS = 1_700_000_000_000L

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidInspectorExporterTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val captureFactory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default()))

    private fun networkStore(): InMemoryNetworkTransactionStore =
        InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))

    private fun recordTransaction(
        store: InMemoryNetworkTransactionStore,
        id: String,
        path: String = "/orders",
        sessionId: String? = null,
    ) {
        val capture =
            captureFactory.capture(
                NetworkRequestInput(
                    method = "GET",
                    url = "https://api.example.test$path",
                    headers = mapOf("Authorization" to "Bearer $SECRET_TOKEN"),
                ),
                NetworkResponseInput(
                    statusCode = 200,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = "{\"ok\":true}".encodeToByteArray(),
                    contentType = "application/json",
                ),
            )
        store.record(
            NetworkTransaction(id, startedAtEpochMs = 0, completedAtEpochMs = 5, capture = capture)
                .withSessionId(sessionId),
        )
    }

    /**
     * Like [recordTransaction], but with a caller-chosen [startedAt] and a path derived from [id]
     * for easy assertion.
     */
    private fun recordTransactionAt(
        store: InMemoryNetworkTransactionStore,
        id: String,
        startedAt: Long,
    ) {
        val capture =
            captureFactory.capture(
                NetworkRequestInput(method = "GET", url = "https://api.example.test/$id"),
                NetworkResponseInput(statusCode = 200),
            )
        store.record(
            NetworkTransaction(id, startedAtEpochMs = startedAt, completedAtEpochMs = startedAt + 5, capture = capture),
        )
    }

    private fun readZipEntries(file: File): Map<String, String> =
        mutableMapOf<String, String>().also { entries ->
            ZipInputStream(file.inputStream()).use { zip ->
                generateSequence(zip.nextEntry) { zip.nextEntry }.forEach { entry ->
                    entries[entry.name] = zip.readBytes().decodeToString()
                }
            }
        }

    /** Always reports an invalid cursor, so callers exercise the "selection could not be resolved" path. */
    private class InvalidCursorNetworkTransactionStore : NetworkTransactionStore {
        override fun record(transaction: NetworkTransaction) = Unit

        override fun page(query: NetworkTransactionQuery): NetworkTransactionPage =
            NetworkTransactionPage(emptyList(), null, false, invalidCursor = true)

        override fun find(id: String): NetworkTransaction? = null

        override fun statusDistribution(): Map<String, Int> = emptyMap()
    }

    @Test
    fun `exportHar writes a HAR file with the captured transactions and no raw secret`() {
        val store = networkStore()
        recordTransaction(store, "tx-1")
        recordTransaction(store, "tx-2")
        val exporter = AndroidInspectorExporter(application, store)

        val outcome = exporter.exportHar()

        val written = outcome as ExportOutcome.Written
        val file = File(written.path)
        assertTrue(file.exists())
        assertEqualsSize(written, file)
        val content = file.readText()
        assertTrue(content.contains("\"log\""))
        assertFalse(content.contains(SECRET_TOKEN))
    }

    @Test
    fun `exportPostman writes a v2_1 collection with the request method, url, and no raw secret`() {
        val store = networkStore()
        recordTransaction(store, "tx-1")
        val exporter = AndroidInspectorExporter(application, store)

        val outcome = exporter.exportPostman()

        val written = outcome as ExportOutcome.Written
        val file = File(written.path)
        assertTrue(file.exists())
        assertEqualsSize(written, file)
        val content = file.readText()
        assertTrue(content.contains("https://schema.getpostman.com/json/collection/v2.1.0/collection.json"))
        assertTrue(content.contains("\"GET\""))
        assertTrue(content.contains("api.example.test"))
        assertFalse(content.contains(SECRET_TOKEN))
    }

    @Test
    fun `exportHar and exportPostman honor an explicit ExportSelection Ids`() {
        val store = networkStore()
        recordTransaction(store, "tx-1", path = "/orders")
        recordTransaction(store, "tx-2", path = "/invoices")
        val exporter = AndroidInspectorExporter(application, store)

        val har = exporter.exportHar(ExportSelection.Ids(setOf("tx-2"))) as ExportOutcome.Written
        val postman = exporter.exportPostman(ExportSelection.Ids(setOf("tx-2"))) as ExportOutcome.Written

        val harContent = File(har.path).readText()
        val postmanContent = File(postman.path).readText()
        assertTrue(harContent.contains("/invoices"))
        assertFalse(harContent.contains("/orders"))
        assertTrue(postmanContent.contains("/invoices"))
        assertFalse(postmanContent.contains("/orders"))
    }

    @Test
    fun `exportSessionZip bundles timeline, network HAR, network Postman, and metadata with no raw secret`() {
        val store = networkStore()
        recordTransaction(store, "tx-1", sessionId = "session-1")
        val timeline =
            InMemoryTimeline(emptyList(), CursorCodec("timeline-cursor-key".encodeToByteArray()))
        timeline.append(
            StoredEvent(
                id = "log-1",
                sessionId = "session-1",
                sequence = 1,
                pluginId = "logs",
                type = "log",
                wallTimeMs = 10,
                monoTimeNs = 10,
                severity = 3,
                summary = "Checkout failed: Authorization Bearer $SECRET_TOKEN",
                tagsJson = "{}",
                payloadJson = null,
            ),
        )
        val exporter =
            AndroidInspectorExporter(
                application,
                store,
                timelineSupplier = { timeline },
                sessionIdSupplier = { "session-1" },
                metadataSupplier = {
                    ServerMetadata(appDisplayName = "Sample App", appPackageName = "com.example.sample")
                },
            )

        val outcome = exporter.exportSessionZip()

        val written = outcome as ExportOutcome.Written
        val file = File(written.path)
        assertTrue(file.exists())
        assertEqualsSize(written, file)
        val entries = readZipEntries(file)
        assertTrue(entries.containsKey("manifest.json"))
        assertTrue(entries.containsKey("timeline.jsonl"))
        assertTrue(entries.containsKey("network.har"))
        assertTrue(entries.containsKey("network.postman_collection.json"))
        assertTrue(entries.containsKey("metadata.json"))
        assertTrue(entries.getValue("network.har").contains("\"log\""))
        assertTrue(entries.getValue("network.postman_collection.json").contains("schema.getpostman.com"))
        assertTrue(entries.getValue("metadata.json").contains("Sample App"))
        assertTrue(entries.getValue("metadata.json").contains("com.example.sample"))
        entries.values.forEach { content -> assertFalse(content.contains(SECRET_TOKEN)) }
    }

    @Test
    fun `exportSessionZip paginates the timeline so events beyond the first page are not dropped`() {
        val store = networkStore()
        val timeline = InMemoryTimeline(emptyList(), CursorCodec("timeline-cursor-key".encodeToByteArray()))
        // 300 events from an earlier, unrelated session...
        repeat(300) { index ->
            timeline.append(logEvent(id = "old-$index", sessionId = "session-old", position = index))
        }
        // ...then 300 events from the current session, so its tail (up to index 599) sits past the
        // first 500-event page.
        repeat(300) { index ->
            timeline.append(logEvent(id = "current-$index", sessionId = "session-current", position = 300 + index))
        }
        val exporter =
            AndroidInspectorExporter(
                application,
                store,
                timelineSupplier = { timeline },
                sessionIdSupplier = { "session-current" },
            )

        val outcome = exporter.exportSessionZip()

        val written = outcome as ExportOutcome.Written
        val entries = readZipEntries(File(written.path))
        val timelineJsonl = entries.getValue("timeline.jsonl")
        assertTrue(timelineJsonl.contains("\"id\":\"current-299\""))
        assertFalse(timelineJsonl.contains("\"id\":\"old-0\""))
    }

    @Test
    fun `exportSessionZip narrows the network trail to the current session, not every captured transaction`() {
        val store = networkStore()
        recordTransaction(store, "tx-current", path = "/current-session-call", sessionId = "session-1")
        recordTransaction(store, "tx-other", path = "/other-session-call", sessionId = "session-2")
        val timeline = InMemoryTimeline(emptyList(), CursorCodec("timeline-cursor-key".encodeToByteArray()))
        val exporter =
            AndroidInspectorExporter(
                application,
                store,
                timelineSupplier = { timeline },
                sessionIdSupplier = { "session-1" },
            )

        val outcome = exporter.exportSessionZip()

        val written = outcome as ExportOutcome.Written
        val entries = readZipEntries(File(written.path))
        assertTrue(entries.getValue("network.har").contains("/current-session-call"))
        assertFalse(entries.getValue("network.har").contains("/other-session-call"))
        assertTrue(entries.getValue("network.postman_collection.json").contains("/current-session-call"))
        assertFalse(entries.getValue("network.postman_collection.json").contains("/other-session-call"))
    }

    @Test
    fun `exportSessionZip manifest omits the appended network, postman, and metadata entries`() {
        // Documents current behavior (see InspectorExporter.bundleSessionZip): manifest.json is
        // computed by EventExportWriter *before* bundleSessionZip copies its entries into the merged
        // archive and appends network.har, network.postman_collection.json, and metadata.json
        // alongside them. Those three appended files are real archive entries but are not described
        // by the manifest's integrity listing -- unlike every EventExportWriter-authored entry, which
        // is (see EventExportWriterTest's "manifest records exact sha256 and uncompressed size").
        val store = networkStore()
        recordTransaction(store, "tx-1", sessionId = "session-1")
        val timeline = InMemoryTimeline(emptyList(), CursorCodec("timeline-cursor-key".encodeToByteArray()))
        val exporter =
            AndroidInspectorExporter(
                application,
                store,
                timelineSupplier = { timeline },
                sessionIdSupplier = { "session-1" },
            )

        val outcome = exporter.exportSessionZip()

        val written = outcome as ExportOutcome.Written
        val entries = readZipEntries(File(written.path))
        val manifest = entries.getValue("manifest.json")
        val manifestPaths = Regex("\"path\":\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        val appendedByBundleSessionZip = setOf("network.har", "network.postman_collection.json", "metadata.json")

        assertTrue(
            "expected the merged archive to contain the appended network/metadata entries",
            entries.keys.containsAll(appendedByBundleSessionZip),
        )
        assertTrue(
            "expected manifest.json to describe at least one EventExportWriter entry",
            manifestPaths.isNotEmpty(),
        )
        assertTrue(
            "manifest.json unexpectedly lists an appended entry it was computed before -- if this now " +
                "passes, bundleSessionZip has started rewriting the manifest and this test should be " +
                "updated to assert full coverage instead",
            manifestPaths.none { it in appendedByBundleSessionZip },
        )
        // Every manifest-listed entry must still be a real archive entry (the manifest describes a
        // strict subset of the archive, not entries that don't exist).
        assertTrue(entries.keys.containsAll(manifestPaths))
    }

    @Test
    fun `exportHar honors ExportSelection TimeRange using the store's inclusive boundary semantics`() {
        val store = networkStore()
        recordTransactionAt(store, "tx-early", startedAt = 50)
        recordTransactionAt(store, "tx-inside", startedAt = 150)
        recordTransactionAt(store, "tx-late", startedAt = 500)
        val exporter = AndroidInspectorExporter(application, store)

        val outcome = exporter.exportHar(ExportSelection.TimeRange(100, 200))

        val written = outcome as ExportOutcome.Written
        val content = File(written.path).readText()
        assertTrue("expected the transaction started inside the inclusive window", content.contains("/tx-inside"))
        assertFalse("expected the transaction started before the window to be excluded", content.contains("/tx-early"))
        assertFalse("expected the transaction started after the window to be excluded", content.contains("/tx-late"))
    }

    @Test
    fun `exportSessionZip includes attachments and bookmarked notes, matching the browser export flow`() {
        val store = networkStore()
        val timeline = InMemoryTimeline(emptyList(), CursorCodec("timeline-cursor-key".encodeToByteArray()))
        timeline.append(
            StoredEvent(
                id = "log-1",
                sessionId = "session-1",
                sequence = 1,
                pluginId = "logs",
                type = "log",
                wallTimeMs = 10,
                monoTimeNs = 10,
                severity = 3,
                summary = "Uploaded receipt",
                tagsJson = "{}",
                attachmentId = "att-1",
            ),
        )
        val annotations =
            InMemoryTimelineAnnotations().apply {
                bookmark("log-1")
                setNote("log-1", "Investigate this one")
            }
        val exporter =
            AndroidInspectorExporter(
                application,
                store,
                timelineSupplier = { timeline },
                sessionIdSupplier = { "session-1" },
                sessionExportSources =
                    SessionExportSources(
                        annotationsSupplier = { annotations },
                        attachmentReader = { attachmentId ->
                            "receipt-bytes".encodeToByteArray().takeIf { attachmentId == "att-1" }
                        },
                    ),
            )

        val outcome = exporter.exportSessionZip()

        val written = outcome as ExportOutcome.Written
        val entries = readZipEntries(File(written.path))
        assertTrue(entries.getValue("bookmarks.json").contains("\"bookmarked\":true"))
        assertTrue(entries.getValue("bookmarks.json").contains("Investigate this one"))
        assertTrue(entries.containsKey("attachments/index.json"))
    }

    @Test
    fun `exportSessionZip fails and deletes the merged artifact when it exceeds the size ceiling`() {
        val store = networkStore()
        recordTransaction(store, "tx-1", sessionId = "session-1")
        val timeline = InMemoryTimeline(emptyList(), CursorCodec("timeline-cursor-key".encodeToByteArray()))
        val exportDir = File(application.filesDir, EXPORT_DIR_NAME_FOR_TESTS)
        val exporter =
            AndroidInspectorExporter(
                application,
                store,
                timelineSupplier = { timeline },
                sessionIdSupplier = { "session-1" },
            ).apply { maxSessionZipBytes = 1 }

        val outcome = exporter.exportSessionZip()

        assertTrue(outcome is ExportOutcome.Failed)
        assertTrue(exportDir.listFiles()?.none { it.name.endsWith(".zip") } ?: true)
    }

    @Test
    fun `exportSessionZip leaves no partial artifact or orphaned temp file when bundling fails`() {
        val store = networkStore()
        recordTransaction(store, "tx-1", sessionId = "session-1")
        val timeline = InMemoryTimeline(emptyList(), CursorCodec("timeline-cursor-key".encodeToByteArray()))
        val exportDir = File(application.filesDir, EXPORT_DIR_NAME_FOR_TESTS)
        val exporter =
            AndroidInspectorExporter(
                application,
                store,
                timelineSupplier = { timeline },
                sessionIdSupplier = { "session-1" },
                metadataSupplier = { error("simulated metadata failure") },
            )

        val outcome = exporter.exportSessionZip()

        assertTrue(outcome is ExportOutcome.Failed)
        assertTrue(exportDir.listFiles()?.filter { it.name.contains("session") }?.isEmpty() ?: true)
    }

    @Test
    fun `all three export methods fail rather than writing an empty artifact on an unresolvable selection`() {
        val store = InvalidCursorNetworkTransactionStore()
        val timeline = InMemoryTimeline(emptyList(), CursorCodec("timeline-cursor-key".encodeToByteArray()))
        val exporter =
            AndroidInspectorExporter(
                application,
                store,
                timelineSupplier = { timeline },
                sessionIdSupplier = { "session-1" },
            )

        assertTrue(exporter.exportHar() is ExportOutcome.Failed)
        assertTrue(exporter.exportPostman() is ExportOutcome.Failed)
        assertTrue(exporter.exportSessionZip() is ExportOutcome.Failed)
    }

    @Test
    fun `exportHar filenames never collide even when called back to back`() {
        val store = networkStore()
        recordTransaction(store, "tx-1")
        val exporter = AndroidInspectorExporter(application, store)

        val first = exporter.exportHar() as ExportOutcome.Written
        val second = exporter.exportHar() as ExportOutcome.Written

        assertFalse(first.path == second.path)
        assertTrue(File(first.path).exists())
        assertTrue(File(second.path).exists())
    }

    @Test
    fun `write prunes older exports so at most the newest 5 remain`() {
        val store = networkStore()
        recordTransaction(store, "tx-1")
        val exporter = AndroidInspectorExporter(application, store)
        val exportDir = File(application.filesDir, EXPORT_DIR_NAME_FOR_TESTS)

        val writtenNames =
            (0 until 7).map { index ->
                val outcome = exporter.exportHar() as ExportOutcome.Written
                val file = File(outcome.path)
                file.setLastModified(PRUNE_TEST_BASE_TIME_MS + index * 1_000L)
                file.name
            }

        val remainingNames =
            exportDir
                .listFiles()
                ?.map { it.name }
                ?.toSet()
                .orEmpty()
        assertEquals(5, remainingNames.size)
        writtenNames.takeLast(5).forEach { name -> assertTrue(name in remainingNames) }
        writtenNames.take(2).forEach { name -> assertFalse(name in remainingNames) }
    }

    private fun logEvent(
        id: String,
        sessionId: String,
        position: Int,
    ) = StoredEvent(
        id = id,
        sessionId = sessionId,
        sequence = position.toLong(),
        pluginId = "logs",
        type = "log",
        wallTimeMs = position.toLong(),
        monoTimeNs = position.toLong(),
        severity = 3,
        summary = "event $position",
        tagsJson = "{}",
    )

    private fun assertEqualsSize(
        written: ExportOutcome.Written,
        file: File,
    ) = org.junit.Assert.assertEquals(file.length(), written.sizeBytes)
}
