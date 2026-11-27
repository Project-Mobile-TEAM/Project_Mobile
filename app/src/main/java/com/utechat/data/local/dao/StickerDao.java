package com.utechat.data.local.dao;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Upsert;
import com.utechat.data.local.entity.StickerEntity;
import com.utechat.data.local.entity.StickerPackEntity;
import java.util.List;

/** DAO kho nhãn dán và cache tệp của tài khoản đang đăng nhập. */
@Dao
public interface StickerDao {
    // Cập nhật danh mục theo UUID và thứ tự do backend quản lý.
    @Upsert
    void upsertPacks(List<StickerPackEntity> packs);

    @Upsert
    void upsertStickers(List<StickerEntity> stickers);

    // Kho hiển thị cả bộ chưa cài để người dùng có thể tải.
    @Query("SELECT * FROM sticker_pack ORDER BY sortOrder ASC, code ASC")
    LiveData<List<StickerPackEntity>> observeAllPacks();

    @Query("SELECT * FROM sticker_pack WHERE installed = 1 ORDER BY sortOrder ASC, code ASC")
    LiveData<List<StickerPackEntity>> observeInstalledPacks();

    @Query("SELECT * FROM sticker WHERE packId = :packId ORDER BY sortOrder ASC, code ASC")
    LiveData<List<StickerEntity>> observeStickersByPack(String packId);

    // Khay chỉ đọc các bộ đã cài.
    @Query("SELECT s.* FROM sticker s INNER JOIN sticker_pack p ON p.packId = s.packId WHERE p.installed = 1 "
            + "ORDER BY p.sortOrder ASC, p.code ASC, s.sortOrder ASC, s.code ASC")
    LiveData<List<StickerEntity>> observeInstalledStickers();

    // Các truy vấn đồng bộ phục vụ cập nhật cache trên luồng đĩa.
    @Query("SELECT * FROM sticker_pack WHERE packId = :packId")
    StickerPackEntity findPackByIdSync(String packId);

    @Query("SELECT * FROM sticker_pack ORDER BY sortOrder ASC, code ASC")
    List<StickerPackEntity> findAllPacksSync();

    @Query("SELECT * FROM sticker WHERE packId = :packId ORDER BY sortOrder ASC, code ASC")
    List<StickerEntity> findStickersByPackSync(String packId);

    @Query("SELECT * FROM sticker WHERE stickerId = :stickerId")
    StickerEntity findStickerByIdSync(String stickerId);

    // Đổi trạng thái cài và giữ metadata nhãn dán trong lịch sử.
    @Query("UPDATE sticker_pack SET installed = :installed WHERE packId = :packId")
    void updatePackInstalled(String packId, boolean installed);

    @Query("UPDATE sticker SET localPath = :localPath WHERE stickerId = :stickerId")
    void updateStickerLocalPath(String stickerId, @Nullable String localPath);

    @Query("DELETE FROM sticker_pack")
    void clearAllPacks();

    @Query("DELETE FROM sticker")
    void clearAllStickers();

    // Dọn bộ nhãn dán cùng metadata khi cần làm mới cache của tài khoản.
    @Transaction
    default void clearAll() {
        clearAllStickers();
        clearAllPacks();
    }
}
