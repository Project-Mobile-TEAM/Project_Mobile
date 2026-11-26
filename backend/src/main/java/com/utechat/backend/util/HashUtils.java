package com.utechat.backend.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Băm SHA-256 cho deviceId, khoá throttle và mã OTP: server chỉ lưu bản băm, không lưu giá trị gốc. */
public final class HashUtils {
    private HashUtils() { }

    /** Khoá 32 byte suy ra từ chuỗi bất kỳ (dùng làm khoá AES-256). */
    public static byte[] sha256Bytes(String input) {
        return HexFormat.of().parseHex(sha256Hex(input));
    }

    /** Trả chuỗi hex 64 ký tự chữ thường của SHA-256(input). */
    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 luôn có trong mọi JVM; nhánh này không xảy ra.
            throw new IllegalStateException(e);
        }
    }
}
