package io.devconsole.storage.room;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.annotation.NonNull;

@Database(
        entities = {
                EventEntity.class,
                AttachmentEntity.class,
                TimelineAnnotationEntity.class,
                SessionEntity.class,
                CaptureRuleEntity.class,
                EvidenceItemEntity.class,
                EvidenceReportEntity.class
        },
        version = 5,
        exportSchema = true)
public abstract class DevConsoleDatabase extends RoomDatabase {
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `timeline_annotations` (" +
                            "`event_id` TEXT NOT NULL, `bookmarked` INTEGER NOT NULL, " +
                            "`note` TEXT, PRIMARY KEY(`event_id`))");
        }
    };

    /** Adds durable app-run metadata and conservatively reconstructs completed legacy runs. */
    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `attachments` ADD COLUMN `pending_deletion` INTEGER NOT NULL DEFAULT 0");
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sessions` (" +
                            "`id` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                            "`started_at_ms` INTEGER NOT NULL, `started_at_monotonic_ns` INTEGER NOT NULL, " +
                            "`ended_at_ms` INTEGER, `application_id` TEXT, " +
                            "`app_version_name` TEXT, `app_version_code` INTEGER, `build_type` TEXT, " +
                            "`device_model` TEXT, `device_api_level` INTEGER, `device_os_version` TEXT, " +
                            "`record_count` INTEGER NOT NULL, `estimated_bytes` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`id`))");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_status` ON `sessions` (`status`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_started_at_ms` ON `sessions` (`started_at_ms`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_ended_at_ms` ON `sessions` (`ended_at_ms`)");
            database.execSQL(
                    "INSERT OR IGNORE INTO `sessions` " +
                            "(`id`, `status`, `started_at_ms`, `started_at_monotonic_ns`, `ended_at_ms`, `record_count`, `estimated_bytes`) " +
                            "SELECT session_id, 'COMPLETED', MIN(timestamp_ms), 0, MAX(timestamp_ms), " +
                            "SUM(record_count), SUM(estimated_bytes) FROM (" +
                            "SELECT session_id, wall_time_ms AS timestamp_ms, 1 AS record_count, " +
                            "96 + LENGTH(CAST(id AS BLOB)) + LENGTH(CAST(session_id AS BLOB)) + " +
                            "LENGTH(CAST(plugin_id AS BLOB)) + LENGTH(CAST(type AS BLOB)) + " +
                            "LENGTH(CAST(summary AS BLOB)) + LENGTH(CAST(tags_json AS BLOB)) + " +
                            "LENGTH(CAST(COALESCE(correlation_id, '') AS BLOB)) + " +
                            "LENGTH(CAST(COALESCE(payload_json, '') AS BLOB)) + " +
                            "LENGTH(CAST(COALESCE(attachment_id, '') AS BLOB)) AS estimated_bytes FROM events " +
                            "UNION ALL SELECT session_id, created_wall_time_ms, 1, stored_length FROM attachments" +
                            ") GROUP BY session_id");
        }
    };

    /** Adds durable capture-exclusion rules; no existing data is touched. */
    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `capture_rules` (" +
                            "`id` TEXT NOT NULL, `host` TEXT NOT NULL, `method` TEXT, " +
                            "`path_prefix` TEXT, `enabled` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`id`))");
        }
    };

    /**
     * Adds the durable evidence tray tables (flagged items and their report draft) and the
     * attachment redaction-applicability column screenshots need. All three changes share one
     * migration so there is exactly one migration author and one exported {@code schemas/…/5.json}.
     * Additive DDL only: no existing row in any table is rewritten.
     */
    public static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `evidence_items` (" +
                            "`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                            "`subject_id` TEXT NOT NULL, `label` TEXT NOT NULL, `flagged_at_ms` INTEGER NOT NULL, " +
                            "`snapshot_json` TEXT NOT NULL, `attachment_id` TEXT, PRIMARY KEY(`id`))");
            database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_evidence_items_subject` " +
                            "ON `evidence_items` (`session_id`, `kind`, `subject_id`)");
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_evidence_items_session_id` " +
                            "ON `evidence_items` (`session_id`)");
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `evidence_reports` (" +
                            "`session_id` TEXT NOT NULL, `severity` TEXT NOT NULL, `summary` TEXT, " +
                            "`expected` TEXT, `actual` TEXT, `updated_at_ms` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`session_id`))");
            database.execSQL(
                    "ALTER TABLE `attachments` " +
                            "ADD COLUMN `redaction_applicability` TEXT NOT NULL DEFAULT 'APPLIED'");
        }
    };

    public abstract EventDao eventDao();
    public abstract AttachmentDao attachmentDao();
    public abstract TimelineAnnotationDao timelineAnnotationDao();
    public abstract SessionDao sessionDao();
    public abstract CaptureRuleDao captureRuleDao();
    public abstract EvidenceItemDao evidenceItemDao();
    public abstract EvidenceReportDao evidenceReportDao();
}
