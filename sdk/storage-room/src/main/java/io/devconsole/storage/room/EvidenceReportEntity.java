/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.storage.room;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "evidence_reports")
public final class EvidenceReportEntity {
    @NonNull
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    public final String sessionId;

    @NonNull public final String severity;
    @Nullable public final String summary;
    @Nullable public final String expected;
    @Nullable public final String actual;
    @ColumnInfo(name = "updated_at_ms") public final long updatedAtMs;

    public EvidenceReportEntity(@NonNull String sessionId, @NonNull String severity,
            @Nullable String summary, @Nullable String expected, @Nullable String actual,
            long updatedAtMs) {
        this.sessionId = sessionId;
        this.severity = severity;
        this.summary = summary;
        this.expected = expected;
        this.actual = actual;
        this.updatedAtMs = updatedAtMs;
    }
}
