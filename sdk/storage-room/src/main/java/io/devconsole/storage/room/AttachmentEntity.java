package io.devconsole.storage.room;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "attachments",
        indices = {
                @Index("event_id"),
                @Index("session_id"),
                @Index("created_wall_time_ms")
        }
)
public final class AttachmentEntity {
    @NonNull @PrimaryKey public final String id;
    @NonNull @ColumnInfo(name = "event_id") public final String eventId;
    @NonNull @ColumnInfo(name = "session_id") public final String sessionId;
    @NonNull @ColumnInfo(name = "mime_type") public final String mimeType;
    @ColumnInfo(name = "original_length") public final long originalLength;
    @ColumnInfo(name = "stored_length") public final long storedLength;
    public final boolean truncated;
    @NonNull public final String sha256;
    @ColumnInfo(name = "is_redacted") public final boolean isRedacted;
    @NonNull @ColumnInfo(name = "relative_path") public final String relativePath;
    @ColumnInfo(name = "created_wall_time_ms") public final long createdWallTimeMs;
    @ColumnInfo(name = "is_bookmarked") public final boolean isBookmarked;
    @ColumnInfo(name = "pending_deletion") public final boolean pendingDeletion;
    @NonNull @ColumnInfo(name = "redaction_applicability") public final String redactionApplicability;

    public AttachmentEntity(@NonNull String id, @NonNull String eventId, @NonNull String sessionId,
            @NonNull String mimeType, long originalLength, long storedLength, boolean truncated,
            @NonNull String sha256, boolean isRedacted, @NonNull String relativePath,
            long createdWallTimeMs, boolean isBookmarked, boolean pendingDeletion,
            @NonNull String redactionApplicability) {
        this.id = id;
        this.eventId = eventId;
        this.sessionId = sessionId;
        this.mimeType = mimeType;
        this.originalLength = originalLength;
        this.storedLength = storedLength;
        this.truncated = truncated;
        this.sha256 = sha256;
        this.isRedacted = isRedacted;
        this.relativePath = relativePath;
        this.createdWallTimeMs = createdWallTimeMs;
        this.isBookmarked = isBookmarked;
        this.pendingDeletion = pendingDeletion;
        this.redactionApplicability = redactionApplicability;
    }
}
