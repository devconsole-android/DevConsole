@file:Suppress("ReturnCount") // Guard-clause early returns are the clearest form for these size/IO checks.

package io.devconsole.export

import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.storage.api.RedactionApplicability
import io.devconsole.storage.api.StoredEvent
import io.devconsole.timeline.TimelineAnnotation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

const val DEFAULT_EXPORT_LIMIT_BYTES: Long = 100L * 1024L * 1024L
private const val MAX_TEXT_SNIFF_BYTES = 4L * 1024L * 1024L
private const val STREAM_BUFFER_SIZE = 8 * 1024
private const val SHA256_HEX_LENGTH = 64
private const val README_BYTES = 200L
private const val JSON_PLACEHOLDER_BYTES = 72L
private const val ZIP_ENTRY_OVERHEAD_BYTES = 256L
private const val UTF16_BYTES_PER_CHAR = 2L
private const val ESTIMATED_MANIFEST_BASE_BYTES = 256L
private const val ESTIMATED_MANIFEST_ENTRY_BYTES = 220L
private const val ESTIMATED_ATTACHMENT_DIGEST_BYTES = 64L
private const val ESTIMATED_ATTACHMENT_INDEX_BASE_BYTES = 180L
private const val ESTIMATED_ATTACHMENT_INDEX_OVERHEAD_BYTES = 12L
private const val ESTIMATED_TEXT_BASE_BYTES = 512L
private const val ESTIMATED_ANNOTATION_BASE_BYTES = 128L
private const val ESTIMATED_EVENT_BASE_BYTES = 256L
private const val ESTIMATED_EVENT_OVERHEAD_BYTES = 128L
private const val ESTIMATED_EVIDENCE_SESSION_BYTES = 6L
private const val ESTIMATED_EVIDENCE_ENTRY_BYTES = 180L
private const val ESTIMATED_EVIDENCE_PATH_BYTES = 6L
private const val ESTIMATED_REDACTED_ATTACHMENT_MULTIPLIER = 4L
private const val ESTIMATED_EVIDENCE_MANIFEST_BYTES = 256L
private const val ESTIMATED_ARCHIVE_FIXED_ENTRIES = 5
private const val ESTIMATED_ATTACHMENTLESS_ARCHIVE_ENTRIES = 4
private const val ESTIMATED_ARCHIVE_OPTIONAL_ENTRY_COUNT = 1
private const val MAX_UTF8_ONE_BYTE_CODE = 0x7f
private const val MAX_UTF8_TWO_BYTE_CODE = 0x7ff
private const val UTF8_TWO_BYTE_WIDTH = 2L
private const val UTF8_FOUR_BYTE_WIDTH = 4L
private const val UTF8_THREE_BYTE_WIDTH = 3L
private const val CONTROL_CHARACTER_LIMIT = 0x20
private const val UNICODE_ESCAPE_WIDTH = 4
private const val UTF8_SURROGATE_PAIR_INDEX_ADJUSTMENT = 1

sealed interface ExportScope {
    data object WholeSession : ExportScope

    data class TimeRange(
        val fromEpochMs: Long,
        val toEpochMs: Long,
    ) : ExportScope {
        init {
            require(fromEpochMs >= 0) { "fromEpochMs must not be negative" }
            require(toEpochMs >= fromEpochMs) { "toEpochMs must not precede fromEpochMs" }
        }
    }

    data class EventIds(
        val ids: Set<String>,
    ) : ExportScope {
        init {
            require(ids.isNotEmpty()) { "EventIds scope must not be empty" }
            require(ids.size <= MAX_EVENT_IDS) { "EventIds scope exceeds $MAX_EVENT_IDS ids" }
            require(ids.all { it.isNotBlank() && it.length <= MAX_EVENT_ID_LENGTH }) {
                "EventIds scope contains an invalid id"
            }
        }
    }

    /**
     * The QA evidence bundle: report.md/report.json, network.har/postman_collection.json, session.json,
     * attachments, and a manifest -- built entirely from [ExportRequest.evidenceBundle], not from
     * [ExportRequest.events]. See [EvidenceBundleContent].
     */
    data object Evidence : ExportScope

    companion object {
        const val MAX_EVENT_IDS = 50_000
        const val MAX_EVENT_ID_LENGTH = 256
    }
}

/**
 * One file inside an evidence bundle's `attachments/` tree (a screenshot PNG or a captured request/
 * response body).
 *
 * [sizeBytes] is metadata-only -- the size the attachment was recorded at when it was written to
 * storage. [EventExportWriter] gates the whole bundle on [sizeBytes] *before* ever calling [open], so a
 * full tray of screenshots never has to be pulled into heap just to discover the export was going to be
 * refused anyway. [open] reads the attachment's actual bytes lazily, once, right before it is streamed
 * into the ZIP; the writer never holds more than one attachment's bytes at a time.
 *
 * [redactionApplicability] travels into `manifest.json` verbatim so a screenshot is visibly marked
 * unredacted rather than silently indistinguishable from a redacted text body. `null` means the caller
 * could not determine it (the metadata read failed, or the record is gone) -- the manifest must then
 * report no redaction claim at all, never guess `APPLIED`.
 */
