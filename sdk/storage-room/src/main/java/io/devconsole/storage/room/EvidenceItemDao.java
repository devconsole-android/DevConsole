/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.storage.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface EvidenceItemDao {
    @Query("SELECT COUNT(*) FROM evidence_items WHERE session_id = :sessionId AND kind = :kind AND subject_id = :subjectId")
    int existsCount(String sessionId, String kind, String subjectId);

    @Query("SELECT COUNT(*) FROM evidence_items WHERE session_id = :sessionId")
    int countForSession(String sessionId);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insert(EvidenceItemEntity item);

    @Query("SELECT * FROM evidence_items WHERE session_id = :sessionId ORDER BY flagged_at_ms ASC")
    List<EvidenceItemEntity> items(String sessionId);

    @Query("DELETE FROM evidence_items WHERE session_id = :sessionId AND kind = :kind AND subject_id = :subjectId")
    void delete(String sessionId, String kind, String subjectId);

    @Query("DELETE FROM evidence_items WHERE session_id = :sessionId")
    void deleteSession(String sessionId);
}
