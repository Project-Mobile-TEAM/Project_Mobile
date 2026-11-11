package com.utechat.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.*;
import java.util.List;
import com.utechat.data.local.entity.*;
import com.utechat.data.local.MessageText;

/** TransferDao: giao tiếp SQLite có kiểm tra câu SQL khi biên dịch. */
@Dao
public interface TransferDao {
    // Cache công việc truyền file; không lưu byte của file vào SQLite.
    @Upsert void insert(AttachmentTransferEntity transfer);
    @Query("SELECT * FROM attachment_transfer WHERE clientId = :id") LiveData<AttachmentTransferEntity> getByClientId(String id);
}
