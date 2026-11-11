package com.utechat.data.local.dao;

import androidx.room.*;
import com.utechat.data.local.entity.SyncStateEntity;

/** Dùng transaction và Upsert của Room để tương thích SQLite cũ từ Android API26. */
@Dao
public abstract class SyncStateDao {
    @Transaction
    public void advance(String userId, long cursor, long at) {
        // Event đến trễ không được làm lùi cursor đã xử lý.
        SyncStateEntity current = get(userId);
        if (current == null || cursor >= current.getLastSyncEventId()) {
            SyncStateEntity state = new SyncStateEntity();
            state.setUserId(userId);
            state.setLastSyncEventId(cursor);
            state.setLastSyncAt(at);
            upsert(state);
        }
    }

    @Upsert protected abstract void upsert(SyncStateEntity state);
    @Query("SELECT * FROM sync_state WHERE userId = :userId")
    public abstract SyncStateEntity get(String userId);
}
