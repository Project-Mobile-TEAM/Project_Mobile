package com.utechat.data.local;

import java.text.Normalizer;
import java.util.Locale;

/** Chuẩn hóa tiếng Việt trước khi ghi message.normalized_text hoặc tạo truy vấn FTS. */
public final class MessageText {
    private MessageText() { }

    public static String normalize(String text) {
        if (text == null) return null;
        // NFD tách dấu kết hợp; đ/Đ cần đổi riêng vì Unicode không tự tách chúng.
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
    }

    public static String matchQuery(String text) {
        String normalized = normalize(text);
        if (normalized == null || normalized.trim().isEmpty()) return "\"\"";
        // Bao từng token trong dấu ngoặc kép để người dùng không chèn toán tử MATCH.
        return java.util.Arrays.stream(normalized.trim().split("\\s+"))
                .map(word -> "\"" + word.replace("\"", "\"\"") + "\"*")
                .collect(java.util.stream.Collectors.joining(" "));
    }
}
