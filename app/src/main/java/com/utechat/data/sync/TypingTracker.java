package com.utechat.data.sync;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Quản lý trạng thái đang nhập (typing) tạm thời theo từng hội thoại:
 * - Tự động xóa người đang nhập sau 5 giây nếu không nhận thêm sự kiện mới.
 * - Giới hạn tần suất gửi typing.start ra mạng tối đa 1 lần mỗi 3 giây trên từng hội thoại.
 * Không dùng ArchTaskExecutor; phát LiveData an toàn qua postValue trên mọi luồng.
 */
public class TypingTracker {
    public static final long EXPIRE_MS = 5_000L;
    public static final long SEND_THROTTLE_MS = 3_000L;

    private final SyncEngine.DelayScheduler scheduler;
    private final LongSupplier clockMs;

    /** Bản đồ conversationId -> (userId -> thời điểm hết hạn ms). */
    private final Map<String, Map<String, Long>> activeByConversation = new HashMap<>();
    /** Bản đồ conversationId -> LiveData phát tập UUID những người đang gõ. */
    private final Map<String, MutableLiveData<Set<String>>> liveDataMap = new HashMap<>();
    /** Bản đồ conversationId -> thời điểm gửi typing.start gần nhất của chính mình. */
    private final Map<String, Long> lastSentStartMs = new HashMap<>();
    /** Các tác vụ hẹn giờ dọn hết hạn đang chờ để có thể hủy khi clearAll(). */
    private final Map<String, SyncEngine.DelayScheduler.Cancellable> pendingCleanups = new HashMap<>();

    public TypingTracker() {
        this(SyncEngine.defaultScheduler(), System::currentTimeMillis);
    }

    public TypingTracker(@NonNull SyncEngine.DelayScheduler scheduler, @NonNull LongSupplier clockMs) {
        this.scheduler = scheduler;
        this.clockMs = clockMs;
    }

    /** Quan sát tập UUID các thành viên đang nhập trong một hội thoại cụ thể. */
    public synchronized LiveData<Set<String>> observeTypingUsers(@NonNull String conversationId) {
        MutableLiveData<Set<String>> live = liveDataMap.get(conversationId);
        if (live == null) {
            live = new MutableLiveData<>(Collections.emptySet());
            liveDataMap.put(conversationId, live);
        }
        return live;
    }

    /** Cập nhật trạng thái đang nhập khi nhận khung WebSocket "typing" từ server. */
    public synchronized void onTypingEvent(String conversationId, String userId, String state) {
        if (conversationId == null || userId == null) return;
        Map<String, Long> users = activeByConversation.computeIfAbsent(conversationId, k -> new HashMap<>());
        if ("start".equalsIgnoreCase(state)) {
            long expiresAt = clockMs.getAsLong() + EXPIRE_MS;
            users.put(userId, expiresAt);
            scheduleCleanup(conversationId);
        } else {
            users.remove(userId);
        }
        publishLocked(conversationId);
    }

    /** Kiểm tra xem đã qua 3 giây kể từ lần gửi typing.start trước của hội thoại này chưa. */
    public synchronized boolean shouldSendTypingStart(String conversationId) {
        if (conversationId == null) return false;
        long now = clockMs.getAsLong();
        Long last = lastSentStartMs.get(conversationId);
        if (last != null && (now - last) < SEND_THROTTLE_MS) {
            return false;
        }
        lastSentStartMs.put(conversationId, now);
        return true;
    }

    /** Xóa dấu giới hạn gửi khi người dùng chủ động dừng nhập hoặc rời màn hình phòng chat. */
    public synchronized void resetSendThrottle(String conversationId) {
        if (conversationId != null) {
            lastSentStartMs.remove(conversationId);
        }
    }

    /** Dọn sạch toàn bộ trạng thái và hủy mọi hẹn giờ khi đăng xuất hoặc đổi tài khoản. */
    public synchronized void clearAll() {
        for (SyncEngine.DelayScheduler.Cancellable task : pendingCleanups.values()) {
            if (task != null) task.cancel();
        }
        pendingCleanups.clear();
        activeByConversation.clear();
        lastSentStartMs.clear();
        for (MutableLiveData<Set<String>> live : liveDataMap.values()) {
            live.postValue(Collections.emptySet());
        }
    }

    private void scheduleCleanup(String conversationId) {
        SyncEngine.DelayScheduler.Cancellable old = pendingCleanups.remove(conversationId);
        if (old != null) old.cancel();
        SyncEngine.DelayScheduler.Cancellable next = scheduler.schedule(EXPIRE_MS, () -> pruneExpired(conversationId));
        pendingCleanups.put(conversationId, next);
    }

    private synchronized void pruneExpired(String conversationId) {
        Map<String, Long> users = activeByConversation.get(conversationId);
        if (users == null || users.isEmpty()) {
            pendingCleanups.remove(conversationId);
            return;
        }
        long now = clockMs.getAsLong();
        long nextRemaining = Long.MAX_VALUE;
        Iterator<Map.Entry<String, Long>> it = users.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (entry.getValue() <= now) {
                it.remove();
            } else {
                nextRemaining = Math.min(nextRemaining, entry.getValue() - now);
            }
        }
        publishLocked(conversationId);
        if (users.isEmpty()) {
            pendingCleanups.remove(conversationId);
        } else {
            SyncEngine.DelayScheduler.Cancellable next = scheduler.schedule(
                    Math.max(50L, nextRemaining),
                    () -> pruneExpired(conversationId));
            pendingCleanups.put(conversationId, next);
        }
    }

    private void publishLocked(String conversationId) {
        MutableLiveData<Set<String>> live = liveDataMap.get(conversationId);
        if (live == null) return;
        Map<String, Long> users = activeByConversation.get(conversationId);
        Set<String> snapshot = (users == null || users.isEmpty())
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(users.keySet()));
        live.postValue(snapshot);
    }
}
