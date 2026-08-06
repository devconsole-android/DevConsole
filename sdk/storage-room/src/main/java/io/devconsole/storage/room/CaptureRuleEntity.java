package io.devconsole.storage.room;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Durable capture-exclusion rule row; {@code position} preserves the author-visible ordering. */
@Entity(tableName = "capture_rules")
public final class CaptureRuleEntity {
    @NonNull
    @PrimaryKey
    @ColumnInfo(name = "id")
    public final String id;

    @NonNull
    @ColumnInfo(name = "host")
    public final String host;

    @Nullable
    @ColumnInfo(name = "method")
    public final String method;

    @Nullable
    @ColumnInfo(name = "path_prefix")
    public final String pathPrefix;

    @ColumnInfo(name = "enabled")
    public final boolean enabled;

    @ColumnInfo(name = "position")
    public final int position;

    public CaptureRuleEntity(
            @NonNull String id,
            @NonNull String host,
            @Nullable String method,
            @Nullable String pathPrefix,
            boolean enabled,
            int position) {
        this.id = id;
        this.host = host;
        this.method = method;
        this.pathPrefix = pathPrefix;
        this.enabled = enabled;
        this.position = position;
    }
}
