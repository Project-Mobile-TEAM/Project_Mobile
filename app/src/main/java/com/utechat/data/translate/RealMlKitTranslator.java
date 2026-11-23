package com.utechat.data.translate;

import androidx.annotation.NonNull;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Triển khai thật MessageTranslator bằng com.google.mlkit:translate:17.0.3 (100% Java 17, D-13):
 * - Chỉ ánh xạ tường minh cặp EN <-> VI (TranslateLanguage.ENGLISH <-> TranslateLanguage.VIETNAMESE),
 *   tuyệt đối không dùng ML Kit Language Identification.
 * - Đóng tài nguyên Translator bằng close() trong cả nhánh thành công lẫn thất bại để tránh rò rỉ native.
 */
public final class RealMlKitTranslator implements MessageTranslator {

    @Override
    public void isModelDownloaded(@NonNull String langCode, @NonNull ModelCheckCallback cb) {
        TranslateRemoteModel model = new TranslateRemoteModel.Builder(toMlKitLanguage(langCode)).build();
        RemoteModelManager.getInstance()
                .isModelDownloaded(model)
                .addOnSuccessListener(downloaded -> cb.onResult(Boolean.TRUE.equals(downloaded)))
                .addOnFailureListener(e -> cb.onResult(false));
    }

    @Override
    public void downloadModel(@NonNull String langCode, boolean allowCellular, @NonNull DownloadCallback cb) {
        TranslateRemoteModel model = new TranslateRemoteModel.Builder(toMlKitLanguage(langCode)).build();
        DownloadConditions.Builder conditionsBuilder = new DownloadConditions.Builder();
        if (!allowCellular) {
            conditionsBuilder.requireWifi();
        }
        RemoteModelManager.getInstance()
                .download(model, conditionsBuilder.build())
                .addOnSuccessListener(unused -> cb.onSuccess())
                .addOnFailureListener(e -> cb.onError(
                        e.getMessage() != null ? e.getMessage() : "DOWNLOAD_FAILED"));
    }

    @Override
    public void translate(
            @NonNull String sourceLang,
            @NonNull String targetLang,
            @NonNull String text,
            @NonNull TranslateCallback cb) {
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(toMlKitLanguage(sourceLang))
                .setTargetLanguage(toMlKitLanguage(targetLang))
                .build();
        Translator translator = Translation.getClient(options);
        AtomicBoolean closed = new AtomicBoolean(false);
        translator.translate(text)
                .addOnSuccessListener(translated -> {
                    closeOnce(translator, closed);
                    cb.onSuccess(translated != null ? translated : "");
                })
                .addOnFailureListener(e -> {
                    closeOnce(translator, closed);
                    cb.onError(e.getMessage() != null ? e.getMessage() : "TRANSLATE_FAILED");
                });
    }

    @NonNull
    private static String toMlKitLanguage(@NonNull String langCode) {
        String normalized = langCode.trim().toLowerCase(Locale.ROOT);
        if (LANG_EN.equals(normalized)) {
            return TranslateLanguage.ENGLISH;
        }
        if (LANG_VI.equals(normalized)) {
            return TranslateLanguage.VIETNAMESE;
        }
        throw new IllegalArgumentException("Ngôn ngữ dịch không được hỗ trợ trong V1: " + langCode);
    }

    private static void closeOnce(@NonNull Translator translator, @NonNull AtomicBoolean closed) {
        if (closed.compareAndSet(false, true)) {
            translator.close();
        }
    }
}
