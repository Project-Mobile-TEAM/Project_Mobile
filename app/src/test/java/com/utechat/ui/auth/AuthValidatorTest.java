package com.utechat.ui.auth;

import static org.junit.Assert.*;

import org.junit.Test;

/** Kiểm tra các quy tắc nhập liệu auth chạy trên JVM, không cần emulator. */
public class AuthValidatorTest {
    @Test public void toE164_acceptsCommonInputs() {
        assertEquals("+84912345678", AuthValidator.toE164("912 345 678")); // Dạng trong thiết kế.
        assertEquals("+84912345678", AuthValidator.toE164("0912345678")); // Có số 0 đầu.
        assertEquals("+84912345678", AuthValidator.toE164("+84912345678")); // Gõ cả +84.
        assertEquals("+84901111111", AuthValidator.toE164("901-111-111")); // Số seed demo.
    }

    @Test public void toE164_rejectsBadInputs() {
        assertNull(AuthValidator.toE164(""));
        assertNull(AuthValidator.toE164("12345"));
        assertNull(AuthValidator.toE164("91234567890")); // Thừa số.
        assertNull(AuthValidator.toE164("abc"));
    }

    @Test public void displayPhone_groupsDigits() {
        assertEquals("+84 912 345 678", AuthValidator.displayPhone("+84912345678"));
        assertEquals("abc", AuthValidator.displayPhone("abc")); // Dạng lạ giữ nguyên.
    }

    @Test public void password_needsLengthLettersAndDigits() {
        assertTrue(AuthValidator.isPasswordValid("abcd1234"));
        assertFalse(AuthValidator.isPasswordValid("abc123")); // Ngắn.
        assertFalse(AuthValidator.isPasswordValid("abcdefgh")); // Thiếu số.
        assertFalse(AuthValidator.isPasswordValid("12345678")); // Thiếu chữ.
    }

    @Test public void passwordScore_countsCriteria() {
        assertEquals(0, AuthValidator.passwordScore(""));
        assertEquals(1, AuthValidator.passwordScore("abcdefgh")); // Chỉ đủ độ dài.
        assertEquals(2, AuthValidator.passwordScore("abcd1234")); // Độ dài + chữ và số.
        assertEquals(3, AuthValidator.passwordScore("abcd123!")); // Thêm ký tự đặc biệt.
    }

    @Test public void name_lengthBounds() {
        assertFalse(AuthValidator.isNameValid("   "));
        assertTrue(AuthValidator.isNameValid("A"));
        assertTrue(AuthValidator.isNameValid("a".repeat(50)));
        assertFalse(AuthValidator.isNameValid("a".repeat(51)));
    }

    @Test public void initials_firstAndLastWord() {
        assertEquals("HK", AuthValidator.initials("Hoàng Phúc Khang"));
        assertEquals("A", AuthValidator.initials("an"));
        assertEquals("", AuthValidator.initials("  "));
    }
}
