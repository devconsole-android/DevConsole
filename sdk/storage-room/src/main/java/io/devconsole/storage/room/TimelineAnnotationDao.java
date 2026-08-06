package io.devconsole.storage.room;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface TimelineAnnotationDao {
    @Nullable
    @Query("SELECT * FROM timeline_annotations WHERE event_id = :eventId")
    TimelineAnnotationEntity annotation(String eventId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(TimelineAnnotationEntity annotation);

    @Query("DELETE FROM timeline_annotations WHERE event_id = :eventId")
    void delete(String eventId);

    @Query("DELETE FROM timeline_annotations WHERE event_id IN (SELECT id FROM events WHERE session_id = :sessionId)")
    void deleteSession(String sessionId);

    @Query("DELETE FROM timeline_annotations WHERE event_id IN (:eventIds)")
    void deleteEvents(java.util.List<String> eventIds);
}
