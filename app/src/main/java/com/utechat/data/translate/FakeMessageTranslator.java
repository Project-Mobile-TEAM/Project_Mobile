package com.utechat.data.translate;

import androidx.annotation.NonNull;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bộ dịch giả lập offline cho chế độ phát triển USE_FAKE_DATA = true:
 * mô phỏng dịch tức thì EN <-> VI mà không cần tải gói mô hình ~30MB.
 */
public final class FakeMessageTranslator implements MessageTranslator {
    private static final Map<String, String> VI_TO_EN = new HashMap<>();
    private static final Map<String, String> EN_TO_VI = new HashMap<>();

    static {
        VI_TO_EN.put("xin chào", "Hello");
        VI_TO_EN.put("chào bạn", "Hi there");
        VI_TO_EN.put("cảm ơn", "Thank you");
        VI_TO_EN.put("tạm biệt", "Goodbye");
        EN_TO_VI.put("hello", "Xin chào");
        EN_TO_VI.put("hi", "Xin chào");
        EN_TO_VI.put("thank you", "Cảm ơn");
        EN_TO_VI.put("goodbye", "Tạm biệt");
    }

    @Override
    public void isModelDownloaded(@NonNull String langCode, @NonNull ModelCheckCallback cb) {
        cb.onResult(true);
    }

    @Override
    public void downloadModel(@NonNull String langCode, boolean allowCellular, @NonNull DownloadCallback cb) {
        cb.onSuccess();
    }

    @Override
    public void translate(
            @NonNull String sourceLang,
            @NonNull String targetLang,
            @NonNull String text,
            @NonNull TranslateCallback cb) {
        String trimmed = text.trim();
        String key = trimmed.toLowerCase(Locale.ROOT);
        if (LANG_EN.equalsIgnoreCase(targetLang)) {
            String mapped = VI_TO_EN.get(key);
            cb.onSuccess(mapped != null ? mapped : "[EN] " + trimmed);
        } else if (LANG_VI.equalsIgnoreCase(targetLang)) {
            String mapped = EN_TO_VI.get(key);
            cb.onSuccess(mapped != null ? mapped : "[VI] " + trimmed);
        } else {
            cb.onError("UNSUPPORTED_TYPE");
        }
    }
}
