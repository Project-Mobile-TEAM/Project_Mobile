package com.utechat.data.notifications;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.content.ContextCompat;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.utechat.MainActivity;
import com.utechat.R;
import com.utechat.data.local.AppDatabase;
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.session.SessionManager;
import com.utechat.ui.chat.SystemMessageFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Bộ quản lý thông báo 2 kênh duy nhất của UTEChat V1 ("messages" và "calls" — D-05, D-06):
 * - Tuyệt đối KHÔNG tạo kênh thứ 3 cho lời mời kết bạn; chỉ điều hướng qua MainActivity bằng PendingIntent FLAG_IMMUTABLE.
 * - Tách rạch ròi logic thuần Java (lọc, dựng model, gom nhóm) khỏi AndroidNotificationSink để kiểm thử JVM 100% không chạm stub.
 * - Lọc nghiêm ngặt tin tự gửi, hội thoại đã tắt thông báo (muted), phòng đang mở, tin loại CALL trên kênh messages và phiên đã đổi.
 */
public class NotificationHelper {
    public static final String CHANNEL_MESSAGES = "messages";
    public static final String CHANNEL_CALLS = "calls";
    public static final String GROUP_KEY_MESSAGES = "utechat_messages";
    public static final String EXTRA_OPEN_CONVERSATION_ID = "extra_open_conversation_id";
    public static final String EXTRA_OPEN_CALL_ID = "extra_open_call_id";
    public static final String EXTRA_NOTIFICATION_SESSION_KEY = "extra_notification_session_key";
    public static final int SUMMARY_NOTIFICATION_ID = 1001;

    /** Giá trị IMPORTANCE_HIGH thuần Java (khớp NotificationManager.IMPORTANCE_HIGH = 4) dùng an toàn trong JVM test. */
    public static final int IMPORTANCE_HIGH = 4;
    private static final int MAX_LINES_PER_CONVERSATION = 10;

    /** Bộ giải mã chuỗi đa ngôn ngữ tương thích SystemMessageFormatter.Strings (Android dùng context::getString). */
    public interface StringResolver extends SystemMessageFormatter.Strings {
        @Override
        String get(@StringRes int resId, Object... args);
    }

    /** Mô tả một NotificationChannel thuần Java. */
    public static final class ChannelSpec {
        @NonNull public final String id;
        @NonNull public final String name;
        @NonNull public final String description;
        public final int importance;

