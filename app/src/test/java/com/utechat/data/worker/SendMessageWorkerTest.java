package com.utechat.data.worker;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ListenableWorker;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import com.utechat.data.local.AppDatabase;
import com.utechat.data.local.dao.ConversationDao;
import com.utechat.data.local.dao.MessageDao;
import com.utechat.data.local.dao.ReactionDao;
import com.utechat.data.local.dao.SyncStateDao;
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.ConversationMemberEntity;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.remote.api.ChatApi;
import com.utechat.data.remote.model.ChatDtos;
import com.utechat.data.repository.ChatResult;
import com.utechat.data.repository.real.RealChatRepository;
import com.utechat.data.session.SessionEvents;
import com.utechat.data.session.SessionManager;
import com.utechat.data.sync.ChatWebSocketManager;
import com.utechat.data.sync.SyncEngine;
import com.utechat.data.sync.SyncEventApplier;
import com.utechat.data.sync.TypingTracker;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.ResponseBody;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Kiểm thử JVM toàn bộ bất biến hàng đợi gửi tin nhắn offline-first của Phase 5 (D-05, D-11, D-14, D-16):
 * - Gửi thành công 201 cập nhật Room không hạ bậc biên nhận.
 * - Lỗi nghiệp vụ 400/403/404/409 đánh dấu FAILED và trả Result.success() để không làm kẹt hàng đợi.
 * - 401 chỉ hủy đúng chuỗi phiên cũ, không đụng phiên đăng nhập mới.
 * - Lỗi tạm thời (IOException, 408, 503, 429 + Retry-After) thử tối đa 3 lần rồi mới đánh FAILED.
 * - Chặn gửi tin nhóm thuộc lần tham gia cũ (old tenure) và bảo toàn clientId khi Gửi lại / Xóa tin lỗi.
 */
public class SendMessageWorkerTest {
    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();

    private static final String USER_A = "u-alice";
    private static final String USER_B = "u-bob";
    private static final String SESSION_A = "1:" + USER_A;
    private static final String SESSION_B = "2:" + USER_B;
    private static final String CONV_DIRECT = "conv-direct";
    private static final String CONV_GROUP = "conv-group";

    private AppDatabase db;
    private MessageDao messageDao;
    private ConversationDao conversationDao;
    private ChatApi api;
    private SessionManager session;
    private WorkManager workManager;

    private final AtomicReference<String> activeSessionKey = new AtomicReference<>(SESSION_A);
    private final AtomicReference<String> activeUserId = new AtomicReference<>(USER_A);
    private final AtomicReference<String> activeToken = new AtomicReference<>("jwt-alice");
    private final AtomicReference<String> activeVersion = new AtomicReference<>("1");
    private final AtomicLong recordedSleepMs = new AtomicLong(0L);
    private final SendMessageWorker.Sleeper fakeSleeper = recordedSleepMs::addAndGet;

