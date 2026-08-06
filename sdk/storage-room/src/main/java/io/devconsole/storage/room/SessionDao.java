package io.devconsole.storage.room;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertIfAbsent(SessionEntity session);

    @Nullable
    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    SessionEntity session(String sessionId);

    @Query("SELECT * FROM sessions ORDER BY started_at_ms DESC, id DESC")
    List<SessionEntity> sessions();

    @Query("SELECT * FROM sessions WHERE status IN ('COMPLETED', 'CRASHED') AND COALESCE(ended_at_ms, started_at_ms) < :cutoffEpochMs ORDER BY COALESCE(ended_at_ms, started_at_ms) ASC, id ASC")
    List<SessionEntity> expiredCompleted(long cutoffEpochMs);

    @Query("SELECT * FROM sessions WHERE status IN ('COMPLETED', 'CRASHED') ORDER BY COALESCE(ended_at_ms, started_at_ms) ASC, id ASC")
    List<SessionEntity> completedOldestFirst();

    @Query("SELECT * FROM sessions WHERE status = 'DELETING' ORDER BY COALESCE(ended_at_ms, started_at_ms) ASC, id ASC")
    List<SessionEntity> deletingSessions();

    @Query("SELECT COUNT(*) FROM sessions")
    long sessionCount();

    @Query("SELECT COALESCE(SUM(estimated_bytes), 0) FROM sessions")
    long totalEstimatedBytes();

    @Query("UPDATE sessions SET status = :status, ended_at_ms = :endedAtMs WHERE id = :sessionId")
    void finish(String sessionId, String status, long endedAtMs);

    @Query("UPDATE sessions SET status = 'DELETING' WHERE id = :sessionId AND status IN ('COMPLETED', 'CRASHED')")
    int markDeleting(String sessionId);

    @Query("UPDATE sessions SET record_count = (SELECT COUNT(*) FROM events WHERE session_id = :sessionId) + (SELECT COUNT(*) FROM attachments WHERE session_id = :sessionId), estimated_bytes = (SELECT COALESCE(SUM(96 + LENGTH(CAST(id AS BLOB)) + LENGTH(CAST(session_id AS BLOB)) + LENGTH(CAST(plugin_id AS BLOB)) + LENGTH(CAST(type AS BLOB)) + LENGTH(CAST(summary AS BLOB)) + LENGTH(CAST(tags_json AS BLOB)) + LENGTH(CAST(COALESCE(correlation_id, '') AS BLOB)) + LENGTH(CAST(COALESCE(payload_json, '') AS BLOB)) + LENGTH(CAST(COALESCE(attachment_id, '') AS BLOB))), 0) FROM events WHERE session_id = :sessionId) + (SELECT COALESCE(SUM(stored_length), 0) FROM attachments WHERE session_id = :sessionId) WHERE id = :sessionId")
    void refreshUsage(String sessionId);

    /**
     * Cheap O(1) correction for the common case: a batch of events/attachments was just written (or
     * removed) for one session, so its usage counters only need to move by that batch's delta rather
     * than re-scanning every row the session owns. {@link #refreshUsage} remains the authoritative
     * full recompute, reserved for after retention pruning or other operations that can't cheaply
     * compute their own delta.
     */
    @Query("UPDATE sessions SET record_count = record_count + :deltaCount, estimated_bytes = estimated_bytes + :deltaBytes WHERE id = :sessionId")
    void incrementUsage(String sessionId, long deltaCount, long deltaBytes);

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    void delete(String sessionId);
}
