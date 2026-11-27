package com.utechat.backend.dto;

import java.util.List;

/** Dữ liệu bộ nhãn dán và ảnh trả cho ứng dụng. */
public final class StickerDtos {
    private StickerDtos() {}

    /** Thông tin đủ để hiển thị và tải một nhãn dán. */
    public record StickerDto(String id, String packId, String code, String label, String imageUrl,
                             String mime, int width, int height, int sortOrder) {}

    /** Trạng thái installed được tính riêng cho tài khoản đã xác thực. */
    public record StickerPackDto(String id, String code, String name, String description, String thumbnailUrl,
                                 boolean isDefault, boolean installed, int sortOrder, List<StickerDto> stickers) {}

    /** Nội dung ảnh đóng gói theo MIME đã xác định. */
    public record StickerAsset(byte[] bytes, String contentType) {}
}