    @Before
    public void setUp() {
        db = mock(AppDatabase.class);
        messageDao = mock(MessageDao.class);
        conversationDao = mock(ConversationDao.class);
        api = mock(ChatApi.class);
        session = mock(SessionManager.class);
        workManager = mock(WorkManager.class);
        recordedSleepMs.set(0L);

        when(db.messageDao()).thenReturn(messageDao);
        when(db.conversationDao()).thenReturn(conversationDao);
        when(db.syncStateDao()).thenReturn(mock(SyncStateDao.class));
        when(db.reactionDao()).thenReturn(mock(ReactionDao.class));
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(db).runInTransaction(any(Runnable.class));
        when(db.runInTransaction(any(java.util.concurrent.Callable.class))).thenAnswer(inv ->
                ((java.util.concurrent.Callable<?>) inv.getArgument(0)).call());

        when(session.sessionKey()).thenAnswer(i -> activeSessionKey.get());
        when(session.getUserId()).thenAnswer(i -> activeUserId.get());
        when(session.getToken()).thenAnswer(i -> activeToken.get());
        when(session.versionKey()).thenAnswer(i -> activeVersion.get());
        when(session.hasToken()).thenAnswer(i -> activeToken.get() != null);
        when(session.runIfCurrent(nullable(String.class), any(Runnable.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Runnable action = inv.getArgument(1);
            if (key == null || !key.equals(activeSessionKey.get())) return false;
            action.run();
            return true;
        });
        when(session.clearIfCurrent(anyString(), anyString())).thenAnswer(inv -> {
            String tok = inv.getArgument(0);
            String ver = inv.getArgument(1);
            if (tok.equals(activeToken.get()) && ver.equals(activeVersion.get())) {
                activeToken.set(null);
                activeUserId.set(null);
                activeSessionKey.set(null);
                activeVersion.set(String.valueOf(Long.parseLong(ver) + 1));
                return true;
            }
            return false;
        });
    }

    @Test
    public void successUpdatesRoomToSentWithoutDowngradingHigherStatus() {
        MessageEntity msg = pendingMsg("cli-1", CONV_DIRECT, USER_A, "Xin chào", null);
        when(messageDao.findByClientId("cli-1")).thenReturn(msg);
        when(conversationDao.getConversationSync(CONV_DIRECT)).thenReturn(conv(CONV_DIRECT, "DIRECT"));

        ChatDtos.MessageDto dto = new ChatDtos.MessageDto();
        dto.id = 501L;
        dto.clientId = "cli-1";
        dto.conversationId = CONV_DIRECT;
        dto.senderId = USER_A;
        dto.type = "TEXT";
        dto.text = "Xin chào";
        dto.createdAt = "2026-11-20T12:00:00Z";
        when(api.sendMessage(eq("Bearer jwt-alice"), eq(CONV_DIRECT), any()))
                .thenReturn(syncCall(() -> Response.success(201, dto)));

        ListenableWorker.Result result = SendMessageWorker.executeSend(
                db, api, session, workManager, fakeSleeper, "cli-1", SESSION_A, 0);

        assertEquals(ListenableWorker.Result.success(), result);
        verify(db).runInTransaction(any(Runnable.class));
        verify(messageDao).markSentFromWorker(eq("cli-1"), eq(501L), anyLong());
        verify(conversationDao).updateLastMessageIfNewer(eq(CONV_DIRECT), eq(501L), anyLong());
    }

    @Test
    public void business4xxErrorsMarkFailedAndReturnSuccessSoQueueDoesNotStall() {
        Object[][] cases = new Object[][]{
                {403, "BLOCKED"},
                {403, "CONTACT_REQUIRED"},
                {403, "NOT_A_MEMBER"},
                {400, "VALIDATION"},
                {404, "NOT_FOUND"},
                {409, "MEMBERSHIP_CHANGED"}
        };

        for (Object[] c : cases) {
            reset(messageDao, conversationDao, api);
            int httpCode = (int) c[0];
            String errCode = (String) c[1];
            String clientId = "cli-" + errCode;

            MessageEntity msg = pendingMsg(clientId, CONV_DIRECT, USER_A, "Hello", null);
            when(messageDao.findByClientId(clientId)).thenReturn(msg);
            when(conversationDao.getConversationSync(CONV_DIRECT)).thenReturn(conv(CONV_DIRECT, "DIRECT"));
            when(api.sendMessage(eq("Bearer jwt-alice"), eq(CONV_DIRECT), any()))
                    .thenReturn(syncCall(() -> errorResponse(httpCode, errCode, null)));

            ListenableWorker.Result result = SendMessageWorker.executeSend(
                    db, api, session, workManager, fakeSleeper, clientId, SESSION_A, 0);

            // Bắt buộc trả Result.success() để chuỗi APPEND_OR_REPLACE tiếp tục chạy tin nhắn sau.
            assertEquals("Lỗi " + errCode + " phải trả Result.success()", ListenableWorker.Result.success(), result);
            verify(messageDao).markFailed(clientId, errCode);
            if ("NOT_A_MEMBER".equals(errCode)) {
                verify(conversationDao).markMemberLeft(CONV_DIRECT, USER_A);
            }
        }
    }

    @Test
    public void http401CancelsOnlyEnqueuedSessionAndDoesNotExpireNewLogin() {
        MessageEntity msgA = pendingMsg("cli-a", CONV_DIRECT, USER_A, "From A", null);
        when(messageDao.findByClientId("cli-a")).thenReturn(msgA);
        when(conversationDao.getConversationSync(CONV_DIRECT)).thenReturn(conv(CONV_DIRECT, "DIRECT"));

        // Khi yêu cầu của A đang bay trên mạng, B đăng nhập và xếp hàng tin mới -> sau đó server mới trả 401 cho A.
        AtomicReference<String> sentAuthHeader = new AtomicReference<>();
        when(api.sendMessage(anyString(), eq(CONV_DIRECT), any())).thenAnswer(inv -> {
            sentAuthHeader.set(inv.getArgument(0));
            return syncCall(() -> {
                // Mô phỏng B đăng nhập giữa chừng trong lúc request của A đang chờ phản hồi.
                activeSessionKey.set(SESSION_B);
                activeUserId.set(USER_B);
                activeToken.set("jwt-bob");
                activeVersion.set("2");
                SendMessageWorker.enqueue(workManager, "cli-b", SESSION_B);
                return errorResponse(401, "UNAUTHORIZED", null);
            });
        });

        ListenableWorker.Result result = SendMessageWorker.executeSend(
                db, api, session, workManager, fakeSleeper, "cli-a", SESSION_A, 0);

        assertEquals(ListenableWorker.Result.failure(), result);
        assertEquals("Bearer jwt-alice", sentAuthHeader.get());
        // Chỉ hủy đúng hàng đợi của phiên A, tuyệt đối không hủy phiên B hay TAG_ALL.
        verify(workManager).cancelUniqueWork(SendMessageWorker.queueName(SESSION_A));
        verify(workManager, never()).cancelUniqueWork(SendMessageWorker.queueName(SESSION_B));
        verify(workManager, never()).cancelAllWorkByTag(anyString());
        // Phiên B vẫn còn nguyên vẹn, không bị clear nhầm.
        assertEquals("jwt-bob", activeToken.get());
        assertEquals(SESSION_B, activeSessionKey.get());
        assertEquals("2", activeVersion.get());
        // clearIfCurrent với token/version cũ phải trả false nên không bắn SessionEvents cho phiên B.
        verify(session).clearIfCurrent("jwt-alice", "1");
    }

    @Test
    public void transientFailuresRetryUpToThreeTimesAndRespectRetryAfterOn429() {
        MessageEntity msg = pendingMsg("cli-t", CONV_DIRECT, USER_A, "Retry me", null);
        when(messageDao.findByClientId("cli-t")).thenReturn(msg);
        when(conversationDao.getConversationSync(CONV_DIRECT)).thenReturn(conv(CONV_DIRECT, "DIRECT"));

        // 1) IOException ở attempt 0 và 1 -> Result.retry(), chưa đánh FAILED.
        when(api.sendMessage(anyString(), eq(CONV_DIRECT), any())).thenReturn(syncCall(() -> {
            throw new IOException("timeout");
        }));
        assertEquals(ListenableWorker.Result.retry(),
                SendMessageWorker.executeSend(db, api, session, workManager, fakeSleeper, "cli-t", SESSION_A, 0));
        assertEquals(ListenableWorker.Result.retry(),
                SendMessageWorker.executeSend(db, api, session, workManager, fakeSleeper, "cli-t", SESSION_A, 1));
        verify(messageDao, never()).markFailed(anyString(), anyString());

        // 2) HTTP 408 và 503 ở attempt 0 -> Result.retry().
        when(api.sendMessage(anyString(), eq(CONV_DIRECT), any()))
                .thenReturn(syncCall(() -> errorResponse(408, "TIMEOUT", null)));
        assertEquals(ListenableWorker.Result.retry(),
                SendMessageWorker.executeSend(db, api, session, workManager, fakeSleeper, "cli-t", SESSION_A, 0));
        when(api.sendMessage(anyString(), eq(CONV_DIRECT), any()))
                .thenReturn(syncCall(() -> errorResponse(503, "UNAVAILABLE", null)));
        assertEquals(ListenableWorker.Result.retry(),
                SendMessageWorker.executeSend(db, api, session, workManager, fakeSleeper, "cli-t", SESSION_A, 0));

        // 3) HTTP 429 kèm Retry-After: 4 ở attempt 0 -> gọi Sleeper tổng đúng 4000ms rồi trả Result.retry().
        recordedSleepMs.set(0L);
        when(api.sendMessage(anyString(), eq(CONV_DIRECT), any()))
                .thenReturn(syncCall(() -> errorResponse(429, "RATE_LIMITED", "4")));
        assertEquals(ListenableWorker.Result.retry(),
                SendMessageWorker.executeSend(db, api, session, workManager, fakeSleeper, "cli-t", SESSION_A, 0));
        assertEquals(4000L, recordedSleepMs.get());

        // 4) Hết 3 lần gửi tự động (attemptCount = 2): đánh dấu FAILED RATE_LIMITED / NETWORK và trả Result.success().
        assertEquals(ListenableWorker.Result.success(),
                SendMessageWorker.executeSend(db, api, session, workManager, fakeSleeper, "cli-t", SESSION_A, 2));
        verify(messageDao).markFailed("cli-t", "RATE_LIMITED");

        when(api.sendMessage(anyString(), eq(CONV_DIRECT), any())).thenReturn(syncCall(() -> {
            throw new IOException("offline");
        }));
        assertEquals(ListenableWorker.Result.success(),
                SendMessageWorker.executeSend(db, api, session, workManager, fakeSleeper, "cli-t", SESSION_A, 2));
        verify(messageDao).markFailed("cli-t", "NETWORK");
    }

    @Test
    public void skipsDeletedAlreadySentOrDifferentAccountMessagesSafely() {
        // 1) Đã bị xóa cục bộ -> trả success, không gọi HTTP.
        when(messageDao.findByClientId("cli-deleted")).thenReturn(null);
        assertEquals(ListenableWorker.Result.success(),
                SendMessageWorker.executeSend(db, api, session, workManager, fakeSleeper, "cli-deleted", SESSION_A, 0));

        // 2) Đã có serverId (đồng bộ qua /sync trước khi worker kịp chạy) -> bỏ qua.
        MessageEntity alreadySent = pendingMsg("cli-acked", CONV_DIRECT, USER_A, "Sent", null);
        alreadySent.setServerId(900L);
        alreadySent.setStatus("SENT");
        when(messageDao.findByClientId("cli-acked")).thenReturn(alreadySent);
        assertEquals(ListenableWorker.Result.success(),
                SendMessageWorker.executeSend(db, api, session, workManager, fakeSleeper, "cli-acked", SESSION_A, 0));

        // 3) Khác tài khoản senderId != activeUserId -> bỏ qua.
        MessageEntity otherSender = pendingMsg("cli-other", CONV_DIRECT, USER_B, "Other", null);
        when(messageDao.findByClientId("cli-other")).thenReturn(otherSender);
        assertEquals(ListenableWorker.Result.success(),
                SendMessageWorker.executeSend(db, api, session, workManager, fakeSleeper, "cli-other", SESSION_A, 0));

        verify(api, never()).sendMessage(anyString(), anyString(), any());
    }

    @Test
    public void retryAndDeleteFailedPreserveClientIdAndOnlyDeleteLocalUnsent() {
        SyncEngine syncEngine = mock(SyncEngine.class);
        ChatWebSocketManager wsManager = mock(ChatWebSocketManager.class);
        SyncEventApplier applier = new SyncEventApplier(db);
        TypingTracker typingTracker = mock(TypingTracker.class);
        RealChatRepository repo = new RealChatRepository(
                db, api, session, syncEngine, wsManager, applier, typingTracker,
                workManager, Runnable::run, Runnable::run, Runnable::run);

        // 1) Retry chủ động giữ nguyên clientId và cập nhật mốc visibleFromMessageId hiện hành của GROUP (25L).
        MessageEntity failedGroupMsg = pendingMsg("cli-retry-1", CONV_GROUP, USER_A, "Thử lại", 10L);
        failedGroupMsg.setStatus("FAILED");
        failedGroupMsg.setErrorCode("MEMBERSHIP_CHANGED");
        when(messageDao.findByClientId("cli-retry-1")).thenReturn(failedGroupMsg);
        when(conversationDao.getConversationSync(CONV_GROUP)).thenReturn(conv(CONV_GROUP, "GROUP"));
        when(conversationDao.getMemberSync(CONV_GROUP, USER_A)).thenReturn(member(CONV_GROUP, USER_A, false, 25L, 0L, 0L));
        when(messageDao.resetForRetry("cli-retry-1", 25L)).thenReturn(1);

        repo.retryFailedMessage("cli-retry-1");

        verify(messageDao).resetForRetry("cli-retry-1", 25L);
        ArgumentCaptor<OneTimeWorkRequest> reqCap = ArgumentCaptor.forClass(OneTimeWorkRequest.class);
        verify(workManager).enqueueUniqueWork(
                eq(SendMessageWorker.queueName(SESSION_A)),
                eq(ExistingWorkPolicy.APPEND_OR_REPLACE),
                reqCap.capture());
        assertEquals("cli-retry-1", reqCap.getValue().getWorkSpec().input.getString(SendMessageWorker.KEY_CLIENT_ID));
        // Input Data chỉ chứa đúng 1 khóa clientId, tuyệt đối không chứa text hay JWT.
        assertEquals(1, reqCap.getValue().getWorkSpec().input.getKeyValueMap().size());

        // 2) deleteFailedMessage chỉ gọi deleteFailedLocal, tuyệt đối không hủy tag trên WorkManager.
        repo.deleteFailedMessage("cli-delete-1");
        verify(messageDao).deleteFailedLocal("cli-delete-1");
        verify(workManager, never()).cancelAllWorkByTag(anyString());
        verify(workManager, never()).cancelUniqueWork(anyString());

        // 3) Kiểm tra loadOlderMessages suy luận trạng thái READ / DELIVERED / SENT từ member watermarks (ADR-010).
        ConversationMemberEntity meMember = member(CONV_DIRECT, USER_A, false, 0L, 0L, 0L);
        ConversationMemberEntity bobMember = member(CONV_DIRECT, USER_B, false, 0L, 200L, 100L);
        when(conversationDao.getMembersSync(CONV_DIRECT)).thenReturn(Arrays.asList(meMember, bobMember));
        when(messageDao.insertRespectingVisibleFrom(any(), eq(USER_A))).thenReturn(true);

        ChatDtos.MessageDto mRead = msgDto(100L, "c-100", CONV_DIRECT, USER_A);
        ChatDtos.MessageDto mDeliv = msgDto(150L, "c-150", CONV_DIRECT, USER_A);
        ChatDtos.MessageDto mSent = msgDto(250L, "c-250", CONV_DIRECT, USER_A);
        when(api.getMessages("Bearer jwt-alice", CONV_DIRECT, null, 50))
                .thenReturn(syncCall(() -> Response.success(Arrays.asList(mRead, mDeliv, mSent))));

        ChatResult<Integer> histRes = repo.loadOlderMessages(CONV_DIRECT, null, 50).getValue();
        assertNotNull(histRes);
        assertTrue(histRes.isSuccess());
        assertEquals(Integer.valueOf(3), histRes.data);

        ArgumentCaptor<MessageEntity> savedCap = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageDao, times(3)).insertRespectingVisibleFrom(savedCap.capture(), eq(USER_A));
        assertEquals("READ", savedCap.getAllValues().get(0).getStatus());
        assertEquals("DELIVERED", savedCap.getAllValues().get(1).getStatus());
        assertEquals("SENT", savedCap.getAllValues().get(2).getStatus());
    }

