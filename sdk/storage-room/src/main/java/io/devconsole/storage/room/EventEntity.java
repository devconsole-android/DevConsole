package io.devconsole.storage.room;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "events",
        indices = {
                @Index("session_id"),
                @Index("plugin_id"),
                @Index("type"),
                @Index("wall_time_ms"),
                @Index("correlation_id")
        }
)
public final class EventEntity {
    @NonNull @PrimaryKey public final String id;
    @NonNull @ColumnInfo(name = "session_id") public final String sessionId;
    public final long sequence;
    @NonNull @ColumnInfo(name = "plugin_id") public final String pluginId;
    @NonNull public final String type;
    @ColumnInfo(name = "wall_time_ms") public final long wallTimeMs;
    @ColumnInfo(name = "mono_time_ns") public final long monoTimeNs;
    public final int severity;
    @NonNull public final String summary;
    @Nullable @ColumnInfo(name = "correlation_id") public final String correlationId;
    @NonNull @ColumnInfo(name = "tags_json") public final String tagsJson;
    @Nullable @ColumnInfo(name = "payload_json") public final String payloadJson;
    @Nullable @ColumnInfo(name = "attachment_id") public final String attachmentId;
    @ColumnInfo(name = "schema_version") public final int schemaVersion;

    public EventEntity(@NonNull String id, @NonNull String sessionId, long sequence,
            @NonNull String pluginId, @NonNull String type,
            long wallTimeMs, long monoTimeNs, int severity, @NonNull String summary,
            @Nullable String correlationId, @NonNull String tagsJson, @Nullable String payloadJson,
            @Nullable String attachmentId, int schemaVersion) {
        this.id = id;
        this.sessionId = sessionId;
        this.sequence = sequence;
        this.pluginId = pluginId;
        this.type = type;
        this.wallTimeMs = wallTimeMs;
        this.monoTimeNs = monoTimeNs;
        this.severity = severity;
        this.summary = summary;
        this.correlationId = correlationId;
        this.tagsJson = tagsJson;
        this.payloadJson = payloadJson;
        this.attachmentId = attachmentId;
        this.schemaVersion = schemaVersion;
    }
}
