package com.utechat.data.local;

import android.content.Context;
import androidx.room.Room;
import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.utechat.data.local.entity.ConversationMemberEntity;
import com.utechat.data.local.entity.MessageEntity;
import java.io.IOException;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * Kiểm thử nâng cấp lược đồ Room từ phiên bản 1 lên phiên bản 2 bằng MigrationTestHelper thật:
 * bảo đảm giữ nguyên toàn bộ dữ liệu cũ, các cột mới mặc định null và FTS4 vẫn tìm kiếm hoạt động.
 */
@RunWith(AndroidJUnit4.class)
public class AppDatabaseMigrationTest {
    private static final String TEST_DB = "migration-v1-v2-test.db";

    @Rule
    public MigrationTestHelper helper = new MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase.class
    );

    @Test
    public void migrate1To2PreservesDataDefaultsNewColumnsAndKeepsFts() throws IOException {
        // Bước 1: Tạo cơ sở dữ liệu phiên bản 1 theo 1.json và chèn dữ liệu mẫu v1.
        try (SupportSQLiteDatabase dbV1 = helper.createDatabase(TEST_DB, 1)) {
            dbV1.execSQL("INSERT INTO user_cache (id, phone, displayName, avatarUrl, allowMessagesFromNonContacts) "
                    + "VALUES ('u-alice', '+84901111111', 'Alice Nguyễn', NULL, 1)");
            dbV1.execSQL("INSERT INTO conversation (id, type, title, avatarUrl, lastMessageId, lastMessageAt, unreadCount, muted) "
                    + "VALUES ('conv-1', 'GROUP', 'Nhóm Đồ Án', NULL, 100, 1731300000000, 0, 0)");
            dbV1.execSQL("INSERT INTO member (conversationId, userId, role, lastDeliveredId, lastReadId, left) "
                    + "VALUES ('conv-1', 'u-alice', 'ADMIN', 100, 99, 0)");
            dbV1.execSQL("INSERT INTO message (localId, serverId, clientId, conversationId, senderId, type, text, normalized_text, attachmentJson, createdAt, status) "
                    + "VALUES (1, 100, 'client-v1-1', 'conv-1', 'u-alice', 'TEXT', 'Chào bạn Đặng', 'chao ban dang', NULL, 1731300000000, 'SENT')");

            // Kiểm tra trigger FTS4 trên v1 đã tự động đánh chỉ mục tin nhắn vừa chèn.
            try (android.database.Cursor ftsCursor = dbV1.query(
                    "SELECT docid FROM message_fts WHERE message_fts MATCH '\"chao\"* \"dang\"*'")) {
                assertEquals(1, ftsCursor.getCount());
            }
        }

        // Bước 2: Chạy MIGRATION_1_2 và để MigrationTestHelper xác thực khớp 100% với schema 2.json.
        SupportSQLiteDatabase migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2);
        migrated.close();

        // Bước 3: Mở AppDatabase v2 đầy đủ qua Room để kiểm tra dữ liệu cũ, cột mới mặc định null và FTS4.
        Context context = ApplicationProvider.getApplicationContext();
        AppDatabase dbV2 = Room.databaseBuilder(context, AppDatabase.class, TEST_DB)
                .addMigrations(AppDatabase.MIGRATION_1_2)
                .build();
        try {
            assertNotNull(dbV2.userDao().findById("u-alice"));
            assertEquals("Alice Nguyễn", dbV2.userDao().findById("u-alice").getDisplayName());

            ConversationMemberEntity member = dbV2.conversationDao().getMemberSync("conv-1", "u-alice");
            assertNotNull(member);
            assertEquals("ADMIN", member.getRole());
            assertNull(member.getVisibleFromMessageId());

            MessageEntity migratedMsg = dbV2.messageDao().findByClientId("client-v1-1");
            assertNotNull(migratedMsg);
            assertEquals(Long.valueOf(100L), migratedMsg.getServerId());
            assertNull(migratedMsg.getErrorCode());
            assertNull(migratedMsg.getQueuedVisibleFromMessageId());

            // Tìm kiếm FTS4 không dấu tiếng Việt phải tiếp tục khớp tin nhắn đã có trước khi nâng cấp.
            String safeQuery = MessageText.safeMatchQuery("chao dang");
            List<MessageEntity> hits = dbV2.messageDao().searchInConversationVisible("conv-1", "u-alice", safeQuery);
            assertEquals(1, hits.size());
            assertEquals("client-v1-1", hits.get(0).getClientId());
        } finally {
            dbV2.close();
            context.deleteDatabase(TEST_DB);
        }
    }
}
