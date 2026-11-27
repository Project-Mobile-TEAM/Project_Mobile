package com.utechat.data.remote.model;

import androidx.annotation.Nullable;
import java.util.List;

/** DTO theo hợp đồng kho nhãn dán, tách biệt với dữ liệu cache Room. */
public final class StickerDtos {
    private StickerDtos() { }

    /** Tệp nhãn dán trong một bộ; URL tương đối được kiểm tra trước khi tải ảnh. */
    public static final class StickerDto {
        public String id;
        public String packId;
        public String code;
        public String label;
        public String imageUrl;
        public String mime;
        public int width;
        public int height;
        public int sortOrder;
    }

    /** Trạng thái installed mang giá trị theo tài khoản ở thời điểm lấy danh mục. */
    public static final class StickerPackDto {
        public String id;
        public String code;
        public String name;
        @Nullable public String description;
        public String thumbnailUrl;
        public boolean isDefault;
        public boolean installed;
        public int sortOrder;
        public List<StickerDto> stickers;
    }
}
