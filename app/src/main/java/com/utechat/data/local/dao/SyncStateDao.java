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

    /**
     * Đặt lại con trỏ đồng bộ không điều kiện; CHỈ dùng khi bootstrap lần đầu
     * hoặc khi hồi phục từ lỗi 410 SYNC_CURSOR_EXPIRED.
     */
    @Transaction
    public void reset(String userId, long cursor, long at) {
        SyncStateEntity state = new SyncStateEntity();
        state.setUserId(userId);
        state.setLastSyncEventId(cursor);
        state.setLastSyncAt(at);
        upsert(state);
    }

    /** Xóa mốc đồng bộ của một tài khoản khi cần dọn trạng thái phiên cục bộ. */
    @Query("DELETE FROM sync_state WHERE userId = :userId")
    public abstract void clear(String userId);

    @Upsert protected abstract void upsert(SyncStateEntity state);
    @Query("SELECT * FROM sync_state WHERE userId = :userId")
    public abstract SyncStateEntity get(String userId);
}
