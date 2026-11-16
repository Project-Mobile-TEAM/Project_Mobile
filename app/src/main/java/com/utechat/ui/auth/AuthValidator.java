package com.utechat.ui.auth;

import androidx.annotation.Nullable;

/** Kiểm tra dữ liệu nhập ở phía app (server vẫn kiểm lại); thuần Java nên test được trên JVM. */
public final class AuthValidator {
    private AuthValidator() { }

    /** Đổi số người dùng gõ (sau tiền tố +84) thành E.164; trả null nếu sai định dạng di động Việt Nam. */
    @Nullable public static String toE164(String input) {
        String digits = input.replaceAll("\\D", ""); // Bỏ khoảng trắng, dấu chấm, gạch, dấu +.
        if (digits.length() == 11 && digits.startsWith("84")) digits = digits.substring(2); // Người dùng gõ cả 84.
        if (digits.startsWith("0")) digits = digits.substring(1); // Bỏ số 0 đầu dạng 09xx.
        if (digits.length() != 9 || digits.charAt(0) == '0') return null; // Di động VN còn đúng 9 số.
        return "+84" + digits;
    }

    /** Hiển thị E.164 dễ đọc: +84 912 345 678. Dạng lạ thì giữ nguyên. */
    public static String displayPhone(String e164) {
        if (e164 == null || !e164.matches("\\+84\\d{9}")) return e164 == null ? "" : e164;
        return "+84 " + e164.substring(3, 6) + " " + e164.substring(6, 9) + " " + e164.substring(9);
    }

    /** Mật khẩu ≥ 8 ký tự, có cả chữ và số (đúng gợi ý trong thiết kế). */
    public static boolean isPasswordValid(String password) {
        return password.length() >= 8 && password.matches(".*[A-Za-z].*") && password.matches(".*\\d.*");
    }

    /** Số tiêu chí mật khẩu đạt (0 đến 3): đủ 8 ký tự, có cả chữ và số, có ký tự đặc biệt. Dùng cho thanh độ mạnh. */
    public static int passwordScore(String password) {
        int score = 0;
        if (password.length() >= 8) score++;
        if (password.matches(".*[A-Za-z].*") && password.matches(".*\\d.*")) score++;
        if (password.matches(".*[^A-Za-z0-9].*")) score++;
        return score;
    }

    /** Tên hiển thị 1 đến 50 ký tự (ADR/F-01). */
    public static boolean isNameValid(String name) {
        int length = name.trim().length();
        return length >= 1 && length <= 50;
    }

    /** Chữ cái đầu của từ đầu và từ cuối, ví dụ "Hoàng Phúc Khang" thành "HK"; tên rỗng trả "". */
    public static String initials(String name) {
        String[] words = name.trim().split("\\s+");
        if (words[0].isEmpty()) return "";
        String first = words[0].substring(0, 1);
        String last = words.length > 1 ? words[words.length - 1].substring(0, 1) : "";
        return (first + last).toUpperCase();
    }
}
