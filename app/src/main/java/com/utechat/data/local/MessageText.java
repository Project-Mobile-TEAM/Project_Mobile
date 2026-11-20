package com.utechat.data.local;

import androidx.annotation.Nullable;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Chuẩn hóa tiếng Việt trước khi ghi message.normalized_text hoặc tạo truy vấn FTS. */
public final class MessageText {
    // Chỉ giữ lại chữ cái, chữ số và dấu gạch dưới để tránh mọi ký tự toán tử đặc biệt của FTS4.
    private static final Pattern SAFE_TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{Nd}_]+");

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

    /**
     * Tạo chuỗi truy vấn FTS4 an toàn tuyệt đối từ ô tìm kiếm người dùng.
     * Trả về null khi đầu vào rỗng hoặc chỉ toàn ký tự dấu câu để DAO bỏ qua MATCH và trả rỗng.
     */
    @Nullable
    public static String safeMatchQuery(@Nullable String raw) {
        String normalized = normalize(raw);
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        Matcher matcher = SAFE_TOKEN_PATTERN.matcher(normalized);
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            tokens.add("\"" + matcher.group() + "\"*");
        }
        if (tokens.isEmpty()) {
            return null;
        }
        return String.join(" ", tokens);
    }
}
