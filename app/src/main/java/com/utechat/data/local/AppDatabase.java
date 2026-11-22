package com.utechat.data.local;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import com.utechat.data.local.dao.*;
import com.utechat.data.local.entity.*;

/** 10 bảng Room theo ERD mục 4 (phiên bản v3 bổ sung 11 cột cho attachment_transfer), khác 13 bảng PostgreSQL. */
@Database(entities = {UserEntity.class, ContactRequestEntity.class, ContactEntity.class, ConversationEntity.class, ConversationMemberEntity.class, MessageEntity.class, AttachmentTransferEntity.class, MessageFtsEntity.class, MessageTranslationEntity.class, SyncStateEntity.class},
        version = 3, exportSchema = true)
@TypeConverters(Converters.class)
public abstract class AppDatabase extends RoomDatabase {
    // Migration tường minh từ v1 lên v2 cho cả Debug và Release để không làm mất dữ liệu cục bộ.
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            // Cột mốc cô lập lịch sử nhóm khi thành viên được thêm lại; null nghĩa là xem toàn bộ.
            db.execSQL("ALTER TABLE member ADD COLUMN visibleFromMessageId INTEGER");
            // Mã lỗi bền vững cho tin nhắn FAILED để hiển thị lại sau khi mở app.
            db.execSQL("ALTER TABLE message ADD COLUMN errorCode TEXT");
            // Mốc tham gia nhóm lúc soạn tin; giúp Worker chặn tự gửi tin từ lần tham gia cũ.
            db.execSQL("ALTER TABLE message ADD COLUMN queuedVisibleFromMessageId INTEGER");
        }
    };

    // Migration tường minh từ v2 lên v3 (ADR-020, D-04): bổ sung 11 cột cho hàng đợi upload/download media bền vững.
    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE attachment_transfer ADD COLUMN direction TEXT NOT NULL DEFAULT 'UPLOAD'");
            db.execSQL("ALTER TABLE attachment_transfer ADD COLUMN localPath TEXT");
            db.execSQL("ALTER TABLE attachment_transfer ADD COLUMN kind TEXT");
            db.execSQL("ALTER TABLE attachment_transfer ADD COLUMN mime TEXT");
            db.execSQL("ALTER TABLE attachment_transfer ADD COLUMN sizeBytes INTEGER");
            db.execSQL("ALTER TABLE attachment_transfer ADD COLUMN fileName TEXT");
            db.execSQL("ALTER TABLE attachment_transfer ADD COLUMN durationMs INTEGER");
            db.execSQL("ALTER TABLE attachment_transfer ADD COLUMN width INTEGER");
            db.execSQL("ALTER TABLE attachment_transfer ADD COLUMN height INTEGER");
            db.execSQL("ALTER TABLE attachment_transfer ADD COLUMN errorCode TEXT");
            db.execSQL("ALTER TABLE attachment_transfer ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0");
        }
    };

    // volatile cùng khóa synchronized bảo đảm chỉ tạo một database cho nhiều luồng.
    private static volatile AppDatabase instance;

    public abstract UserDao userDao();
    public abstract ContactDao contactDao();
    public abstract ConversationDao conversationDao();
    public abstract MessageDao messageDao();
    public abstract TransferDao transferDao();
    public abstract TranslationDao translationDao();
    public abstract SyncStateDao syncStateDao();

    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    // Luôn gắn đầy đủ MIGRATION_1_2 và MIGRATION_2_3 cho mọi biến thể build, tuyệt đối không dùng fallbackToDestructiveMigration.
                    RoomDatabase.Builder<AppDatabase> builder = Room.databaseBuilder(
                            context.getApplicationContext(), AppDatabase.class, "utechat.db")
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3);
                    instance = builder.build();
                }
            }
        }
        return instance;
    }
}
