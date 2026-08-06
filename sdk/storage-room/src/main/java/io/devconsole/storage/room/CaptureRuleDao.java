package io.devconsole.storage.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

@Dao
public interface CaptureRuleDao {
    @Query("SELECT * FROM capture_rules ORDER BY position ASC, id ASC")
    List<CaptureRuleEntity> rules();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<CaptureRuleEntity> rules);

    @Query("DELETE FROM capture_rules")
    void deleteAll();

    /**
     * Whole-set replacement in one transaction so a partially written rule set can never become
     * the active exclusion policy after a crash.
     */
    @Transaction
    default void replaceAll(List<CaptureRuleEntity> rules) {
        deleteAll();
        insertAll(rules);
    }
}