    @Test
    public void oldGroupTenureFailsWithoutAutoResend() {
        when(conversationDao.getConversationSync(CONV_GROUP)).thenReturn(conv(CONV_GROUP, "GROUP"));

        // Trường hợp 1: Xếp hàng ở cutoff 10, sau đó bị xóa và thêm lại với cutoff 25 -> fail closed không gọi HTTP.
        MessageEntity oldTenure = pendingMsg("cli-old-tenure", CONV_GROUP, USER_A, "Tin lần cũ", 10L);
        when(messageDao.findByClientId("cli-old-tenure")).thenReturn(oldTenure);
        when(conversationDao.getMemberSync(CONV_GROUP, USER_A)).thenReturn(member(CONV_GROUP, USER_A, false, 25L, 0L, 0L));

        assertEquals(ListenableWorker.Result.success(),
                SendMessageWorker.executeSend(db, api, session, workManager, fakeSleeper, "cli-old-tenure", SESSION_A, 0));
        verify(messageDao).markFailed("cli-old-tenure", "MEMBERSHIP_CHANGED");
        verify(api, never()).sendMessage(anyString(), anyString(), any());

        // Trường hợp 2: Tin nhóm cũ nâng cấp từ Room v1 có queuedVisibleFromMessageId == null -> fail closed không gửi.
        MessageEntity migratedNull = pendingMsg("cli-migrated", CONV_GROUP, USER_A, "Tin v1 cũ", null);
        when(messageDao.findByClientId("cli-migrated")).thenReturn(migratedNull);
        assertEquals(ListenableWorker.Result.success(),
                SendMessageWorker.executeSend(db, api, session, workManager, fakeSleeper, "cli-migrated", SESSION_A, 0));
        verify(messageDao).markFailed("cli-migrated", "MEMBERSHIP_CHANGED");
        verify(api, never()).sendMessage(anyString(), anyString(), any());

        // Trường hợp 3: Cache local vẫn tưởng cutoff là 10, nhưng server trả 409 MEMBERSHIP_CHANGED -> FAILED + success.
        when(conversationDao.getMemberSync(CONV_GROUP, USER_A)).thenReturn(member(CONV_GROUP, USER_A, false, 10L, 0L, 0L));
        when(api.sendMessage(eq("Bearer jwt-alice"), eq(CONV_GROUP), any()))
                .thenReturn(syncCall(() -> errorResponse(409, "MEMBERSHIP_CHANGED", null)));

        assertEquals(ListenableWorker.Result.success(),
                SendMessageWorker.executeSend(db, api, session, workManager, fakeSleeper, "cli-old-tenure", SESSION_A, 0));
        verify(messageDao, times(2)).markFailed("cli-old-tenure", "MEMBERSHIP_CHANGED");
    }

