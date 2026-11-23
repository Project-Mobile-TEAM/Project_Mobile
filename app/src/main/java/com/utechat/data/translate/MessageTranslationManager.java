package com.utechat.data.translate;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.utechat.data.local.dao.TranslationDao;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.local.entity.MessageTranslationEntity;
import com.utechat.data.session.SessionManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * Điều phối dịch tin nhắn trên thiết bị (D-13, D-14, D-15, T-06-21, T-06-22):
 * - Chỉ cho phép tin nhắn loại TEXT có nội dung không rỗng; từ chối SYSTEM/CALL/media.
 * - Dịch thẳng EN <-> VI (không dùng Language ID).
 * - Ưu tiên đọc cache Room (TranslationDao.findSync) trước để hoạt động 100% offline.
 * - Khi thiếu mô hình (~30MB):
 *   + OFFLINE -> trả lỗi giữ nguyên tin gốc để thử lại.
 *   + CELLULAR_METERED & chưa xác nhận -> trả NEEDS_CELLULAR_CONFIRMATION.
 *   + WIFI_UNMETERED hoặc đã xác nhận mạng di động -> phát trạng thái DOWNLOADING_MODEL không xác định
 *     (tuyệt đối KHÔNG bịa phần trăm tải!), tải mô hình, dịch cục bộ và chỉ lưu Room khi sessionKey còn khớp.
 */
public class MessageTranslationManager {
    public static final String ERR_UNSUPPORTED_TYPE = "UNSUPPORTED_TYPE";
    public static final String ERR_OFFLINE = "OFFLINE";
    public static final String ERR_DOWNLOAD_FAILED = "DOWNLOAD_FAILED";
    public static final String ERR_TRANSLATE_FAILED = "TRANSLATE_FAILED";
    public static final String ERR_UNAUTHENTICATED = "UNAUTHENTICATED";

    /** Trạng thái kết nối mạng dùng cho cổng xác nhận tải mô hình ~30MB. */
    public enum NetworkState {
        OFFLINE,
        WIFI_UNMETERED,
        CELLULAR_METERED
    }

    /** Seam kiểm tra mạng thuần Java (có thể mock trong JVM test). */
    public interface NetworkChecker {
        @NonNull
        NetworkState currentState();
    }

    /**
     * Các trạng thái tiến trình dịch trả về cho UI (D-15):
     * DOWNLOADING_MODEL cố ý KHÔNG chứa trường phần trăm để không hiển thị con số ước đoán sai.
     */
    public enum TranslationStatus {
        NEEDS_CELLULAR_CONFIRMATION,
        DOWNLOADING_MODEL,
        TRANSLATED,
        ERROR
    }

    /** Ảnh chụp kết quả hoặc trạng thái hiện tại của thao tác dịch tin nhắn. */
    public static final class TranslationUpdate {
        @NonNull public final TranslationStatus status;
        @Nullable public final String translatedText;
        @Nullable public final String targetLang;
        @Nullable public final String errorCode;

        private TranslationUpdate(
                @NonNull TranslationStatus status,
                @Nullable String translatedText,
                @Nullable String targetLang,
                @Nullable String errorCode) {
            this.status = status;
            this.translatedText = translatedText;
            this.targetLang = targetLang;
            this.errorCode = errorCode;
        }

        @NonNull
        public static TranslationUpdate needsCellularConfirmation(@NonNull String targetLang) {
            return new TranslationUpdate(TranslationStatus.NEEDS_CELLULAR_CONFIRMATION, null, targetLang, null);
        }

        @NonNull
        public static TranslationUpdate downloadingModel(@NonNull String targetLang) {
            return new TranslationUpdate(TranslationStatus.DOWNLOADING_MODEL, null, targetLang, null);
        }

        @NonNull
        public static TranslationUpdate translated(@NonNull String translatedText, @NonNull String targetLang) {
            return new TranslationUpdate(TranslationStatus.TRANSLATED, translatedText, targetLang, null);
        }

        @NonNull
        public static TranslationUpdate error(@NonNull String errorCode) {
            return new TranslationUpdate(TranslationStatus.ERROR, null, null, errorCode);
        }
    }

    /** Callback nhận cập nhật trên mainExecutor. */
    public interface Callback {
        void onUpdate(@NonNull TranslationUpdate update);
    }

    private final TranslationDao translationDao;
    private final MessageTranslator translator;
    private final NetworkChecker networkChecker;
    private final SessionManager session;
    private final Executor diskExecutor;
    private final Executor mainExecutor;