        public ChannelSpec(@NonNull String id, @NonNull String name, @NonNull String description, int importance) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.importance = importance;
        }
    }

    /** Một dòng tin nhắn hiển thị trong MessagingStyle của một hội thoại. */
    public static final class MessageLineModel {
        @NonNull public final String senderId;
        @NonNull public final String senderName;
        @NonNull public final String text;
        public final long timestampMs;

        public MessageLineModel(
                @NonNull String senderId,
                @NonNull String senderName,
                @NonNull String text,
                long timestampMs) {
            this.senderId = senderId;
            this.senderName = senderName;
            this.text = text;
            this.timestampMs = timestampMs;
        }
    }

    /** Model thông báo gom theo từng hội thoại trên kênh "messages". */
    public static final class ConversationNotificationModel {
        @NonNull public final String conversationId;
        public final int notificationId;
        @NonNull public final String channelId;
        @NonNull public final String groupKey;
        @NonNull public final String title;
        public final boolean isGroup;
        @NonNull public final String selfName;
        @Nullable public final String sessionKey;
        @NonNull public final List<MessageLineModel> lines;

        public ConversationNotificationModel(
                @NonNull String conversationId,
                int notificationId,
                @NonNull String channelId,
                @NonNull String groupKey,
                @NonNull String title,
                boolean isGroup,
                @NonNull String selfName,
                @Nullable String sessionKey,
                @NonNull List<MessageLineModel> lines) {
            this.conversationId = conversationId;
            this.notificationId = notificationId;
            this.channelId = channelId;
            this.groupKey = groupKey;
            this.title = title;
            this.isGroup = isGroup;
            this.selfName = selfName;
            this.sessionKey = sessionKey;
            this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
        }
    }

    /** Model thông báo tóm tắt nhóm khi có từ 2 hội thoại trở lên đang có tin mới. */
    public static final class SummaryNotificationModel {
        public final int notificationId;
        @NonNull public final String channelId;
        @NonNull public final String groupKey;
        @NonNull public final String summaryText;
        public final int conversationCount;
        public final int totalMessages;
        @Nullable public final String sessionKey;

        public SummaryNotificationModel(
                int notificationId,
                @NonNull String channelId,
                @NonNull String groupKey,
                @NonNull String summaryText,
                int conversationCount,
                int totalMessages,
                @Nullable String sessionKey) {
            this.notificationId = notificationId;
            this.channelId = channelId;
            this.groupKey = groupKey;
            this.summaryText = summaryText;
            this.conversationCount = conversationCount;
            this.totalMessages = totalMessages;
            this.sessionKey = sessionKey;
        }
    }

    /** Dữ liệu sự kiện cuộc gọi nhỡ do SyncEventApplier thu thập từ CALL_UPDATED (state = MISSED). */
    public static final class MissedCallNotice {
        @NonNull public final String callId;
        @Nullable public final String conversationId;
        @NonNull public final String callerId;
        @Nullable public final String callerDisplayName;

        public MissedCallNotice(
                @NonNull String callId,
                @Nullable String conversationId,
                @NonNull String callerId,
                @Nullable String callerDisplayName) {
            this.callId = callId;
            this.conversationId = conversationId;
            this.callerId = callerId;
            this.callerDisplayName = callerDisplayName;
        }
    }

    /** Model thông báo cuộc gọi nhỡ trên kênh "calls". */
    public static final class MissedCallNotificationModel {
        @NonNull public final String callId;
        @Nullable public final String conversationId;
        public final int notificationId;
        @NonNull public final String channelId;
        @NonNull public final String title;
        @NonNull public final String body;
        @Nullable public final String sessionKey;

        public MissedCallNotificationModel(
                @NonNull String callId,
                @Nullable String conversationId,
                int notificationId,
                @NonNull String channelId,
                @NonNull String title,
                @NonNull String body,
                @Nullable String sessionKey) {
            this.callId = callId;
            this.conversationId = conversationId;
            this.notificationId = notificationId;
            this.channelId = channelId;
            this.title = title;
            this.body = body;
            this.sessionKey = sessionKey;
        }
    }

    /** Model thông báo cuộc gọi đến trên kênh "calls" (hỗ trợ fullScreenIntent có kiểm tra quyền Android 14+). */
    public static final class IncomingCallNotificationModel {
        @NonNull public final String callId;
        @NonNull public final String conversationId;
        public final int notificationId;
        @NonNull public final String channelId;
        @NonNull public final String title;
        @NonNull public final String body;
        @Nullable public final String sessionKey;

        public IncomingCallNotificationModel(
                @NonNull String callId,
                @NonNull String conversationId,
                int notificationId,
                @NonNull String channelId,
                @NonNull String title,
                @NonNull String body,
                @Nullable String sessionKey) {
            this.callId = callId;
            this.conversationId = conversationId;
            this.notificationId = notificationId;
            this.channelId = channelId;
            this.title = title;
            this.body = body;
            this.sessionKey = sessionKey;
        }
    }

    /** Ranh giới xuất thông báo ra hệ điều hành (Android dùng AndroidNotificationSink; JVM test dùng fake sink). */
    public interface NotificationSink {
        void ensureChannels(@NonNull List<ChannelSpec> channels);

        void postConversation(@NonNull ConversationNotificationModel model);

        void postSummary(@NonNull SummaryNotificationModel model);

        void postMissedCall(@NonNull MissedCallNotificationModel model);

        void postIncomingCall(@NonNull IncomingCallNotificationModel model);

        void cancel(int notificationId);

        void cancelAll();
    }

    /**
     * Hiện thực Android thật của NotificationSink: đóng gói toàn bộ Android framework (NotificationChannel,
     * MessagingStyle, Person, PendingIntent FLAG_IMMUTABLE, kiểm tra canUseFullScreenIntent API 34+).
     * Chỉ điều hướng tới MainActivity, không phụ thuộc bất kỳ Fragment nào của 06-06 / 06-09.
     */
    public static final class AndroidNotificationSink implements NotificationSink {
        private static final String ACTION_OPEN_NOTIFICATION = "com.utechat.action.OPEN_NOTIFICATION";

        private final Context appContext;

        public AndroidNotificationSink(@NonNull Context context) {
            this.appContext = context.getApplicationContext();
        }

        @Override
        public void ensureChannels(@NonNull List<ChannelSpec> channels) {
            NotificationManager nm = appContext.getSystemService(NotificationManager.class);
            if (nm == null) return;
            for (ChannelSpec spec : channels) {
                NotificationChannel channel = new NotificationChannel(spec.id, spec.name, spec.importance);
                channel.setDescription(spec.description);
                nm.createNotificationChannel(channel);
            }
        }

        @Override
        public void postConversation(@NonNull ConversationNotificationModel model) {
            if (!canPostNotifications()) return;
            Person self = new Person.Builder().setKey("self").setName(model.selfName).build();
            NotificationCompat.MessagingStyle style = new NotificationCompat.MessagingStyle(self);
            if (model.isGroup) {
                style.setGroupConversation(true);
                style.setConversationTitle(model.title);
            }
            for (MessageLineModel line : model.lines) {
                Person sender = new Person.Builder().setKey(line.senderId).setName(line.senderName).build();
                style.addMessage(new NotificationCompat.MessagingStyle.Message(line.text, line.timestampMs, sender));
            }
            String uniqueKey = "conv:" + safePart(model.sessionKey) + ":" + model.conversationId;
            PendingIntent contentIntent = buildMainActivityPendingIntent(
                    appContext, uniqueKey, model.sessionKey, model.conversationId, null);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, model.channelId)
                    .setSmallIcon(R.drawable.ic_tab_chat)
                    .setStyle(style)
                    .setGroup(model.groupKey)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent);
            notifySafely(model.notificationId, builder);
        }

        @Override
        public void postSummary(@NonNull SummaryNotificationModel model) {
            if (!canPostNotifications()) return;
            String uniqueKey = "summary:" + safePart(model.sessionKey);
            PendingIntent contentIntent = buildMainActivityPendingIntent(
                    appContext, uniqueKey, model.sessionKey, null, null);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, model.channelId)
                    .setSmallIcon(R.drawable.ic_tab_chat)
                    .setContentTitle(appContext.getString(R.string.app_name))
                    .setContentText(model.summaryText)
                    .setGroup(model.groupKey)
                    .setGroupSummary(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent);
            notifySafely(model.notificationId, builder);
        }

        @Override
        public void postMissedCall(@NonNull MissedCallNotificationModel model) {
            if (!canPostNotifications()) return;
            String uniqueKey = "missed:" + safePart(model.sessionKey) + ":" + model.callId;
            PendingIntent contentIntent = buildMainActivityPendingIntent(
                    appContext, uniqueKey, model.sessionKey, model.conversationId, null);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, model.channelId)
                    .setSmallIcon(R.drawable.ic_phone)
                    .setContentTitle(model.title)
                    .setContentText(model.body)
                    .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent);
            notifySafely(model.notificationId, builder);
        }

        @Override
        public void postIncomingCall(@NonNull IncomingCallNotificationModel model) {
            if (!canPostNotifications()) return;
            String uniqueKey = "incoming:" + safePart(model.sessionKey) + ":" + model.callId;
            PendingIntent callPendingIntent = buildMainActivityPendingIntent(
                    appContext, uniqueKey, model.sessionKey, model.conversationId, model.callId);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, model.channelId)
                    .setSmallIcon(R.drawable.ic_phone)
                    .setContentTitle(model.title)
                    .setContentText(model.body)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setContentIntent(callPendingIntent);

            // Android 14+ (API 34+): chỉ gọi setFullScreenIntent khi hệ thống cấp quyền, ngược lại hạ về heads-up.
            NotificationManager notificationManager = appContext.getSystemService(NotificationManager.class);
            if (Build.VERSION.SDK_INT < 34
                    || (notificationManager != null && notificationManager.canUseFullScreenIntent())) {
                builder.setFullScreenIntent(callPendingIntent, true);
            }
            notifySafely(model.notificationId, builder);
        }

        @Override
        public void cancel(int notificationId) {
            NotificationManagerCompat.from(appContext).cancel(notificationId);
        }

        @Override
        public void cancelAll() {
            NotificationManagerCompat.from(appContext).cancelAll();
        }

        private boolean canPostNotifications() {
            if (Build.VERSION.SDK_INT < 33) {
                return true;
            }
            return ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }

        private void notifySafely(int notificationId, NotificationCompat.Builder builder) {
            try {
                NotificationManagerCompat.from(appContext).notify(notificationId, builder.build());
            } catch (SecurityException ignored) {
                // Người dùng vừa thu hồi quyền POST_NOTIFICATIONS giữa lúc gửi thông báo.
            }
        }

        /**
         * Tạo PendingIntent bất biến (FLAG_IMMUTABLE bắt buộc trên targetSdk 36) trỏ về MainActivity,
         * có URI định danh duy nhất theo (sessionKey, destination) để không ghi đè extras giữa các thông báo.
         */
        @NonNull
        public static PendingIntent buildMainActivityPendingIntent(
                @NonNull Context context,
                @NonNull String uniqueKey,
                @Nullable String sessionKey,
                @Nullable String conversationId,
                @Nullable String callId) {
            Intent intent = new Intent(context.getApplicationContext(), MainActivity.class);
            intent.setAction(ACTION_OPEN_NOTIFICATION);
            intent.setData(Uri.parse("utechat://notification/" + Uri.encode(uniqueKey)));
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (sessionKey != null && !sessionKey.isEmpty()) {
                intent.putExtra(EXTRA_NOTIFICATION_SESSION_KEY, sessionKey);
            }
            if (conversationId != null && !conversationId.isEmpty()) {
                intent.putExtra(EXTRA_OPEN_CONVERSATION_ID, conversationId);
            }
            if (callId != null && !callId.isEmpty()) {
                intent.putExtra(EXTRA_OPEN_CALL_ID, callId);
            }
            return PendingIntent.getActivity(
                    context.getApplicationContext(),
                    uniqueKey.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        /** Tạo PendingIntent cho thông báo cuộc gọi đang diễn ra (CallService ở 06-06 tái sử dụng cùng hợp đồng). */
        @NonNull
        public static PendingIntent buildOngoingCallPendingIntent(
                @NonNull Context context,
                @NonNull String callId,
                @NonNull String conversationId,
                @Nullable String sessionKey) {
            String uniqueKey = "ongoing:" + safePart(sessionKey) + ":" + callId;
            return buildMainActivityPendingIntent(context, uniqueKey, sessionKey, conversationId, callId);
        }

        private static String safePart(@Nullable String value) {
            return value == null ? "none" : value;
        }
    }

    private final AppDatabase db;
    private final NotificationSink sink;
    private final StringResolver strings;
    @Nullable private final SessionManager session;
    private final Map<String, List<MessageLineModel>> conversationHistory = new LinkedHashMap<>();

    /** Constructor thuận tiện cho Android thật (tự bọc AndroidNotificationSink và context::getString). */
    public NotificationHelper(
            @NonNull Context context,
            @NonNull AppDatabase db,
            @Nullable SessionManager session) {
        this(db, new AndroidNotificationSink(context), context.getApplicationContext()::getString, session);
    }

    /** Constructor thuần Java dùng cho DI và kiểm thử JVM (tiêm fake sink và fake StringResolver). */
    public NotificationHelper(
            @NonNull AppDatabase db,
            @NonNull NotificationSink sink,
            @NonNull StringResolver strings,
            @Nullable SessionManager session) {
        this.db = db;
        this.sink = sink;
        this.strings = strings;
        this.session = session;
    }

    /** Đăng ký ĐÚNG 2 kênh thông báo V1 ("messages" và "calls"), tuyệt đối không thêm kênh thứ 3 (D-06). */
    public void ensureChannels() {
        List<ChannelSpec> channels = Arrays.asList(
                new ChannelSpec(
                        CHANNEL_MESSAGES,
                        strings.get(R.string.notif_channel_messages_name),
                        strings.get(R.string.notif_channel_messages_desc),
                        IMPORTANCE_HIGH),
                new ChannelSpec(
                        CHANNEL_CALLS,
                        strings.get(R.string.notif_channel_calls_name),
                        strings.get(R.string.notif_channel_calls_desc),
                        IMPORTANCE_HIGH)
        );
        sink.ensureChannels(channels);
    }

    /** Lọc danh sách tin nhắn mới hợp lệ để thông báo: loại tin tự gửi, CALL, phòng đang mở và hội thoại muted. */
    @NonNull
    public List<MessageEntity> filterNotifiableMessages(
            @NonNull String myUserId,
            @Nullable List<MessageEntity> newMessages,
            @NonNull ActiveRoomTracker activeTracker) {
        if (newMessages == null || newMessages.isEmpty()) {
            return Collections.emptyList();
        }
        String activeConvId = activeTracker.getActiveConversationId();
        Map<String, ConversationEntity> convCache = new HashMap<>();
        List<MessageEntity> filtered = new ArrayList<>();
        for (MessageEntity msg : newMessages) {
            if (msg == null || msg.getConversationId() == null) continue;
            if (myUserId.equals(msg.getSenderId())) continue;
            if ("CALL".equals(msg.getType())) continue;
            if (msg.getConversationId().equals(activeConvId)) continue;
            ConversationEntity conv = lookupConversation(convCache, msg.getConversationId());
            if (conv == null || conv.getMuted()) continue;
            filtered.add(msg);
        }
        return filtered;
    }

    /**
     * Phát thông báo cho lô tin mới và cuộc gọi nhỡ sau khi Room đã commit.
     * Bỏ qua hoàn toàn nếu sessionStillValid trả false hoặc sessionKey đã đổi.
     */
    public void notifyBatch(
            @NonNull String myUserId,
            @Nullable List<MessageEntity> newMessages,
            @Nullable List<MissedCallNotice> missedCalls,
            @NonNull ActiveRoomTracker activeTracker,
            @NonNull Supplier<Boolean> sessionStillValid) {
        String currentKey = session != null ? session.sessionKey() : null;
        notifyBatch(myUserId, currentKey, newMessages, missedCalls, activeTracker, sessionStillValid);
    }

    /**
     * Phiên bản mang tường minh originating sessionKey để khóa đăng thông báo trong session.runIfCurrent(...),
     * đảm bảo thứ tự chặt với cancelAll() khi người dùng đăng xuất hoặc đổi tài khoản.
     */
    public void notifyBatch(
            @NonNull String myUserId,
            @Nullable String sessionKey,
            @Nullable List<MessageEntity> newMessages,
            @Nullable List<MissedCallNotice> missedCalls,
            @NonNull ActiveRoomTracker activeTracker,
            @NonNull Supplier<Boolean> sessionStillValid) {
        if (!Boolean.TRUE.equals(sessionStillValid.get())) {
            return;
        }
        List<MessageEntity> notifiable = filterNotifiableMessages(myUserId, newMessages, activeTracker);

        // Chuẩn bị toàn bộ model trước (đọc Room) rồi mới kiểm tra khóa phiên và xuất ra sink.
        Map<String, ConversationEntity> convCache = new HashMap<>();
        Map<String, UserEntity> userCache = new HashMap<>();
        String selfName = strings.get(R.string.chat_you);

        Map<String, List<MessageLineModel>> incomingByConv = new LinkedHashMap<>();
        for (MessageEntity msg : notifiable) {
            String convId = msg.getConversationId();
            String senderId = msg.getSenderId() != null ? msg.getSenderId() : "";
            String senderName = resolveUserDisplayName(senderId, userCache);
            String preview = resolvePreviewText(msg, myUserId, userCache);
            MessageLineModel line = new MessageLineModel(senderId, senderName, preview, msg.getCreatedAt());
            incomingByConv.computeIfAbsent(convId, k -> new ArrayList<>()).add(line);
            lookupConversation(convCache, convId);
        }

        List<MissedCallNotificationModel> missedModels = new ArrayList<>();
        if (missedCalls != null) {
            for (MissedCallNotice notice : missedCalls) {
                if (notice == null || notice.callId == null || notice.callId.trim().isEmpty()) continue;
                if (myUserId.equals(notice.callerId)) continue;
                String callerName = (notice.callerDisplayName != null && !notice.callerDisplayName.trim().isEmpty())
                        ? notice.callerDisplayName.trim()
                        : resolveUserDisplayName(notice.callerId, userCache);
                missedModels.add(new MissedCallNotificationModel(
                        notice.callId,
                        notice.conversationId,
                        missedCallNotificationId(notice.callId),
                        CHANNEL_CALLS,
                        strings.get(R.string.notif_missed_call_title),
                        strings.get(R.string.notif_missed_call_body, callerName),
                        sessionKey));
            }
        }

        if (incomingByConv.isEmpty() && missedModels.isEmpty()) {
            return;
        }

        publishGuarded(sessionKey, sessionStillValid, () -> {
            List<ConversationNotificationModel> convModels = new ArrayList<>();
            SummaryNotificationModel summaryModel = null;
            synchronized (conversationHistory) {
                for (Map.Entry<String, List<MessageLineModel>> entry : incomingByConv.entrySet()) {
                    String convId = entry.getKey();
                    ConversationEntity conv = convCache.get(convId);
                    if (conv == null || conv.getMuted()) continue;
                    List<MessageLineModel> history = conversationHistory.computeIfAbsent(convId, k -> new ArrayList<>());
                    history.addAll(entry.getValue());
                    while (history.size() > MAX_LINES_PER_CONVERSATION) {
                        history.remove(0);
                    }
                    boolean isGroup = "GROUP".equals(conv.getType());
                    String title = resolveConversationTitle(conv, history);
                    convModels.add(new ConversationNotificationModel(
                            convId,
                            convId.hashCode(),
                            CHANNEL_MESSAGES,
                            GROUP_KEY_MESSAGES,
                            title,
                            isGroup,
                            selfName,
                            sessionKey,
                            history));
                }
                if (!convModels.isEmpty() && conversationHistory.size() >= 2) {
                    int totalMessages = 0;
                    for (List<MessageLineModel> lines : conversationHistory.values()) {
                        totalMessages += lines.size();
                    }
                    summaryModel = new SummaryNotificationModel(
                            SUMMARY_NOTIFICATION_ID,
                            CHANNEL_MESSAGES,
                            GROUP_KEY_MESSAGES,
                            strings.get(R.string.notif_group_summary, totalMessages),
                            conversationHistory.size(),
                            totalMessages,
                            sessionKey);
                }
            }
            for (ConversationNotificationModel model : convModels) {
                sink.postConversation(model);
            }
            if (summaryModel != null) {
                sink.postSummary(summaryModel);
            }
            for (MissedCallNotificationModel missed : missedModels) {
                sink.postMissedCall(missed);
            }
        });
    }

    /** Hiển thị thông báo cuộc gọi thoại đến trên kênh "calls" (gọi bởi CallCoordinator hoặc WebSocket). */
    public void showIncomingCallNotification(
            @NonNull String callId,
            @NonNull String conversationId,
            @Nullable String callerName) {
        String currentKey = session != null ? session.sessionKey() : null;
        showIncomingCallNotification(callId, conversationId, callerName, currentKey);
    }

    /** Phiên bản mang sessionKey tường minh để bảo đảm không đăng thông báo cuộc gọi sau khi phiên đã thoát. */
    public void showIncomingCallNotification(
            @NonNull String callId,
            @NonNull String conversationId,
            @Nullable String callerName,
            @Nullable String sessionKey) {
        String displayName = (callerName != null && !callerName.trim().isEmpty())
                ? callerName.trim()
                : strings.get(R.string.chat_someone);
        IncomingCallNotificationModel model = new IncomingCallNotificationModel(
                callId,
                conversationId,
                incomingCallNotificationId(callId),
                CHANNEL_CALLS,
                displayName,
                strings.get(R.string.notif_incoming_call),
                sessionKey);
        publishGuarded(sessionKey, () -> true, () -> sink.postIncomingCall(model));
    }

    /** Hủy thông báo cuộc gọi đến khi đã nhận máy, từ chối, kết thúc hoặc xử lý trên thiết bị khác. */
    public void cancelIncomingCallNotification(@NonNull String callId) {
        sink.cancel(incomingCallNotificationId(callId));
    }

    /** Xóa bộ đệm và thông báo của một hội thoại khi người dùng mở phòng chat đó; cập nhật lại summary nếu còn >= 2 phòng. */
    public void cancelConversation(@NonNull String conversationId) {
        boolean cancelSummary;
        SummaryNotificationModel updatedSummary = null;
        String currentKey = session != null ? session.sessionKey() : null;
        synchronized (conversationHistory) {
            boolean removed = conversationHistory.remove(conversationId) != null;
            int remainingConvs = conversationHistory.size();
            cancelSummary = remainingConvs < 2;
            if (removed && !cancelSummary) {
                int totalMessages = 0;
                for (List<MessageLineModel> lines : conversationHistory.values()) {
                    totalMessages += lines.size();
                }
                updatedSummary = new SummaryNotificationModel(
                        SUMMARY_NOTIFICATION_ID,
                        CHANNEL_MESSAGES,
                        GROUP_KEY_MESSAGES,
                        strings.get(R.string.notif_group_summary, totalMessages),
                        remainingConvs,
                        totalMessages,
                        currentKey);
            }
        }
        sink.cancel(conversationId.hashCode());
        if (cancelSummary) {
            sink.cancel(SUMMARY_NOTIFICATION_ID);
        } else if (updatedSummary != null) {
            final SummaryNotificationModel summaryToPost = updatedSummary;
            publishGuarded(currentKey, () -> true, () -> sink.postSummary(summaryToPost));
        }
    }

    /** Xóa sạch mọi thông báo trên khay hệ thống và bộ đệm gom nhóm (gọi khi logout / 401 / 4401 / đổi tài khoản). */
    public void cancelAll() {
        synchronized (conversationHistory) {
            conversationHistory.clear();
        }
        sink.cancelAll();
    }

    /** Tính mã thông báo ổn định cho cuộc gọi đến. */
    public static int incomingCallNotificationId(@NonNull String callId) {
        return ("incoming_call:" + callId).hashCode();
    }

    /** Tính mã thông báo ổn định cho cuộc gọi nhỡ. */
    public static int missedCallNotificationId(@NonNull String callId) {
        return ("missed_call:" + callId).hashCode();
    }

    // ======================== HÀM NỘI BỘ ========================

    private void publishGuarded(
            @Nullable String sessionKey,
            @NonNull Supplier<Boolean> sessionStillValid,
            @NonNull Runnable action) {
        if (!Boolean.TRUE.equals(sessionStillValid.get())) {
            return;
        }
        if (session != null) {
            // Khi có SessionManager thật mà sessionKey null (đã đăng xuất), tuyệt đối không lọt xuống post thông báo.
            if (sessionKey == null) {
                return;
            }
            // Khóa trong session.runIfCurrent để mọi thao tác đăng thông báo luôn có thứ tự rõ ràng với cancelAll().
            session.runIfCurrent(sessionKey, () -> {
                if (Boolean.TRUE.equals(sessionStillValid.get())) {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    @Nullable
    private ConversationEntity lookupConversation(Map<String, ConversationEntity> cache, String conversationId) {
        if (cache.containsKey(conversationId)) {
            return cache.get(conversationId);
        }
        ConversationEntity entity = db.conversationDao().getConversationSync(conversationId);
        cache.put(conversationId, entity);
        return entity;
    }

    @NonNull
    private String resolveUserDisplayName(@Nullable String userId, Map<String, UserEntity> userCache) {
        if (userId == null || userId.trim().isEmpty()) {
            return strings.get(R.string.chat_someone);
        }
        UserEntity user;
        if (userCache.containsKey(userId)) {
            user = userCache.get(userId);
        } else {
            user = db.userDao().findById(userId);
            userCache.put(userId, user);
        }
        if (user == null || user.getDisplayName() == null || user.getDisplayName().trim().isEmpty()) {
            return strings.get(R.string.chat_someone);
        }
        return user.getDisplayName().trim();
    }

    @NonNull
    private String resolvePreviewText(
            @NonNull MessageEntity msg,
            @NonNull String myUserId,
            Map<String, UserEntity> userCache) {
        // Tin đã gỡ chỉ được dùng nhãn trạng thái, kể cả khi loại gốc là TEXT.
        if (msg.getRecalledAt() != null) return strings.get(R.string.chat_message_recalled);
        String type = msg.getType() != null ? msg.getType() : "TEXT";
        if ("STICKER".equals(type)) return strings.get(R.string.chat_type_sticker);
        if ("TEXT".equals(type)) {
            String text = msg.getText();
            if (text != null && !text.trim().isEmpty()) {
                return text.trim();
            }
            return strings.get(R.string.notif_new_message_fallback);
        }
        if ("SYSTEM".equals(type)) {
            preloadSystemMessageUsers(msg.getAttachmentJson(), userCache);
            return SystemMessageFormatter.format(strings, msg.getAttachmentJson(), userCache, myUserId);
        }
        return strings.get(SystemMessageFormatter.summaryLabel(type));
    }

    /** Nạp trước tên actorId / targetUserId / targetUserIds từ Room cho tin SYSTEM để không rơi về "Someone". */
    private void preloadSystemMessageUsers(@Nullable String attachmentJson, Map<String, UserEntity> userCache) {
        if (attachmentJson == null || attachmentJson.trim().isEmpty()) {
            return;
        }
        try {
            JsonElement rootEl = JsonParser.parseString(attachmentJson);
            if (!rootEl.isJsonObject()) {
                return;
            }
            JsonObject meta = rootEl.getAsJsonObject();
            JsonElement paramsEl = meta.get("systemParams");
            JsonObject params = null;
            if (paramsEl != null && paramsEl.isJsonPrimitive()) {
                JsonElement parsedParams = JsonParser.parseString(paramsEl.getAsString());
                if (parsedParams.isJsonObject()) {
                    params = parsedParams.getAsJsonObject();
                }
            } else if (paramsEl != null && paramsEl.isJsonObject()) {
                params = paramsEl.getAsJsonObject();
            }
            if (params == null) {
                return;
            }
            preloadSingleUser(params.get("actorId"), userCache);
            preloadSingleUser(params.get("targetUserId"), userCache);
            JsonElement ids = params.get("targetUserIds");
            if (ids != null && ids.isJsonArray()) {
                for (JsonElement id : (JsonArray) ids) {
                    preloadSingleUser(id, userCache);
                }
            }
        } catch (RuntimeException ignored) {
            // JSON hỏng sẽ do SystemMessageFormatter fallback an toàn.
        }
    }

    private void preloadSingleUser(@Nullable JsonElement el, Map<String, UserEntity> userCache) {
        if (el != null && el.isJsonPrimitive()) {
            String uid = el.getAsString();
            if (uid != null && !uid.trim().isEmpty()) {
                resolveUserDisplayName(uid.trim(), userCache);
            }
        }
    }

    @NonNull
    private String resolveConversationTitle(
            @NonNull ConversationEntity conv,
            @NonNull List<MessageLineModel> lines) {
        if ("GROUP".equals(conv.getType()) && conv.getTitle() != null && !conv.getTitle().trim().isEmpty()) {
            return conv.getTitle().trim();
        }
        if (!lines.isEmpty()) {
            return lines.get(lines.size() - 1).senderName;
        }
        return strings.get(R.string.chat_someone);
    }
}
