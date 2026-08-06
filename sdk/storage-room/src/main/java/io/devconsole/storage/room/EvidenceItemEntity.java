/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.storage.room;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "evidence_items",
        indices = {
                @Index(value = {"session_id", "kind", "subject_id"}, unique = true, name = "index_evidence_items_subject"),
                @Index(value = "session_id", name = "index_evidence_items_session_id")
        }
)
public final class EvidenceItemEntity {
    @NonNull @PrimaryKey public final String id;
    @NonNull @ColumnInfo(name = "session_id") public final String sessionId;
    @NonNull public final String kind;
    @NonNull @ColumnInfo(name = "subject_id") public final String subjectId;
    @NonNull public final String label;
    @ColumnInfo(name = "flagged_at_ms") public final long flaggedAtMs;
    @NonNull @ColumnInfo(name = "snapshot_json") public final String snapshotJson;
    @Nullable @ColumnInfo(name = "attachment_id") public final String attachmentId;

    public EvidenceItemEntity(@NonNull String id, @NonNull String sessionId, @NonNull String kind,
            @NonNull String subjectId, @NonNull String label, long flaggedAtMs,
            @NonNull String snapshotJson, @Nullable String attachmentId) {
        this.id = id;
        this.sessionId = sessionId;
        this.kind = kind;
        this.subjectId = subjectId;
        this.label = label;
        this.flaggedAtMs = flaggedAtMs;
        this.snapshotJson = snapshotJson;
        this.attachmentId = attachmentId;
    }
}
