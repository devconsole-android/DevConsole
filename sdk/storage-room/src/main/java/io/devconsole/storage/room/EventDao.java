package io.devconsole.storage.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface EventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<EventEntity> events);

    @Query("SELECT * FROM events WHERE session_id = :sessionId ORDER BY sequence ASC")
    List<EventEntity> eventsForSession(String sessionId);

    @Query(
            "SELECT * FROM (" +
                    "SELECT * FROM events WHERE session_id = :sessionId " +
                    "ORDER BY wall_time_ms DESC, sequence DESC, id DESC LIMIT :limit" +
                    ") ORDER BY wall_time_ms ASC, sequence ASC, id ASC")
    List<EventEntity> recentEventsForSession(String sessionId, int limit);

    /**
     * Same windowed-newest-N shape as {@link #recentEventsForSession}, but the plugin_id IN (...)
     * predicate is applied inside the inner SELECT -- before ORDER BY/LIMIT trims to the page size
     * -- so a rare plugin (e.g. one crash among hundreds of network events) still survives a small
     * limit instead of being crowded out by unrelated rows that happened to be more recent.
     */
    @Query(
            "SELECT * FROM (" +
                    "SELECT * FROM events WHERE session_id = :sessionId AND plugin_id IN (:pluginIds) " +
                    "ORDER BY wall_time_ms DESC, sequence DESC, id DESC LIMIT :limit" +
                    ") ORDER BY wall_time_ms ASC, sequence ASC, id ASC")
    List<EventEntity> recentEventsForSessionByPlugin(String sessionId, List<String> pluginIds, int limit);

    /**
     * Cross-session counterpart of {@link #recentEventsForSessionByPlugin} -- no session_id
     * predicate at all, so a crash captured under a run that has since ended (its process died,
     * a new session is now current) is still reachable once the caller knows only the plugin id,
     * not which session wrote it. Ordered by wall_time_ms (real epoch time, comparable across
     * process restarts), unlike the mono_time_ns ordering {@link #recentEvents} uses -- monotonic
     * clocks reset per-process, so they cannot be trusted to order rows from different sessions.
     */
    @Query(
            "SELECT * FROM (" +
                    "SELECT * FROM events WHERE plugin_id IN (:pluginIds) " +
                    "ORDER BY wall_time_ms DESC, sequence DESC, id DESC LIMIT :limit" +
                    ") ORDER BY wall_time_ms ASC, sequence ASC, id ASC")
    List<EventEntity> recentEventsByPlugin(List<String> pluginIds, int limit);

    @Query("DELETE FROM events WHERE session_id = :sessionId")
    void deleteSession(String sessionId);

    @Query("SELECT COUNT(*) FROM events")
    long eventCount();

    @Query("DELETE FROM events WHERE wall_time_ms < :cutoffEpochMs")
    int deleteOlderThan(long cutoffEpochMs);

    @Query(
            "SELECT COALESCE(SUM(" +
                    "96 + LENGTH(CAST(id AS BLOB)) + LENGTH(CAST(session_id AS BLOB)) + " +
                    "LENGTH(CAST(plugin_id AS BLOB)) + LENGTH(CAST(type AS BLOB)) + " +
                    "LENGTH(CAST(summary AS BLOB)) + LENGTH(CAST(tags_json AS BLOB)) + " +
                    "LENGTH(CAST(COALESCE(correlation_id, '') AS BLOB)) + " +
                    "LENGTH(CAST(COALESCE(payload_json, '') AS BLOB)) + " +
                    "LENGTH(CAST(COALESCE(attachment_id, '') AS BLOB))" +
                    "), 0) FROM events")
    long estimatedStoredBytes();

    @Query(
            "SELECT * FROM (" +
                    "SELECT * FROM events ORDER BY mono_time_ns DESC, sequence DESC, id DESC LIMIT :limit" +
                    ") ORDER BY mono_time_ns ASC, sequence ASC, id ASC")
    List<EventEntity> recentEvents(int limit);

    @Query("SELECT id FROM events ORDER BY severity ASC, wall_time_ms ASC LIMIT :limit")
    List<String> oldestLowSeverityFirst(int limit);

    @Query("DELETE FROM events WHERE id IN (:ids)")
    void deleteByIds(List<String> ids);

    @Query("SELECT id FROM events WHERE session_id = :sessionId AND id NOT IN (SELECT event_id FROM timeline_annotations WHERE bookmarked = 1) AND NOT EXISTS (SELECT 1 FROM attachments WHERE attachments.event_id = events.id) ORDER BY severity ASC, wall_time_ms ASC LIMIT :limit")
    List<String> oldestUnbookmarkedLowSeverityFirstForSession(String sessionId, int limit);

    /** Distinct owning sessions for rows a global age-based prune is about to delete, captured before the delete. */
    @Query("SELECT DISTINCT session_id FROM events WHERE wall_time_ms < :cutoffEpochMs")
    List<String> sessionIdsOlderThan(long cutoffEpochMs);

    /** Distinct owning sessions for rows a global quota prune is about to delete, captured before the delete. */
    @Query("SELECT DISTINCT session_id FROM events WHERE id IN (:ids)")
    List<String> sessionIdsForIds(List<String> ids);
}
