package io.devconsole.storage.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface AttachmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(AttachmentEntity attachment);

    @Query("SELECT * FROM attachments WHERE is_bookmarked = 0 AND pending_deletion = 0 AND NOT EXISTS (SELECT 1 FROM timeline_annotations WHERE event_id = attachments.event_id AND bookmarked = 1) ORDER BY created_wall_time_ms ASC")
    List<AttachmentEntity> oldestUnbookmarked();

    @Query("SELECT COALESCE(SUM(stored_length), 0) FROM attachments")
    long totalStoredBytes();

    @Query("SELECT COUNT(*) FROM attachments WHERE id = :id")
    int contains(String id);

    @Query("SELECT * FROM attachments WHERE id = :id AND pending_deletion = 0 LIMIT 1")
    AttachmentEntity attachment(String id);

    @Query("DELETE FROM attachments WHERE id = :id")
    void deleteById(String id);

    @Query("UPDATE attachments SET pending_deletion = 1 WHERE id = :id AND pending_deletion = 0")
    int markPendingDeletion(String id);

    @Query("UPDATE attachments SET pending_deletion = 0 WHERE id = :id AND pending_deletion = 1")
    int clearPendingDeletion(String id);

    @Query("SELECT * FROM attachments WHERE pending_deletion = 1 ORDER BY created_wall_time_ms ASC")
    List<AttachmentEntity> pendingDeletion();

    @Query("SELECT * FROM attachments WHERE session_id = :sessionId AND pending_deletion = 1 ORDER BY created_wall_time_ms ASC")
    List<AttachmentEntity> pendingDeletionForSession(String sessionId);

    @Query("UPDATE attachments SET pending_deletion = 1 WHERE session_id = :sessionId")
    void markSessionPendingDeletion(String sessionId);

    @Query("DELETE FROM attachments WHERE session_id = :sessionId")
    void deleteSession(String sessionId);

    @Query("SELECT * FROM attachments WHERE session_id = :sessionId AND is_bookmarked = 0 AND pending_deletion = 0 AND NOT EXISTS (SELECT 1 FROM timeline_annotations WHERE event_id = attachments.event_id AND bookmarked = 1) ORDER BY created_wall_time_ms ASC")
    List<AttachmentEntity> oldestUnbookmarkedForSession(String sessionId);
}
