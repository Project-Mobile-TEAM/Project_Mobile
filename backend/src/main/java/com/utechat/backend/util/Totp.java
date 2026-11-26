package com.utechat.backend.util;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** TOTP theo RFC 6238 (HMAC-SHA1, 6 số, nhịp 30 giây) và Base32 RFC 4648 cho khoá bí mật. */
public final class Totp {
    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final int STEP_SECONDS = 30;
    private Totp() { }

    /** Khoá 160 bit (khuyến nghị của RFC 4226) dạng Base32 32 ký tự. */
    public static String newSecret() {
        byte[] b = new byte[20];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte x : b) {
            buffer = (buffer << 8) | (x & 0xff);
            bits += 8;
            while (bits >= 5) { sb.append(B32.charAt((buffer >> (bits - 5)) & 31)); bits -= 5; }
        }
        return sb.toString(); // 160 chia hết cho 5 nên không cần đệm.
    }

    private static byte[] decode(String s) {
        ByteBuffer out = ByteBuffer.allocate(s.length() * 5 / 8);
        int buffer = 0, bits = 0;
        for (char c : s.toCharArray()) {
            buffer = (buffer << 5) | B32.indexOf(c);
            bits += 5;
            if (bits >= 8) { out.put((byte) (buffer >> (bits - 8))); bits -= 8; }
        }
        return out.array();
    }

    public static String code(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decode(secret), "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0xf;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static long stepNow() { return System.currentTimeMillis() / 1000 / STEP_SECONDS; }

    /** Trả nhịp khớp trong cửa sổ ±1 quanh nhịp hiện tại, hoặc -1 nếu sai. */
    public static long match(String secret, String code, long nowStep) {
        for (long s = nowStep - 1; s <= nowStep + 1; s++) {
            if (code(secret, s).equals(code)) return s;
        }
        return -1;
    }
}
