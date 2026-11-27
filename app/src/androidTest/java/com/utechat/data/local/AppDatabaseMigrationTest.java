package com.utechat.data.local;

import android.content.Context;
import android.database.Cursor;
import androidx.room.Room;
import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.utechat.data.local.entity.AttachmentTransferEntity;
import com.utechat.data.local.entity.ConversationMemberEntity;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.local.entity.MessageTranslationEntity;
import java.io.IOException;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * Kiểm thử nâng cấp lược đồ Room bằng MigrationTestHelper thật (ADR-020):
 * - v1 -> v2 (MIGRATION_1_2) khớp schema 2.json
 * - v2 -> v3 (MIGRATION_2_3) bổ sung 11 cột attachment_transfer, giữ nguyên dữ liệu cũ, message_translation và FTS4
 * - v1 -> v2 -> v3 -> v4 và v3 -> v4 giữ nguyên dữ liệu cũ, thêm cache cảm xúc và nhãn dán.
 */
@RunWith(AndroidJUnit4.class)
public class AppDatabaseMigrationTest {
    private static final String TEST_DB_V1_V2 = "migration-v1-v2-test.db";
    private static final String TEST_DB_V2_V3 = "migration-v2-v3-test.db";
    private static final String TEST_DB_V1_V3 = "migration-v1-v3-test.db";
    private static final String TEST_DB_V3_V4 = "migration-v3-v4-test.db";
    private static final String TEST_DB_V1_V4 = "migration-v1-v4-test.db";