data class EvidenceBundleAttachment(
    val path: String,
    val sizeBytes: Long,
    val redactionApplicability: RedactionApplicability?,
    val open: suspend () -> ByteArray?,
)

/** Lazy, metadata-backed source for one standard session attachment. */
data class ExportAttachmentSource(
    val byteCount: Long,
    val mimeType: String,
    val open: suspend () -> InputStream?,
)

/**
 * Everything [EventExportWriter] needs to assemble an [ExportScope.Evidence] bundle. The caller (the
 * `/api/v1/evidence`-owning route) resolves flagged items, the report draft, live network transactions
 * for HAR/Postman, and session/device metadata, then renders the human- and machine-readable report
 * text itself -- this type is a plain content carrier, not a builder, so [EventExportWriter] stays
 * free of any evidence-domain knowledge beyond "zip these named files and manifest them".
 */
data class EvidenceBundleContent(
    val reportMarkdown: String,
    val reportJson: String,
    val networkHar: String,
    val postmanCollection: String,
    val sessionJson: String,
    val itemCount: Int,
    val attachments: List<EvidenceBundleAttachment> = emptyList(),
)

data class ExportRequest(
    val sessionId: String,
    val events: List<StoredEvent>,
    val destination: File,
    val maxBytes: Long = DEFAULT_EXPORT_LIMIT_BYTES,
) {
    var annotations: Map<String, TimelineAnnotation> = emptyMap()
        private set

    var scope: ExportScope = ExportScope.WholeSession
        private set

    var metadataOnly: Boolean = false
        private set

    /**
     * Optional attachment bytes. Callers must supply bytes that are already safe/redacted; unknown
     * attachment IDs and attachments outside the selected event scope are never included.
     */
    var attachments: Map<String, ByteArray> = emptyMap()
        private set

    internal var attachmentSources: Map<String, ExportAttachmentSource> = emptyMap()
        private set

    /** Only consulted when [scope] is [ExportScope.Evidence]; every other scope ignores this. */
    var evidenceBundle: EvidenceBundleContent? = null
        private set

    fun withAnnotations(annotations: Map<String, TimelineAnnotation>): ExportRequest =
        duplicate().also { it.annotations = annotations.toMap() }

    fun withScope(scope: ExportScope): ExportRequest = duplicate().also { it.scope = scope }

    fun withMetadataOnly(value: Boolean = true): ExportRequest = duplicate().also { it.metadataOnly = value }

    fun withAttachments(attachments: Map<String, ByteArray>): ExportRequest =
        duplicate().also {
            it.attachments = attachments.mapValues { (_, bytes) -> bytes.copyOf() }
        }

    fun withAttachmentSources(sources: Map<String, ExportAttachmentSource>): ExportRequest =
        duplicate().also { it.attachmentSources = sources.toMap() }

    fun withEvidenceBundle(bundle: EvidenceBundleContent): ExportRequest =
        duplicate().also { it.evidenceBundle = bundle }

    private fun duplicate(): ExportRequest =
        copy().also {
            it.annotations = annotations.toMap()
            it.scope = scope
            it.metadataOnly = metadataOnly
            it.attachments = attachments.mapValues { (_, bytes) -> bytes.copyOf() }
            it.attachmentSources = attachmentSources.toMap()
            it.evidenceBundle = evidenceBundle
        }
}

sealed interface ExportResult {
    data class Success(
        val file: File,
        val eventCount: Int,
    ) : ExportResult

    data object ExceedsSizeLimit : ExportResult {
        const val GUIDANCE: String =
            "Retry with metadataOnly=true, selected eventIds, or a smaller inclusive time range."
    }

    data object Unavailable : ExportResult
}

/**
 * Diagnostic ZIP writer with a second redaction boundary and atomic publication.
 *
 * Android callers should use [writeAsync]. The compatibility [write] entry point refuses Android's
 * main thread, so file I/O, hashing, and compression can never stall the host UI.
 */
