/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole.storage.room;

import androidx.room.ColumnInfo;

/** Lightweight ordered event projection used by retention scans. */
public final class EventPruneCandidate {
    @ColumnInfo(name = "id")
    public String id;

    @ColumnInfo(name = "bytes")
    public long bytes;

    public EventPruneCandidate() {}
}