    @Test
    public void oldSessionTagNeverSendsUsingNewAccount() {
        // Phiên hiện tại đã là B, nhưng một WorkRequest tồn đọng mang tag SESSION_A chạy lên -> phải thoát ngay không gọi HTTP.
        activeSessionKey.set(SESSION_B);
        activeUserId.set(USER_B);
        activeToken.set("jwt-bob");
        activeVersion.set("2");

        assertNotEquals(SendMessageWorker.queueName(SESSION_A), SendMessageWorker.queueName(SESSION_B));

        ListenableWorker.Result result = SendMessageWorker.executeSend(
                db, api, session, workManager, fakeSleeper, "cli-from-a", SESSION_A, 0);

        assertEquals(ListenableWorker.Result.success(), result);
        verify(api, never()).sendMessage(anyString(), anyString(), any());
        verify(messageDao, never()).markFailed(anyString(), anyString());
        verify(workManager, never()).cancelUniqueWork(anyString());
    }

    // ======================== HELPERS ========================

    @Test
    public void queuedStickerSendsOnlyIdAndAcceptsIdempotentReplay() {
        String stickerId = "22222222-2222-4222-8222-222222220101";
        MessageEntity pending = pendingMsg("sticker-client", CONV_DIRECT, USER_A, null, null);
        pending.setType("STICKER");
        pending.setAttachmentJson("{\"stickerId\":\"" + stickerId + "\"}");
        when(messageDao.findByClientId("sticker-client")).thenReturn(pending);
        when(conversationDao.getConversationSync(CONV_DIRECT)).thenReturn(conv(CONV_DIRECT, "DIRECT"));
        ChatDtos.MessageDto dto = msgDto(502L, "sticker-client", CONV_DIRECT, USER_A);
        when(api.sendMessage(eq("Bearer jwt-alice"), eq(CONV_DIRECT), any()))
                .thenReturn(syncCall(() -> Response.success(dto)));

        assertEquals(ListenableWorker.Result.success(), SendMessageWorker.executeSend(
                db, api, session, workManager, fakeSleeper, "sticker-client", SESSION_A, 1));
        verify(api).sendMessage(eq("Bearer jwt-alice"), eq(CONV_DIRECT), argThat(request ->
                request != null && stickerId.equals(request.stickerId) && "STICKER".equals(request.type)
                        && "sticker-client".equals(request.clientId) && request.mediaUrl == null));
        verify(messageDao).markSentFromWorker(eq("sticker-client"), eq(502L), anyLong());
    }