@Suppress("TooManyFunctions") // Small private payload/manifest/zip helpers kept beside write().
class EventExportWriter(
    private val redaction: RedactionEngine = RedactionEngine(RedactionPolicy.default()),
) {
    /** Blocking compatibility entry point; [isAndroidMainThread] guarantees this never runs there. */
    fun write(request: ExportRequest): ExportResult =
        if (isAndroidMainThread()) ExportResult.Unavailable else runBlocking { writeInternal(request) }

    suspend fun writeAsync(request: ExportRequest): ExportResult =
        withContext(Dispatchers.IO) {
            writeInternal(request)
        }

    /**
     * The same size arithmetic [writeInternal] trusts for its size gate, computed without touching
     * disk -- and, for [ExportScope.Evidence], without reading a single attachment's bytes: attachment
     * size comes from [EvidenceBundleAttachment.sizeBytes] alone. Safe to call from any thread
     * (including Android's main thread) since it never writes a file and never opens an attachment.
     */
    fun estimateBytes(request: ExportRequest): Long =
        if (request.scope is ExportScope.Evidence) {
            estimateEvidenceBytes(request)
        } else {
            val selected = request.events.select(request.sessionId, request.scope)
            estimateStandardBytes(request, selected)
        }

    private fun estimateStandardBytes(
        request: ExportRequest,
        events: List<StoredEvent>,
    ): Long {
        val textBytes = standardTextEstimate(request, events)
        val attachments = if (request.metadataOnly) emptyList() else standardAttachments(request, events)
        val attachmentBytes =
            attachments.sumOf { attachment ->
                attachment.byteCount
            }
        val attachmentIndexBytes =
            if (attachments.isEmpty()) {
                0L
            } else {
                attachments.sumOf {
                    (it.id.length + it.eventId.length) * UTF16_BYTES_PER_CHAR +
                        ESTIMATED_ATTACHMENT_INDEX_BASE_BYTES + decimalLength(it.byteCount)
                } + ESTIMATED_ATTACHMENT_INDEX_OVERHEAD_BYTES
            }
        val manifestEntryCount =
            ESTIMATED_ATTACHMENTLESS_ARCHIVE_ENTRIES + attachments.size +
                if (attachments.isEmpty()) 0 else ESTIMATED_ARCHIVE_OPTIONAL_ENTRY_COUNT
        val manifestBytes =
            ESTIMATED_MANIFEST_BASE_BYTES + request.sessionId.length * UTF16_BYTES_PER_CHAR +
                decimalLength(events.size.toLong()) + manifestEntryCount * ESTIMATED_MANIFEST_ENTRY_BYTES +
                attachments.size * ESTIMATED_ATTACHMENT_DIGEST_BYTES
        return textBytes + attachmentBytes + attachmentIndexBytes + manifestBytes +
            estimatedZipOverhead(attachments.size)
    }

    private fun estimatedZipOverhead(attachmentCount: Int): Long {
        val optionalIndexCount = if (attachmentCount == 0) 0 else ESTIMATED_ARCHIVE_OPTIONAL_ENTRY_COUNT
        return (ESTIMATED_ARCHIVE_FIXED_ENTRIES + attachmentCount + optionalIndexCount) * ZIP_ENTRY_OVERHEAD_BYTES
    }

    private fun decimalLength(value: Long): Long = value.toString().length.toLong()

    private fun standardTextEstimate(
        request: ExportRequest,
        events: List<StoredEvent>,
    ): Long =
        ESTIMATED_TEXT_BASE_BYTES + events.sumOf(::eventEstimate) +
            events.sumOf { event ->
                request.annotations[event.id]?.let {
                    (event.id.length + (it.note?.length ?: 0) + ESTIMATED_ANNOTATION_BASE_BYTES) *
                        UTF16_BYTES_PER_CHAR
                } ?: 0L
            } + ESTIMATED_ATTACHMENT_INDEX_OVERHEAD_BYTES + README_BYTES

    private fun eventEstimate(event: StoredEvent): Long =
        (
            event.id.length + event.sessionId.length + event.pluginId.length + event.type.length +
                event.summary.length +
                event.tagsJson.length + (event.correlationId?.length ?: 0) + (event.payloadJson?.length ?: 0) +
                (event.attachmentId?.length ?: 0) + ESTIMATED_EVENT_BASE_BYTES
        ) * UTF16_BYTES_PER_CHAR + ESTIMATED_EVENT_OVERHEAD_BYTES

    private suspend fun writeInternal(request: ExportRequest): ExportResult {
        require(request.maxBytes > 0) { "maxBytes must be positive" }
        if (request.scope is ExportScope.Evidence) return writeEvidenceInternal(request)

        val selected = request.events.select(request.sessionId, request.scope)
        val estimatedBytes = estimateStandardBytes(request, selected)
        if (estimatedBytes > request.maxBytes) return ExportResult.ExceedsSizeLimit

        var temporary: File? = null
        return try {
            val destination = request.destination.absoluteFile
            val parent = destination.parentFile ?: return ExportResult.Unavailable
            if (!ensureDirectoryExists(parent)) return ExportResult.Unavailable
            temporary = File.createTempFile(".${destination.name}.", ".tmp", parent)
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                val payloads = standardTextPayloads(request, selected)
                val manifestEntries = mutableListOf<ManifestData>()
                payloads.forEach { payload ->
                    zip.writeBytes(payload.path, payload.bytes)
                    manifestEntries += ManifestData(payload.path, payload.bytes.sha256(), payload.bytes.size.toLong())
                }
                manifestEntries += zip.writeTimeline(selected, request)
                if (!request.metadataOnly) zip.streamStandardAttachments(request, selected, manifestEntries)
                val manifestBytes = manifest(request, selected.size, manifestEntries).encodeToByteArray()
                zip.writeBytes("manifest.json", manifestBytes)
            }
            // The pre-write estimate is metadata-only; the archive actually written is the final gate.
            if (temporary.length() > request.maxBytes) return ExportResult.ExceedsSizeLimit
            publishAtomically(temporary, destination)
            temporary = null
            ExportResult.Success(destination, selected.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ExportResult.Unavailable
        } finally {
            temporary?.delete()
        }
    }

    // ============================================================================================
    // ExportScope.Evidence: sized and streamed without ever holding every attachment's bytes at once.
    // The size gate below is computed purely from EvidenceBundleAttachment.sizeBytes (metadata the
    // caller already has), so an oversized bundle is refused before EvidenceBundleAttachment.open is
    // ever invoked -- the whole tray's screenshots never have to be pulled into heap for a bundle that
    // was always going to be rejected. estimateBytes() calls this exact function, so the two can never
    // disagree about where the size boundary falls.
    // ============================================================================================

    private fun estimateEvidenceBytes(request: ExportRequest): Long {
        val bundle = request.evidenceBundle ?: return 0L
        val textMetadata = evidenceTextMetadata(bundle)
        val textEntries =
            textMetadata.map { (path, size) ->
                ManifestFileEntry(path, PLACEHOLDER_SHA256, size, RedactionApplicability.APPLIED)
            }
        // Attachments are never opened here: the placeholder hash is a fixed-length stand-in (a real
        // sha256 hex digest is always PLACEHOLDER_SHA256.length characters, regardless of content) and
        // sizeBytes stands in for the eventual attachment length, with a conservative multiplier for
        // text redaction and UTF-8 expansion. This keeps the estimate independent of attachment content.
        val attachmentEntries =
            bundle.attachments.map { attachment ->
                ManifestFileEntry(
                    path = attachment.path,
                    sha256 = PLACEHOLDER_SHA256,
                    bytes = attachment.sizeBytes,
                    applicability = attachment.redactionApplicability,
                )
            }
        val manifestBytes =
            ESTIMATED_EVIDENCE_MANIFEST_BYTES + request.sessionId.length * ESTIMATED_EVIDENCE_SESSION_BYTES +
                decimalLength(bundle.itemCount.toLong()) +
                (textEntries.size + attachmentEntries.size) * ESTIMATED_EVIDENCE_ENTRY_BYTES +
                bundle.attachments.sumOf {
                    it.path.length * ESTIMATED_EVIDENCE_PATH_BYTES + decimalLength(it.sizeBytes)
                }
        val payloadBytes =
            textMetadata.sumOf { it.second } +
                bundle.attachments.sumOf { attachment ->
                    if (attachment.redactionApplicability == RedactionApplicability.NOT_APPLICABLE) {
                        attachment.sizeBytes
                    } else {
                        attachment.sizeBytes * ESTIMATED_REDACTED_ATTACHMENT_MULTIPLIER
                    }
                }
        val entryCount = textMetadata.size + bundle.attachments.size + ESTIMATED_ARCHIVE_OPTIONAL_ENTRY_COUNT.toLong()
        return payloadBytes + manifestBytes + entryCount * ZIP_ENTRY_OVERHEAD_BYTES
    }

    private suspend fun writeEvidenceInternal(request: ExportRequest): ExportResult {
        val bundle = request.evidenceBundle ?: return ExportResult.Unavailable
        if (estimateEvidenceBytes(request) > request.maxBytes) return ExportResult.ExceedsSizeLimit

        var temporary: File? = null
        return try {
            val destination = request.destination.absoluteFile
            val parent = destination.parentFile ?: return ExportResult.Unavailable
            if (!ensureDirectoryExists(parent)) return ExportResult.Unavailable
            temporary = File.createTempFile(".${destination.name}.", ".tmp", parent)
            val textPayloads = evidenceTextPayloads(bundle)
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                val textEntries = zip.writeEvidenceTextEntries(textPayloads)
                val textBytes = textPayloads.sumOf { it.bytes.size.toLong() }
                val attachmentEntries = zip.streamEvidenceAttachments(bundle.attachments, request.maxBytes, textBytes)
                val manifestBytes =
                    evidenceManifestJson(request.sessionId, bundle.itemCount, textEntries + attachmentEntries)
                        .encodeToByteArray()
                zip.writeBytes("manifest.json", manifestBytes)
            }
            // The pre-write estimate is metadata-only; the archive actually written is the final gate.
            if (temporary.length() > request.maxBytes) return ExportResult.ExceedsSizeLimit
            publishAtomically(temporary, destination)
            temporary = null
            ExportResult.Success(destination, bundle.itemCount)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: EvidenceBudgetExceededException) {
            ExportResult.ExceedsSizeLimit
        } catch (_: Exception) {
            ExportResult.Unavailable
        } finally {
            temporary?.delete()
        }
    }

    /** Writes every text payload as its own ZIP entry and returns its (always `APPLIED`) manifest entry. */
    private fun ZipOutputStream.writeEvidenceTextEntries(payloads: List<PayloadFile>): List<ManifestFileEntry> =
        payloads.map { payload ->
            writeBytes(payload.path, payload.bytes)
            payload.toAppliedManifestEntry()
        }

    private fun PayloadFile.toAppliedManifestEntry(): ManifestFileEntry =
        ManifestFileEntry(path, bytes.sha256(), bytes.size.toLong(), RedactionApplicability.APPLIED)

    /**
     * Streams each attachment's bytes into this ZIP one at a time -- only ever one attachment's raw
     * bytes (plus its redacted copy) are resident, never the whole tray -- and throws
     * [EvidenceBudgetExceededException] the moment the running total exceeds [maxBytes]. A stale
     * [EvidenceBundleAttachment.sizeBytes] can only have made [estimateEvidenceBytes]'s pre-check more
     * conservative (redaction only shrinks), but this re-check guards against real bytes drifting the
     * other way once they are in hand.
     */
    private suspend fun ZipOutputStream.streamEvidenceAttachments(
        attachments: List<EvidenceBundleAttachment>,
        maxBytes: Long,
        alreadyWrittenBytes: Long,
    ): List<ManifestFileEntry> {
        var writtenBytes = alreadyWrittenBytes
        return attachments.mapNotNull { attachment ->
            val raw = attachment.open() ?: return@mapNotNull null
            val redactionNotApplicable = attachment.redactionApplicability == RedactionApplicability.NOT_APPLICABLE
            val bytes = if (redactionNotApplicable) raw else raw.redactedCopy()
            writtenBytes += bytes.size
            if (writtenBytes > maxBytes) throw EvidenceBudgetExceededException()
            writeBytes(attachment.path, bytes)
            ManifestFileEntry(attachment.path, bytes.sha256(), bytes.size.toLong(), attachment.redactionApplicability)
        }
    }

    /** Signals [writeEvidenceInternal]'s own catch clause; never escapes this class. */
    private class EvidenceBudgetExceededException : Exception()

    /** report.md/report.json/network.har/postman_collection.json/session.json, redacted a second time. */
    private fun evidenceTextPayloads(bundle: EvidenceBundleContent): List<PayloadFile> =
        evidenceTextMetadata(bundle).map { (path, _) ->
            val content =
                when (path) {
                    "report.md" -> bundle.reportMarkdown
                    "report.json" -> bundle.reportJson
                    "network.har" -> bundle.networkHar
                    "postman_collection.json" -> bundle.postmanCollection
                    else -> bundle.sessionJson
                }
            redactedTextPayload(path, content)
        }

    private fun evidenceTextMetadata(bundle: EvidenceBundleContent): List<Pair<String, Long>> =
        listOf(
            "report.md" to utf8ByteCount(bundle.reportMarkdown),
            "report.json" to maxOf(utf8ByteCount(bundle.reportJson), JSON_PLACEHOLDER_BYTES),
            "network.har" to maxOf(utf8ByteCount(bundle.networkHar), JSON_PLACEHOLDER_BYTES),
            "postman_collection.json" to maxOf(utf8ByteCount(bundle.postmanCollection), JSON_PLACEHOLDER_BYTES),
            "session.json" to maxOf(utf8ByteCount(bundle.sessionJson), JSON_PLACEHOLDER_BYTES),
        )

    private fun utf8ByteCount(value: String): Long {
        var bytes = 0L
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char.code <= MAX_UTF8_ONE_BYTE_CODE -> bytes++
                char.code <= MAX_UTF8_TWO_BYTE_CODE -> bytes += UTF8_TWO_BYTE_WIDTH
                char.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate() -> {
                    bytes += UTF8_FOUR_BYTE_WIDTH
                    index += UTF8_SURROGATE_PAIR_INDEX_ADJUSTMENT
                }
                else -> bytes += UTF8_THREE_BYTE_WIDTH
            }
            index++
        }
        return bytes
    }

    private data class ManifestFileEntry(
        val path: String,
        val sha256: String,
        val bytes: Long,
        val applicability: RedactionApplicability?,
    )

    /**
     * Every file in an evidence bundle carries [RedactionApplicability] in the manifest, not just its
     * size -- the whole point being that a screenshot in `attachments/` shows up as visibly
     * `NOT_APPLICABLE` (unredacted by construction) rather than looking identical to a redacted text
     * file. `null` means the caller could not establish an applicability claim at all (a metadata read
     * failed, or the underlying record is gone) -- it is reported as `null`, never guessed at
     * `APPLIED`, since that would be a false "this was redacted" claim on content that might not be.
     */
    private fun evidenceManifestJson(
        sessionId: String,
        itemCount: Int,
        entries: List<ManifestFileEntry>,
    ): String =
        "{\"format\":\"devconsole-evidence-bundle-v1\"," +
            "\"sessionId\":\"${escape(redaction.redactText(sessionId))}\"," +
            "\"itemCount\":$itemCount,\"manifestSelfExcluded\":true,\"files\":[${
                entries.joinToString(",") { entry ->
                    "{\"path\":\"${escape(entry.path)}\",\"sha256\":\"${entry.sha256}\"," +
                        "\"bytes\":${entry.bytes},\"redactionApplicability\":${
                            entry.applicability?.let { "\"${it.name}\"" } ?: "null"
                        }}"
                }
            }]}"

    private fun standardTextPayloads(
        request: ExportRequest,
        events: List<StoredEvent>,
    ): List<PayloadFile> =
        buildList {
            val scopeJson = request.scope.json()
            add(
                PayloadFile.text(
                    "session.json",
                    "{\"sessionId\":\"${escape(redaction.redactText(request.sessionId))}\"," +
                        "\"scope\":$scopeJson,\"metadataOnly\":${request.metadataOnly}," +
                        "\"eventCount\":${events.size}}",
                ),
            )
            val bookmarkJson =
                events
                    .mapNotNull { event ->
                        request.annotations[event.id]
                            ?.takeIf { it.bookmarked || it.note != null }
                            ?.let { annotation ->
                                val note =
                                    annotation.note?.let { "\"${escape(redaction.redactText(it))}\"" } ?: "null"
                                "{\"eventId\":\"${escape(redaction.redactText(event.id))}\"," +
                                    "\"bookmarked\":${annotation.bookmarked},\"note\":$note}"
                            }
                    }.joinToString(",")
            add(PayloadFile.text("bookmarks.json", "{\"data\":[$bookmarkJson]}"))

            add(
                PayloadFile.text(
                    "README.txt",
                    "Captured text has been redacted at capture time and export time.\n" +
                        "Binary attachments are bounded and redacted before persistence, then " +
                        "redacted again for export.\n" +
                        "manifest.json contains SHA-256 and uncompressed byte size for every other archive entry.\n",
                ),
            )
        }

    private data class StandardAttachment(
        val id: String,
        val eventId: String,
        val mimeType: String,
        val byteCount: Long,
        val open: suspend () -> InputStream?,
    )

    private fun standardAttachments(
        request: ExportRequest,
        events: List<StoredEvent>,
    ): List<StandardAttachment> =
        events
            .mapNotNull { event ->
                val id = event.attachmentId ?: return@mapNotNull null
                val source = request.attachmentSources[id]
                if (source != null) {
                    return@mapNotNull StandardAttachment(id, event.id, source.mimeType, source.byteCount, source.open)
                }
                request.attachments[id]?.let { bytes ->
                    // Legacy in-memory attachments carry no MIME type. Redact as text only when they
                    // decode as UTF-8.
                    val mimeType = if (bytes.isUtf8Text()) "text/plain" else "application/octet-stream"
                    StandardAttachment(id, event.id, mimeType, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
                }
            }.distinctBy { it.id }
            .filter { it.byteCount > 0 }

    private fun attachmentPath(id: String): String =
        "attachments/${id.encodeToByteArray().sha256().take(ATTACHMENT_NAME_HASH_CHARS)}.bin"

    private suspend fun ZipOutputStream.streamStandardAttachments(
        request: ExportRequest,
        events: List<StoredEvent>,
        manifestEntries: MutableList<ManifestData>,
    ) {
        val attachmentIndex = mutableListOf<String>()
        standardAttachments(request, events).forEach { attachment ->
            val path = attachmentPath(attachment.id)
            val result = streamStandardAttachment(attachment, path) ?: return@forEach
            val (digest, byteCount) = result
            manifestEntries += ManifestData(path, digest, byteCount)
            attachmentIndex += attachmentIndexEntry(attachment, path, byteCount)
        }
        if (attachmentIndex.isNotEmpty()) {
            val index = "{\"data\":[${attachmentIndex.joinToString(",")}]}"
            val indexBytes = index.encodeToByteArray()
            writeBytes("attachments/index.json", indexBytes)
            manifestEntries += ManifestData("attachments/index.json", indexBytes.sha256(), indexBytes.size.toLong())
        }
    }

    private suspend fun ZipOutputStream.streamStandardAttachment(
        attachment: StandardAttachment,
        path: String,
    ): Pair<String, Long>? {
        val input = attachment.open() ?: return null
        return input.use { source ->
            if (isTextMime(attachment.mimeType)) {
                val bytes = redactTextBytes(source.readBytes())
                writeBytes(path, bytes)
                bytes.sha256() to bytes.size.toLong()
            } else if (attachment.byteCount <= MAX_TEXT_SNIFF_BYTES) {
                // Capture-capped bodies are small: sniff them, so text stored without a
                // textual MIME type still gets its second redaction pass while binary bytes
                // are copied untouched.
                val raw = source.readBytes()
                val bytes = if (raw.isUtf8Text()) redactTextBytes(raw) else raw
                writeBytes(path, bytes)
                bytes.sha256() to bytes.size.toLong()
            } else {
                writeStream(path, source)
            }
        }
    }

    private fun attachmentIndexEntry(
        attachment: StandardAttachment,
        path: String,
        byteCount: Long,
    ): String =
        "{\"eventId\":\"${escape(redaction.redactText(attachment.eventId))}\"," +
            "\"attachmentId\":\"${escape(
                redaction.redactText(attachment.id),
            )}\",\"path\":\"$path\",\"bytes\":$byteCount}"

    private fun isTextMime(mimeType: String): Boolean {
        val mime = mimeType.substringBefore(';').trim().lowercase()
        return mime.startsWith("text/") ||
            mime.contains("json") ||
            mime.contains("xml") ||
            mime.contains("x-www-form-urlencoded") ||
            mime.contains("javascript")
    }

    private fun ByteArray.isUtf8Text(): Boolean =
        runCatching {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(this))
        }.isSuccess

    private fun redactTextBytes(raw: ByteArray): ByteArray =
        redaction.redactText(raw.decodeToString(), raw.size.coerceAtLeast(1)).encodeToByteArray()

    private data class ManifestData(
        val path: String,
        val sha256: String,
        val bytes: Long,
    )

    private fun manifest(
        request: ExportRequest,
        eventCount: Int,
        payloads: List<ManifestData>,
    ): String =
        "{\"format\":\"devconsole-export-v2\"," +
            "\"sessionId\":\"${escape(redaction.redactText(request.sessionId))}\"," +
            "\"eventCount\":$eventCount,\"metadataOnly\":${request.metadataOnly}," +
            "\"manifestSelfExcluded\":true,\"files\":[${payloads.joinToString(",") { entry ->
                "{\"path\":\"${escape(entry.path)}\",\"sha256\":\"${entry.sha256}\",\"bytes\":${entry.bytes}}"
            }}]}"

    private fun List<StoredEvent>.select(
        sessionId: String,
        scope: ExportScope,
    ): List<StoredEvent> =
        asSequence()
            .filter { it.sessionId == sessionId }
            .filter { event ->
                when (scope) {
                    ExportScope.WholeSession -> true
                    is ExportScope.TimeRange ->
                        event.wallTimeMs in scope.fromEpochMs..scope.toEpochMs
                    is ExportScope.EventIds -> event.id in scope.ids
                    // Evidence bundles are built entirely from ExportRequest.evidenceBundle; no
                    // StoredEvent is ever selected for one.
                    ExportScope.Evidence -> false
                }
            }.sortedWith(
                compareBy<StoredEvent>(StoredEvent::monoTimeNs)
                    .thenBy(StoredEvent::sequence)
                    .thenBy(StoredEvent::id),
            ).toList()

    private fun StoredEvent.toJsonLine(
        annotation: TimelineAnnotation?,
        metadataOnly: Boolean,
    ): String =
        buildString {
            append('{')
            appendJson("id", redaction.redactText(id))
            append(',')
            appendJson("sessionId", redaction.redactText(sessionId))
            append(',')
            appendJson("pluginId", redaction.redactText(pluginId))
            append(',')
            appendJson("type", redaction.redactText(type))
            append(",\"sequence\":").append(sequence)
            append(",\"wallTimeMs\":").append(wallTimeMs)
            append(",\"monoTimeNs\":").append(monoTimeNs)
            append(",\"severity\":").append(severity)
            append(",\"schemaVersion\":").append(schemaVersion)
            append(',')
            appendJson("summary", redaction.redactText(summary))
            correlationId?.let { append(',').appendJson("correlationId", redaction.redactText(it)) }
            append(',').appendJson("tags", redaction.redactText(tagsJson))
            if (!metadataOnly) {
                payloadJson?.let { append(',').appendJson("payload", redaction.redactText(it)) }
                attachmentId?.let { append(',').appendJson("attachmentId", redaction.redactText(it)) }
            }
            annotation?.let {
                append(",\"bookmarked\":").append(it.bookmarked)
                it.note?.let { note -> append(',').appendJson("note", redaction.redactText(note)) }
            }
            append('}')
        }

    private fun StringBuilder.appendJson(
        name: String,
        value: String,
    ) {
        append('"')
            .append(name)
            .append("\":\"")
            .append(escape(value))
            .append('"')
    }

    private fun ZipOutputStream.writeBytes(
        name: String,
        content: ByteArray,
    ) {
        putNextEntry(ZipEntry(name))
        write(content)
        closeEntry()
    }

    private fun ZipOutputStream.writeTimeline(
        events: List<StoredEvent>,
        request: ExportRequest,
    ): ManifestData {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        putNextEntry(ZipEntry("timeline.jsonl"))
        events.forEachIndexed { index, event ->
            if (index > 0) {
                write('\n'.code)
                digest.update('\n'.code.toByte())
                total++
            }
            val line = event.toJsonLine(request.annotations[event.id], request.metadataOnly).encodeToByteArray()
            write(line)
            digest.update(line)
            total += line.size
        }
        closeEntry()
        return ManifestData(
            "timeline.jsonl",
            digest.digest().joinToString("") {
                "%02x".format(it.toInt() and BYTE_MASK)
            },
            total,
        )
    }

    private fun ZipOutputStream.writeStream(
        name: String,
        input: InputStream,
    ): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        putNextEntry(ZipEntry(name))
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            write(buffer, 0, count)
            digest.update(buffer, 0, count)
            total += count
        }
        closeEntry()
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) } to total
    }

    private fun ExportScope.json(): String =
        when (this) {
            ExportScope.WholeSession -> "{\"type\":\"WHOLE_SESSION\"}"
            is ExportScope.TimeRange ->
                "{\"type\":\"TIME_RANGE\",\"fromEpochMs\":$fromEpochMs,\"toEpochMs\":$toEpochMs}"
            is ExportScope.EventIds ->
                "{\"type\":\"EVENT_IDS\",\"ids\":[" +
                    ids.sorted().joinToString(",") { "\"${escape(redaction.redactText(it))}\"" } +
                    "]}"
            ExportScope.Evidence -> "{\"type\":\"EVIDENCE\"}"
        }

    private fun escape(value: String): String =
        buildString(value.length) {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else ->
                        if (char.code < CONTROL_CHARACTER_LIMIT) {
                            append("\\u%0${UNICODE_ESCAPE_WIDTH}x".format(char.code))
                        } else {
                            append(char)
                        }
                }
            }
        }

    private fun redactedTextPayload(
        path: String,
        content: String,
    ): PayloadFile {
        val redacted = redaction.redactText(content, Int.MAX_VALUE)
        val structured = path.endsWith(".json") || path.endsWith(".har")
        val safe =
            if (structured && redacted.encodeToByteArray().size > content.encodeToByteArray().size) {
                "{\"_comment\":\"redacted structured body omitted to preserve valid JSON\"}"
            } else {
                redacted
            }
        return PayloadFile.text(path, safe)
    }

    /**
     * Second redaction pass for a bundle attachment whose text content is already redacted (a
     * captured request/response body). Mirrors [attachmentPayloads]'s own re-redaction: bounded by
     * the original byte length so this can only mask matches, never grow past the source size.
     */
    private fun ByteArray.redactedCopy(): ByteArray =
        redaction
            .redactText(decodeToString(), size.coerceAtLeast(1))
            .encodeToByteArray()
            .let { redacted -> if (redacted.size <= size) redacted else redacted.copyOf(size) }

    private fun isAndroidMainThread(): Boolean =
        runCatching {
            val looper = Class.forName("android.os.Looper")
            val main = looper.getMethod("getMainLooper").invoke(null)
            val current = looper.getMethod("myLooper").invoke(null)
            main != null && main == current
        }.getOrDefault(false)

    private data class PayloadFile(
        val path: String,
        val bytes: ByteArray,
    ) {
        companion object {
            fun text(
                path: String,
                content: String,
            ) = PayloadFile(path, content.encodeToByteArray())
        }
    }

    private companion object {
        // Truncated hex of the attachment id's SHA-256: enough to avoid collisions while keeping
        // archive entry names short and free of any characters recovered from the id itself.
        const val ATTACHMENT_NAME_HASH_CHARS = 32

        // A real sha256 hex digest is always 64 characters; used as a fixed-length stand-in in the
        // Evidence-scope size estimate so an un-opened attachment's manifest entry is sized correctly
        // without ever computing (or needing) its real digest.
        val PLACEHOLDER_SHA256 = "0".repeat(SHA256_HEX_LENGTH)

        fun ensureDirectoryExists(directory: File): Boolean = directory.isDirectory || directory.mkdirs()

        /**
         * Publishes [source] as [destination] without `java.nio.file` (whose `Files.move` requires API
         * 26+ core library desugaring at this module's minSdk 23). `File.renameTo` is atomic on POSIX
         * filesystems -- true for every real Android deployment target -- when both files share a
         * volume, which they always do here since [source] is created inside `destination`'s own parent
         * directory. The fallbacks below only matter on filesystems where rename-over-existing isn't
         * supported.
         */
        fun publishAtomically(
            source: File,
            destination: File,
        ) {
            if (source.renameTo(destination)) return
            if (destination.exists() && destination.delete() && source.renameTo(destination)) return
            source.copyTo(destination, overwrite = true)
            source.delete()
        }
    }
}

private const val BYTE_MASK = 0xff

private fun ByteArray.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }
