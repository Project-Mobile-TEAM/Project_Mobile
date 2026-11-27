package com.utechat.data.local.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Metadata nhãn dán và đường dẫn tệp cho khay offline. */
@Entity(tableName = "sticker", indices = {@Index("packId")})
public class StickerEntity {
    /** UUID dùng khi gửi nhãn dán. */
    @PrimaryKey @NonNull
    private String stickerId = "";

    /** UUID bộ chứa nhãn dán. */
    @NonNull
    private String packId = "";

    /** Mã nhãn dán trong bộ. */
    @NonNull
    private String code = "";

    /** Tên dùng để nhận diện và hỗ trợ tiếp cận. */
    @NonNull
    private String label = "";

    /** Đường dẫn tương đối tới ảnh trên backend. */
    @NonNull
    private String imageUrl = "";

    /** Đường dẫn tệp đã lưu riêng trong ứng dụng. */
    @Nullable
    private String localPath;

    /** Chiều rộng ảnh theo pixel. */
    private int width;

    /** Chiều cao ảnh theo pixel. */
    private int height;

    /** Thứ tự hiển thị trong bộ. */
    private int sortOrder;

    // Room tạo đối tượng rồi điền từng cột qua setter.
    public StickerEntity() { }

    @NonNull public String getStickerId() { return stickerId; }
    public void setStickerId(@NonNull String value) { stickerId = value; }

    @NonNull public String getPackId() { return packId; }
    public void setPackId(@NonNull String value) { packId = value; }

    @NonNull public String getCode() { return code; }
    public void setCode(@NonNull String value) { code = value; }

    @NonNull public String getLabel() { return label; }
    public void setLabel(@NonNull String value) { label = value; }

    @NonNull public String getImageUrl() { return imageUrl; }
    public void setImageUrl(@NonNull String value) { imageUrl = value; }

    @Nullable public String getLocalPath() { return localPath; }
    public void setLocalPath(@Nullable String value) { localPath = value; }

    public int getWidth() { return width; }
    public void setWidth(int value) { width = value; }

    public int getHeight() { return height; }
    public void setHeight(int value) { height = value; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int value) { sortOrder = value; }
}
