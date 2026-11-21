package com.utechat.data.sync;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.content.SharedPreferences;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.utechat.data.local.AppDatabase;
import com.utechat.data.local.dao.ContactDao;
import com.utechat.data.local.dao.ConversationDao;
import com.utechat.data.local.dao.MessageDao;
import com.utechat.data.local.dao.SyncStateDao;
import com.utechat.data.local.dao.UserDao;
import com.utechat.data.local.entity.ContactEntity;
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.ConversationMemberEntity;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.local.entity.SyncStateEntity;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.remote.api.ChatApi;
import com.utechat.data.remote.model.AuthDtos;
import com.utechat.data.remote.model.ChatDtos;
import com.utechat.data.session.SessionManager;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Bộ kiểm thử JVM cho toàn bộ tầng đồng bộ Phase 5 (SyncEngine, SyncEventApplier, ChatWebSocketManager, SessionManager):
 * xác minh single-flight, hồi phục 410, đủ 8 loại sự kiện Chat+Contact+CALL, cô lập visibleFromMessageId,
 * timer barrier retryAfterMs, heartbeat JSON 25s, bền vững sessionKey qua restart và chặn phiên cũ.
 */
public class SyncEngineAndApplierTest {
    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();

    private static final Gson GSON = new Gson();
    private static final String ME = "u-me";
    private static final String BOB = "u-bob";
    private static final String CONV_ID = "conv-1";

    private AppDatabase db;
    private UserDao userDao;
    private ContactDao contactDao;
    private ConversationDao conversationDao;
    private MessageDao messageDao;
    private SyncStateDao syncStateDao;
    private ChatApi api;
    private SessionManager session;

    private final AtomicReference<String> currentSessionKey = new AtomicReference<>("1:" + ME);
    private final AtomicReference<String> currentToken = new AtomicReference<>("jwt-token-1");
    private final AtomicReference<String> currentVersion = new AtomicReference<>("1");
    private final AtomicLong fakeClockMs = new AtomicLong(1_731_300_000_000L);
    private FakeDelayScheduler fakeScheduler;

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
        fakeScheduler = new FakeDelayScheduler();

        when(db.userDao()).thenReturn(userDao);
        when(db.contactDao()).thenReturn(contactDao);
        when(db.conversationDao()).thenReturn(conversationDao);
        when(db.messageDao()).thenReturn(messageDao);
        when(db.syncStateDao()).thenReturn(syncStateDao);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(db).runInTransaction(any(Runnable.class));

        when(session.hasToken()).thenAnswer(i -> currentToken.get() != null);
        when(session.getToken()).thenAnswer(i -> currentToken.get());
        when(session.versionKey()).thenAnswer(i -> currentVersion.get());
        when(session.sessionKey()).thenAnswer(i -> currentSessionKey.get());
        when(session.getUserId()).thenAnswer(i -> currentToken.get() != null ? ME : null);
        when(session.runIfCurrent(nullable(String.class), any(Runnable.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Runnable action = inv.getArgument(1);
            if (key == null || !key.equals(currentSessionKey.get())) return false;
            action.run();
            return true;
        });
        when(session.clearIfCurrent(anyString(), anyString())).thenAnswer(inv -> {
            String tok = inv.getArgument(0);
            String ver = inv.getArgument(1);
            if (tok.equals(currentToken.get()) && ver.equals(currentVersion.get())) {
                currentToken.set(null);
                currentSessionKey.set(null);
                currentVersion.set(String.valueOf(Long.parseLong(ver) + 1));
                return true;
            }
            return false;
        });
    }

