package com.utechat.data.notification;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ListenableWorker;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import com.google.gson.JsonParser;
import com.utechat.R;
import com.utechat.data.local.AppDatabase;
import com.utechat.data.local.dao.ContactDao;
import com.utechat.data.local.dao.ConversationDao;
import com.utechat.data.local.dao.MessageDao;
import com.utechat.data.local.dao.SyncStateDao;
import com.utechat.data.local.dao.UserDao;
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.ConversationMemberEntity;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.local.entity.SyncStateEntity;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.notifications.ActiveRoomTracker;
import com.utechat.data.notifications.NotificationHelper;
import com.utechat.data.remote.api.ChatApi;
import com.utechat.data.remote.model.AuthDtos;
import com.utechat.data.remote.model.ChatDtos;
import com.utechat.data.session.SessionManager;
import com.utechat.data.sync.SyncEngine;
import com.utechat.data.sync.SyncEventApplier;
import com.utechat.data.worker.SyncWorker;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Request;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Kiểm thử JVM toàn bộ quy tắc D-05, D-06, D-08 của Plan 06-05 (không chạm Android framework stub):
 * 1. Đúng 2 NotificationChannel V1 ("messages", "calls") + show/cancelIncomingCallNotification.
 * 2. Lọc tin tự gửi, hội thoại muted, phòng đang mở (ActiveRoomTracker), tin CALL trên kênh messages, và gom nhóm MessagingStyle + summary.
 * 3. Tắt thông báo trong bootstrap; đồng bộ tăng dần /sync phát thông báo tin mới (messages) và cuộc gọi nhỡ (calls).
 * 4. SyncWorker 15 phút KEEP + inputData sessionKey + khóa single-flight chung với SyncEngine + hủy sạch thông báo khi logout.
 */
public class NotificationAndSyncWorkerTest {
    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();

    private static final String ME = "u-me";
    private static final String BOB = "u-bob";
    private static final String CHARLIE = "u-charlie";
    private static final String SESSION_1 = "1:" + ME;
    private static final String SESSION_2 = "2:u-other";

    private AppDatabase db;
    private UserDao userDao;
    private ContactDao contactDao;
    private ConversationDao conversationDao;
    private MessageDao messageDao;
    private SyncStateDao syncStateDao;
    private ChatApi api;
    private SessionManager session;
    private FakeNotificationSink fakeSink;
    private NotificationHelper.StringResolver fakeStrings;
    private ActiveRoomTracker activeRoomTracker;

    private final AtomicReference<String> activeSessionKey = new AtomicReference<>(SESSION_1);
    private final AtomicReference<String> activeUserId = new AtomicReference<>(ME);
    private final AtomicLong fakeClockMs = new AtomicLong(1_731_500_000_000L);

