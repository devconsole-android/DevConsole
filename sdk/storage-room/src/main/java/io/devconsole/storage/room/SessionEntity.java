package io.devconsole.storage.room;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "sessions",
        indices = {
                @Index("status"),
                @Index("started_at_ms"),
                @Index("ended_at_ms")
        }
)
public final class SessionEntity {
    @NonNull @PrimaryKey public final String id;
    @NonNull public final String status;
    @ColumnInfo(name = "started_at_ms") public final long startedAtMs;
    @ColumnInfo(name = "started_at_monotonic_ns") public final long startedAtMonotonicNs;
    @Nullable @ColumnInfo(name = "ended_at_ms") public final Long endedAtMs;
    @Nullable @ColumnInfo(name = "application_id") public final String applicationId;
    @Nullable @ColumnInfo(name = "app_version_name") public final String appVersionName;
    @Nullable @ColumnInfo(name = "app_version_code") public final Long appVersionCode;
    @Nullable @ColumnInfo(name = "build_type") public final String buildType;
    @Nullable @ColumnInfo(name = "device_model") public final String deviceModel;
    @Nullable @ColumnInfo(name = "device_api_level") public final Integer deviceApiLevel;
    @Nullable @ColumnInfo(name = "device_os_version") public final String deviceOsVersion;
    @ColumnInfo(name = "record_count") public final long recordCount;
    @ColumnInfo(name = "estimated_bytes") public final long estimatedBytes;

    public SessionEntity(@NonNull String id, @NonNull String status, long startedAtMs,
            long startedAtMonotonicNs, @Nullable Long endedAtMs, @Nullable String applicationId,
            @Nullable String appVersionName, @Nullable Long appVersionCode, @Nullable String buildType,
            @Nullable String deviceModel, @Nullable Integer deviceApiLevel,
            @Nullable String deviceOsVersion, long recordCount, long estimatedBytes) {
        this.id = id;
        this.status = status;
        this.startedAtMs = startedAtMs;
        this.startedAtMonotonicNs = startedAtMonotonicNs;
        this.endedAtMs = endedAtMs;
        this.applicationId = applicationId;
        this.appVersionName = appVersionName;
        this.appVersionCode = appVersionCode;
        this.buildType = buildType;
        this.deviceModel = deviceModel;
        this.deviceApiLevel = deviceApiLevel;
        this.deviceOsVersion = deviceOsVersion;
        this.recordCount = recordCount;
        this.estimatedBytes = estimatedBytes;
    }
}