    /** Constructor chính 100% kiểm thử được trên JVM (tiêm NetworkChecker giả). */
    public MessageTranslationManager(
            @NonNull TranslationDao translationDao,
            @NonNull MessageTranslator translator,
            @NonNull NetworkChecker networkChecker,
            @NonNull SessionManager session,
            @NonNull Executor diskExecutor,
            @NonNull Executor mainExecutor) {
        this.translationDao = translationDao;
        this.translator = translator;
        this.networkChecker = networkChecker;
        this.session = session;
        this.diskExecutor = diskExecutor;
        this.mainExecutor = mainExecutor;
    }

    /** Constructor tiện ích cho Android Injection.java (Plan 06-08) nhận trực tiếp ConnectivityManager. */
    public MessageTranslationManager(
            @NonNull TranslationDao translationDao,
            @NonNull MessageTranslator translator,
            @Nullable ConnectivityManager connectivityManager,
            @NonNull SessionManager session,
            @NonNull Executor diskExecutor,
            @NonNull Executor mainExecutor) {
        this(translationDao, translator, fromConnectivityManager(connectivityManager),
                session, diskExecutor, mainExecutor);
    }

    /** Dựng NetworkChecker từ ConnectivityManager của hệ điều hành Android (xử lý an toàn mọi giá trị null). */
    @NonNull
    public static NetworkChecker fromConnectivityManager(@Nullable ConnectivityManager cm) {
        return () -> {
            if (cm == null) {
                return NetworkState.OFFLINE;
            }
            Network active = cm.getActiveNetwork();
            if (active == null) {
                return NetworkState.OFFLINE;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return NetworkState.OFFLINE;
            }
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                return NetworkState.WIFI_UNMETERED;
            }
            return NetworkState.CELLULAR_METERED;
        };
    }

    /**
     * Dịch một tin nhắn TEXT sang targetLang ("en" hoặc "vi"):
     * - Kiểm tra TEXT-only và cặp ngôn ngữ hợp lệ.
     * - Kiểm tra Room cache trước (hoạt động 100% offline).
     * - Nếu thiếu mô hình: kiểm tra mạng (OFFLINE / cần xác nhận 4G / tự tải trên Wi-Fi) rồi mới dịch và lưu cache.
     */
    public void translateMessage(
            @Nullable MessageEntity message,
            @Nullable String targetLang,
            boolean cellularConfirmed,
            @NonNull Callback callback) {
        // 1. Chỉ cho phép tin nhắn TEXT có nội dung không rỗng (D-14).
        if (message == null
                || !"TEXT".equals(message.getType())
                || message.getClientId() == null
                || message.getClientId().trim().isEmpty()
                || message.getText() == null
                || message.getText().trim().isEmpty()) {
            deliver(callback, TranslationUpdate.error(ERR_UNSUPPORTED_TYPE));
            return;
        }

        // 2. Xác định chiều dịch trực tiếp EN <-> VI (D-13, không dùng Language ID).
        String normalizedTarget = targetLang != null ? targetLang.trim().toLowerCase(Locale.ROOT) : "";
        final String sourceLang;
        if (MessageTranslator.LANG_VI.equals(normalizedTarget)) {
            sourceLang = MessageTranslator.LANG_EN;
        } else if (MessageTranslator.LANG_EN.equals(normalizedTarget)) {
            sourceLang = MessageTranslator.LANG_VI;
        } else {
            deliver(callback, TranslationUpdate.error(ERR_UNSUPPORTED_TYPE));
            return;
        }

        final String sessionKey = session.sessionKey();
        if (sessionKey == null) {
            deliver(callback, TranslationUpdate.error(ERR_UNAUTHENTICATED));
            return;
        }

        final String messageKey = message.getClientId();
        final String sourceText = message.getText();

        // 3. Tra cứu cache cục bộ trong Room trước (chạy trên diskExecutor).
        diskExecutor.execute(() -> {
            if (!sessionKey.equals(session.sessionKey())) {
                return;
            }
            MessageTranslationEntity cached = translationDao.findSync(messageKey, normalizedTarget);
            if (cached != null && cached.getTranslatedText() != null && !cached.getTranslatedText().isEmpty()) {
                deliverIfCurrent(sessionKey, callback,
                        TranslationUpdate.translated(cached.getTranslatedText(), normalizedTarget));
                return;
            }

            // 4. Chưa có cache: kiểm tra hai mô hình ngôn ngữ cục bộ đã tải sẵn chưa.
            translator.isModelDownloaded(sourceLang, sourceReady -> {
                if (!sessionKey.equals(session.sessionKey())) {
                    return;
                }
                translator.isModelDownloaded(normalizedTarget, targetReady -> {
                    if (!sessionKey.equals(session.sessionKey())) {
                        return;
                    }
                    List<String> missingModels = new ArrayList<>(2);
                    if (!sourceReady) {
                        missingModels.add(sourceLang);
                    }
                    if (!targetReady) {
                        missingModels.add(normalizedTarget);
                    }

                    if (missingModels.isEmpty()) {
                        // Mô hình đã sẵn trên thiết bị -> dịch trực tiếp hoàn toàn offline.
                        performTranslateAndCache(sessionKey, messageKey, sourceLang, normalizedTarget,
                                sourceText, callback);
                        return;
                    }

                    // 5. Thiếu mô hình (~30MB): kiểm tra cổng mạng Wi-Fi vs di động (D-15).
                    NetworkState netState = networkChecker.currentState();
                    if (netState == NetworkState.OFFLINE) {
                        deliverIfCurrent(sessionKey, callback, TranslationUpdate.error(ERR_OFFLINE));
                        return;
                    }
                    if (netState == NetworkState.CELLULAR_METERED && !cellularConfirmed) {
                        deliverIfCurrent(sessionKey, callback,
                                TranslationUpdate.needsCellularConfirmation(normalizedTarget));
                        return;
                    }

                    boolean allowCellular = (netState == NetworkState.CELLULAR_METERED) && cellularConfirmed;
                    deliverIfCurrent(sessionKey, callback, TranslationUpdate.downloadingModel(normalizedTarget));
                    downloadMissingModelsSequentially(
                            sessionKey, missingModels, 0, allowCellular,
                            messageKey, sourceLang, normalizedTarget, sourceText, callback);
                });
            });
        });
    }

    private void downloadMissingModelsSequentially(
            @NonNull String sessionKey,
            @NonNull List<String> missingModels,
            int index,
            boolean allowCellular,
            @NonNull String messageKey,
            @NonNull String sourceLang,
            @NonNull String targetLang,
            @NonNull String sourceText,
            @NonNull Callback callback) {
        if (!sessionKey.equals(session.sessionKey())) {
            return;
        }
        if (index >= missingModels.size()) {
            performTranslateAndCache(sessionKey, messageKey, sourceLang, targetLang, sourceText, callback);
            return;
        }
        String langToDownload = missingModels.get(index);
        translator.downloadModel(langToDownload, allowCellular, new MessageTranslator.DownloadCallback() {
            @Override
            public void onSuccess() {
                downloadMissingModelsSequentially(
                        sessionKey, missingModels, index + 1, allowCellular,
                        messageKey, sourceLang, targetLang, sourceText, callback);
            }

            @Override
            public void onError(@NonNull String reason) {
                deliverIfCurrent(sessionKey, callback, TranslationUpdate.error(ERR_DOWNLOAD_FAILED));
            }
        });
    }

    private void performTranslateAndCache(
            @NonNull String sessionKey,
            @NonNull String messageKey,
            @NonNull String sourceLang,
            @NonNull String targetLang,
            @NonNull String sourceText,
            @NonNull Callback callback) {
        if (!sessionKey.equals(session.sessionKey())) {
            return;
        }
        translator.translate(sourceLang, targetLang, sourceText, new MessageTranslator.TranslateCallback() {
            @Override
            public void onSuccess(@NonNull String translatedText) {
                diskExecutor.execute(() -> {
                    // Bảo vệ chống ghi nhầm cache sang tài khoản mới sau khi đăng xuất/đổi phiên (T-06-21).
                    boolean saved = session.runIfCurrent(sessionKey, () -> {
                        MessageTranslationEntity entity = new MessageTranslationEntity();
                        entity.setMessageKey(messageKey);
                        entity.setTargetLang(targetLang);
                        entity.setTranslatedText(translatedText);
                        translationDao.insert(entity);
                    });
                    if (saved) {
                        deliverIfCurrent(sessionKey, callback,
                                TranslationUpdate.translated(translatedText, targetLang));
                    }
                });
            }

            @Override
            public void onError(@NonNull String reason) {
                deliverIfCurrent(sessionKey, callback, TranslationUpdate.error(ERR_TRANSLATE_FAILED));
            }
        });
    }

    private void deliver(@NonNull Callback callback, @NonNull TranslationUpdate update) {
        mainExecutor.execute(() -> callback.onUpdate(update));
    }

    private void deliverIfCurrent(
            @NonNull String sessionKey,
            @NonNull Callback callback,
            @NonNull TranslationUpdate update) {
        mainExecutor.execute(() -> {
            if (sessionKey.equals(session.sessionKey())) {
                callback.onUpdate(update);
            }
        });
    }
}
