package com.utechat.data.local.dao;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Query;
import androidx.room.Upsert;
import com.utechat.data.local.entity.AttachmentTransferEntity;
import java.util.List;

/** TransferDao: giao tiếp SQLite cho bảng attachment_transfer, kiểm tra câu SQL ngay khi biên dịch. */
@Dao
public interface TransferDao {
    // Cache công việc truyền file; không lưu byte của file vào SQLite.
    @Upsert
    void insert(AttachmentTransferEntity transfer);

    @Query("SELECT * FROM attachment_transfer WHERE clientId = :id")
    LiveData<AttachmentTransferEntity> getByClientId(String id);

    // Tra cứu đồng bộ trong Worker/Repository nền mà không cần quan sát LiveData.
    @Query("SELECT * FROM attachment_transfer WHERE clientId = :id LIMIT 1")
    AttachmentTransferEntity findByClientIdSync(String id);

    // Quan sát toàn bộ tiến độ upload/download để cập nhật badge trên danh sách tin nhắn.
    @Query("SELECT * FROM attachment_transfer")
    LiveData<List<AttachmentTransferEntity>> observeAll();

    // Cập nhật tiến độ phần trăm khi đang truyền dữ liệu.
    @Query("UPDATE attachment_transfer SET progress = :progress, state = :state, updatedAt = :updatedAt WHERE clientId = :clientId")
    void updateProgress(String clientId, String state, int progress, long updatedAt);

    // Cập nhật trạng thái và mã lỗi bền vững (ví dụ FAILED + NETWORK / FILE_TOO_LARGE).
    @Query("UPDATE attachment_transfer SET state = :state, progress = :progress, errorCode = :errorCode, updatedAt = :updatedAt WHERE clientId = :clientId")
    void updateState(String clientId, String state, int progress, @Nullable String errorCode, long updatedAt);

    // Chốt kết quả sau khi POST /api/media thành công, giữ lại metadata cũ nếu server trả null.
    @Query("UPDATE attachment_transfer SET mediaUrl = :mediaUrl, state = 'UPLOADED', progress = 100, "
            + "mime = COALESCE(:mime, mime), sizeBytes = COALESCE(:sizeBytes, sizeBytes), "
            + "durationMs = COALESCE(:durationMs, durationMs), width = COALESCE(:width, width), "
            + "height = COALESCE(:height, height), fileName = COALESCE(:fileName, fileName), "
            + "errorCode = NULL, updatedAt = :updatedAt WHERE clientId = :clientId")
    void markUploaded(
            String clientId,
            String mediaUrl,
            @Nullable String mime,
            @Nullable Long sizeBytes,
            @Nullable Integer durationMs,
            @Nullable Integer width,
            @Nullable Integer height,
            @Nullable String fileName,
            long updatedAt);

    // Xóa bản ghi transfer sau khi người dùng hủy hoặc dọn tệp tạm thành công.
    @Query("DELETE FROM attachment_transfer WHERE clientId = :clientId")
    void deleteByClientId(String clientId);
}
