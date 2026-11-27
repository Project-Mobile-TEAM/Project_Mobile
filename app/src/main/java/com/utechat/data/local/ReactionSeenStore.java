package com.utechat.data.local;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Lưu mốc thời gian cảm xúc mới nhất đã xem theo từng cặp tài khoản và hội thoại
 * để dòng thông báo cảm xúc ở danh sách chat chỉ hiện khi có lượt thả mới chưa xem.
 */
public final class ReactionSeenStore {
    private static final String PREFS_NAME = "utechat_reaction_seen";

    private final SharedPreferences prefs;
    private final Map<String, Long> seenTimestamps = new HashMap<>();
    private final MutableLiveData<Map<String, Long>> seenLiveData = new MutableLiveData<>(Collections.emptyMap());

    public ReactionSeenStore(@NonNull Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (entry.getValue() instanceof Long) {
                seenTimestamps.put(entry.getKey(), (Long) entry.getValue());
            } else if (entry.getValue() instanceof Number) {
                seenTimestamps.put(entry.getKey(), ((Number) entry.getValue()).longValue());
            }
        }
        seenLiveData.setValue(Collections.unmodifiableMap(new HashMap<>(seenTimestamps)));
    }

    /** Khóa lưu mốc đã xem tách biệt theo từng tài khoản đăng nhập. */
    @NonNull
    public static String key(@Nullable String userId, @Nullable String conversationId) {
        return (userId == null ? "" : userId) + ":" + (conversationId == null ? "" : conversationId);
    }

    @NonNull
    public LiveData<Map<String, Long>> observeAll() {
        return seenLiveData;
    }

    /** Nâng mốc thời gian đã xem một cách đơn điệu (không bao giờ lùi mốc cũ). */
    public synchronized void markSeen(@Nullable String userId, @Nullable String conversationId, long reactionTimestampMs) {
        if (conversationId == null || conversationId.trim().isEmpty() || reactionTimestampMs <= 0L) {
            return;
        }
        String storageKey = key(userId, conversationId);
        Long current = seenTimestamps.get(storageKey);
        if (current != null && reactionTimestampMs <= current) {
            return;
        }
        seenTimestamps.put(storageKey, reactionTimestampMs);
        prefs.edit().putLong(storageKey, reactionTimestampMs).apply();
        seenLiveData.postValue(Collections.unmodifiableMap(new HashMap<>(seenTimestamps)));
    }

    /** Xóa sạch dấu đã xem khi đăng xuất hoặc đổi tài khoản để không rò trạng thái phiên cũ. */
    public synchronized void clear() {
        seenTimestamps.clear();
        prefs.edit().clear().apply();
        seenLiveData.postValue(Collections.emptyMap());
    }
}