    @Before
    public void setUp() {
        db = mock(AppDatabase.class);
        userDao = mock(UserDao.class);
        contactDao = mock(ContactDao.class);
        conversationDao = mock(ConversationDao.class);
        messageDao = mock(MessageDao.class);
        syncStateDao = mock(SyncStateDao.class);
        api = mock(ChatApi.class);
        session = mock(SessionManager.class);
        fakeSink = new FakeNotificationSink();
        activeRoomTracker = new ActiveRoomTracker();

        when(db.userDao()).thenReturn(userDao);
        when(db.contactDao()).thenReturn(contactDao);
        when(db.conversationDao()).thenReturn(conversationDao);
        when(db.messageDao()).thenReturn(messageDao);
        when(db.syncStateDao()).thenReturn(syncStateDao);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(db).runInTransaction(any(Runnable.class));

        when(session.sessionKey()).thenAnswer(i -> activeSessionKey.get());
        when(session.getUserId()).thenAnswer(i -> activeUserId.get());
        when(session.runIfCurrent(nullable(String.class), any(Runnable.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Runnable action = inv.getArgument(1);
            if (key == null || !key.equals(activeSessionKey.get())) return false;
            action.run();
            return true;
        });

        fakeStrings = (resId, args) -> {
            if (resId == R.string.notif_channel_messages_name) return "Messages";
            if (resId == R.string.notif_channel_messages_desc) return "New chat messages";
            if (resId == R.string.notif_channel_calls_name) return "Voice calls";
            if (resId == R.string.notif_channel_calls_desc) return "Incoming and missed voice calls";
            if (resId == R.string.notif_missed_call_title) return "Missed voice call";
            if (resId == R.string.notif_missed_call_body) return "Missed call from " + (args.length > 0 ? args[0] : "");
            if (resId == R.string.notif_incoming_call) return "Incoming voice call";
            if (resId == R.string.notif_group_summary) return (args.length > 0 ? args[0] : 0) + " new messages";
            if (resId == R.string.notif_new_message_fallback) return "New message";
            if (resId == R.string.chat_you) return "You";
            if (resId == R.string.chat_someone) return "Someone";
            if (resId == R.string.chat_type_image) return "Photo";
            if (resId == R.string.chat_type_audio) return "Voice message";
            if (resId == R.string.chat_type_location) return "Location";
            return "str:" + resId;
        };
    }

    @Test
    public void ensureChannelsCreatesExactlyMessagesAndCallsChannels() {
        NotificationHelper helper = new NotificationHelper(db, fakeSink, fakeStrings, session);
        helper.ensureChannels();

        // Đúng 2 kênh V1: "messages" và "calls", tuyệt đối không có kênh lời mời kết bạn.
        assertEquals(2, fakeSink.registeredChannels.size());
        assertEquals(NotificationHelper.CHANNEL_MESSAGES, fakeSink.registeredChannels.get(0).id);
        assertEquals("Messages", fakeSink.registeredChannels.get(0).name);
        assertEquals(NotificationHelper.CHANNEL_CALLS, fakeSink.registeredChannels.get(1).id);
        assertEquals("Voice calls", fakeSink.registeredChannels.get(1).name);

        // Thông báo cuộc gọi đến và hủy thông báo cuộc gọi đến đi qua kênh "calls".
        helper.showIncomingCallNotification("call-1", "conv-1", "Bob");
        assertEquals(1, fakeSink.incomingCalls.size());
        NotificationHelper.IncomingCallNotificationModel inc = fakeSink.incomingCalls.get(0);
        assertEquals("call-1", inc.callId);
        assertEquals("conv-1", inc.conversationId);
        assertEquals(NotificationHelper.CHANNEL_CALLS, inc.channelId);
        assertEquals("Bob", inc.title);
        assertEquals("Incoming voice call", inc.body);
        assertEquals(SESSION_1, inc.sessionKey);

        helper.cancelIncomingCallNotification("call-1");
        assertEquals(Collections.singletonList(NotificationHelper.incomingCallNotificationId("call-1")),
                fakeSink.canceledIds);
    }

    @Test
    public void filtersSelfMutedActiveRoomAndCallTypeMessages() {
        NotificationHelper helper = new NotificationHelper(db, fakeSink, fakeStrings, session);

        when(userDao.findById(BOB)).thenReturn(user(BOB, "Bob"));
        when(userDao.findById(CHARLIE)).thenReturn(user(CHARLIE, "Charlie"));
        when(conversationDao.getConversationSync("conv-a")).thenReturn(conv("conv-a", "DIRECT", null, false));
        when(conversationDao.getConversationSync("conv-muted")).thenReturn(conv("conv-muted", "DIRECT", null, true));
        when(conversationDao.getConversationSync("conv-group")).thenReturn(conv("conv-group", "GROUP", "Nhóm 6", false));

        // Phòng conv-a đang mở trên màn hình -> bị lọc bỏ.
        activeRoomTracker.setActive("conv-a");

        List<MessageEntity> batch1 = Arrays.asList(
                msg("m-self", "conv-group", ME, "TEXT", "Tự gửi", 1000L),
                msg("m-muted", "conv-muted", BOB, "TEXT", "Phòng tắt tiếng", 1001L),
                msg("m-active", "conv-a", BOB, "TEXT", "Đang mở phòng này", 1002L),
                msg("m-call", "conv-group", BOB, "CALL", null, 1003L),
                msg("m-ok-1", "conv-group", CHARLIE, "IMAGE", null, 1004L)
        );

        helper.notifyBatch(ME, SESSION_1, batch1, Collections.emptyList(), activeRoomTracker, () -> true);

        // Chỉ duy nhất tin m-ok-1 (IMAGE trong conv-group) được phát ra kênh messages.
        assertEquals(1, fakeSink.conversations.size());
        NotificationHelper.ConversationNotificationModel posted1 = fakeSink.conversations.get(0);
        assertEquals("conv-group", posted1.conversationId);
        assertEquals("conv-group".hashCode(), posted1.notificationId);
        assertEquals(NotificationHelper.CHANNEL_MESSAGES, posted1.channelId);
        assertEquals(NotificationHelper.GROUP_KEY_MESSAGES, posted1.groupKey);
        assertTrue(posted1.isGroup);
        assertEquals("Nhóm 6", posted1.title);
        assertEquals(1, posted1.lines.size());
        assertEquals("Photo", posted1.lines.get(0).text);
        assertEquals("Charlie", posted1.lines.get(0).senderName);
        assertTrue("Mới có 1 hội thoại -> chưa phát summary", fakeSink.summaries.isEmpty());

        // Rời khỏi phòng conv-a -> tin mới ở conv-a được thông báo VÀ phát thêm summary cho >= 2 hội thoại.
        activeRoomTracker.clearIfMatches("conv-other"); // Không khớp -> vẫn giữ conv-a.
        assertEquals("conv-a", activeRoomTracker.getActiveConversationId());
        activeRoomTracker.clearIfMatches("conv-a");
        assertNull(activeRoomTracker.getActiveConversationId());

        helper.notifyBatch(ME, SESSION_1,
                Collections.singletonList(msg("m-ok-2", "conv-a", BOB, "TEXT", "Hello Bob", 2000L)),
                Collections.emptyList(),
                activeRoomTracker,
                () -> true);

        assertEquals(2, fakeSink.conversations.size());
        NotificationHelper.ConversationNotificationModel posted2 = fakeSink.conversations.get(1);
        assertEquals("conv-a", posted2.conversationId);
        assertEquals("conv-a".hashCode(), posted2.notificationId);
        assertEquals("Bob", posted2.title);
        assertEquals("Hello Bob", posted2.lines.get(0).text);

        assertEquals(1, fakeSink.summaries.size());
        NotificationHelper.SummaryNotificationModel summary = fakeSink.summaries.get(0);
        assertEquals(NotificationHelper.SUMMARY_NOTIFICATION_ID, summary.notificationId);
        assertEquals(NotificationHelper.CHANNEL_MESSAGES, summary.channelId);
        assertEquals("2 new messages", summary.summaryText);
        assertEquals(2, summary.conversationCount);
    }

    @Test
    public void bootstrapNeverFiresNotificationsAndIncrementalMissedCallUsesCallsChannel() {
        NotificationHelper helper = new NotificationHelper(db, fakeSink, fakeStrings, session);
        when(userDao.findById(BOB)).thenReturn(user(BOB, "Bob"));
        when(conversationDao.getConversationSync("conv-1")).thenReturn(conv("conv-1", "DIRECT", null, false));
        when(messageDao.insertRespectingVisibleFrom(any(MessageEntity.class), eq(ME))).thenReturn(true);

        // Bước 1: chưa có sync_state -> chạy bootstrap (có unread) rồi /sync trả rỗng -> TUYỆT ĐỐI không bắn thông báo.
        AtomicReference<SyncStateEntity> stateHolder = new AtomicReference<>(null);
        when(syncStateDao.get(ME)).thenAnswer(i -> stateHolder.get());
        doAnswer(inv -> {
            stateHolder.set(syncState(inv.getArgument(0), inv.getArgument(1)));
            return null;
        }).when(syncStateDao).reset(eq(ME), anyLong(), anyLong());
        doAnswer(inv -> {
            stateHolder.set(syncState(inv.getArgument(0), inv.getArgument(1)));
            return null;
        }).when(syncStateDao).advance(eq(ME), anyLong(), anyLong());

        ChatDtos.BootstrapResponse snap = new ChatDtos.BootstrapResponse();
        snap.syncCursor = 10L;
        snap.me = new AuthDtos.UserDto();
        snap.me.id = ME;
        snap.me.displayName = "Me";
        ChatDtos.ConversationDto bootConv = new ChatDtos.ConversationDto();
        bootConv.id = "conv-1";
        bootConv.type = "DIRECT";
        bootConv.unreadCount = 3;
        ChatDtos.MessageDto bootMsg = new ChatDtos.MessageDto();
        bootMsg.id = 9L;
        bootMsg.clientId = "boot-msg";
        bootMsg.conversationId = "conv-1";
        bootMsg.senderId = BOB;
        bootMsg.type = "TEXT";
        bootMsg.text = "Tin cũ lúc bootstrap";
        bootConv.lastMessage = bootMsg;
        snap.conversations = Collections.singletonList(bootConv);
        when(api.bootstrap()).thenReturn(syncCall(() -> Response.success(snap)));

        ChatDtos.SyncResponse emptySync = new ChatDtos.SyncResponse();
        emptySync.events = Collections.emptyList();
        emptySync.nextCursor = 10L;
        emptySync.hasMore = false;
        when(api.sync(10L, 100)).thenReturn(syncCall(() -> Response.success(emptySync)));
        when(api.markDelivered(anyString(), any())).thenReturn(syncCall(() -> Response.success(null)));

        SyncEventApplier applier = new SyncEventApplier(db);
        SyncEngine engine = new SyncEngine(
                db, api, applier, session, Runnable::run, Runnable::run,
                (delayMs, task) -> () -> { }, fakeClockMs::get);
        engine.setNotificationCallback((sKey, userId, outcome) ->
                helper.notifyBatch(userId, sKey, outcome.newlyInsertedMessages, outcome.missedCalls,
                        activeRoomTracker, () -> sKey.equals(session.sessionKey())));

        assertTrue(engine.syncBlocking(SESSION_1));
        assertTrue("Bootstrap không bao giờ được phát thông báo tin nhắn", fakeSink.conversations.isEmpty());
        assertTrue("Bootstrap không bao giờ được phát thông báo cuộc gọi nhỡ", fakeSink.missedCalls.isEmpty());

        // Bước 2: đồng bộ tăng dần /sync có 1 MESSAGE_CREATED từ Bob + 1 CALL_UPDATED (MISSED từ Bob).
        ChatDtos.SyncResponse incResp = new ChatDtos.SyncResponse();
        incResp.events = Arrays.asList(
                syncEvent(11L, "MESSAGE_CREATED",
                        "{\"id\":101,\"clientId\":\"cli-101\",\"conversationId\":\"conv-1\",\"senderId\":\"u-bob\","
                                + "\"type\":\"TEXT\",\"text\":\"Tin mới qua sync\",\"createdAt\":\"2026-11-22T12:00:00Z\"}"),
                syncEvent(12L, "CALL_UPDATED",
                        "{\"callId\":\"call-missed-1\",\"conversationId\":\"conv-1\",\"state\":\"MISSED\","
                                + "\"endReason\":\"TIMEOUT\",\"wsType\":\"call.ended\","
                                + "\"caller\":{\"id\":\"u-bob\",\"displayName\":\"Bob\"}}")
        );
        incResp.nextCursor = 12L;
        incResp.hasMore = false;
        when(api.sync(10L, 100)).thenReturn(syncCall(() -> Response.success(incResp)));

        assertTrue(engine.syncBlocking(SESSION_1));

        assertEquals(1, fakeSink.conversations.size());
        assertEquals(NotificationHelper.CHANNEL_MESSAGES, fakeSink.conversations.get(0).channelId);
        assertEquals("Tin mới qua sync", fakeSink.conversations.get(0).lines.get(0).text);

        assertEquals(1, fakeSink.missedCalls.size());
        NotificationHelper.MissedCallNotificationModel missed = fakeSink.missedCalls.get(0);
        assertEquals("call-missed-1", missed.callId);
        assertEquals("conv-1", missed.conversationId);
        assertEquals(NotificationHelper.CHANNEL_CALLS, missed.channelId);
        assertEquals("Missed voice call", missed.title);
        assertEquals("Missed call from Bob", missed.body);
    }

    @Test
    public void syncWorkerEnforcesSessionKeySingleFlightAtomicDrainAndLogoutCancelsNotifications() throws Exception {
        // 1) enqueuePeriodic đăng ký đúng tên "periodic-sync:<sessionKey>", KEEP, CONNECTED và đặt sessionKey vào inputData.
        WorkManager wm = mock(WorkManager.class);
        SyncWorker.enqueuePeriodic(wm, SESSION_1);

        ArgumentCaptor<PeriodicWorkRequest> reqCap = ArgumentCaptor.forClass(PeriodicWorkRequest.class);
        verify(wm).enqueueUniquePeriodicWork(
                eq(SyncWorker.queueName(SESSION_1)),
                eq(ExistingPeriodicWorkPolicy.KEEP),
                reqCap.capture());
        PeriodicWorkRequest submitted = reqCap.getValue();
        assertEquals(NetworkType.CONNECTED, submitted.getWorkSpec().constraints.getRequiredNetworkType());
        assertEquals(SESSION_1, submitted.getWorkSpec().input.getString(SyncWorker.KEY_SESSION_KEY));
        assertTrue(submitted.getTags().contains(SyncWorker.TAG_SESSION_PREFIX + SESSION_1));

        // 2) Phiên cũ hoặc thiếu inputData -> trả Result.success() ngay, không bao giờ gọi SyncEngine.
        SyncEngine mockEngine = mock(SyncEngine.class);
        assertEquals(ListenableWorker.Result.success(),
                SyncWorker.executePeriodicSync(session, mockEngine, null));
        assertEquals(ListenableWorker.Result.success(),
                SyncWorker.executePeriodicSync(session, mockEngine, SESSION_2));
        verifyNoInteractions(mockEngine);

        // 3) Chặn HTTP /sync lượt 1 bằng latch để kiểm chứng:
        //    - requestSync() gọi xen ngang giữa chừng trả về NGAY (không chặn),
        //    - chỉ có tối đa 1 người ghi sync_state (single-flight),
        //    - SyncWorker chờ cho tới khi cả lượt bổ sung do requestSync() kích hoạt chạy xong hoàn toàn.
        AtomicReference<SyncStateEntity> stateHolder = new AtomicReference<>(syncState(ME, 20L));
        when(syncStateDao.get(ME)).thenAnswer(i -> stateHolder.get());
        AtomicInteger concurrentCursorWriters = new AtomicInteger(0);
        AtomicInteger maxConcurrentWriters = new AtomicInteger(0);
        doAnswer(inv -> {
            int active = concurrentCursorWriters.incrementAndGet();
            maxConcurrentWriters.accumulateAndGet(active, Math::max);
            try {
                stateHolder.set(syncState(inv.getArgument(0), inv.getArgument(1)));
            } finally {
                concurrentCursorWriters.decrementAndGet();
            }
            return null;
        }).when(syncStateDao).advance(eq(ME), anyLong(), anyLong());

        CountDownLatch pass1Entered = new CountDownLatch(1);
        CountDownLatch releasePass1 = new CountDownLatch(1);
        AtomicInteger syncCallCount = new AtomicInteger(0);
        when(api.sync(anyLong(), anyInt())).thenAnswer(inv -> syncCall(() -> {
            int n = syncCallCount.incrementAndGet();
            if (n == 1) {
                pass1Entered.countDown();
                assertTrue(releasePass1.await(3, TimeUnit.SECONDS));
                ChatDtos.SyncResponse r1 = new ChatDtos.SyncResponse();
                r1.events = Collections.emptyList();
                r1.nextCursor = 21L;
                r1.hasMore = false;
                return Response.success(r1);
            }
            ChatDtos.SyncResponse r2 = new ChatDtos.SyncResponse();
            r2.events = Collections.emptyList();
            r2.nextCursor = 22L;
            r2.hasMore = false;
            return Response.success(r2);
        }));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            SyncEventApplier applier = new SyncEventApplier(db);
            SyncEngine engine = new SyncEngine(
                    db, api, applier, session, pool, Runnable::run,
                    (delayMs, task) -> () -> { }, fakeClockMs::get);

            // Chạy SyncWorker với đúng sessionKey đọc từ submitted inputData.
            String inputKey = submitted.getWorkSpec().input.getString(SyncWorker.KEY_SESSION_KEY);
            Future<ListenableWorker.Result> workerFuture = pool.submit(
                    () -> SyncWorker.executePeriodicSync(session, engine, inputKey));

            assertTrue("Lượt /sync 1 phải bắt đầu", pass1Entered.await(3, TimeUnit.SECONDS));

            // requestSync() đồng thời phải trả về tức thì (< 500ms) mà không đợi lượt 1 kết thúc.
            long t0 = System.nanoTime();
            engine.requestSync();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue("requestSync() phải trả về ngay, thực tế=" + elapsedMs + "ms", elapsedMs < 500L);

            releasePass1.countDown();
            ListenableWorker.Result workerResult = workerFuture.get(5, TimeUnit.SECONDS);
            assertEquals(ListenableWorker.Result.success(), workerResult);
            assertEquals("Phải hoàn thành đủ 2 lượt (lượt ban đầu + lượt resyncRequested) trước khi syncBlocking thoát",
                    2, syncCallCount.get());
            assertEquals("Chỉ 1 writer cho sync_state tại mọi thời điểm", 1, maxConcurrentWriters.get());
            assertEquals(22L, stateHolder.get().getLastSyncEventId());
        } finally {
            pool.shutdownNow();
        }

        // 4) Phiên bị logout giữa lúc /sync đang chạy -> bỏ thông báo trễ và trả success (không retry); cancelAll xóa sạch.
        NotificationHelper helper = new NotificationHelper(db, fakeSink, fakeStrings, session);
        when(userDao.findById(BOB)).thenReturn(user(BOB, "Bob"));
        when(conversationDao.getConversationSync("conv-1")).thenReturn(conv("conv-1", "DIRECT", null, false));
        when(messageDao.insertRespectingVisibleFrom(any(MessageEntity.class), eq(ME))).thenReturn(true);

        when(api.sync(anyLong(), anyInt())).thenReturn(syncCall(() -> {
            // Người dùng đăng xuất ngay khi phản hồi mạng trên đường về.
            activeSessionKey.set(null);
            activeUserId.set(null);
            ChatDtos.SyncResponse late = new ChatDtos.SyncResponse();
            late.events = Collections.singletonList(syncEvent(23L, "MESSAGE_CREATED",
                    "{\"id\":200,\"clientId\":\"cli-late\",\"conversationId\":\"conv-1\",\"senderId\":\"u-bob\","
                            + "\"type\":\"TEXT\",\"text\":\"Tin trễ sau logout\"}"));
            late.nextCursor = 23L;
            late.hasMore = false;
            return Response.success(late);
        }));

        SyncEventApplier applier2 = new SyncEventApplier(db);
        SyncEngine engine2 = new SyncEngine(
                db, api, applier2, session, Runnable::run, Runnable::run,
                (delayMs, task) -> () -> { }, fakeClockMs::get);
        engine2.setNotificationCallback((sKey, userId, outcome) ->
                helper.notifyBatch(userId, sKey, outcome.newlyInsertedMessages, outcome.missedCalls,
                        activeRoomTracker, () -> sKey.equals(session.sessionKey())));

        activeSessionKey.set(SESSION_1);
        activeUserId.set(ME);
        assertEquals(ListenableWorker.Result.success(),
                SyncWorker.executePeriodicSync(session, engine2, SESSION_1));
        assertTrue("Không được phát thông báo khi phiên đã bị xóa giữa chừng", fakeSink.conversations.isEmpty());

        // Kiểm tra lỗi mạng tạm thời trên cùng phiên -> SyncWorker trả Result.retry().
        activeSessionKey.set(SESSION_1);
        activeUserId.set(ME);
        when(api.sync(anyLong(), anyInt())).thenReturn(syncCall(() -> { throw new IOException("mất mạng"); }));
        assertEquals(ListenableWorker.Result.retry(),
                SyncWorker.executePeriodicSync(session, engine2, SESSION_1));

        // Kiểm tra cancelAll() xóa sạch thông báo OS và bộ đệm gom nhóm trong RAM.
        helper.notifyBatch(ME, SESSION_1,
                Collections.singletonList(msg("m-buf", "conv-1", BOB, "TEXT", "Hi", 3000L)),
                Collections.emptyList(), activeRoomTracker, () -> true);
        assertEquals(1, fakeSink.conversations.size());
        helper.cancelAll();
        assertEquals(1, fakeSink.cancelAllCalls);

        // Sau cancelAll(), bộ đệm cũ đã sạch nên tin mới tiếp theo chỉ chứa 1 dòng duy nhất (không lẫn "Hi").
        helper.notifyBatch(ME, SESSION_1,
                Collections.singletonList(msg("m-after", "conv-1", BOB, "TEXT", "Fresh", 4000L)),
                Collections.emptyList(), activeRoomTracker, () -> true);
        assertEquals(1, fakeSink.conversations.get(fakeSink.conversations.size() - 1).lines.size());
        assertEquals("Fresh", fakeSink.conversations.get(fakeSink.conversations.size() - 1).lines.get(0).text);
    }

