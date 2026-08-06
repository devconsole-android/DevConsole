package io.devconsole.storage.room;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "timeline_annotations")
public final class TimelineAnnotationEntity {
    @NonNull
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    public final String eventId;

    public final boolean bookmarked;

    @Nullable
    public final String note;

    public TimelineAnnotationEntity(
            @NonNull String eventId,
            boolean bookmarked,
            @Nullable String note) {
        this.eventId = eventId;
        this.bookmarked = bookmarked;
        this.note = note;
    }
}
