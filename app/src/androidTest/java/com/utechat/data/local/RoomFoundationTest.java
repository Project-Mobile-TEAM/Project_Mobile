package com.utechat.data.local;

import android.content.Context;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.utechat.data.local.entity.MessageEntity;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Chạy SQLite/FTS thật trên Android để phát hiện lỗi mà converter JVM không thấy được. */
@RunWith(AndroidJUnit4.class)
public class RoomFoundationTest {
    private AppDatabase db;
    @Before public void open() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase.class).build();
    }
    @After public void close() { db.close(); }

    @Test public void ftsFindsVietnameseAndAckKeepsLocalIdentity() {
        MessageEntity pending = message("client-1", null, "Chào bạn Đặng");
        db.messageDao().insert(pending);
        long local = db.messageDao().findLocalId("client-1");
        assertEquals(1, db.messageDao().search("conversation-1", MessageText.matchQuery("chao ban")).size());
        // ACK cùng clientId phải cập nhật dòng cũ; FTS cũng phải đổi theo text mới.
        db.messageDao().insert(message("client-1", 201L, "Đã nhận tin"));
        assertEquals(local, (long) db.messageDao().findLocalId("client-1"));
        assertEquals(0, db.messageDao().search("conversation-1", MessageText.matchQuery("chao")).size());
        assertEquals(1, db.messageDao().search("conversation-1", MessageText.matchQuery("da nhan")).size());
    }

    @Test public void syncCursorCannotRegressOrMixAccounts() {
        db.syncStateDao().advance("alice", 100, 1000);
        db.syncStateDao().advance("alice", 99, 2000);
        db.syncStateDao().advance("bob", 5, 3000);
        assertEquals(100, db.syncStateDao().get("alice").getLastSyncEventId());
        assertEquals(1000, db.syncStateDao().get("alice").getLastSyncAt());
        assertEquals(5, db.syncStateDao().get("bob").getLastSyncEventId());
    }

    @Test public void localSchemaDoesNotContainServerCredentials() {
        // Kiểm bằng schema SQLite thực tế thay vì chỉ tìm chuỗi trong file Java.
        try (android.database.Cursor cursor = db.getOpenHelper().getReadableDatabase().query("PRAGMA table_info(user_cache)")) {
            while (cursor.moveToNext()) {
                String name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                assertFalse(name.contains("password"));
                assertFalse(name.contains("code_hash"));
            }
        }
        try (android.database.Cursor cursor = db.getOpenHelper().getReadableDatabase()
                .query("SELECT name FROM sqlite_master WHERE name IN ('otp_challenges','auth_sessions','auth_throttles')")) {
            assertEquals(0, cursor.getCount());
        }
    }

    private MessageEntity message(String clientId, Long serverId, String text) {
        MessageEntity message = new MessageEntity();
        message.setClientId(clientId);
        message.setServerId(serverId);
        message.setConversationId("conversation-1");
        message.setSenderId("alice");
        message.setType("TEXT");
        message.setText(text);
        message.setStatus(serverId == null ? "SENDING" : "SENT");
        return message;
    }
}
