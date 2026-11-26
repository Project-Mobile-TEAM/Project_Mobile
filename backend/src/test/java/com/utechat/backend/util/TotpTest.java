package com.utechat.backend.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TotpTest {
    // Vector RFC 6238 phụ lục B: khoá "12345678901234567890", T=59 giây là nhịp 1 → 8 số 94287082, 6 số cuối 287082.
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test void matchesRfc6238Vector() {
        assertEquals("287082", Totp.code(RFC_SECRET, 1));
        assertEquals("081804", Totp.code(RFC_SECRET, 37037036)); // T=1111111109 → 07081804.
    }

    @Test void matchAcceptsPlusMinusOneStepOnly() {
        assertEquals(1, Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, 1), 2));
        assertEquals(-1, Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, 1), 3));
    }

    @Test void newSecretIsBase32Of160Bits() {
        String s = Totp.newSecret();
        assertEquals(32, s.length());
        assertTrue(s.matches("[A-Z2-7]{32}"));
        assertNotEquals(s, Totp.newSecret());
    }
}
