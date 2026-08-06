/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.storage.room;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface EvidenceReportDao {
    @Nullable
    @Query("SELECT * FROM evidence_reports WHERE session_id = :sessionId LIMIT 1")
    EvidenceReportEntity report(String sessionId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(EvidenceReportEntity report);

    @Query("DELETE FROM evidence_reports WHERE session_id = :sessionId")
    void deleteSession(String sessionId);
}
