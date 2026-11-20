package com.utechat.ui.contacts;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ContactsTextTest {
    @Test public void initialsUseFirstAndLastWord() {
        assertEquals("GN", ContactsText.initials("Gia Nam"));
        assertEquals("LT", ContactsText.initials("  Lý  Thành Tín "));
        assertEquals("Đ", ContactsText.initials("đạt"));
        assertEquals("?", ContactsText.initials("   "));
        assertEquals("?", ContactsText.initials(null));
    }

    @Test public void formatPhoneGroupsVietnameseNumbers() {
        assertEquals("+84 901 234 567", ContactsText.formatPhone("+84901234567"));
        assertEquals("+14155551234", ContactsText.formatPhone("+14155551234"));
        assertEquals("", ContactsText.formatPhone(null));
    }

    @Test public void maskPhoneHidesMiddleDigits() {
        assertEquals("+84 93 *** 7710", ContactsText.maskPhone("+84930007710"));
        assertEquals("+14 *** 1234", ContactsText.maskPhone("+14155551234"));
        assertEquals("***", ContactsText.maskPhone("+84123"));
        assertEquals("", ContactsText.maskPhone(" "));
    }

    @Test public void storedPhoneNormalizesTypedDigits() {
        assertEquals("+84901234567", ContactsText.storedPhone("901 234 567"));
        assertEquals("+84901234567", ContactsText.storedPhone("0901 234 567"));
        assertEquals("+84901234567", ContactsText.storedPhone("84901234567"));
        assertEquals("", ContactsText.storedPhone(" "));
        assertEquals("", ContactsText.storedPhone(null));
    }

    @Test public void typedPhoneRoundTripsStoredValue() {
        assertEquals("901234567", ContactsText.typedPhone("+84901234567"));
        assertEquals("", ContactsText.typedPhone(""));
    }
}
