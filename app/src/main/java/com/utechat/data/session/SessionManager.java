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
public final class SessionManager {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "utechat_session_key";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_DEVICE = "device_id";
    private static final int IV_LENGTH = 12; // Độ dài IV chuẩn của GCM.
    private static SessionManager instance;

    private final SharedPreferences prefs;
    private volatile String token; // Cache bộ nhớ để mỗi request không phải giải mã lại.
    private volatile boolean tokenLoaded;
    private String deviceId;

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
        prefs.edit().putString(KEY_TOKEN, encrypt(newToken)).apply();
        token = newToken;
        tokenLoaded = true;
    }

    /** Đăng xuất hoặc phiên hết hạn: xoá token (deviceId giữ nguyên để cùng một máy luôn là một thiết bị). */
    public synchronized void clear() {
        prefs.edit().remove(KEY_TOKEN).apply();
        token = null;
        tokenLoaded = true;
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
