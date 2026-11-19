package com.utechat.data.session;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import androidx.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.UUID;
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
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "utechat_session_key";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_DEVICE = "device_id";
    private static final String KEY_ME = "me_id";
    private static final String KEY_DISCOVERABLE = "me_discoverable";
    private static final int IV_LENGTH = 12; // Độ dài IV chuẩn của GCM.
    private static SessionManager instance;

    private final SharedPreferences prefs;
    private volatile String token; // Cache bộ nhớ để mỗi request không phải giải mã lại.
    private volatile boolean tokenLoaded;
    private String deviceId;
    // Tăng khi đổi phiên để callback cũ không ghi vào cache của lần đăng nhập mới.
    private long generation;
    // Injection gắn việc dọn Room và bộ nhớ; callback chạy trong khóa phiên để xếp DISK đúng thứ tự.
    private Runnable onSessionChanged = () -> { };

    public synchronized void setOnSessionChanged(Runnable action) {
        onSessionChanged = java.util.Objects.requireNonNull(action);
        if (!hasToken()) onSessionChanged.run();
    }

    /** Khóa không chứa JWT; đổi cả khi cùng tài khoản đăng nhập lại với cùng token. */
    @Nullable public synchronized String contactsSessionKey() {
        String owner = getCurrentUserId();
        return owner == null ? null : generation + ":" + owner;
    }
    @Override public synchronized String sessionKey() { return contactsSessionKey(); }
    @Override public synchronized String getCurrentUserId() { return hasToken() ? getMeId() : null; }
    // Version dùng cả khi chưa đăng nhập để loại callback login/OTP cũ sau logout.
    public synchronized String versionKey() { return Long.toString(generation); }
    @Override public synchronized boolean runIfCurrent(String key, Runnable action) {
        if (key == null || !key.equals(sessionKey())) return false;
        action.run();
        return true;
    }

    private SessionManager(Context context) {
        prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE);
    }

    /** Gọi một lần từ Application. */
    public static synchronized void init(Context context) {
        if (instance == null) instance = new SessionManager(context.getApplicationContext());
    }

    public static SessionManager get() { return instance; }

    /** deviceId ngẫu nhiên do app tạo lần đầu (ADR-003); server chỉ lưu bản băm. */
    public synchronized String getDeviceId() {
        if (deviceId == null) deviceId = decrypt(prefs.getString(KEY_DEVICE, null));
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE, encrypt(deviceId)).apply();
        }
        return deviceId;
    }

    @Nullable public String getToken() {
        if (!tokenLoaded) {
            synchronized (this) {
                if (!tokenLoaded) {
                    token = decrypt(prefs.getString(KEY_TOKEN, null));
                    tokenLoaded = true;
                }
            }
        }
        return token;
    }

    public boolean hasToken() { return getToken() != null; }

    public synchronized void saveToken(String newToken) {
        String encrypted = encrypt(java.util.Objects.requireNonNull(newToken));
        generation++;
        prefs.edit().putString(KEY_TOKEN, encrypted).remove(KEY_ME).remove(KEY_DISCOVERABLE).apply();
        token = newToken;
        tokenLoaded = true;
        onSessionChanged.run();
    }

    /** Lưu token và actor nguyên tử; dọn cache cũ được xếp trước ghi hồ sơ của phiên mới. */
    public synchronized void saveAuthenticated(String newToken, String userId, boolean discoverable) {
        java.util.Objects.requireNonNull(userId);
        String encrypted = encrypt(java.util.Objects.requireNonNull(newToken));
        generation++;
        prefs.edit().putString(KEY_TOKEN, encrypted).putString(KEY_ME, userId)
                .putBoolean(KEY_DISCOVERABLE, discoverable).apply();
        token = newToken;
        tokenLoaded = true;
        onSessionChanged.run();
    }

    /** Nhớ id của mình (khoá đọc hồ sơ cache trong Room) và cờ discoverable mà bảng user_cache không có. */
    public synchronized void saveMe(String userId, boolean discoverable) {
        boolean changed = !java.util.Objects.equals(userId, getMeId());
        prefs.edit().putString(KEY_ME, userId).putBoolean(KEY_DISCOVERABLE, discoverable).apply();
        if (changed) { generation++; onSessionChanged.run(); }
    }
    @Nullable public String getMeId() { return prefs.getString(KEY_ME, null); }
    public boolean isMeDiscoverable() { return prefs.getBoolean(KEY_DISCOVERABLE, true); }

    /** Chỉ xoá khi token đang lưu vẫn là token bị 401; phiên mới đăng nhập sau đó không bị xoá nhầm. */
    public synchronized boolean clearIfCurrent(String expired) {
        if (!expired.equals(getToken())) return false;
        clear();
        return true;
    }

    /** 401 trễ không được xóa lần đăng nhập mới dù server cấp lại cùng chuỗi JWT. */
    public synchronized boolean clearIfCurrent(String expired, String version) {
        return versionKey().equals(version) && clearIfCurrent(expired);
    }

    /** Đăng xuất hoặc phiên hết hạn: xoá token (deviceId giữ nguyên để cùng một máy luôn là một thiết bị). */
    public synchronized void clear() {
        generation++;
        prefs.edit().remove(KEY_TOKEN).remove(KEY_ME).remove(KEY_DISCOVERABLE).apply();
        token = null;
        tokenLoaded = true;
        onSessionChanged.run();
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

    private static String encrypt(String plain) {
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
    @Nullable private static String decrypt(@Nullable String stored) {
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
