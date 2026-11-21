package com.utechat.data.session;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.UUID;
import java.util.concurrent.Executor;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Giữ JWT và deviceId (ADR-003, ADR-018; slide Session 6). Khoá AES-256-GCM nằm trong Android Keystore và không xuất
 * ra được; SharedPreferences chỉ chứa Base64(IV + ciphertext). Token được giữ trong bộ nhớ sau lần giải mã đầu.
 * Không bao giờ ghi token vào log, Room hay URL.
 */
public final class SessionManager implements com.utechat.data.repository.CurrentUserIdProvider {
    /** Chiến lược mã hóa dữ liệu phiên, cho phép thay thế bằng bộ giả lập trong bài kiểm thử JVM. */
    interface TokenCipher {
        String encrypt(String plain);
        @Nullable String decrypt(@Nullable String stored);
    }

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "utechat_session_key";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_DEVICE = "device_id";
    private static final String KEY_ME = "me_id";
    private static final String KEY_DISCOVERABLE = "me_discoverable";
    private static final String KEY_GENERATION = "session_generation";
    private static final int IV_LENGTH = 12; // Độ dài IV chuẩn của GCM.
    private static final TokenCipher KEYSTORE_CIPHER = new TokenCipher() {
        @Override public String encrypt(String plain) { return encryptWithKeystore(plain); }
        @Override @Nullable public String decrypt(@Nullable String stored) { return decryptWithKeystore(stored); }
    };
    private static SessionManager instance;

    private final SharedPreferences prefs;
    private final TokenCipher cipher;
    private volatile Executor mainExecutor;
    private volatile String token; // Cache bộ nhớ để mỗi request không phải giải mã lại.
    private volatile boolean tokenLoaded;
    private String deviceId;
    // Lưu bền vững qua SharedPreferences để sessionKey sống qua process restart nhưng đổi khi login/clear mới.
    private long generation;
    // Injection gắn việc dọn Room và bộ nhớ; callback chạy trong khóa phiên để xếp DISK đúng thứ tự.
    private Runnable onSessionChanged = () -> { };
    private final androidx.lifecycle.MutableLiveData<String> changes = new androidx.lifecycle.MutableLiveData<>();
    @Override public androidx.lifecycle.LiveData<String> sessionChanges() { return changes; }

    /** Xếp thông báo trên main và bỏ thông báo phiên cũ nếu main đang bận; tuyệt đối không dùng ArchTaskExecutor. */
    private void announceSessionChange() {
        onSessionChanged.run();
        String version = versionKey();
        Executor exec = mainExecutor;
        if (exec != null) {
            exec.execute(() -> {
                synchronized (this) {
                    if (version.equals(versionKey())) changes.setValue(sessionKey());
                }
            });
        } else {
            // postValue an toàn trên cả Android main thread và InstantTaskExecutorRule của JVM test.
            changes.postValue(sessionKey());
        }
    }

    /** Cho phép bài kiểm thử JVM tiêm Runnable::run làm executor luồng chính mà không chạm Android Looper. */
    @VisibleForTesting
    public synchronized void setMainExecutorForTest(@Nullable Executor executor) {
        this.mainExecutor = executor;
    }

    public synchronized void setOnSessionChanged(Runnable action) {
        onSessionChanged = java.util.Objects.requireNonNull(action);
        if (!hasToken()) onSessionChanged.run();
    }

    /** Khóa không chứa JWT; đổi cả khi cùng tài khoản đăng nhập lại với cùng token và giữ nguyên qua restart tiến trình. */
    @Nullable public synchronized String contactsSessionKey() {
        String owner = getCurrentUserId();
        return owner == null ? null : generation + ":" + owner;
    }
    @Override public synchronized String sessionKey() { return contactsSessionKey(); }
    @Override public synchronized String getCurrentUserId() { return hasToken() ? getUserId() : null; }

    /**
     * Trả về UUID người dùng hiện tại: ưu tiên đọc me_id đã lưu, nếu chưa có thì giải mã claim "sub" từ JWT payload.
     */
    @Nullable public synchronized String getUserId() {
        String saved = getMeId();
        if (saved != null && !saved.isEmpty()) return saved;
        String currentToken = getToken();
        if (currentToken == null) return null;
        return decodeJwtSubject(currentToken);
    }

    // Version dùng cả khi chưa đăng nhập để loại callback login/OTP cũ sau logout.
    public synchronized String versionKey() { return Long.toString(generation); }
    @Override public synchronized boolean runIfCurrent(String key, Runnable action) {
        if (key == null || !key.equals(sessionKey())) return false;
        action.run();
        return true;
    }

    private SessionManager(Context context) {
        this(context.getSharedPreferences("session", Context.MODE_PRIVATE),
                KEYSTORE_CIPHER,
                com.utechat.data.MainThreadExecutor.INSTANCE);
    }

    private SessionManager(SharedPreferences prefs, TokenCipher cipher, @Nullable Executor mainExecutor) {
        this.prefs = prefs;
        this.cipher = cipher;
        this.mainExecutor = mainExecutor;
        this.generation = prefs.getLong(KEY_GENERATION, 0L);
    }