    @Test
    public void suppressesAlreadyReadMessagesAndResolvesMissedCallOrderingEdgeCases() {
        NotificationHelper helper = new NotificationHelper(db, fakeSink, fakeStrings, session);
        when(userDao.findById(BOB)).thenReturn(user(BOB, "Bob"));
        when(conversationDao.getConversationSync("conv-1")).thenReturn(conv("conv-1", "DIRECT", null, false));
        when(messageDao.insertRespectingVisibleFrom(any(MessageEntity.class), eq(ME))).thenReturn(true);

        // Mốc đã đọc hiện tại của bản thân trên thiết bị khác đã là 100.
        ConversationMemberEntity meMember = new ConversationMemberEntity();
        meMember.setConversationId("conv-1");
        meMember.setUserId(ME);
        meMember.setRole("MEMBER");
        meMember.setLastReadId(100L);
        meMember.setLastDeliveredId(100L);
        when(conversationDao.getMemberSync("conv-1", ME)).thenReturn(meMember);

        SyncEventApplier applier = new SyncEventApplier(db);

        // Lô 1:
        // - Tin #99 <= lastReadId(100) -> không phát thông báo.
        // - Tin #105 > 100 nhưng đi kèm RECEIPT_UPDATED của chính ME nâng readUpTo=105 trong cùng lô -> không phát thông báo.
        // - Tin #106 > 105 -> PHẢI phát thông báo.
        // - CALL_UPDATED(MISSED) không có trường caller đi TRƯỚC MESSAGE_CREATED(type=CALL) -> vẫn đối chiếu được caller=Bob.
        List<ChatDtos.SyncEventDto> batch = Arrays.asList(
                syncEvent(50L, "MESSAGE_CREATED",
                        "{\"id\":99,\"clientId\":\"cli-99\",\"conversationId\":\"conv-1\",\"senderId\":\"u-bob\","
                                + "\"type\":\"TEXT\",\"text\":\"Tin da doc tu truoc\",\"createdAt\":\"2026-11-22T12:00:00Z\"}"),
                syncEvent(51L, "MESSAGE_CREATED",
                        "{\"id\":105,\"clientId\":\"cli-105\",\"conversationId\":\"conv-1\",\"senderId\":\"u-bob\","
                                + "\"type\":\"TEXT\",\"text\":\"Tin doc cung batch\",\"createdAt\":\"2026-11-22T12:00:01Z\"}"),
                syncEvent(52L, "RECEIPT_UPDATED",
                        "{\"conversationId\":\"conv-1\",\"userId\":\"u-me\",\"deliveredUpTo\":105,\"readUpTo\":105}"),
                syncEvent(53L, "MESSAGE_CREATED",
                        "{\"id\":106,\"clientId\":\"cli-106\",\"conversationId\":\"conv-1\",\"senderId\":\"u-bob\","
                                + "\"type\":\"TEXT\",\"text\":\"Tin chua doc\",\"createdAt\":\"2026-11-22T12:00:02Z\"}"),
                syncEvent(54L, "CALL_UPDATED",
                        "{\"callId\":\"call-legacy-order\",\"conversationId\":\"conv-1\",\"state\":\"MISSED\","
                                + "\"endReason\":\"TIMEOUT\",\"wsType\":\"call.ended\"}"),
                syncEvent(55L, "MESSAGE_CREATED",
                        "{\"id\":107,\"clientId\":\"call-legacy-order\",\"conversationId\":\"conv-1\",\"senderId\":\"u-bob\","
                                + "\"type\":\"CALL\",\"call\":{\"id\":\"call-legacy-order\",\"state\":\"MISSED\",\"media\":\"VOICE\",\"endReason\":\"TIMEOUT\"},"
                                + "\"createdAt\":\"2026-11-22T12:00:03Z\"}")
        );

        SyncEventApplier.SyncBatchOutcome outcome = applier.applyBatchDetailed(ME, batch);
        helper.notifyBatch(ME, SESSION_1, outcome.newlyInsertedMessages, outcome.missedCalls,
                activeRoomTracker, () -> true);

        // Chỉ tin #106 xuất hiện trên kênh messages (tin #99, #105 và tin CALL #107 đều bị lọc).
        assertEquals(1, fakeSink.conversations.size());
        assertEquals(1, fakeSink.conversations.get(0).lines.size());
        assertEquals("Tin chua doc", fakeSink.conversations.get(0).lines.get(0).text);

        // Cuộc gọi nhỡ call-legacy-order vẫn được phát trên kênh calls.
        assertEquals(1, fakeSink.missedCalls.size());
        assertEquals("call-legacy-order", fakeSink.missedCalls.get(0).callId);
        assertEquals(NotificationHelper.CHANNEL_CALLS, fakeSink.missedCalls.get(0).channelId);
        assertEquals("Missed call from Bob", fakeSink.missedCalls.get(0).body);
    }