    @Rule
    public MigrationTestHelper helper = new MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase.class
    );

    @Test
    public void migrate1To2PreservesDataDefaultsNewColumnsAndKeepsFts() throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(TEST_DB_V1_V2);
        try {
            // Bước 1: Tạo cơ sở dữ liệu phiên bản 1 theo 1.json và chèn dữ liệu mẫu v1.
            try (SupportSQLiteDatabase dbV1 = helper.createDatabase(TEST_DB_V1_V2, 1)) {
                dbV1.execSQL("INSERT INTO user_cache (id, phone, displayName, avatarUrl, allowMessagesFromNonContacts) "
                        + "VALUES ('u-alice', '+84901111111', 'Alice Nguyễn', NULL, 1)");
                dbV1.execSQL("INSERT INTO conversation (id, type, title, avatarUrl, lastMessageId, lastMessageAt, unreadCount, muted) "
                        + "VALUES ('conv-1', 'GROUP', 'Nhóm Đồ Án', NULL, 100, 1731300000000, 0, 0)");
                dbV1.execSQL("INSERT INTO member (conversationId, userId, role, lastDeliveredId, lastReadId, left) "
                        + "VALUES ('conv-1', 'u-alice', 'ADMIN', 100, 99, 0)");
                dbV1.execSQL("INSERT INTO message (localId, serverId, clientId, conversationId, senderId, type, text, normalized_text, attachmentJson, createdAt, status) "
                        + "VALUES (1, 100, 'client-v1-1', 'conv-1', 'u-alice', 'TEXT', 'Chào bạn Đặng', 'chao ban dang', NULL, 1731300000000, 'SENT')");

                try (Cursor ftsCursor = dbV1.query(
                        "SELECT docid FROM message_fts WHERE message_fts MATCH '\"chao\"* \"dang\"*'")) {
                    assertEquals(1, ftsCursor.getCount());
                }
            }

            // Bước 2: Chạy MIGRATION_1_2 và xác thực khớp 100% với schema 2.json cùng dữ liệu bảo toàn.
            try (SupportSQLiteDatabase migrated = helper.runMigrationsAndValidate(
                    TEST_DB_V1_V2, 2, true, AppDatabase.MIGRATION_1_2)) {
                try (Cursor memberCursor = migrated.query(
                        "SELECT role, visibleFromMessageId FROM member WHERE conversationId = 'conv-1' AND userId = 'u-alice'")) {
                    assertTrue(memberCursor.moveToFirst());
                    assertEquals("ADMIN", memberCursor.getString(0));
                    assertTrue(memberCursor.isNull(1));
                }
                try (Cursor msgCursor = migrated.query(
                        "SELECT serverId, errorCode, queuedVisibleFromMessageId FROM message WHERE clientId = 'client-v1-1'")) {
                    assertTrue(msgCursor.moveToFirst());
                    assertEquals(100L, msgCursor.getLong(0));
                    assertTrue(msgCursor.isNull(1));
                    assertTrue(msgCursor.isNull(2));
                }
                try (Cursor ftsCursor = migrated.query(
                        "SELECT docid FROM message_fts WHERE message_fts MATCH '\"chao\"* \"dang\"*'")) {
                    assertEquals(1, ftsCursor.getCount());
                }
            }
        } finally {
            context.deleteDatabase(TEST_DB_V1_V2);
        }
    }

    @Test
    public void migrate2To3PreservesDataAndAddsTransferColumns() throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(TEST_DB_V2_V3);
        try {
            // Bước 1: Tạo cơ sở dữ liệu phiên bản 2 theo 2.json và chèn dữ liệu mẫu v2.
            try (SupportSQLiteDatabase dbV2 = helper.createDatabase(TEST_DB_V2_V3, 2)) {
                dbV2.execSQL("INSERT INTO user_cache (id, phone, displayName, avatarUrl, allowMessagesFromNonContacts) "
                        + "VALUES ('u-bob', '+84902222222', 'Trần Bình', NULL, 1)");
                dbV2.execSQL("INSERT INTO conversation (id, type, title, avatarUrl, lastMessageId, lastMessageAt, unreadCount, muted) "
                        + "VALUES ('conv-2', 'DIRECT', NULL, NULL, 200, 1731400000000, 0, 0)");
                dbV2.execSQL("INSERT INTO member (conversationId, userId, role, lastDeliveredId, lastReadId, left, visibleFromMessageId) "
                        + "VALUES ('conv-2', 'u-bob', 'MEMBER', 200, 200, 0, 0)");
                dbV2.execSQL("INSERT INTO message (localId, serverId, clientId, conversationId, senderId, type, text, normalized_text, attachmentJson, createdAt, status, errorCode, queuedVisibleFromMessageId) "
                        + "VALUES (1, 200, 'client-v2-1', 'conv-2', 'u-bob', 'TEXT', 'Hẹn gặp tại thư viện', 'hen gap tai thu vien', NULL, 1731400000000, 'SENT', NULL, NULL)");
                dbV2.execSQL("INSERT INTO attachment_transfer (clientId, localUri, state, progress, mediaUrl) "
                        + "VALUES ('tx-v2-1', 'content://media/external/images/42', 'PENDING', 25, NULL)");
                dbV2.execSQL("INSERT INTO message_translation (messageKey, targetLang, translatedText) "
                        + "VALUES ('client-v2-1', 'en', 'See you at the library')");
            }

            // Bước 2: Chạy MIGRATION_2_3 và xác thực khớp 100% với schema 3.json.
            SupportSQLiteDatabase migrated = helper.runMigrationsAndValidate(
                    TEST_DB_V2_V3, 3, true, AppDatabase.MIGRATION_2_3);
            migrated.close();

            // Bước 3: Mở bản app hiện tại sau khi đã xác thực riêng migration lên v3.
            AppDatabase dbV3 = Room.databaseBuilder(context, AppDatabase.class, TEST_DB_V2_V3)
                    .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
                    .build();
            try {
                AttachmentTransferEntity transfer = dbV3.transferDao().findByClientIdSync("tx-v2-1");
                assertNotNull(transfer);
                assertEquals("content://media/external/images/42", transfer.getLocalUri());
                assertEquals("PENDING", transfer.getState());
                assertEquals(25, transfer.getProgress());
                assertNull(transfer.getMediaUrl());
                assertEquals("UPLOAD", transfer.getDirection());
                assertEquals(0L, transfer.getUpdatedAt());
                assertNull(transfer.getLocalPath());
                assertNull(transfer.getKind());
                assertNull(transfer.getMime());
                assertNull(transfer.getSizeBytes());
                assertNull(transfer.getFileName());
                assertNull(transfer.getDurationMs());
                assertNull(transfer.getWidth());
                assertNull(transfer.getHeight());
                assertNull(transfer.getErrorCode());

                MessageTranslationEntity translation = dbV3.translationDao().findSync("client-v2-1", "en");
                assertNotNull(translation);
                assertEquals("See you at the library", translation.getTranslatedText());

                MessageEntity migratedMsg = dbV3.messageDao().findByClientId("client-v2-1");
                assertNotNull(migratedMsg);
                assertEquals(Long.valueOf(200L), migratedMsg.getServerId());

                // FTS4 tiếng Việt không dấu phải hoạt động bình thường sau khi nâng cấp lên v3.
                List<MessageEntity> hits = dbV3.messageDao().searchInConversationVisible(
                        "conv-2", "u-bob", MessageText.safeMatchQuery("thu vien"));
                assertEquals(1, hits.size());
                assertEquals("client-v2-1", hits.get(0).getClientId());
            } finally {
                dbV3.close();
            }
        } finally {
            context.deleteDatabase(TEST_DB_V2_V3);
        }
    }

    @Test
    public void migrate1To3EndToEnd() throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(TEST_DB_V1_V3);
        try {
            // Bước 1: Tạo cơ sở dữ liệu từ phiên bản 1 theo 1.json.
            try (SupportSQLiteDatabase dbV1 = helper.createDatabase(TEST_DB_V1_V3, 1)) {
                dbV1.execSQL("INSERT INTO user_cache (id, phone, displayName, avatarUrl, allowMessagesFromNonContacts) "
                        + "VALUES ('u-alice', '+84901111111', 'Alice Nguyễn', NULL, 1)");
                dbV1.execSQL("INSERT INTO conversation (id, type, title, avatarUrl, lastMessageId, lastMessageAt, unreadCount, muted) "
                        + "VALUES ('conv-1', 'GROUP', 'Nhóm Đồ Án', NULL, 100, 1731300000000, 0, 0)");
                dbV1.execSQL("INSERT INTO member (conversationId, userId, role, lastDeliveredId, lastReadId, left) "
                        + "VALUES ('conv-1', 'u-alice', 'ADMIN', 100, 99, 0)");
                dbV1.execSQL("INSERT INTO message (localId, serverId, clientId, conversationId, senderId, type, text, normalized_text, attachmentJson, createdAt, status) "
                        + "VALUES (1, 100, 'client-v1-1', 'conv-1', 'u-alice', 'TEXT', 'Chào bạn Đặng', 'chao ban dang', NULL, 1731300000000, 'SENT')");
                dbV1.execSQL("INSERT INTO attachment_transfer (clientId, localUri, state, progress, mediaUrl) "
                        + "VALUES ('tx-v1-1', 'content://legacy/1', 'UPLOADED', 100, '/api/media/legacy-1')");
            }

            // Bước 2: Chạy nối tiếp MIGRATION_1_2 -> MIGRATION_2_3 và xác thực schema 3.json.
            SupportSQLiteDatabase migrated = helper.runMigrationsAndValidate(
                    TEST_DB_V1_V3, 3, true, AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3);
            migrated.close();

            // Bước 3: Mở bản app hiện tại và kiểm chứng toàn bộ dữ liệu lịch sử.
            AppDatabase dbV3 = Room.databaseBuilder(context, AppDatabase.class, TEST_DB_V1_V3)
                    .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
                    .build();
            try {
                assertNotNull(dbV3.userDao().findById("u-alice"));
                assertEquals("Alice Nguyễn", dbV3.userDao().findById("u-alice").getDisplayName());

                ConversationMemberEntity member = dbV3.conversationDao().getMemberSync("conv-1", "u-alice");
                assertNotNull(member);
                assertEquals("ADMIN", member.getRole());
                assertNull(member.getVisibleFromMessageId());

                MessageEntity migratedMsg = dbV3.messageDao().findByClientId("client-v1-1");
                assertNotNull(migratedMsg);
                assertEquals(Long.valueOf(100L), migratedMsg.getServerId());
                assertNull(migratedMsg.getErrorCode());
                assertNull(migratedMsg.getQueuedVisibleFromMessageId());

                AttachmentTransferEntity transfer = dbV3.transferDao().findByClientIdSync("tx-v1-1");
                assertNotNull(transfer);
                assertEquals("UPLOAD", transfer.getDirection());
                assertEquals("/api/media/legacy-1", transfer.getMediaUrl());
                assertEquals(0L, transfer.getUpdatedAt());

                List<MessageEntity> hits = dbV3.messageDao().searchInConversationVisible(
                        "conv-1", "u-alice", MessageText.safeMatchQuery("chao dang"));
                assertEquals(1, hits.size());
                assertEquals("client-v1-1", hits.get(0).getClientId());
            } finally {
                dbV3.close();
            }
        } finally {
            context.deleteDatabase(TEST_DB_V1_V3);
        }
    }
    @Test
    public void migrate3To4PreservesDataAndAddsPhase7Tables() throws IOException {
        verifyMigrationTo4(TEST_DB_V3_V4, 3, AppDatabase.MIGRATION_3_4);
    }

    @Test
    public void migrate1To4EndToEnd() throws IOException {
        verifyMigrationTo4(TEST_DB_V1_V4, 1,
                AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4);
    }

    private void verifyMigrationTo4(String dbName, int fromVersion,
            androidx.room.migration.Migration... migrations) throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(dbName);
        try {
            // Dữ liệu bắt đầu ở cả v1 và v3 để thử tải trực tiếp lên phiên bản hiện tại.
            try (SupportSQLiteDatabase original = helper.createDatabase(dbName, fromVersion)) {
                original.execSQL("INSERT INTO message (localId, serverId, clientId, conversationId, senderId, "
                        + "type, text, normalized_text, createdAt, status) "
                        + "VALUES (1, 100, 'legacy-message', 'conv-1', 'u-alice', 'TEXT', 'Chào bạn', 'chao ban', 1000, 'SENT')");
                original.execSQL("INSERT INTO attachment_transfer (clientId, localUri, state, progress) "
                        + "VALUES ('legacy-transfer', 'content://legacy/1', 'UPLOADED', 100)");
                original.execSQL("INSERT INTO message_translation (messageKey, targetLang, translatedText) "
                        + "VALUES ('legacy-message', 'en', 'Hello')");
            }
            try (SupportSQLiteDatabase migrated = helper.runMigrationsAndValidate(dbName, 4, true, migrations)) {
                // Tin, chỉ mục FTS4, tiến độ tệp và bản dịch cũ vẫn đọc được sau khi nâng cấp.
                try (Cursor rows = migrated.query("SELECT text, recalledAt FROM message WHERE serverId = 100")) {
                    assertTrue(rows.moveToFirst());
                    assertEquals("Chào bạn", rows.getString(0));
                    assertTrue(rows.isNull(1));
                }
                try (Cursor matches = migrated.query("SELECT docid FROM message_fts WHERE message_fts MATCH 'chao'")) {
                    assertEquals(1, matches.getCount());
                }
                try (Cursor rows = migrated.query("SELECT translatedText FROM message_translation WHERE messageKey = 'legacy-message'")) {
                    assertTrue(rows.moveToFirst());
                    assertEquals("Hello", rows.getString(0));
                }
                try (Cursor rows = migrated.query("SELECT progress, direction FROM attachment_transfer WHERE clientId = 'legacy-transfer'")) {
                    assertTrue(rows.moveToFirst());
                    assertEquals(100, rows.getInt(0));
                    assertEquals("UPLOAD", rows.getString(1));
                }

                // Ghi thử các bảng mới để kiểm ràng buộc cột và khả năng lưu cache.
                migrated.execSQL("INSERT INTO message_deletion (serverId) VALUES (99)");
                migrated.execSQL("INSERT INTO message_reaction (messageServerId, userId, emoji, createdAt) "
                        + "VALUES (100, 'u-alice', '👍', 1001)");
                migrated.execSQL("INSERT INTO sticker_pack (packId, code, name, thumbnailUrl, installed, isDefault, sortOrder) "
                        + "VALUES ('pack-1', 'daily', 'Thường Ngày', '/api/stickers/assets/daily/hi.png', 1, 1, 0)");
                migrated.execSQL("INSERT INTO sticker (stickerId, packId, code, label, imageUrl, width, height, sortOrder) "
                        + "VALUES ('sticker-1', 'pack-1', 'hi', 'Chào', '/api/stickers/assets/daily/hi.png', 256, 256, 0)");
                try (Cursor rows = migrated.query("SELECT emoji FROM message_reaction WHERE messageServerId = 100")) {
                    assertTrue(rows.moveToFirst());
                    assertEquals("👍", rows.getString(0));
                }
                try (Cursor rows = migrated.query("SELECT s.width, p.installed FROM sticker s JOIN sticker_pack p ON s.packId = p.packId")) {
                    assertTrue(rows.moveToFirst());
                    assertEquals(256, rows.getInt(0));
                    assertEquals(1, rows.getInt(1));
                }
            }
        } finally {
            context.deleteDatabase(dbName);
        }
    }
}
