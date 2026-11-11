package com.utechat.data.local;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import com.utechat.BuildConfig;
import com.utechat.data.local.dao.*;
import com.utechat.data.local.entity.*;

/** 10 bảng Room theo ERD mục4, khác 13 bảng nghiệp vụ trên PostgreSQL. */
@Database(entities = {UserEntity.class, ContactRequestEntity.class, ContactEntity.class, ConversationEntity.class, ConversationMemberEntity.class, MessageEntity.class, AttachmentTransferEntity.class, MessageFtsEntity.class, MessageTranslationEntity.class, SyncStateEntity.class},
        version = 1, exportSchema = true)
@TypeConverters(Converters.class)
public abstract class AppDatabase extends RoomDatabase {
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
                    RoomDatabase.Builder<AppDatabase> builder = Room.databaseBuilder(
                            context.getApplicationContext(), AppDatabase.class, "utechat.db");
                    // Chỉ Debug được xóa cache khi đổi version; Release phải có migration rõ ràng.
                    if (BuildConfig.DEBUG) builder.fallbackToDestructiveMigration();
                    instance = builder.build();
                }
            }
        }
        return instance;
    }
}