    @Test
    public void lateWorkerAckDoesNotRestoreMessageDeletedOnAnotherDevice() {
        when(messageDao.findByClientId("deleted-client")).thenReturn(
                pendingMsg("deleted-client", CONV_DIRECT, USER_A, "Đã xóa", null));
        when(conversationDao.getConversationSync(CONV_DIRECT)).thenReturn(conv(CONV_DIRECT, "DIRECT"));
        when(messageDao.isDeletedForMe(503L)).thenReturn(true);
        ChatDtos.MessageDto dto = msgDto(503L, "deleted-client", CONV_DIRECT, USER_A);
        when(api.sendMessage(eq("Bearer jwt-alice"), eq(CONV_DIRECT), any()))
                .thenReturn(syncCall(() -> Response.success(dto)));

        assertEquals(ListenableWorker.Result.success(), SendMessageWorker.executeSend(
                db, api, session, workManager, fakeSleeper, "deleted-client", SESSION_A, 0));
        // Tin chờ không có serverId nên phải dọn theo clientId khi xác nhận về trễ.
        verify(messageDao).deletePendingByClientId("deleted-client");
        verify(messageDao, never()).markSentFromWorker(anyString(), anyLong(), anyLong());
    }

    private static MessageEntity pendingMsg(String clientId, String convId, String senderId, String text, Long queuedCutoff) {
        MessageEntity m = new MessageEntity();
        m.setLocalId(1L);
        m.setClientId(clientId);
        m.setServerId(null);
        m.setConversationId(convId);
        m.setSenderId(senderId);
        m.setType("TEXT");
        m.setText(text);
        m.setCreatedAt(1000L);
        m.setStatus("SENDING");
        m.setQueuedVisibleFromMessageId(queuedCutoff);
        return m;
    }

