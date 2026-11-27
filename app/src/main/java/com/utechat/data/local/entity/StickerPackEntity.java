package com.utechat.data.local.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Bộ nhãn dán và trạng thái cài của tài khoản hiện tại. */
@Entity(tableName = "sticker_pack")
public class StickerPackEntity {
    /** UUID của bộ nhãn dán. */
    @PrimaryKey @NonNull
    private String packId = "";

    /** Mã ổn định trong kho. */
    @NonNull
    private String code = "";

    /** Tên hiển thị trong kho và khay. */
    @NonNull
    private String name = "";

    /** Mô tả tùy chọn. */
    @Nullable
    private String description;

    /** Đường dẫn ảnh xem trước trên backend. */
    @NonNull
    private String thumbnailUrl = "";

    /** Trạng thái cài của tài khoản. */
    private boolean installed;

    /** Bộ cài sẵn cho tài khoản mới. */
    private boolean isDefault;

    /** Thứ tự hiển thị trong kho. */
    private int sortOrder;

    // Room tạo đối tượng rồi điền từng cột qua setter.
    public StickerPackEntity() { }

    @NonNull public String getPackId() { return packId; }
    public void setPackId(@NonNull String value) { packId = value; }

    @NonNull public String getCode() { return code; }
    public void setCode(@NonNull String value) { code = value; }

    @NonNull public String getName() { return name; }
    public void setName(@NonNull String value) { name = value; }

    @Nullable public String getDescription() { return description; }
    public void setDescription(@Nullable String value) { description = value; }

    @NonNull public String getThumbnailUrl() { return thumbnailUrl; }
    public void setThumbnailUrl(@NonNull String value) { thumbnailUrl = value; }

    public boolean getInstalled() { return installed; }
    public void setInstalled(boolean value) { installed = value; }

    public boolean getIsDefault() { return isDefault; }
    public void setIsDefault(boolean value) { isDefault = value; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int value) { sortOrder = value; }
}