    @Test
    public void singleFlightCoalescesBurstAndAdvancesCursorOnlyAfterSyncTransaction() throws Exception {
        // Giả lập trạng thái Room đang ở cursor = 10.
        SyncStateEntity initialState = syncState(ME, 10L);
        when(syncStateDao.get(ME)).thenReturn(initialState);

        // Chặn /api/sync lần 1 bằng latch để kiểm chứng các lời gọi requestSync() dồn dập KHÔNG chạy song song.
        CountDownLatch firstSyncStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSync = new CountDownLatch(1);
        AtomicInteger activeInFlight = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger syncCalls = new AtomicInteger(0);

        List<String> appliedTitles = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            ConversationEntity c = inv.getArgument(0);
            appliedTitles.add(c.getTitle());
            return null;
        }).when(conversationDao).insert(any(ConversationEntity.class));

        when(api.sync(anyLong(), anyInt())).thenAnswer(inv -> {
            int callNo = syncCalls.incrementAndGet();
            return syncCall(() -> {
                int nowActive = activeInFlight.incrementAndGet();
                maxConcurrent.accumulateAndGet(nowActive, Math::max);
                try {
                    if (callNo == 1) {
                        firstSyncStarted.countDown();
                        assertTrue(releaseFirstSync.await(3, TimeUnit.SECONDS));
                        ChatDtos.SyncResponse r1 = new ChatDtos.SyncResponse();
                        r1.events = Arrays.asList(
                                convEvent(11L, "Tên Cũ 11", false),
                                convEvent(12L, "Tên Mới Nhất 12", true)
                        );
                        r1.nextCursor = 12L;
                        r1.hasMore = false;
                        return Response.success(r1);
                    }
                    ChatDtos.SyncResponse r2 = new ChatDtos.SyncResponse();
                    r2.events = Collections.emptyList();
                    r2.nextCursor = 12L;
                    r2.hasMore = false;
                    return Response.success(r2);
                } finally {
                    activeInFlight.decrementAndGet();
                }
            });
        });

        ExecutorService netExec = Executors.newFixedThreadPool(2);
        try {
            SyncEventApplier applier = new SyncEventApplier(db);
            SyncEngine engine = new SyncEngine(db, api, applier, session, netExec, Runnable::run, fakeScheduler, fakeClockMs::get);
            TypingTracker typing = new TypingTracker(fakeScheduler, fakeClockMs::get);
            AtomicReference<WebSocketListener> capturedListener = new AtomicReference<>();
            WebSocket fakeWs = mock(WebSocket.class);
            WebSocket.Factory wsFactory = (req, listener) -> {
                capturedListener.set(listener);
                return fakeWs;
            };

            ChatWebSocketManager wsManager = new ChatWebSocketManager(
                    session, engine, typing, wsFactory, fakeScheduler, fakeClockMs::get, "http://10.0.2.2:8080/", false);
            wsManager.connectIfAuthenticated();
            assertNotNull(capturedListener.get());

            // Mở WS -> kích hoạt pass sync 1 đang bị giữ trên latch.
            capturedListener.get().onOpen(fakeWs, okUpgradeResponse());
            assertTrue(firstSyncStarted.await(3, TimeUnit.SECONDS));

            // Đẩy một khung WS conversation.updated + contact.updated dồn dập trong khi sync 1 đang chạy.
            capturedListener.get().onMessage(fakeWs,
                    "{\"v\":1,\"type\":\"conversation.updated\",\"syncEventId\":99,\"payload\":{\"id\":\"conv-1\",\"type\":\"GROUP\",\"title\":\"WS-Payload-Should-Not-Write\"}}");
            capturedListener.get().onMessage(fakeWs,
                    "{\"v\":1,\"type\":\"contact.updated\",\"syncEventId\":100,\"payload\":{\"contactUserId\":\"u-bob\",\"removed\":true}}");

            // Khung WS tuyệt đối không được ghi Room hay nâng SyncStateDao.
            verify(conversationDao, never()).insert(any());
            verify(contactDao, never()).deleteContact(anyString());
            verify(syncStateDao, never()).advance(anyString(), anyLong(), anyLong());

            // Gọi thêm requestSync dồn dập -> tất cả gộp vào 1 cờ resyncRequested, KHÔNG chạy song song.
            engine.requestSync();
            engine.requestSync();
            releaseFirstSync.countDown();

            // Chờ trạng thái CONNECTED báo hiệu toàn bộ single-flight đã chạy xong.
            long deadline = System.currentTimeMillis() + 3000L;
            while (wsManager.getConnectionState().getValue() != WsConnectionState.CONNECTED
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(WsConnectionState.CONNECTED, wsManager.getConnectionState().getValue());
            assertEquals("Không bao giờ chạy >1 /api/sync song song", 1, maxConcurrent.get());

            // Lô /api/sync phải được áp đúng thứ tự tăng dần (11 rồi 12), không bị WS payload ghi đè.
            assertEquals(Arrays.asList("Tên Cũ 11", "Tên Mới Nhất 12"), appliedTitles);

            // Xác minh applyBatch và advance() nằm trong cùng 1 transaction và advance(12) chạy sau insert.
            InOrder order = inOrder(db, conversationDao, syncStateDao);
            order.verify(db).runInTransaction(any(Runnable.class));
            order.verify(conversationDao, times(2)).insert(any(ConversationEntity.class));
            order.verify(syncStateDao).advance(eq(ME), eq(12L), anyLong());
        } finally {
            netExec.shutdownNow();
        }
    }

    @Test
    public void sync410CursorExpiredTriggersRebootstrapAndPreservesPendingLocalMessages() throws Exception {
        SyncStateEntity oldState = syncState(ME, 5L);
        SyncStateEntity resetState = syncState(ME, 50L);
        AtomicReference<SyncStateEntity> stateHolder = new AtomicReference<>(oldState);
        when(syncStateDao.get(ME)).thenAnswer(i -> stateHolder.get());
        doAnswer(inv -> {
            stateHolder.set(syncState(inv.getArgument(0), inv.getArgument(1)));
            return null;
        }).when(syncStateDao).reset(eq(ME), anyLong(), anyLong());

        // Lần gọi /sync(after=5) trả 410 SYNC_CURSOR_EXPIRED; sau re-bootstrap sẽ gọi /sync(after=50) trả 200.
        Response<ChatDtos.SyncResponse> gone410 = Response.error(
                410,
                ResponseBody.create("{\"error\":{\"code\":\"SYNC_CURSOR_EXPIRED\",\"message\":\"Expired\"}}",
                        MediaType.parse("application/json")));
        ChatDtos.SyncResponse afterResetResp = new ChatDtos.SyncResponse();
        afterResetResp.events = Collections.emptyList();
        afterResetResp.nextCursor = 50L;
        afterResetResp.hasMore = false;

        when(api.sync(5L, 100)).thenReturn(syncCall(() -> gone410));
        when(api.sync(50L, 100)).thenReturn(syncCall(() -> Response.success(afterResetResp)));

        ChatDtos.BootstrapResponse snap = new ChatDtos.BootstrapResponse();
        snap.syncCursor = 50L;
        snap.me = new AuthDtos.UserDto();
        snap.me.id = ME;
        snap.me.displayName = "Alice";
        snap.conversations = Collections.emptyList();
        snap.friends = Collections.emptyList();
        snap.blocked = Collections.emptyList();
        snap.contactRequests = Collections.emptyList();
        when(api.bootstrap()).thenReturn(syncCall(() -> Response.success(snap)));

        SyncEventApplier applier = new SyncEventApplier(db);
        SyncEngine engine = new SyncEngine(db, api, applier, session, Runnable::run, Runnable::run, fakeScheduler, fakeClockMs::get);
        engine.requestSync();

        verify(api, times(1)).bootstrap();
        verify(syncStateDao).reset(eq(ME), eq(50L), anyLong());
        verify(api).sync(50L, 100);
        verify(syncStateDao).advance(eq(ME), eq(50L), anyLong());
        // Bảo đảm bootstrap hồi phục KHÔNG bao giờ xóa tin cục bộ SENDING/FAILED (serverId IS NULL).
        verify(messageDao, never()).deleteFailedLocal(anyString());
        verify(messageDao, never()).purgeBeforeVisibleId(anyString(), anyLong());
    }

    @Test
    public void appliesAllChatAndContactEventsIdempotentlyIncludingRemovedAndUnfriend() {
        SyncEventApplier applier = new SyncEventApplier(db);

        // 1) Kiểm tra applyBootstrapSnapshot suy luận trạng thái READ / DELIVERED / SENT cho lastMessage của chính mình.
        ChatDtos.BootstrapResponse snap = new ChatDtos.BootstrapResponse();
        snap.syncCursor = 100L;
        snap.conversations = Arrays.asList(
                bootstrapConv("c-read", 10L, 10L, 10L),
                bootstrapConv("c-deliv", 20L, 20L, 15L),
                bootstrapConv("c-sent", 30L, 25L, 15L)
        );
        when(messageDao.insertRespectingVisibleFrom(any(MessageEntity.class), eq(ME))).thenReturn(true);

        applier.applyBootstrapSnapshot(ME, snap, 1000L);

        ArgumentCaptor<MessageEntity> bootMsgCap = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageDao, times(3)).insertRespectingVisibleFrom(bootMsgCap.capture(), eq(ME));
        Map<String, String> bootStatusByConv = new HashMap<>();
        for (MessageEntity m : bootMsgCap.getAllValues()) {
            bootStatusByConv.put(m.getConversationId(), m.getStatus());
        }
        assertEquals("READ", bootStatusByConv.get("c-read"));
        assertEquals("DELIVERED", bootStatusByConv.get("c-deliv"));
        assertEquals("SENT", bootStatusByConv.get("c-sent"));

        reset(messageDao, contactDao, userDao, conversationDao);
        when(messageDao.insertRespectingVisibleFrom(any(MessageEntity.class), eq(ME))).thenReturn(true);

        // 2) Chuẩn bị tin CALL có sẵn trong cache để kiểm tra CALL_UPDATED với payload giống hệt V2 seed.
        MessageEntity cachedCall = new MessageEntity();
        cachedCall.setLocalId(77L);
        cachedCall.setType("CALL");
        cachedCall.setAttachmentJson("{\"call\":{\"id\":\"dddddddd-0001-0000-0000-000000000001\",\"state\":\"RINGING\",\"media\":\"VIDEO\"},\"extra\":\"keep\"}");
        when(messageDao.findCallMessagesSync()).thenReturn(Collections.singletonList(cachedCall));

        // 3) Chạy một lô gồm ĐỦ 8 loại sự kiện (Chat + CALL_UPDATED + DEVICE_REVOKED + Contact).
        List<ChatDtos.SyncEventDto> batch = new ArrayList<>();
        // MESSAGE_CREATED: IMAGE (bảo toàn đủ metadata vào attachmentJson, không hạ cấp về TEXT).
        batch.add(event(101L, "MESSAGE_CREATED",
                "{\"id\":501,\"clientId\":\"cli-img\",\"conversationId\":\"conv-1\",\"senderId\":\"u-bob\",\"type\":\"IMAGE\","
                        + "\"mediaUrl\":\"https://cdn.test/a.jpg\",\"mime\":\"image/jpeg\",\"sizeBytes\":12345,\"width\":800,\"height\":600,"
                        + "\"createdAt\":\"2026-11-20T10:00:00Z\"}"));
        // MESSAGE_CREATED: LOCATION
        batch.add(event(102L, "MESSAGE_CREATED",
                "{\"id\":502,\"clientId\":\"cli-loc\",\"conversationId\":\"conv-1\",\"senderId\":\"u-bob\",\"type\":\"LOCATION\","
                        + "\"latitude\":10.8506,\"longitude\":106.7719,\"createdAt\":\"2026-11-20T10:00:01Z\"}"));
        // MESSAGE_CREATED: CALL
        batch.add(event(103L, "MESSAGE_CREATED",
                "{\"id\":503,\"clientId\":\"cli-call\",\"conversationId\":\"conv-1\",\"senderId\":\"u-bob\",\"type\":\"CALL\","
                        + "\"call\":{\"id\":\"call-99\",\"state\":\"ENDED\",\"media\":\"AUDIO\",\"endReason\":\"COMPLETED\"},"
                        + "\"createdAt\":\"2026-11-20T10:00:02Z\"}"));
        // CONVERSATION_UPDATED
        batch.add(convEvent(104L, "Nhóm Cập Nhật", false));
        // RECEIPT_UPDATED từ BOB nâng READ tới 500
        batch.add(event(105L, "RECEIPT_UPDATED",
                "{\"conversationId\":\"conv-1\",\"userId\":\"u-bob\",\"deliveredUpTo\":500,\"readUpTo\":500}"));
        // DEVICE_REVOKED của thiết bị khác -> no-op hợp lệ, KHÔNG ném lỗi.
        batch.add(event(106L, "DEVICE_REVOKED", "{\"deviceIdHash\":\"other-device-hash\"}"));
        // CALL_UPDATED khớp bản ghi V2 seed -> cập nhật đúng state & endReason, giữ nguyên media & extra.
        batch.add(event(107L, "CALL_UPDATED",
                "{\"callId\":\"dddddddd-0001-0000-0000-000000000001\",\"state\":\"MISSED\",\"endReason\":\"NO_ANSWER\"}"));
        // CALL_UPDATED không có trong cache -> handled no-op, không ném lỗi.
        batch.add(event(108L, "CALL_UPDATED",
                "{\"callId\":\"call-absent\",\"state\":\"ENDED\",\"endReason\":\"CANCELED\"}"));
        // CONTACT_REQUEST_CREATED (PENDING)
        batch.add(event(109L, "CONTACT_REQUEST_CREATED",
                "{\"id\":\"req-1\",\"requester\":{\"id\":\"u-bob\",\"displayName\":\"Bob\"},\"addressee\":{\"id\":\"u-me\",\"displayName\":\"Me\"},"
                        + "\"status\":\"PENDING\",\"createdAt\":\"2026-11-20T10:01:00Z\"}"));
        // CONTACT_REQUEST_UPDATED (ACCEPTED) -> chỉ xóa lời mời chờ, KHÔNG tự tạo contact giả.
        batch.add(event(110L, "CONTACT_REQUEST_UPDATED",
                "{\"id\":\"req-1\",\"requester\":{\"id\":\"u-bob\",\"displayName\":\"Bob\"},\"addressee\":{\"id\":\"u-me\",\"displayName\":\"Me\"},"
                        + "\"status\":\"ACCEPTED\"}"));
        // CONTACT_UPDATED: removed = true -> deleteContact
        batch.add(event(111L, "CONTACT_UPDATED", "{\"contactUserId\":\"u-removed\",\"isFriend\":false,\"blocked\":false,\"removed\":true}"));
        // CONTACT_UPDATED: unfriend (isFriend=false && blocked=false) -> deleteContact
        batch.add(event(112L, "CONTACT_UPDATED", "{\"contactUserId\":\"u-unfriend\",\"isFriend\":false,\"blocked\":false,\"removed\":false}"));
        // CONTACT_UPDATED: blocked=true -> upsert contact(blocked=true) + upsertPreservingPrivacy(user)
        batch.add(event(113L, "CONTACT_UPDATED",
                "{\"contactUserId\":\"u-blocked\",\"isFriend\":false,\"blocked\":true,\"removed\":false,"
                        + "\"user\":{\"id\":\"u-blocked\",\"displayName\":\"Blocked User\",\"blocked\":true}}"));

        Map<String, Long> highestIncoming = applier.applyBatch(ME, batch);
        assertEquals(Long.valueOf(503L), highestIncoming.get("conv-1"));

        // Kiểm tra metadata không bị mất cho IMAGE, LOCATION, CALL.
        ArgumentCaptor<MessageEntity> msgCap = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageDao, times(3)).insertRespectingVisibleFrom(msgCap.capture(), eq(ME));
        MessageEntity imgMsg = msgCap.getAllValues().get(0);
        assertEquals("IMAGE", imgMsg.getType());
        assertTrue(imgMsg.getAttachmentJson().contains("\"mediaUrl\":\"https://cdn.test/a.jpg\""));
        assertTrue(imgMsg.getAttachmentJson().contains("\"width\":800"));

        MessageEntity locMsg = msgCap.getAllValues().get(1);
        assertEquals("LOCATION", locMsg.getType());
        assertTrue(locMsg.getAttachmentJson().contains("10.8506"));

        MessageEntity callMsg = msgCap.getAllValues().get(2);
        assertEquals("CALL", callMsg.getType());
        assertTrue(callMsg.getAttachmentJson().contains("\"endReason\":\"COMPLETED\""));

        // Kiểm tra CALL_UPDATED chỉ cập nhật state/endReason và bảo toàn media/extra.
        ArgumentCaptor<String> callJsonCap = ArgumentCaptor.forClass(String.class);
        verify(messageDao).updateAttachmentJson(eq(77L), callJsonCap.capture());
        JsonObject updatedCallRoot = JsonParser.parseString(callJsonCap.getValue()).getAsJsonObject();
        assertEquals("keep", updatedCallRoot.get("extra").getAsString());
        JsonObject updatedCall = updatedCallRoot.getAsJsonObject("call");
        assertEquals("VIDEO", updatedCall.get("media").getAsString());
        assertEquals("MISSED", updatedCall.get("state").getAsString());
        assertEquals("NO_ANSWER", updatedCall.get("endReason").getAsString());

        // Kiểm tra RECEIPT_UPDATED nâng đơn điệu cho tin gửi của mình.
        verify(messageDao).upgradeReceiptsForSender("conv-1", ME, 500L, "DELIVERED");
        verify(messageDao).upgradeReceiptsForSender("conv-1", ME, 500L, "READ");

        // Kiểm tra Contact events: xóa req-1 khi ACCEPTED, xóa u-removed/u-unfriend, upsert u-blocked.
        verify(contactDao).insertRequest(argThat(r -> "req-1".equals(r.getId())));
        verify(contactDao).deleteRequest("req-1");
        verify(contactDao).deleteContact("u-removed");
        verify(contactDao).deleteContact("u-unfriend");
        verify(contactDao).insert(argThat((ContactEntity c) -> "u-blocked".equals(c.getContactUserId()) && c.getBlocked()));
        verify(userDao, atLeastOnce()).upsertPreservingPrivacy(argThat((UserEntity u) -> "u-blocked".equals(u.getId())));
    }

    @Test
    public void readdPurgesOldHistoryWithoutLoweringVisibleFromMessageIdAndIgnoresLateLowerCutoff() {
        // Giữ trạng thái thành viên trong bộ nhớ để thực thi đúng logic đơn điệu của ConversationDao.upsertMemberMonotonic.
        AtomicReference<ConversationMemberEntity> storedMe = new AtomicReference<>();
        ConversationMemberEntity initial = new ConversationMemberEntity();
        initial.setConversationId(CONV_ID);
        initial.setUserId(ME);
        initial.setRole("MEMBER");
        initial.setVisibleFromMessageId(10L);
        storedMe.set(initial);

        when(conversationDao.getMemberSync(CONV_ID, ME)).thenAnswer(i -> storedMe.get());
        doCallRealMethod().when(conversationDao).upsertMemberMonotonic(any(ConversationMemberEntity.class));
        doAnswer(inv -> {
            ConversationMemberEntity m = inv.getArgument(0);
            if (ME.equals(m.getUserId())) storedMe.set(m);
            return null;
        }).when(conversationDao).insertMember(any(ConversationMemberEntity.class));

        // Giả lập MessageDao.insertRespectingVisibleFrom thật qua findMemberVisibleFrom.
        when(messageDao.findMemberVisibleFrom(CONV_ID, ME)).thenAnswer(i ->
                storedMe.get() != null ? storedMe.get().getVisibleFromMessageId() : null);
        when(messageDao.insertRespectingVisibleFrom(any(MessageEntity.class), eq(ME))).thenCallRealMethod();

        SyncEventApplier applier = new SyncEventApplier(db);

        // 1) Được thêm lại nhóm với visibleFromMessageId = 25 -> phải purge <= 25 và recompute lastMessage.
        ChatDtos.SyncEventDto readdEvent = event(201L, "CONVERSATION_UPDATED",
                "{\"id\":\"conv-1\",\"type\":\"GROUP\",\"title\":\"Nhóm\",\"visibleFromMessageId\":25,"
                        + "\"members\":[{\"userId\":\"u-me\",\"displayName\":\"Me\",\"role\":\"MEMBER\",\"visibleFromMessageId\":25}]}");
        applier.applyBatch(ME, Collections.singletonList(readdEvent));

        assertEquals(Long.valueOf(25L), storedMe.get().getVisibleFromMessageId());
        verify(messageDao, times(1)).purgeBeforeVisibleId(CONV_ID, 25L);
        verify(conversationDao, times(1)).recomputeLastMessageAfterPurge(CONV_ID, 25L);

        // 2) Sự kiện đến trễ mang visibleFromMessageId = 12 -> KHÔNG được hạ từ 25 xuống 12, KHÔNG purge lại với 12.
        ChatDtos.SyncEventDto lateLowerEvent = event(202L, "CONVERSATION_UPDATED",
                "{\"id\":\"conv-1\",\"type\":\"GROUP\",\"title\":\"Nhóm\",\"visibleFromMessageId\":12,"
                        + "\"members\":[{\"userId\":\"u-me\",\"displayName\":\"Me\",\"role\":\"MEMBER\",\"visibleFromMessageId\":12}]}");
        applier.applyBatch(ME, Collections.singletonList(lateLowerEvent));

        assertEquals(Long.valueOf(25L), storedMe.get().getVisibleFromMessageId());
        verify(messageDao, never()).purgeBeforeVisibleId(CONV_ID, 12L);

        // 3) Tin nhắn cũ serverId = 25 (<= 25) bị insertRespectingVisibleFrom bỏ qua hoàn toàn.
        ChatDtos.SyncEventDto staleMsgEvent = event(203L, "MESSAGE_CREATED",
                "{\"id\":25,\"clientId\":\"cli-25\",\"conversationId\":\"conv-1\",\"senderId\":\"u-bob\",\"type\":\"TEXT\",\"text\":\"Old\"}");
        applier.applyBatch(ME, Collections.singletonList(staleMsgEvent));
        verify(messageDao, never()).upsert(argThat(m -> Long.valueOf(25L).equals(m.getServerId())));
    }

    @Test
    public void barrierRetryDoesNotSpinAndEventuallyCatchesUpWithoutNewWs() {
        AtomicReference<SyncStateEntity> stateHolder = new AtomicReference<>(syncState(ME, 10L));
        when(syncStateDao.get(ME)).thenAnswer(i -> stateHolder.get());
        doAnswer(inv -> {
            stateHolder.set(syncState(inv.getArgument(0), inv.getArgument(1)));
            return null;
        }).when(syncStateDao).advance(eq(ME), anyLong(), anyLong());
        when(messageDao.insertRespectingVisibleFrom(any(MessageEntity.class), eq(ME))).thenReturn(true);

        // Phản hồi 1: rỗng, hasMore=false, retryAfterMs=1000 (barrier đang giữ sự kiện).
        ChatDtos.SyncResponse heldResp = new ChatDtos.SyncResponse();
        heldResp.events = Collections.emptyList();
        heldResp.nextCursor = 10L;
        heldResp.hasMore = false;
        heldResp.retryAfterMs = 1000L;

        // Phản hồi 2: sự kiện đã sẵn sàng, retryAfterMs=null.
        ChatDtos.SyncResponse readyResp = new ChatDtos.SyncResponse();
        readyResp.events = Collections.singletonList(event(11L, "MESSAGE_CREATED",
                "{\"id\":901,\"clientId\":\"cli-901\",\"conversationId\":\"conv-1\",\"senderId\":\"u-bob\",\"type\":\"TEXT\",\"text\":\"Ready\"}"));
        readyResp.nextCursor = 11L;
        readyResp.hasMore = false;
        readyResp.retryAfterMs = null;

        AtomicInteger calls = new AtomicInteger();
        when(api.sync(anyLong(), anyInt())).thenAnswer(inv -> syncCall(() ->
                calls.incrementAndGet() == 1 ? Response.success(heldResp) : Response.success(readyResp)));
        when(api.markDelivered(anyString(), any())).thenReturn(syncCall(() -> Response.success(null)));

        SyncEventApplier applier = new SyncEventApplier(db);
        SyncEngine engine = new SyncEngine(db, api, applier, session, Runnable::run, Runnable::run, fakeScheduler, fakeClockMs::get);
        TypingTracker typing = new TypingTracker(fakeScheduler, fakeClockMs::get);
        AtomicReference<WebSocketListener> wsListener = new AtomicReference<>();
        WebSocket fakeWs = mock(WebSocket.class);
        ChatWebSocketManager wsManager = new ChatWebSocketManager(
                session, engine, typing, (req, l) -> { wsListener.set(l); return fakeWs; },
                fakeScheduler, fakeClockMs::get, "http://10.0.2.2:8080/", false);

        wsManager.connectIfAuthenticated();
        wsListener.get().onOpen(fakeWs, okUpgradeResponse());

        // Sau lần 1: KHÔNG spin, chỉ gọi đúng 1 lần /sync, giữ RECONNECTING và đặt đúng 1 hẹn giờ 1000ms.
        assertEquals(1, calls.get());
        assertEquals(WsConnectionState.RECONNECTING, wsManager.getConnectionState().getValue());
        assertEquals(1, fakeScheduler.activeTaskCountWithDelay(1000L));

        // Kích hoạt hẹn giờ barrier (KHÔNG có khung WS thứ hai) -> áp dụng tin 901 và chuyển sang CONNECTED.
        fakeScheduler.runNextWithDelay(1000L);
        assertEquals(2, calls.get());
        assertEquals(WsConnectionState.CONNECTED, wsManager.getConnectionState().getValue());
        verify(syncStateDao).advance(eq(ME), eq(11L), anyLong());

        // Kiểm tra hủy timer khi disconnect: tạo lại một lần barrier nữa rồi disconnect() trước khi timer chạy.
        calls.set(0);
        engine.requestSync();
        assertEquals(1, fakeScheduler.activeTaskCountWithDelay(1000L));
        wsManager.disconnect();
        assertEquals(0, fakeScheduler.activeTaskCountWithDelay(1000L));
    }

    @Test
    public void applicationHeartbeatHasOneTimerPerSocketAndStopsWithSession() {
        SyncStateEntity st = syncState(ME, 20L);
        when(syncStateDao.get(ME)).thenReturn(st);
        ChatDtos.SyncResponse empty = new ChatDtos.SyncResponse();
        empty.events = Collections.emptyList();
        empty.nextCursor = 20L;
        empty.hasMore = false;
        when(api.sync(anyLong(), anyInt())).thenReturn(syncCall(() -> Response.success(empty)));

        SyncEventApplier applier = new SyncEventApplier(db);
        SyncEngine engine = new SyncEngine(db, api, applier, session, Runnable::run, Runnable::run, fakeScheduler, fakeClockMs::get);
        TypingTracker typing = new TypingTracker(fakeScheduler, fakeClockMs::get);

        List<WebSocketListener> listeners = new ArrayList<>();
        List<WebSocket> sockets = new ArrayList<>();
        ChatWebSocketManager wsManager = new ChatWebSocketManager(
                session, engine, typing,
                (req, l) -> {
                    // Xác minh JWT chỉ nằm ở header Authorization, tuyệt đối không nằm trên URL.
                    assertNull(req.url().query());
                    assertEquals("Bearer " + currentToken.get(), req.header("Authorization"));
                    WebSocket ws = mock(WebSocket.class);
                    when(ws.send(anyString())).thenReturn(true);
                    listeners.add(l);
                    sockets.add(ws);
                    return ws;
                },
                fakeScheduler, fakeClockMs::get, "https://chat.ute.vn/", false);

        wsManager.connectIfAuthenticated();
        listeners.get(0).onOpen(sockets.get(0), okUpgradeResponse());

        // Đúng 1 timer heartbeat 25s cho socket hiện tại.
        assertEquals(1, fakeScheduler.activeTaskCountWithDelay(ChatWebSocketManager.HEARTBEAT_INTERVAL_MS));

        // Tiến đồng hồ 25s và kích hoạt timer -> gửi JSON ping, nhận JSON pong, vẫn chỉ có đúng 1 timer kế tiếp.
        fakeClockMs.addAndGet(25_000L);
        fakeScheduler.runNextWithDelay(ChatWebSocketManager.HEARTBEAT_INTERVAL_MS);
        verify(sockets.get(0), times(1)).send(argThat((String s) -> s.contains("\"type\":\"ping\"")));
        assertEquals(1, fakeScheduler.activeTaskCountWithDelay(ChatWebSocketManager.HEARTBEAT_INTERVAL_MS));

        listeners.get(0).onMessage(sockets.get(0), "{\"v\":1,\"type\":\"pong\"}");

        // Khi thu hồi thiết bị (4401) -> đóng socket, hủy sạch timer heartbeat và KHÔNG lên lịch reconnect.
        listeners.get(0).onClosed(sockets.get(0), ChatWebSocketManager.CLOSE_CODE_SESSION_REVOKED, "revoked");
        assertEquals(0, fakeScheduler.activeTaskCountWithDelay(ChatWebSocketManager.HEARTBEAT_INTERVAL_MS));
        assertEquals(WsConnectionState.DISCONNECTED, wsManager.getConnectionState().getValue());
        assertNull(currentToken.get());
    }

    @Test
    public void sessionKeyPersistsAcrossProcessRestartButChangesOnNewLogin() {
        InMemorySharedPreferences sharedPrefs = new InMemorySharedPreferences();

        // Quá trình 1: đăng nhập -> sinh sessionKey và lưu bền vững generation vào SharedPreferences.
        SessionManager proc1 = SessionManager.createForTest(sharedPrefs, Runnable::run);
        proc1.saveAuthenticated("jwt-a", "user-1", true);
        String keyBeforeRestart = proc1.sessionKey();
        assertNotNull(keyBeforeRestart);
        String queueNameBefore = "send-messages:" + keyBeforeRestart;

        // Tải lại cùng hồ sơ user-1 (saveMe cùng userId) KHÔNG được làm đổi sessionKey hay hủy hàng đợi.
        proc1.saveMe("user-1", false);
        assertEquals(keyBeforeRestart, proc1.sessionKey());

        // Quá trình 2 (mô phỏng khởi động lại tiến trình Android trên cùng SharedPreferences):
        SessionManager proc2 = SessionManager.createForTest(sharedPrefs, Runnable::run);
        assertEquals("sessionKey phải giữ nguyên sau process restart", keyBeforeRestart, proc2.sessionKey());
        assertEquals(queueNameBefore, "send-messages:" + proc2.sessionKey());

        // Đăng xuất hoặc đăng nhập lại thật sự phải tăng generation và đổi sessionKey.
        proc2.clear();
        assertNull(proc2.sessionKey());

        proc2.saveAuthenticated("jwt-a", "user-1", true);
        String keyAfterRelogin = proc2.sessionKey();
        assertNotNull(keyAfterRelogin);
        assertNotEquals("Đăng nhập mới phải đổi sessionKey dù cùng user và token", keyBeforeRestart, keyAfterRelogin);
    }

    @Test
    public void staleSessionDropsLateSyncAndWebSocketCallbacks() {
        when(syncStateDao.get(ME)).thenReturn(syncState(ME, 10L));

        // Giả lập tài khoản đăng xuất ngay trong lúc gọi HTTP /api/sync đang trên đường về.
        when(api.sync(10L, 100)).thenReturn(syncCall(() -> {
            // Đổi phiên trước khi phản hồi mạng quay về.
            currentSessionKey.set("2:u-other");
            currentToken.set("jwt-other");
            ChatDtos.SyncResponse late = new ChatDtos.SyncResponse();
            late.events = Collections.singletonList(event(11L, "MESSAGE_CREATED",
                    "{\"id\":888,\"clientId\":\"cli-late\",\"conversationId\":\"conv-1\",\"senderId\":\"u-bob\",\"type\":\"TEXT\",\"text\":\"Late\"}"));
            late.nextCursor = 11L;
            late.hasMore = false;
            return Response.success(late);
        }));

        SyncEventApplier applier = new SyncEventApplier(db);
        SyncEngine engine = new SyncEngine(db, api, applier, session, Runnable::run, Runnable::run, fakeScheduler, fakeClockMs::get);
        engine.requestSync();

        // Không được ghi bất kỳ dòng nào vào MessageDao, ContactDao hay SyncStateDao sau khi phiên đã đổi.
        verify(messageDao, never()).insertRespectingVisibleFrom(any(), anyString());
        verify(contactDao, never()).insert(any());
        verify(syncStateDao, never()).advance(anyString(), anyLong(), anyLong());
    }

    // ======================== TEST HELPERS ========================

    private static SyncStateEntity syncState(String userId, long cursor) {
        SyncStateEntity s = new SyncStateEntity();
        s.setUserId(userId);
        s.setLastSyncEventId(cursor);
        s.setLastSyncAt(1000L);
        return s;
    }

    private static ChatDtos.SyncEventDto event(long id, String type, String jsonPayload) {
        ChatDtos.SyncEventDto e = new ChatDtos.SyncEventDto();
        e.id = id;
        e.eventType = type;
        e.payload = JsonParser.parseString(jsonPayload);
        e.createdAt = "2026-11-20T10:00:00Z";
        return e;
    }

    private static ChatDtos.SyncEventDto convEvent(long id, String title, boolean left) {
        String leftJson = left ? "\"2026-11-20T10:05:00Z\"" : "null";
        return event(id, "CONVERSATION_UPDATED",
                "{\"id\":\"conv-1\",\"type\":\"GROUP\",\"title\":\"" + title + "\",\"leftAt\":" + leftJson
                        + ",\"members\":[{\"userId\":\"u-me\",\"displayName\":\"Me\",\"role\":\"MEMBER\",\"leftAt\":" + leftJson + "}]}");
    }

    private static ChatDtos.ConversationDto bootstrapConv(String convId, long msgId, long bobDelivered, long bobRead) {
        ChatDtos.ConversationDto conv = new ChatDtos.ConversationDto();
        conv.id = convId;
        conv.type = "DIRECT";
        conv.lastMessageId = msgId;
        conv.createdAt = "2026-11-20T10:00:00Z";

        ChatDtos.MemberDto me = new ChatDtos.MemberDto();
        me.userId = ME;
        me.displayName = "Me";
        me.role = "MEMBER";

        ChatDtos.MemberDto bob = new ChatDtos.MemberDto();
        bob.userId = BOB;
        bob.displayName = "Bob";
        bob.role = "MEMBER";
        bob.lastDeliveredMessageId = bobDelivered;
        bob.lastReadMessageId = bobRead;
        conv.members = Arrays.asList(me, bob);

        ChatDtos.MessageDto last = new ChatDtos.MessageDto();
        last.id = msgId;
        last.clientId = "cli-" + convId;
        last.conversationId = convId;
        last.senderId = ME;
        last.type = "TEXT";
        last.text = "Hello";
        last.createdAt = "2026-11-20T10:00:00Z";
        conv.lastMessage = last;
        return conv;
    }

    private static okhttp3.Response okUpgradeResponse() {
        return new okhttp3.Response.Builder()
                .request(new Request.Builder().url("http://10.0.2.2:8080/ws").build())
                .protocol(Protocol.HTTP_1_1)
                .code(101)
                .message("Switching Protocols")
                .build();
    }

    private static <T> Call<T> syncCall(ThrowingSupplier<Response<T>> supplier) {
        return new Call<T>() {
            private boolean executed;
            private boolean canceled;

            @Override
            public Response<T> execute() throws java.io.IOException {
                executed = true;
                try {
                    return supplier.get();
                } catch (java.io.IOException e) {
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

    /** Bộ lập lịch giả lập có thể kiểm đếm và kích hoạt thủ công từng hẹn giờ trên JVM không cần Thread.sleep. */
    private static final class FakeDelayScheduler implements SyncEngine.DelayScheduler {
        private static final class ScheduledItem implements Cancellable {
            final long delayMs;
            final Runnable task;
            boolean cancelled;
            boolean executed;

            ScheduledItem(long delayMs, Runnable task) {
                this.delayMs = delayMs;
                this.task = task;
            }

            @Override
            public void cancel() {
                cancelled = true;
            }

            boolean isActive() {
                return !cancelled && !executed;
            }
        }

        private final List<ScheduledItem> items = new ArrayList<>();

        @Override
        public synchronized Cancellable schedule(long delayMs, Runnable task) {
            ScheduledItem item = new ScheduledItem(delayMs, task);
            items.add(item);
            return item;
        }

        synchronized int activeTaskCountWithDelay(long delayMs) {
            int count = 0;
            for (ScheduledItem item : items) {
                if (item.isActive() && item.delayMs == delayMs) count++;
            }
            return count;
        }

        void runNextWithDelay(long delayMs) {
            ScheduledItem target = null;
            synchronized (this) {
                for (ScheduledItem item : items) {
                    if (item.isActive() && item.delayMs == delayMs) {
                        target = item;
                        item.executed = true;
                        break;
                    }
                }
            }
            assertNotNull("Không tìm thấy timer đang hoạt động với delay=" + delayMs, target);
            target.task.run();
        }
    }

    /** SharedPreferences lưu trên RAM để mô phỏng khởi động lại tiến trình Android trong bài test JVM. */
    private static final class InMemorySharedPreferences implements SharedPreferences {
        private final Map<String, Object> data = new HashMap<>();

        @Override public Map<String, ?> getAll() { return new HashMap<>(data); }
        @Override public String getString(String key, String defValue) { Object v = data.get(key); return v instanceof String ? (String) v : defValue; }
        @Override public Set<String> getStringSet(String key, Set<String> defValues) { return defValues; }
        @Override public int getInt(String key, int defValue) { Object v = data.get(key); return v instanceof Integer ? (Integer) v : defValue; }
        @Override public long getLong(String key, long defValue) { Object v = data.get(key); return v instanceof Long ? (Long) v : defValue; }
        @Override public float getFloat(String key, float defValue) { Object v = data.get(key); return v instanceof Float ? (Float) v : defValue; }
        @Override public boolean getBoolean(String key, boolean defValue) { Object v = data.get(key); return v instanceof Boolean ? (Boolean) v : defValue; }
        @Override public boolean contains(String key) { return data.containsKey(key); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }

        @Override
        public Editor edit() {
            return new Editor() {
                private final Map<String, Object> pending = new HashMap<>();
                private final List<String> removals = new ArrayList<>();
                private boolean clearAll;

                @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
                @Override public Editor putStringSet(String key, Set<String> values) { pending.put(key, values); return this; }
                @Override public Editor putInt(String key, int value) { pending.put(key, value); return this; }
                @Override public Editor putLong(String key, long value) { pending.put(key, value); return this; }
                @Override public Editor putFloat(String key, float value) { pending.put(key, value); return this; }
                @Override public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
                @Override public Editor remove(String key) { removals.add(key); pending.remove(key); return this; }
                @Override public Editor clear() { clearAll = true; pending.clear(); return this; }
                @Override public boolean commit() { apply(); return true; }
                @Override public void apply() {
                    if (clearAll) data.clear();
                    for (String key : removals) data.remove(key);
                    data.putAll(pending);
                }
            };
        }
    }
}