    // ======================== HELPERS ========================

    private static UserEntity user(String id, String name) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setDisplayName(name);
        return u;
    }

    private static ConversationEntity conv(String id, String type, String title, boolean muted) {
        ConversationEntity c = new ConversationEntity();
        c.setId(id);
        c.setType(type);
        c.setTitle(title);
        c.setMuted(muted);
        return c;
    }

    private static MessageEntity msg(
            String clientId, String convId, String senderId, String type, String text, long createdAt) {
        MessageEntity m = new MessageEntity();
        m.setClientId(clientId);
        m.setConversationId(convId);
        m.setSenderId(senderId);
        m.setType(type);
        m.setText(text);
        m.setCreatedAt(createdAt);
        m.setStatus("SENT");
        return m;
    }

    private static SyncStateEntity syncState(String userId, long cursor) {
        SyncStateEntity s = new SyncStateEntity();
        s.setUserId(userId);
        s.setLastSyncEventId(cursor);
        s.setLastSyncAt(1000L);
        return s;
    }

    private static ChatDtos.SyncEventDto syncEvent(long id, String type, String payloadJson) {
        ChatDtos.SyncEventDto e = new ChatDtos.SyncEventDto();
        e.id = id;
        e.eventType = type;
        e.payload = JsonParser.parseString(payloadJson);
        e.createdAt = "2026-11-22T12:00:00Z";
        return e;
    }

    private static <T> Call<T> syncCall(ThrowingSupplier<Response<T>> supplier) {
        return new Call<T>() {
            private boolean executed;
            private boolean canceled;

            @Override
            public Response<T> execute() throws IOException {
                executed = true;
                try {
                    return supplier.get();
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override public void enqueue(retrofit2.Callback<T> callback) { throw new UnsupportedOperationException(); }
            @Override public boolean isExecuted() { return executed; }
            @Override public void cancel() { canceled = true; }
            @Override public boolean isCanceled() { return canceled; }
            @Override public Call<T> clone() { return syncCall(supplier); }
            @Override public Request request() { return new Request.Builder().url("http://10.0.2.2:8080/api/sync").build(); }
            @Override public okio.Timeout timeout() { return okio.Timeout.NONE; }
        };
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /** Fake NotificationSink ghi lại mọi lệnh tạo kênh, đăng/hủy thông báo để kiểm chứng chính xác trên JVM. */
    private static final class FakeNotificationSink implements NotificationHelper.NotificationSink {
        final List<NotificationHelper.ChannelSpec> registeredChannels = new ArrayList<>();
        final List<NotificationHelper.ConversationNotificationModel> conversations = new ArrayList<>();
        final List<NotificationHelper.SummaryNotificationModel> summaries = new ArrayList<>();
        final List<NotificationHelper.MissedCallNotificationModel> missedCalls = new ArrayList<>();
        final List<NotificationHelper.IncomingCallNotificationModel> incomingCalls = new ArrayList<>();
        final List<Integer> canceledIds = new ArrayList<>();
        int cancelAllCalls = 0;

        @Override
        public void ensureChannels(List<NotificationHelper.ChannelSpec> channels) {
            registeredChannels.clear();
            registeredChannels.addAll(channels);
        }

        @Override
        public void postConversation(NotificationHelper.ConversationNotificationModel model) {
            conversations.add(model);
        }

        @Override
        public void postSummary(NotificationHelper.SummaryNotificationModel model) {
            summaries.add(model);
        }

        @Override
        public void postMissedCall(NotificationHelper.MissedCallNotificationModel model) {
            missedCalls.add(model);
        }

        @Override
        public void postIncomingCall(NotificationHelper.IncomingCallNotificationModel model) {
            incomingCalls.add(model);
        }

        @Override
        public void cancel(int notificationId) {
            canceledIds.add(notificationId);
        }

        @Override
        public void cancelAll() {
            cancelAllCalls++;
        }
    }
}
