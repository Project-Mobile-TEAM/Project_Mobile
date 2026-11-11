package com.utechat.data.local.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.*;

/** Cache bản dịch theo khóa tin và ngôn ngữ đích. */
@Entity(tableName = "message_translation", primaryKeys = {"messageKey", "targetLang"})
public class MessageTranslationEntity {
    /** Khóa ổn định của tin, ưu tiên clientId. */
    @NonNull
    private String messageKey;

    /** Mã ngôn ngữ ML Kit, ví dụ vi/en. */
    @NonNull
    private String targetLang;

    /** Kết quả dịch chỉ lưu ở thiết bị. */
    @NonNull
    private String translatedText;

    // Room dùng constructor rỗng để tạo đối tượng rồi đọc/ghi qua getter và setter.
    public MessageTranslationEntity() { }

    // Đọc và cập nhật messageKey khi ánh xạ DTO hoặc khôi phục cache.
    public String getMessageKey() { return messageKey; }
    public void setMessageKey(String value) { this.messageKey = value; }

    // Đọc và cập nhật targetLang khi ánh xạ DTO hoặc khôi phục cache.
    public String getTargetLang() { return targetLang; }
    public void setTargetLang(String value) { this.targetLang = value; }

    // Đọc và cập nhật translatedText khi ánh xạ DTO hoặc khôi phục cache.
    public String getTranslatedText() { return translatedText; }
    public void setTranslatedText(String value) { this.translatedText = value; }
}