    private static ConversationEntity conv(String id, String type) {
        ConversationEntity c = new ConversationEntity();
        c.setId(id);
        c.setType(type);
        return c;
    }

    private static ConversationMemberEntity member(
            String convId, String userId, boolean left, long visibleFrom, long delivered, long read) {
        ConversationMemberEntity m = new ConversationMemberEntity();
        m.setConversationId(convId);
        m.setUserId(userId);
        m.setRole("MEMBER");
        m.setLeft(left);
        m.setVisibleFromMessageId(visibleFrom);
        m.setLastDeliveredId(delivered);
        m.setLastReadId(read);
        return m;
    }

    private static ChatDtos.MessageDto msgDto(long id, String clientId, String convId, String senderId) {
        ChatDtos.MessageDto d = new ChatDtos.MessageDto();
        d.id = id;
        d.clientId = clientId;
        d.conversationId = convId;
        d.senderId = senderId;
        d.type = "TEXT";
        d.text = "Msg " + id;
        d.createdAt = "2026-11-20T12:00:00Z";
        return d;
    }

    private static <T> Response<T> errorResponse(int code, String errorCode, String retryAfterHeader) {
        String json = "{\"error\":{\"code\":\"" + errorCode + "\",\"message\":\"err\"}}";
        ResponseBody body = ResponseBody.create(json, MediaType.parse("application/json"));
        okhttp3.Response.Builder rawBuilder = new okhttp3.Response.Builder()
                .code(code)
                .message("Err")
                .protocol(Protocol.HTTP_1_1)
                .request(new Request.Builder().url("http://10.0.2.2:8080/api/conversations/c/messages").build());
        if (retryAfterHeader != null) {
            rawBuilder.headers(Headers.of("Retry-After", retryAfterHeader));
        }
        return Response.error(body, rawBuilder.build());
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
            @Override public Request request() { return new Request.Builder().url("http://10.0.2.2:8080/api").build(); }
            @Override public okio.Timeout timeout() { return okio.Timeout.NONE; }
        };
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
