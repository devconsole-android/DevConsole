/**
 * @author Zakaria
 * @since 26/10/26
 */
package io.devconsole.locationping

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@Suppress("TooManyFunctions")
class LocationPingStore
    @JvmOverloads
    constructor(
        private val rootDirectory: File,
        private val switches: LocationPingSwitches = InMemoryLocationPingSwitches(),
        private val redactFields: (Map<String, String>) -> Map<String, String> = { it },
        private val writer: Executor = sharedWriter,
        private val clock: () -> Long = System::currentTimeMillis,
        private val timeZone: TimeZone = TimeZone.getDefault(),
    ) {
        @Volatile
        var tracking: LocationPingTracking = LocationPingTracking()
            private set

        @Volatile
        private var lastStorageError: String? = null

        private val fileLock = Any()
        private var knownUsedBytes: Long? = null

        fun configure(tracking: LocationPingTracking) {
            this.tracking = tracking
        }

        fun streams(): List<LocationPingStreamStatus> =
            tracking.streams.map { LocationPingStreamStatus(it.name, it.path, switches.isEnabled(it.name)) }

        fun isEnabled(streamName: String? = null): Boolean {
            val configured = tracking.streams
            return if (streamName == null) {
                configured.any { switches.isEnabled(it.name) }
            } else {
                configured.any { it.name == streamName } && switches.isEnabled(streamName)
            }
        }

        fun setEnabled(
            streamName: String,
            enabled: Boolean,
        ): Boolean {
            if (tracking.stream(streamName) == null) return false
            switches.setEnabled(streamName, enabled)
            return true
        }

        fun recordNetworkPing(
            url: String,
            body: ByteArray?,
            statusCode: Int?,
        ) {
            if (body == null || body.isEmpty()) return
            val outcome =
                statusCode?.takeIf { it in HTTP_STATUS_RANGE }?.let(LocationPingOutcome::Answered)
                    ?: LocationPingOutcome.NoConnection
            enqueue(url, outcome) { body.decodeToString() }
        }

        fun record(
            url: String,
            body: String,
            outcome: LocationPingOutcome,
        ): Boolean = enqueue(url, outcome) { body }

        private fun enqueue(
            url: String,
            outcome: LocationPingOutcome,
            body: () -> String,
        ): Boolean {
            val stream = tracking.streamFor(url)?.takeIf { switches.isEnabled(it.name) } ?: return false
            val schema = tracking.schema
            val recordedAt = clock()
            writer.execute { writeSafely(stream, schema, body, outcome, recordedAt) }
            return true
        }

        fun storage(): LocationPingStorage =
            LocationPingStorage(
                usedBytes = synchronized(fileLock) { usedBytesLocked() },
                maxBytes = tracking.maxStorageBytes,
                lastError = lastStorageError,
            )

        fun tracks(): List<LocationPingTrack> =
            streamDirectories()
                .flatMap { directory -> directory.listFiles().orEmpty().mapNotNull { trackOf(directory.name, it) } }
                .sortedByDescending { it.lastModifiedEpochMs }

        fun track(
            streamName: String,
            fileName: String,
        ): LocationPingTrackDetail? {
            val file = trackFile(streamName, fileName) ?: return null
            val reader = LocationPingTrackReader(tracking.schema, redactFields, MAX_POINTS_PER_TRACK)
            return trackOf(streamName, file)?.let { track -> reader.read(track, file) }
        }

        fun deleteTrack(
            streamName: String,
            fileName: String,
        ): Boolean =
            synchronized(fileLock) {
                val file = trackFile(streamName, fileName) ?: return false
                val deleted = file.delete()
                knownUsedBytes = null
                deleted
            }

        fun delete(streamNames: Set<String>? = null): LocationPingDeleteResult =
            synchronized(fileLock) {
                val directories = streamDirectories().filter { streamNames == null || it.name in streamNames }
                val files = directories.flatMap { it.listFiles().orEmpty().filter(File::isFile) }
                val deleted = files.count(File::delete)
                directories.forEach(File::delete)
                knownUsedBytes = null
                if (deleted == files.size) lastStorageError = null
                LocationPingDeleteResult(filesDeleted = deleted, filesLeft = files.size - deleted)
            }

        private fun writeSafely(
            stream: LocationPingStream,
            schema: LocationPingSchema,
            body: () -> String,
            outcome: LocationPingOutcome,
            recordedAt: Long,
        ) {
            runCatching { write(stream, schema, body(), outcome, recordedAt) }
                .onFailure { lastStorageError = "Writing a ${stream.name} ping failed: ${it.readableMessage()}" }
        }

        private fun write(
            stream: LocationPingStream,
            schema: LocationPingSchema,
            body: String,
            outcome: LocationPingOutcome,
            recordedAt: Long,
        ) {
            val parsedBody = PingJson.parseOrNull(body) as? PingJson.Obj ?: return
            val line = recordLine(parsedBody, outcome, recordedAt)
            val lineBytes = line.toByteArray(Charsets.UTF_8)
            synchronized(fileLock) {
                val used = usedBytesLocked()
                if (used + lineBytes.size > tracking.maxStorageBytes) {
                    lastStorageError = "Location ping storage is full (${tracking.maxStorageBytes} bytes). " +
                        "Delete captured pings to keep recording."
                    return
                }
                val directory = File(rootDirectory, stream.name)
                check(directory.isDirectory || directory.mkdirs()) { "Could not create ${directory.path}" }
                File(directory, trackFileName(groupIdOf(parsedBody, schema) ?: stream.name, recordedAt))
                    .appendBytes(lineBytes)
                knownUsedBytes = used + lineBytes.size
            }
        }

        private fun recordLine(
            body: PingJson.Obj,
            outcome: LocationPingOutcome,
            recordedAt: Long,
        ): String {
            val record =
                PingJson.Obj(
                    linkedMapOf(
                        RECORD_STATUS to PingJson.Num(outcome.storedStatus().toString()),
                        RECORD_RECORDED_AT to PingJson.Num(recordedAt.toString()),
                        RECORD_BODY to body,
                    ),
                )
            return record.toCompactJson() + "\n"
        }

        private fun groupIdOf(
            body: PingJson.Obj,
            schema: LocationPingSchema,
        ): String? {
            val value = schema.groupIdKey?.let { body[it] ?: firstPingOf(body, schema)?.get(it) } ?: return null
            val text =
                when (value) {
                    is PingJson.Arr -> value.items.map { it.asReadableText() }.firstOrNull(String::isNotBlank)
                    else -> value.asReadableText()
                }
            return text
                ?.replace(UNSAFE_FILE_NAME_CHARS, "_")
                ?.take(MAX_GROUP_ID_LENGTH)
                ?.takeIf { it.isNotBlank() && LocationPingStream.STREAM_NAME.matches(it) }
        }

        private fun firstPingOf(
            body: PingJson.Obj,
            schema: LocationPingSchema,
        ): PingJson.Obj? =
            schema.pingListKey
                ?.let { (body[it] as? PingJson.Arr)?.items?.firstOrNull() as? PingJson.Obj }

        private fun trackFileName(
            groupId: String,
            recordedAt: Long,
        ): String {
            val dateFormat =
                SimpleDateFormat(FILE_DATE_PATTERN, Locale.US).apply {
                    timeZone =
                        this@LocationPingStore.timeZone
                }
            return "$groupId-${dateFormat.format(Date(recordedAt))}$TRACK_FILE_EXTENSION"
        }

        private fun usedBytesLocked(): Long =
            knownUsedBytes ?: rootDirectory
                .walkTopDown()
                .filter(File::isFile)
                .sumOf(File::length)
                .also { knownUsedBytes = it }

        private fun streamDirectories(): List<File> =
            rootDirectory
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory && LocationPingStream.STREAM_NAME.matches(it.name) }

        private fun trackFile(
            streamName: String,
            fileName: String,
        ): File? {
            if (!LocationPingStream.STREAM_NAME.matches(streamName) || !TRACK_FILE_NAME.matches(fileName)) return null
            return File(File(rootDirectory, streamName), fileName).takeIf(File::isFile)
        }

        private fun trackOf(
            streamName: String,
            file: File,
        ): LocationPingTrack? {
            val match = TRACK_FILE_NAME.matchEntire(file.name)?.takeIf { file.isFile } ?: return null
            return LocationPingTrack(
                stream = streamName,
                file = file.name,
                groupId = match.groupValues[1],
                capturedDate = match.groupValues[2],
                sizeBytes = file.length(),
                lastModifiedEpochMs = file.lastModified(),
            )
        }

        private fun Throwable.readableMessage(): String = message?.takeIf(String::isNotBlank) ?: javaClass.simpleName

        private companion object {
            const val MAX_POINTS_PER_TRACK = 20_000
            const val MAX_GROUP_ID_LENGTH = 64
            const val FILE_DATE_PATTERN = "yyyy-MM-dd"
            const val TRACK_FILE_EXTENSION = ".jsonl"
            val UNSAFE_FILE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")
            val TRACK_FILE_NAME = Regex("([A-Za-z0-9._-]{1,64})-(\\d{4}-\\d{2}-\\d{2})\\.jsonl")

            val sharedWriter: Executor by lazy {
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "devconsole-location-pings").apply { isDaemon = true }
                }
            }
        }
    }
