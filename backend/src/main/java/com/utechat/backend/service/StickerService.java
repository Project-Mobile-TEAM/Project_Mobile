package com.utechat.backend.service;

import com.utechat.backend.dto.StickerDtos.StickerAsset;
import com.utechat.backend.dto.StickerDtos.StickerDto;
import com.utechat.backend.dto.StickerDtos.StickerPackDto;
import com.utechat.backend.exception.ApiException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Đọc danh mục cố định và lưu lựa chọn thêm/gỡ nhãn dán theo từng tài khoản. */
@Service
public class StickerService {
    private static final Pattern PACK_CODE = Pattern.compile("[a-z0-9_-]{1,50}");
    private static final Pattern ASSET_NAME = Pattern.compile("[a-z0-9_-]{1,50}\\.(png|webp)");

    private final JdbcTemplate jdbc;

    public StickerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<StickerPackDto> listPacks(UUID actorId) {
        // Gom nhãn dán một lần để tránh truy vấn lặp cho từng bộ.
        Map<String, List<StickerDto>> byPack = jdbc.query("""
                SELECT id, pack_id, code, label, image_url, mime, width, height, sort_order
                FROM public.stickers ORDER BY sort_order, code
                """, (rs, row) -> new StickerDto(
                rs.getString("id"), rs.getString("pack_id"), rs.getString("code"), rs.getString("label"),
                rs.getString("image_url"), rs.getString("mime"), rs.getInt("width"), rs.getInt("height"),
                rs.getInt("sort_order"))).stream().collect(Collectors.groupingBy(StickerDto::packId));

        // Khi chưa có lựa chọn riêng thì dùng cờ mặc định của bộ.
        return jdbc.query("""
                SELECT sp.id, sp.code, sp.name, sp.description, sp.thumbnail_url, sp.is_default, sp.sort_order,
                       COALESCE(usp.installed, sp.is_default) AS installed
                FROM public.sticker_packs sp
                LEFT JOIN public.user_sticker_packs usp ON usp.pack_id = sp.id AND usp.user_id = ?
                ORDER BY sp.sort_order, sp.code
                """, (rs, row) -> new StickerPackDto(
                rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getString("description"),
                rs.getString("thumbnail_url"), rs.getBoolean("is_default"), rs.getBoolean("installed"),
                rs.getInt("sort_order"), byPack.getOrDefault(rs.getString("id"), List.of())), actorId);
    }

    @Transactional
    public void setInstalled(UUID actorId, UUID packId, boolean installed) {
        // INSERT ... SELECT tránh tạo bản ghi cho bộ không tồn tại. Ghi cả lựa chọn gỡ bộ mặc định.
        int rows = jdbc.update("""
                INSERT INTO public.user_sticker_packs (user_id, pack_id, installed, installed_at)
                SELECT ?, id, ?, now() FROM public.sticker_packs WHERE id = ?
                ON CONFLICT (user_id, pack_id) DO UPDATE
                SET installed = EXCLUDED.installed,
                    installed_at = CASE WHEN EXCLUDED.installed AND NOT user_sticker_packs.installed
                                        THEN EXCLUDED.installed_at ELSE user_sticker_packs.installed_at END
                """, actorId, installed, packId);
        if (rows == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Sticker pack not found");
        }
    }

    public StickerAsset loadAsset(String packCode, String fileName) {
        // Chỉ đọc ảnh đóng gói trong classpath sau khi kiểm tra từng thành phần của đường dẫn.
        if (!PACK_CODE.matcher(packCode).matches() || !ASSET_NAME.matcher(fileName).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Invalid sticker asset path");
        }
        ClassPathResource resource = new ClassPathResource("stickers/" + packCode + "/" + fileName);
        if (!resource.exists()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Sticker asset not found");
        }
        try {
            return new StickerAsset(resource.getContentAsByteArray(),
                    fileName.endsWith(".webp") ? "image/webp" : "image/png");
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Cannot read sticker asset");
        }
    }
}
