package com.utechat.ui.contacts;

import java.util.Locale;

/** Định dạng chữ hiển thị của danh bạ (thuần Java để test được trên JVM). */
final class ContactsText {
    private ContactsText() { }

    /** Chữ cái đầu của từ đầu và từ cuối ("Gia Nam" thành "GN"); tên rỗng trả "?". */
    static String initials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] words = name.trim().split("\\s+");
        String out = first(words[0]);
        return words.length > 1 ? out + first(words[words.length - 1]) : out;
    }

    private static String first(String word) {
        return word.substring(0, word.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
    }

    /** "+84901234567" thành "+84 901 234 567"; số quốc tế khác giữ nguyên. */
    static String formatPhone(String e164) {
        if (e164 == null) return "";
        if (!e164.startsWith("+84")) return e164;
        String rest = e164.substring(3);
        StringBuilder out = new StringBuilder("+84");
        for (int i = 0; i < rest.length(); i++) {
            if (i % 3 == 0) out.append(' ');
            out.append(rest.charAt(i));
        }
        return out.toString();
    }

    /** Che số của người bị chặn: "+84930007710" thành "+84 93 *** 7710"; quá ngắn thì che hết. */
    static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) return "";
        boolean vn = phone.startsWith("+84");
        String rest = vn ? phone.substring(3) : phone;
        int head = vn ? 2 : 3;
        if (rest.length() < head + 4 + 1) return "***";
        return (vn ? "+84 " : "") + rest.substring(0, head) + " *** " + rest.substring(rest.length() - 4);
    }

    /**
     * Chuỗi người dùng nhập sau tiền tố "+84" thành số E.164 để gửi ViewModel; rỗng nếu chưa có chữ số.
     * Bỏ số 0 đầu; số dán kèm mã nước ("84901234567", 11 chữ số) không bị thêm +84 lần hai.
     * shortcut: ô nhập chỉ nhận số Việt Nam theo Figma, số quốc tế khác phải mở rộng ô khi cần.
     */
    static String storedPhone(String typed) {
        String digits = typed == null ? "" : typed.replaceAll("\\D", "");
        if (digits.startsWith("84") && digits.length() == 11) return "+" + digits;
        if (digits.startsWith("0")) digits = digits.substring(1);
        return digits.isEmpty() ? "" : "+84" + digits;
    }

    /** Ngược lại của {@link #storedPhone}: phần hiển thị sau tiền tố "+84". */
    static String typedPhone(String stored) {
        return stored != null && stored.startsWith("+84") ? stored.substring(3) : "";
    }
}
