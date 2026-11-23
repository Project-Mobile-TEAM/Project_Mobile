package com.utechat.data.translate;

import androidx.annotation.NonNull;

/**
 * Giao diện trừu tượng (seam D-13) tách biệt ML Kit Translate (EN <-> VI) khỏi luồng cache Room & cổng mạng.
 * Tuyệt đối không sử dụng thư viện nhận diện ngôn ngữ (com.google.mlkit:language-id) và không gửi nội dung tin lên server ngoài.
 */
public interface MessageTranslator {
    String LANG_EN = "en";
    String LANG_VI = "vi";

    interface ModelCheckCallback {
        void onResult(boolean downloaded);
    }

    interface DownloadCallback {
        void onSuccess();

        void onError(@NonNull String reason);
    }

    interface TranslateCallback {
        void onSuccess(@NonNull String translatedText);

        void onError(@NonNull String reason);
    }

    /** Kiểm tra mô hình ngôn ngữ cục bộ ("en" hoặc "vi") đã tải xuống máy chưa. */
    void isModelDownloaded(@NonNull String langCode, @NonNull ModelCheckCallback cb);

    /** Tải mô hình ngôn ngữ (~30MB) về thiết bị; allowCellular=false ép điều kiện chỉ tải qua Wi-Fi. */
    void downloadModel(@NonNull String langCode, boolean allowCellular, @NonNull DownloadCallback cb);

    /** Dịch trực tiếp trên thiết bị giữa EN và VI sau khi mô hình đã sẵn sàng. */
    void translate(
            @NonNull String sourceLang,
            @NonNull String targetLang,
            @NonNull String text,
            @NonNull TranslateCallback cb);
}
