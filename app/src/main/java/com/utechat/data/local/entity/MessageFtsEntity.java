package com.utechat.data.local.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.*;

/** Chỉ mục tìm kiếm FTS4 đồng bộ bằng trigger với bảng message. */
@Fts4(contentEntity = MessageEntity.class)
@Entity(tableName = "message_fts")
public class MessageFtsEntity {
    /** Văn bản nguyên gốc cho tìm kiếm có dấu. */
    @Nullable
    private String text;

    /** Văn bản bỏ dấu cho tìm kiếm tiếng Việt. */
    @Nullable @ColumnInfo(name = "normalized_text")
    private String normalizedText;

    // Room dùng constructor rỗng để tạo đối tượng rồi đọc/ghi qua getter và setter.
    public MessageFtsEntity() { }

    // Đọc và cập nhật text khi ánh xạ DTO hoặc khôi phục cache.
    public String getText() { return text; }
    public void setText(String value) { this.text = value; }

    // Đọc và cập nhật normalizedText khi ánh xạ DTO hoặc khôi phục cache.
    public String getNormalizedText() { return normalizedText; }
    public void setNormalizedText(String value) { this.normalizedText = value; }
}
