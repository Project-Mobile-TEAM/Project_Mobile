package com.utechat.ui.chat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.utechat.R;
import com.utechat.data.local.entity.UserEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dựng câu hiển thị cho tin SYSTEM (D-09) và dòng xem trước của tin cuối (D-15).
 * attachmentJson có dạng {"systemEvent":"MEMBER_ADDED","systemParams":"{\"actorId\":...}"} do SyncEventApplier ghi.
 */
public final class SystemMessageFormatter {
    /** Context::getString khớp chữ ký này; test JVM truyền lambda giả. */
    public interface Strings { String get(int resId, Object... args); }

    private SystemMessageFormatter() { }

    /** Câu mô tả sự kiện nhóm, ví dụ "Khang đã thêm An vào nhóm". */
    @NonNull public static String format(@NonNull Strings s, @Nullable String attachmentJson,
                                         @Nullable Map<String, UserEntity> users, @Nullable String meId) {
        JsonObject meta = parse(attachmentJson);
        String event = str(meta, "systemEvent");
        JsonObject p = parse(str(meta, "systemParams"));
        String actor = name(s, str(p, "actorId"), users, meId);
        String target = name(s, str(p, "targetUserId"), users, meId);
        if (event == null) return s.get(R.string.chat_sys_unknown);
        switch (event) {
            case "GROUP_CREATED": return s.get(R.string.chat_sys_group_created, actor, nz(str(p, "title")));
            case "GROUP_RENAMED": return s.get(R.string.chat_sys_group_renamed, actor, nz(str(p, "newTitle")));
            case "MEMBER_ADDED": return s.get(R.string.chat_sys_member_added, actor, names(s, p, users, meId));
            case "MEMBER_REMOVED": return s.get(R.string.chat_sys_member_removed, actor, target);
            case "MEMBER_LEFT": return s.get(R.string.chat_sys_member_left, target);
            case "ROLE_CHANGED": {
                String targetId = str(p, "targetUserId");
                boolean isAdmin = "ADMIN".equals(str(p, "newRole"));
                if (targetId != null && targetId.equals(meId)) {
                    return s.get(isAdmin ? R.string.chat_sys_role_admin_you : R.string.chat_sys_role_member_you);
                }
                return s.get(isAdmin ? R.string.chat_sys_role_admin : R.string.chat_sys_role_member, target);
            }
            default: return s.get(R.string.chat_sys_unknown);
        }
    }

    /** Dòng xem trước tin cuối ở danh sách chat: TEXT giữ nguyên, loại khác thay bằng nhãn tóm tắt. */
    @NonNull public static String preview(@NonNull Strings s, @Nullable String type, @Nullable String text,
                                          @Nullable String attachmentJson, @Nullable String senderId, boolean group,
                                          @Nullable Map<String, UserEntity> users, @Nullable String meId) {
        return preview(s, type, text, attachmentJson, senderId, group, users, meId, null);
    }

    /** Tin đã gỡ dùng nhãn cố định, kể cả khi dữ liệu cũ còn giữ nội dung trong Room. */
    @NonNull public static String preview(@NonNull Strings s, @Nullable String type, @Nullable String text,
                                          @Nullable String attachmentJson, @Nullable String senderId, boolean group,
                                          @Nullable Map<String, UserEntity> users, @Nullable String meId,
                                          @Nullable Long recalledAt) {
        if (recalledAt != null) return s.get(R.string.chat_message_recalled);
        if (type == null) return "";
        if ("SYSTEM".equals(type)) return format(s, attachmentJson, users, meId);
        String body = "TEXT".equals(type) ? nz(text) : s.get(summaryLabel(type));
        // Tin của mình luôn có "Bạn:"; tin người khác chỉ ghi tên người gửi trong nhóm (theo Figma).
        if (senderId != null && senderId.equals(meId)) return s.get(R.string.chat_preview_me, body);
        if (group && senderId != null) return s.get(R.string.chat_preview_sender, name(s, senderId, users, meId), body);
        return body;
    }

    /** Nhãn cho tin không phải chữ (D-15); loại lạ dùng nhãn "không hỗ trợ". */
    public static int summaryLabel(@Nullable String type) {
        if (type == null) return R.string.chat_type_unknown;
        switch (type) {
            case "IMAGE": return R.string.chat_type_image;
            case "AUDIO": return R.string.chat_type_audio;
            case "VIDEO": return R.string.chat_type_video;
            case "FILE": return R.string.chat_type_file;
            case "LOCATION": return R.string.chat_type_location;
            case "CALL": return R.string.chat_type_call;
            case "STICKER": return R.string.chat_type_sticker;
            default: return R.string.chat_type_unknown;
        }
    }

    private static String names(Strings s, JsonObject p, Map<String, UserEntity> users, String meId) {
        List<String> out = new ArrayList<>();
        JsonElement ids = p == null ? null : p.get("targetUserIds");
        if (ids != null && ids.isJsonArray()) for (JsonElement id : (JsonArray) ids) {
            if (id.isJsonPrimitive()) out.add(name(s, id.getAsString(), users, meId));
        }
        return String.join(", ", out);
    }

    private static String name(Strings s, @Nullable String userId, @Nullable Map<String, UserEntity> users, @Nullable String meId) {
        if (userId == null) return s.get(R.string.chat_someone);
        if (userId.equals(meId)) return s.get(R.string.chat_you);
        UserEntity u = users == null ? null : users.get(userId);
        return u == null || u.getDisplayName() == null || u.getDisplayName().isBlank() ? s.get(R.string.chat_someone) : u.getDisplayName();
    }

    @Nullable private static JsonObject parse(@Nullable String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonElement e = JsonParser.parseString(json);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException malformed) {
            return null; // JSON hỏng từ cache cũ: hiển thị câu dự phòng thay vì crash danh sách.
        }
    }

    @Nullable private static String str(@Nullable JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static String nz(@Nullable String v) { return v == null ? "" : v; }
}