    /** Tạo SessionManager cho kiểm thử JVM trên SharedPreferences giả lập (không cần Android Keystore/Looper). */
    @VisibleForTesting
    public static SessionManager createForTest(SharedPreferences prefs, @Nullable Executor mainExecutor) {
        TokenCipher plainCipher = new TokenCipher() {
            @Override public String encrypt(String plain) { return plain; }
            @Override @Nullable public String decrypt(@Nullable String stored) { return stored; }
        };
        return new SessionManager(prefs, plainCipher, mainExecutor);
    }

    /** Gọi một lần từ Application. */
    public static synchronized void init(Context context) {
        if (instance == null) instance = new SessionManager(context.getApplicationContext());
    }

    public static SessionManager get() { return instance; }

    /** deviceId ngẫu nhiên do app tạo lần đầu (ADR-003); server chỉ lưu bản băm. */
    public synchronized String getDeviceId() {
        if (deviceId == null) deviceId = cipher.decrypt(prefs.getString(KEY_DEVICE, null));
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE, cipher.encrypt(deviceId)).apply();
        }
        return deviceId;
    }

    @Nullable public String getToken() {
        if (!tokenLoaded) {
            synchronized (this) {
                if (!tokenLoaded) {
                    token = cipher.decrypt(prefs.getString(KEY_TOKEN, null));
                    tokenLoaded = true;
                }
            }
        }
        return token;
    }

    public boolean hasToken() { return getToken() != null; }

    public synchronized void saveToken(String newToken) {
        String encrypted = cipher.encrypt(java.util.Objects.requireNonNull(newToken));
        generation++;
        prefs.edit().putString(KEY_TOKEN, encrypted)
                .putLong(KEY_GENERATION, generation)
                .remove(KEY_ME).remove(KEY_DISCOVERABLE).apply();
        token = newToken;
        tokenLoaded = true;
        announceSessionChange();
    }

    /** Lưu token và actor nguyên tử; dọn cache cũ được xếp trước ghi hồ sơ của phiên mới. */
    public synchronized void saveAuthenticated(String newToken, String userId, boolean discoverable) {
        java.util.Objects.requireNonNull(userId);
        String encrypted = cipher.encrypt(java.util.Objects.requireNonNull(newToken));
        generation++;
        prefs.edit().putString(KEY_TOKEN, encrypted)
                .putString(KEY_ME, userId)
                .putBoolean(KEY_DISCOVERABLE, discoverable)
                .putLong(KEY_GENERATION, generation).apply();
        token = newToken;
        tokenLoaded = true;
        announceSessionChange();
    }

    /** Nhớ id của mình (khoá đọc hồ sơ cache trong Room) và cờ discoverable mà bảng user_cache không có. */
    public synchronized void saveMe(String userId, boolean discoverable) {
        boolean changed = !java.util.Objects.equals(userId, getMeId());
        SharedPreferences.Editor editor = prefs.edit()
                .putString(KEY_ME, userId)
                .putBoolean(KEY_DISCOVERABLE, discoverable);
        if (changed) {
            generation++;
            editor.putLong(KEY_GENERATION, generation);
        }
        editor.apply();
        if (changed) announceSessionChange();
    }
    @Nullable public String getMeId() { return prefs.getString(KEY_ME, null); }
    public boolean isMeDiscoverable() { return prefs.getBoolean(KEY_DISCOVERABLE, true); }

    /** Chỉ xoá khi token đang lưu vẫn là token bị 401; phiên mới đăng nhập sau đó không bị xoá nhầm. */
    public synchronized boolean clearIfCurrent(String expired) {
        if (expired == null || !expired.equals(getToken())) return false;
        clear();
        return true;
    }

    /** 401 trễ không được xóa lần đăng nhập mới dù server cấp lại cùng chuỗi JWT. */
    public synchronized boolean clearIfCurrent(String expired, String version) {
        return version != null && versionKey().equals(version) && clearIfCurrent(expired);
    }

    /** Đăng xuất hoặc phiên hết hạn: xoá token (deviceId giữ nguyên để cùng một máy luôn là một thiết bị). */
    public synchronized void clear() {
        generation++;
        prefs.edit().remove(KEY_TOKEN).remove(KEY_ME).remove(KEY_DISCOVERABLE)
                .putLong(KEY_GENERATION, generation).apply();
        token = null;
        tokenLoaded = true;
        announceSessionChange();
    }

    /** Giải mã trường "sub" trong phần payload JWT bằng Base64URL chuẩn Java 17 (không cần Android Base64). */
    @Nullable
    private static String decodeJwtSubject(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;
            byte[] decoded = java.util.Base64.getUrlDecoder().decode(parts[1]);
            JsonObject obj = JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8)).getAsJsonObject();
            return obj.has("sub") && !obj.get("sub").isJsonNull() ? obj.get("sub").getAsString() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (store.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }

    private static String encryptWithKeystore(String plain) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] data = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + data.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(data, 0, out, iv.length, data.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            // Không lưu bản rõ làm phương án dự phòng: thà mất phiên còn hơn lộ token.
            throw new IllegalStateException("Không mã hoá được dữ liệu phiên", e);
        }
    }

    /** Trả null nếu chưa có, bị hỏng hoặc khoá đã mất (khi đó coi như chưa đăng nhập). */
    @Nullable private static String decryptWithKeystore(@Nullable String stored) {
        if (stored == null) return null;
        try {
            byte[] all = Base64.decode(stored, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 0, IV_LENGTH));
            return new String(cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
