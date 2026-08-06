package io.devconsole.export

import io.devconsole.storage.api.RedactionApplicability
import io.devconsole.storage.api.StoredEvent
import io.devconsole.timeline.TimelineAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

class EventExportWriterTest {
    @Test
    fun `re-redacts timeline content in the export archive`() {
        val directory = Files.createTempDirectory("devconsole-export-test").toFile()
        try {
            val destination = File(directory, "export.zip")
            val result =
                EventExportWriter().write(
                    ExportRequest(
                        sessionId = "session-1",
                        destination = destination,
                        events = listOf(event(summary = "request Bearer export-secret")),
                    ),
                )

            assertTrue(result is ExportResult.Success)
            ZipFile(destination).use { zip ->
                val timeline = zip.getInputStream(zip.getEntry("timeline.jsonl")).bufferedReader().readText()
                assertFalse(timeline.contains("export-secret"))
                assertTrue(timeline.contains("<redacted>"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `refuses exports above the configured size limit`() {
        val directory = Files.createTempDirectory("devconsole-export-limit-test").toFile()
        try {
            val result =
                EventExportWriter().write(
                    ExportRequest(
                        sessionId = "session-1",
                        destination = File(directory, "export.zip"),
                        events = listOf(event(summary = "large")),
                        maxBytes = 1,
                    ),
                )

            assertEquals(ExportResult.ExceedsSizeLimit, result)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `estimateBytes matches the figure the size gate trusts, without touching disk`() {
        val directory = Files.createTempDirectory("devconsole-export-estimate-test").toFile()
        try {
            val request =
                ExportRequest(
                    sessionId = "session-1",
                    destination = File(directory, "export.zip"),
                    events = listOf(event(summary = "request")),
                )
            val writer = EventExportWriter()

            val estimated = writer.estimateBytes(request)
            assertTrue(directory.listFiles().isNullOrEmpty())

            // The estimate is exactly what write() itself gates on: sized just below it, the export
            // is refused; sized to fit it exactly, the export succeeds.
            val refused = writer.write(request.copy(maxBytes = estimated - 1))
            val accepted = writer.write(request.copy(maxBytes = estimated))

            assertTrue(estimated > 0)
            assertEquals(ExportResult.ExceedsSizeLimit, refused)
            assertTrue(accepted is ExportResult.Success)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `estimateBytes grows once an attachment is included and shrinks for metadata-only exports`() {
        val withoutAttachment =
            ExportRequest(
                sessionId = "session-1",
                destination = File("unused-1.zip"),
                events = listOf(event(summary = "request", attachmentId = "attachment-1")),
            )
        val withAttachment =
            withoutAttachment.withAttachments(mapOf("attachment-1" to ByteArray(4_096)))
        val metadataOnly = withAttachment.withMetadataOnly()
        val writer = EventExportWriter()

        val baseline = writer.estimateBytes(withoutAttachment)
        val withBytes = writer.estimateBytes(withAttachment)
        val metadataOnlyEstimate = writer.estimateBytes(metadataOnly)

        assertTrue(withBytes > baseline)
        assertTrue(metadataOnlyEstimate < withBytes)
    }

    @Test
    fun `includes bookmarks and re-redacted notes in timeline export`() {
        val directory = Files.createTempDirectory("devconsole-export-annotation-test").toFile()
        try {
            val destination = File(directory, "export.zip")
            val request =
                ExportRequest(
                    sessionId = "session-1",
                    destination = destination,
                    events = listOf(event(summary = "request")),
                ).withAnnotations(
                    mapOf(
                        "event-1" to
                            TimelineAnnotation(
                                bookmarked = true,
                                note = "Authorization: Bearer note-secret",
                            ),
                    ),
                )

            assertTrue(EventExportWriter().write(request) is ExportResult.Success)
            ZipFile(destination).use { zip ->
                val timeline = zip.getInputStream(zip.getEntry("timeline.jsonl")).bufferedReader().readText()
                assertTrue(timeline.contains("\"bookmarked\":true"))
                assertTrue(timeline.contains("\"note\":\"Authorization: <redacted>\""))
                assertFalse(timeline.contains("note-secret"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `applies selected event and inclusive time range scopes`() {
        val directory = Files.createTempDirectory("devconsole-export-scope-test").toFile()
        try {
            val selectedDestination = File(directory, "selected.zip")
            val rangedDestination = File(directory, "ranged.zip")
            val events =
                listOf(
                    event(id = "event-1", wallTimeMs = 100, summary = "first"),
                    event(id = "event-2", wallTimeMs = 200, summary = "second"),
                    event(id = "event-3", wallTimeMs = 300, summary = "third"),
                )

            EventExportWriter().write(
                ExportRequest("session-1", events, selectedDestination)
                    .withScope(ExportScope.EventIds(setOf("event-2"))),
            )
            EventExportWriter().write(
                ExportRequest("session-1", events, rangedDestination)
                    .withScope(ExportScope.TimeRange(fromEpochMs = 100, toEpochMs = 200)),
            )

            assertEquals(listOf("event-2"), selectedDestination.timelineIds())
            assertEquals(listOf("event-1", "event-2"), rangedDestination.timelineIds())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `manifest records exact sha256 and uncompressed size for every payload file`() {
        val directory = Files.createTempDirectory("devconsole-export-integrity-test").toFile()
        try {
            val destination = File(directory, "export.zip")

            assertTrue(
                EventExportWriter().write(
                    ExportRequest(
                        "session-1",
                        listOf(event(summary = "integrity")),
                        destination,
                    ),
                ) is ExportResult.Success,
            )

            ZipFile(destination).use { zip ->
                val manifest = zip.readText("manifest.json")
                val payloadEntries =
                    zip
                        .entries()
                        .asSequence()
                        .filter { it.name != "manifest.json" }
                        .toList()
                payloadEntries.forEach { entry ->
                    val bytes = zip.getInputStream(entry).readBytes()
                    assertTrue(
                        "missing integrity metadata for ${entry.name}: $manifest",
                        manifest.contains(
                            "\"path\":\"${entry.name}\",\"sha256\":\"${bytes.sha256()}\",\"bytes\":${bytes.size}",
                        ),
                    )
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `metadata-only mode omits payload and attachments and a rejected export is atomic`() {
        val directory = Files.createTempDirectory("devconsole-export-atomic-test").toFile()
        try {
            val metadataDestination = File(directory, "metadata.zip")
            val destination = File(directory, "existing.zip").apply { writeText("preserve-me") }
            val attached =
                event(
                    summary = "request",
                    payloadJson = "{\"token\":\"do-not-export\"}",
                    attachmentId = "capture/body",
                )
            val metadataResult =
                EventExportWriter().write(
                    ExportRequest("session-1", listOf(attached), metadataDestination)
                        .withMetadataOnly()
                        .withAttachments(mapOf("capture/body" to "binary-secret".encodeToByteArray())),
                )
            val rejected =
                EventExportWriter().write(
                    ExportRequest("session-1", listOf(attached), destination, maxBytes = 1),
                )

            assertTrue(metadataResult is ExportResult.Success)
            ZipFile(metadataDestination).use { zip ->
                val timeline = zip.readText("timeline.jsonl")
                assertFalse(timeline.contains("do-not-export"))
                assertTrue(zip.entries().asSequence().none { it.name.startsWith("attachments/") })
            }
            assertEquals(ExportResult.ExceedsSizeLimit, rejected)
            assertEquals("preserve-me", destination.readText())
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            directory.deleteRecursively()
        }
    }

    // ============================================================================================
    // ExportScope.Evidence (C -- the evidence bundle)
    // ============================================================================================

    @Test
    fun `evidence bundle contains every named file with the requested content`() {
        val directory = Files.createTempDirectory("devconsole-evidence-export-test").toFile()
        try {
            val destination = File(directory, "evidence.zip")
            val request = evidenceRequest(destination)

            assertTrue(EventExportWriter().write(request) is ExportResult.Success)
            ZipFile(destination).use { zip ->
                assertTrue(zip.readText("report.md").contains("QA Evidence Report"))
                assertTrue(zip.readText("report.json").contains("\"items\""))
                assertTrue(zip.readText("network.har").contains("har-content"))
                assertTrue(zip.readText("postman_collection.json").contains("postman-content"))
                assertTrue(zip.readText("session.json").contains("com.example.app"))
                assertEquals("screenshot-bytes", zip.readText("attachments/screenshots/shot.png"))
                assertEquals("body-bytes", zip.readText("attachments/bodies/body.bin"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `evidence manifest marks a screenshot NOT_APPLICABLE and everything else APPLIED`() {
        val directory = Files.createTempDirectory("devconsole-evidence-manifest-test").toFile()
        try {
            val destination = File(directory, "evidence.zip")
            assertTrue(EventExportWriter().write(evidenceRequest(destination)) is ExportResult.Success)

            ZipFile(destination).use { zip ->
                val manifest = zip.readText("manifest.json")
                assertTrue(manifest.contains("\"format\":\"devconsole-evidence-bundle-v1\""))
                assertTrue(manifest.contains("\"itemCount\":2"))
                assertTrue(
                    "screenshot attachment must be visibly marked unredacted",
                    manifest.contains(
                        "\"path\":\"attachments/screenshots/shot.png\"," +
                            "\"sha256\":\"${"screenshot-bytes".encodeToByteArray().sha256()}\"," +
                            "\"bytes\":16,\"redactionApplicability\":\"NOT_APPLICABLE\"",
                    ),
                )
                assertTrue(
                    "a captured body is treated as already-redacted text",
                    manifest.contains(
                        "\"path\":\"attachments/bodies/body.bin\"," +
                            "\"sha256\":\"${"body-bytes".encodeToByteArray().sha256()}\"," +
                            "\"bytes\":10,\"redactionApplicability\":\"APPLIED\"",
                    ),
                )
                assertTrue(
                    "report.md is text content and always APPLIED",
                    manifest.contains("\"path\":\"report.md\"") &&
                        manifest.contains("\"redactionApplicability\":\"APPLIED\""),
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `evidence bundle re-redacts report text on the way out`() {
        val directory = Files.createTempDirectory("devconsole-evidence-redaction-test").toFile()
        try {
            val destination = File(directory, "evidence.zip")
            val request =
                evidenceRequest(destination).withEvidenceBundle(
                    baseEvidenceBundle().copy(
                        reportMarkdown = "Summary: Authorization: Bearer report-secret",
                    ),
                )

            assertTrue(EventExportWriter().write(request) is ExportResult.Success)
            ZipFile(destination).use { zip ->
                val reportMd = zip.readText("report.md")
                assertFalse(reportMd.contains("report-secret"))
                assertTrue(reportMd.contains("<redacted>"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `evidence bundle refuses to write without evidenceBundle content and reports size-limit truncation`() {
        val directory = Files.createTempDirectory("devconsole-evidence-unavailable-test").toFile()
        try {
            val missingBundle =
                ExportRequest(
                    sessionId = "session-1",
                    destination = File(directory, "unused.zip"),
                    events = emptyList(),
                ).withScope(ExportScope.Evidence)
            val oversized = evidenceRequest(File(directory, "oversized.zip"), maxBytes = 1)

            assertEquals(ExportResult.Unavailable, EventExportWriter().write(missingBundle))
            assertEquals(ExportResult.ExceedsSizeLimit, EventExportWriter().write(oversized))
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `an oversized evidence bundle is refused before a single attachment is read into memory`() {
        val directory = Files.createTempDirectory("devconsole-evidence-preflight-test").toFile()
        try {
            var opened = false
            val hugeAttachment =
                EvidenceBundleAttachment(
                    path = "attachments/bodies/huge.bin",
                    // sizeBytes alone already exceeds maxBytes below; open() must never run.
                    sizeBytes = 500L * 1024L * 1024L,
                    redactionApplicability = RedactionApplicability.APPLIED,
                    open = {
                        opened = true
                        ByteArray(1)
                    },
                )
            val request =
                evidenceRequest(File(directory, "oversized.zip"), maxBytes = 1_000_000L)
                    .withEvidenceBundle(baseEvidenceBundle().copy(attachments = listOf(hugeAttachment)))

            val result = EventExportWriter().write(request)

            assertEquals(ExportResult.ExceedsSizeLimit, result)
            assertFalse("the size gate must reject using metadata alone, never opening the attachment", opened)
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `evidence estimateBytes matches what write() gates on, without touching disk`() {
        val directory = Files.createTempDirectory("devconsole-evidence-estimate-test").toFile()
        try {
            val estimated = EventExportWriter().estimateBytes(evidenceRequest(File(directory, "unused.zip")))
            assertTrue(directory.listFiles().isNullOrEmpty())

            val refused =
                EventExportWriter().write(evidenceRequest(File(directory, "refused.zip"), maxBytes = estimated - 1))
            val accepted =
                EventExportWriter().write(evidenceRequest(File(directory, "accepted.zip"), maxBytes = estimated))

            assertTrue(estimated > 0)
            assertEquals(ExportResult.ExceedsSizeLimit, refused)
            assertTrue(accepted is ExportResult.Success)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun baseEvidenceBundle(): EvidenceBundleContent =
        EvidenceBundleContent(
            reportMarkdown = "# QA Evidence Report\n\nflagged items follow",
            reportJson = "{\"report\":{},\"items\":[{},{}]}",
            networkHar = "{\"log\":\"har-content\"}",
            postmanCollection = "{\"collection\":\"postman-content\"}",
            sessionJson = "{\"applicationId\":\"com.example.app\"}",
            itemCount = 2,
            attachments =
                listOf(
                    evidenceAttachment(
                        path = "attachments/screenshots/shot.png",
                        bytes = "screenshot-bytes".encodeToByteArray(),
                        redactionApplicability = RedactionApplicability.NOT_APPLICABLE,
                    ),
                    evidenceAttachment(
                        path = "attachments/bodies/body.bin",
                        bytes = "body-bytes".encodeToByteArray(),
                        redactionApplicability = RedactionApplicability.APPLIED,
                    ),
                ),
        )

    /** [EvidenceBundleAttachment.open] just replays the fixture's own in-memory bytes. */
    private fun evidenceAttachment(
        path: String,
        bytes: ByteArray,
        redactionApplicability: RedactionApplicability?,
    ): EvidenceBundleAttachment =
        EvidenceBundleAttachment(
            path = path,
            sizeBytes = bytes.size.toLong(),
            redactionApplicability = redactionApplicability,
            open = { bytes },
        )

    private fun evidenceRequest(
        destination: File,
        maxBytes: Long = DEFAULT_EXPORT_LIMIT_BYTES,
    ): ExportRequest =
        ExportRequest(
            sessionId = "session-1",
            destination = destination,
            events = emptyList(),
            maxBytes = maxBytes,
        ).withScope(ExportScope.Evidence).withEvidenceBundle(baseEvidenceBundle())

    private fun event(
        summary: String,
        id: String = "event-1",
        wallTimeMs: Long = 1,
        payloadJson: String? = null,
        attachmentId: String? = null,
    ): StoredEvent =
        StoredEvent(
            id = id,
            sessionId = "session-1",
            sequence = 1,
            pluginId = "system",
            type = "system.event",
            wallTimeMs = wallTimeMs,
            monoTimeNs = 1,
            severity = 1,
            summary = summary,
            payloadJson = payloadJson,
            attachmentId = attachmentId,
        )

    private fun File.timelineIds(): List<String> =
        ZipFile(this).use { zip ->
            Regex("\"id\":\"([^\"]+)\"")
                .findAll(zip.readText("timeline.jsonl"))
                .map { it.groupValues[1] }
                .toList()
        }

    private fun ZipFile.readText(name: String): String = getInputStream(getEntry(name)).bufferedReader().readText()

    private fun ByteArray.sha256(): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(this)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
