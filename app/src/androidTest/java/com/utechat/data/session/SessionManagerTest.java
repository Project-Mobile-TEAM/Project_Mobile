package com.utechat.data.session;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Kiểm thử trên thiết bị vì Keystore không chạy được trên JVM: token được mã hoá, đọc lại đúng và xoá sạch. */
@RunWith(AndroidJUnit4.class)
public class SessionManagerTest {
    @Test public void tokenRoundTripsEncryptedAndClears() {
        Context context = ApplicationProvider.getApplicationContext();
        SessionManager.init(context);
        SessionManager session = SessionManager.get();
        session.saveToken("header.payload.signature");
        assertEquals("header.payload.signature", session.getToken());
        // Ổ đĩa chỉ chứa bản mã hoá, không có chuỗi token gốc.
        String stored = context.getSharedPreferences("session", Context.MODE_PRIVATE).getString("token", null);
        assertNotNull(stored);
        assertFalse(stored.contains("payload"));
        session.clear();
        assertNull(session.getToken());
        assertNull(context.getSharedPreferences("session", Context.MODE_PRIVATE).getString("token", null));
    }

    @Test public void deviceIdIsGeneratedOnceAndKeptAfterLogout() {
        SessionManager.init(ApplicationProvider.getApplicationContext());
        SessionManager session = SessionManager.get();
        String first = session.getDeviceId();
        assertEquals(36, first.length()); // UUID
        session.clear();
        assertEquals(first, session.getDeviceId());
    }
}
