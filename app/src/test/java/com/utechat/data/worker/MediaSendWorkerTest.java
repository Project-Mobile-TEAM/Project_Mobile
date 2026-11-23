package com.utechat.data.worker;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.work.ListenableWorker;
import androidx.work.WorkManager;
import com.utechat.data.local.AppDatabase;
import com.utechat.data.local.dao.ConversationDao;
import com.utechat.data.local.dao.MessageDao;
import com.utechat.data.local.dao.TransferDao;
import com.utechat.data.local.entity.AttachmentTransferEntity;
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.remote.api.ChatApi;
import com.utechat.data.remote.api.MediaApi;
import com.utechat.data.remote.model.ChatDtos;
import com.utechat.data.remote.model.MediaDtos;
import com.utechat.data.session.SessionManager;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Kiểm thử JVM hàng đợi gửi media 2 pha của Phase 6 (D-04):
 * - Kịch bản 1: media xếp hàng offline -&gt; upload 1 lần, lưu mediaUrl, gửi tin, đánh COMPLETED, xóa tệp tạm.
 * - Kịch bản 2: mất mạng giữa chừng SAU upload -&gt; lần thử lại dùng mediaUrl đã lưu, KHÔNG upload lại.
 * - 413/415 đánh FAILED ngay và trả success (không kẹt hàng đợi), LOCATION không chạm MediaApi,
 *   401 chỉ hủy đúng hàng đợi phiên cũ, upload lại dùng đúng clientId cũ, lỗi pha 2 giữ nguyên mediaUrl.
 */
public class MediaSendWorkerTest {
    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static final String USER_A = "u-alice";
    private static final String USER_B = "u-bob";
    private static final String SESSION_A = "1:" + USER_A;
    private static final String SESSION_B = "2:" + USER_B;
    private static final String CONV = "conv-direct";
    private static final String CLIENT_ID = "11111111-1111-1111-1111-111111111111";

    private AppDatabase db;
    private MessageDao messageDao;
    private ConversationDao conversationDao;
    private TransferDao transferDao;
    private ChatApi api;
    private MediaApi mediaApi;
    private SessionManager session;
    private WorkManager workManager;

    private final AtomicReference<String> activeSessionKey = new AtomicReference<>(SESSION_A);
    private final AtomicReference<String> activeUserId = new AtomicReference<>(USER_A);
    private final AtomicReference<String> activeToken = new AtomicReference<>("jwt-alice");
    private final AtomicReference<String> activeVersion = new AtomicReference<>("1");
    private final SendMessageWorker.Sleeper noSleep = ms -> { };

    @Before
    public void setUp() {
        db = mock(AppDatabase.class);
        messageDao = mock(MessageDao.class);
        conversationDao = mock(ConversationDao.class);
        transferDao = mock(TransferDao.class);
        api = mock(ChatApi.class);
        mediaApi = mock(MediaApi.class);
        session = mock(SessionManager.class);
        workManager = mock(WorkManager.class);

        when(db.messageDao()).thenReturn(messageDao);
        when(db.conversationDao()).thenReturn(conversationDao);
        when(db.transferDao()).thenReturn(transferDao);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(db).runInTransaction(any(Runnable.class));
        when(conversationDao.getConversationSync(CONV)).thenReturn(conv());

        when(session.sessionKey()).thenAnswer(i -> activeSessionKey.get());
        when(session.getUserId()).thenAnswer(i -> activeUserId.get());
        when(session.getToken()).thenAnswer(i -> activeToken.get());
        when(session.versionKey()).thenAnswer(i -> activeVersion.get());
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

    /** Kịch bản 1: xếp hàng offline -> upload đúng 1 lần -> lưu mediaUrl -> gửi tin IMAGE -> COMPLETED -> xóa tệp tạm. */
    @Test
    public void offlineMediaQueuedExecutesTwoPhaseUploadAndCleansUpTempFile() throws Exception {
        File temp = tempFolder.newFile("pending.jpg");
        MessageEntity msg = mediaMsg("IMAGE", null);
        when(messageDao.findByClientId(CLIENT_ID)).thenReturn(msg);
        AttachmentTransferEntity transfer = transfer(temp, null);
        when(transferDao.findByClientIdSync(CLIENT_ID)).thenReturn(transfer);

        when(mediaApi.uploadMedia(eq("Bearer jwt-alice"), any(), any(), any(), any()))
                .thenReturn(syncCall(() -> Response.success(201, uploadResponse("/api/media/att-1"))));
        when(api.sendMessage(eq("Bearer jwt-alice"), eq(CONV), any()))
                .thenReturn(syncCall(() -> Response.success(201, sentDto(700L, "IMAGE"))));

        ListenableWorker.Result result = run(0);

        assertEquals(ListenableWorker.Result.success(), result);
        verify(mediaApi, times(1)).uploadMedia(anyString(), any(), any(), any(), any());
        verify(transferDao).markUploaded(eq(CLIENT_ID), eq("/api/media/att-1"), any(), any(), any(), any(), any(), any(), anyLong());
        verify(messageDao).updateAttachmentJson(eq(1L), contains("/api/media/att-1"));

        ArgumentCaptor<ChatDtos.SendMessageRequest> captor = ArgumentCaptor.forClass(ChatDtos.SendMessageRequest.class);
        verify(api).sendMessage(eq("Bearer jwt-alice"), eq(CONV), captor.capture());
        assertEquals("IMAGE", captor.getValue().type);
        assertEquals("/api/media/att-1", captor.getValue().mediaUrl);
        assertEquals(CLIENT_ID, captor.getValue().clientId);
        assertNull(captor.getValue().latitude);

        verify(messageDao).markSentFromWorker(eq(CLIENT_ID), eq(700L), anyLong());
        verify(transferDao).updateState(eq(CLIENT_ID), eq("COMPLETED"), eq(100), isNull(), anyLong());
        assertFalse("Tệp tạm phải bị xóa sau khi server xác nhận", temp.exists());
    }

    /** Kịch bản 2: upload xong rồi mất mạng khi gửi tin; lần thử lại không upload lại mà dùng mediaUrl đã lưu. */
    @Test
    public void midFlightNetworkDropAfterUploadReusesSavedMediaUrlWithoutReupload() throws Exception {
        File temp = tempFolder.newFile("pending2.jpg");
        MessageEntity msg = mediaMsg("IMAGE", null);
        when(messageDao.findByClientId(CLIENT_ID)).thenReturn(msg);
        AttachmentTransferEntity transfer = transfer(temp, null);
        when(transferDao.findByClientIdSync(CLIENT_ID)).thenReturn(transfer);
        // Mô phỏng Room thật: markUploaded ghi mediaUrl vào hàng transfer.
        doAnswer(inv -> {
            transfer.setMediaUrl(inv.getArgument(1));
            transfer.setState("UPLOADED");
            return null;
        }).when(transferDao).markUploaded(anyString(), anyString(), any(), any(), any(), any(), any(), any(), anyLong());

        when(mediaApi.uploadMedia(anyString(), any(), any(), any(), any()))
                .thenReturn(syncCall(() -> Response.success(201, uploadResponse("/api/media/att-1"))));
        when(api.sendMessage(anyString(), eq(CONV), any()))
                .thenReturn(syncCall(() -> { throw new IOException("mất mạng"); }));

        // Lần 0: upload thành công nhưng gửi tin lỗi mạng -> retry, giữ tệp tạm.
        assertEquals(ListenableWorker.Result.retry(), run(0));
        assertEquals("/api/media/att-1", transfer.getMediaUrl());
        assertTrue(temp.exists());
        verify(messageDao).updateAttachmentJson(eq(1L), contains("/api/media/att-1"));

        // Lần 1 (có mạng lại): gửi tin thành công với đúng mediaUrl đã lưu.
        when(api.sendMessage(anyString(), eq(CONV), any()))
                .thenReturn(syncCall(() -> Response.success(201, sentDto(701L, "IMAGE"))));
        assertEquals(ListenableWorker.Result.success(), run(1));

        verify(mediaApi, times(1)).uploadMedia(anyString(), any(), any(), any(), any());
        ArgumentCaptor<ChatDtos.SendMessageRequest> captor = ArgumentCaptor.forClass(ChatDtos.SendMessageRequest.class);
        verify(api, times(2)).sendMessage(anyString(), eq(CONV), captor.capture());
        for (ChatDtos.SendMessageRequest req : captor.getAllValues()) {
            assertEquals("/api/media/att-1", req.mediaUrl);
            assertEquals(CLIENT_ID, req.clientId);
        }
        assertFalse(temp.exists());
        verify(transferDao).updateState(eq(CLIENT_ID), eq("COMPLETED"), eq(100), isNull(), anyLong());
    }

    /** Lỗi vĩnh viễn ở pha 2: tin FAILED, transfer FAILED nhưng vẫn giữ mediaUrl để thử lại không upload lại. */
    @Test
    public void phase2PermanentFailureUpdatesTransferStateToFailedWhilePreservingMediaUrl() throws Exception {
        File temp = tempFolder.newFile("pending3.jpg");
        MessageEntity msg = mediaMsg("IMAGE", null);
        when(messageDao.findByClientId(CLIENT_ID)).thenReturn(msg);
        AttachmentTransferEntity transfer = transfer(temp, "/api/media/att-9");
        when(transferDao.findByClientIdSync(CLIENT_ID)).thenReturn(transfer);
        when(api.sendMessage(anyString(), eq(CONV), any()))
                .thenReturn(syncCall(() -> errorResponse(409, "MEMBERSHIP_CHANGED")));

        assertEquals(ListenableWorker.Result.success(), run(0));

        verify(mediaApi, never()).uploadMedia(anyString(), any(), any(), any(), any());
        verify(messageDao).markFailed(CLIENT_ID, "MEMBERSHIP_CHANGED");
        verify(transferDao).updateState(eq(CLIENT_ID), eq("FAILED"), eq(100), eq("MEMBERSHIP_CHANGED"), anyLong());
        assertEquals("/api/media/att-9", transfer.getMediaUrl());
        assertTrue("Giữ tệp tạm để người dùng thử lại", temp.exists());
    }

    /** 413 và 415 ở pha 1: FAILED ngay, xóa tệp tạm, trả success và không gọi POST tin nhắn. */
    @Test
    public void payloadTooLarge413AndUnsupported415MarkFailedImmediatelyWithoutBlockingQueue() throws Exception {
        Object[][] cases = {{413, "FILE_TOO_LARGE"}, {415, "UNSUPPORTED_TYPE"}};
        for (Object[] c : cases) {
            reset(messageDao, transferDao, mediaApi, api);
            int http = (int) c[0];
            String expectedCode = (String) c[1];
            File temp = tempFolder.newFile("pending-" + http + ".bin");
            when(messageDao.findByClientId(CLIENT_ID)).thenReturn(mediaMsg("FILE", null));
            when(transferDao.findByClientIdSync(CLIENT_ID)).thenReturn(transfer(temp, null));
            when(mediaApi.uploadMedia(anyString(), any(), any(), any(), any()))
                    .thenReturn(syncCall(() -> errorResponse(http, expectedCode)));

            ListenableWorker.Result result = run(0);

            assertEquals("HTTP " + http + " phải trả success để hàng đợi chạy tiếp",
                    ListenableWorker.Result.success(), result);
            verify(messageDao).markFailed(CLIENT_ID, expectedCode);
            verify(transferDao).updateState(eq(CLIENT_ID), eq("FAILED"), eq(0), eq(expectedCode), anyLong());
            verify(api, never()).sendMessage(anyString(), anyString(), any());
            assertFalse("Tệp tạm phải bị xóa khi " + http, temp.exists());
        }
    }

    /** LOCATION chỉ gửi tọa độ, không bao giờ chạm tới MediaApi hay TransferDao. */
    @Test
    public void locationMessageSendsCoordinatesWithoutMediaUpload() {
        MessageEntity msg = mediaMsg("LOCATION", "{\"latitude\":10.85,\"longitude\":106.77}");
        when(messageDao.findByClientId(CLIENT_ID)).thenReturn(msg);
        when(api.sendMessage(anyString(), eq(CONV), any()))
                .thenReturn(syncCall(() -> Response.success(201, sentDto(702L, "LOCATION"))));

        assertEquals(ListenableWorker.Result.success(), run(0));

        verifyNoInteractions(mediaApi);
        verifyNoInteractions(transferDao);
        ArgumentCaptor<ChatDtos.SendMessageRequest> captor = ArgumentCaptor.forClass(ChatDtos.SendMessageRequest.class);
        verify(api).sendMessage(eq("Bearer jwt-alice"), eq(CONV), captor.capture());
        assertEquals("LOCATION", captor.getValue().type);
        assertEquals(Double.valueOf(10.85), captor.getValue().latitude);
        assertEquals(Double.valueOf(106.77), captor.getValue().longitude);
        assertNull(captor.getValue().mediaUrl);
        verify(messageDao).markSentFromWorker(eq(CLIENT_ID), eq(702L), anyLong());
    }

    /** Tọa độ thiếu hoặc ngoài khoảng hợp lệ: FAILED VALIDATION, không gọi HTTP. */
    @Test
    public void invalidLocationPayloadFailsValidationWithoutHttp() {
        when(messageDao.findByClientId(CLIENT_ID)).thenReturn(mediaMsg("LOCATION", "{\"latitude\":120.0,\"longitude\":106.77}"));

        assertEquals(ListenableWorker.Result.success(), run(0));

        verify(messageDao).markFailed(CLIENT_ID, "VALIDATION");
        verify(api, never()).sendMessage(anyString(), anyString(), any());
    }

    /** 401 khi upload: chỉ hủy hàng đợi của phiên A, không đụng phiên B vừa đăng nhập. */
    @Test
    public void upload401CancelsOnlyDepartingQueueAndIsolatesNewSession() throws Exception {
        File temp = tempFolder.newFile("pending401.jpg");
        when(messageDao.findByClientId(CLIENT_ID)).thenReturn(mediaMsg("IMAGE", null));
        when(transferDao.findByClientIdSync(CLIENT_ID)).thenReturn(transfer(temp, null));

        AtomicReference<String> sentAuth = new AtomicReference<>();
        when(mediaApi.uploadMedia(anyString(), any(), any(), any(), any())).thenAnswer(inv -> {
            sentAuth.set(inv.getArgument(0));
            return syncCall(() -> {
                // B đăng nhập giữa lúc request của A đang bay.
                activeSessionKey.set(SESSION_B);
                activeUserId.set(USER_B);
                activeToken.set("jwt-bob");
                activeVersion.set("2");
                return errorResponse(401, "SESSION_EXPIRED");
            });
        });

        assertEquals(ListenableWorker.Result.failure(), run(0));

        assertEquals("Bearer jwt-alice", sentAuth.get());
        verify(workManager).cancelUniqueWork(SendMessageWorker.queueName(SESSION_A));
        verify(workManager, never()).cancelUniqueWork(SendMessageWorker.queueName(SESSION_B));
        verify(workManager, never()).cancelAllWorkByTag(anyString());
        verify(session).clearIfCurrent("jwt-alice", "1");
        assertEquals("jwt-bob", activeToken.get());
        assertEquals(SESSION_B, activeSessionKey.get());
        verify(api, never()).sendMessage(anyString(), anyString(), any());
        assertTrue(temp.exists());
    }

    /** Mất phản hồi upload (IOException trước khi lưu mediaUrl): lần sau upload lại với ĐÚNG clientId cũ. */
    @Test
    public void lostUploadResponseRetriesWithSameClientId() throws Exception {
        File temp = tempFolder.newFile("pending-lost.jpg");
        when(messageDao.findByClientId(CLIENT_ID)).thenReturn(mediaMsg("IMAGE", null));
        AttachmentTransferEntity transfer = transfer(temp, null);
        when(transferDao.findByClientIdSync(CLIENT_ID)).thenReturn(transfer);

        List<String> clientIdParts = new ArrayList<>();
        when(mediaApi.uploadMedia(anyString(), any(), any(), any(), any())).thenAnswer(inv -> {
            RequestBody part = inv.getArgument(1);
            Buffer buffer = new Buffer();
            part.writeTo(buffer);
            clientIdParts.add(buffer.readUtf8());
            if (clientIdParts.size() == 1) {
                return syncCall(() -> { throw new IOException("mất phản hồi"); });
            }
            return syncCall(() -> Response.success(200, uploadResponse("/api/media/att-1")));
        });
        when(api.sendMessage(anyString(), eq(CONV), any()))
                .thenReturn(syncCall(() -> Response.success(201, sentDto(703L, "IMAGE"))));

        assertEquals(ListenableWorker.Result.retry(), run(0));
        verify(transferDao, never()).markUploaded(anyString(), anyString(), any(), any(), any(), any(), any(), any(), anyLong());
        assertNull(transfer.getMediaUrl());

        assertEquals(ListenableWorker.Result.success(), run(1));
        assertEquals(2, clientIdParts.size());
        assertEquals(CLIENT_ID, clientIdParts.get(0));
        assertEquals(CLIENT_ID, clientIdParts.get(1));
        verify(transferDao).markUploaded(eq(CLIENT_ID), eq("/api/media/att-1"), any(), any(), any(), any(), any(), any(), anyLong());
    }

    /** Tệp tạm không còn: FAILED FILE_MISSING cho cả tin và transfer, không upload, không kẹt hàng đợi. */
    @Test
    public void missingLocalFileFailsWithFileMissingWithoutUpload() {
        when(messageDao.findByClientId(CLIENT_ID)).thenReturn(mediaMsg("IMAGE", null));
        AttachmentTransferEntity transfer = new AttachmentTransferEntity();
        transfer.setClientId(CLIENT_ID);
        transfer.setLocalUri("content://x");
        transfer.setState("PENDING");
        transfer.setLocalPath(new File(tempFolder.getRoot(), "khong-ton-tai.jpg").getAbsolutePath());
        when(transferDao.findByClientIdSync(CLIENT_ID)).thenReturn(transfer);

        assertEquals(ListenableWorker.Result.success(), run(0));

        verify(messageDao).markFailed(CLIENT_ID, "FILE_MISSING");
        verify(transferDao).updateState(eq(CLIENT_ID), eq("FAILED"), eq(0), eq("FILE_MISSING"), anyLong());
        verifyNoInteractions(mediaApi);
    }

    /** Hết 3 lần thử lỗi mạng khi upload: tin và transfer cùng FAILED NETWORK, tệp tạm được giữ lại. */
    @Test
    public void uploadNetworkErrorExhaustsAttemptsAndMarksTransferFailed() throws Exception {
        File temp = tempFolder.newFile("pending-net.jpg");
        when(messageDao.findByClientId(CLIENT_ID)).thenReturn(mediaMsg("IMAGE", null));
        when(transferDao.findByClientIdSync(CLIENT_ID)).thenReturn(transfer(temp, null));
        when(mediaApi.uploadMedia(anyString(), any(), any(), any(), any()))
                .thenReturn(syncCall(() -> { throw new IOException("offline"); }));

        assertEquals(ListenableWorker.Result.retry(), run(0));
        verify(messageDao, never()).markFailed(anyString(), anyString());

        assertEquals(ListenableWorker.Result.success(), run(SendMessageWorker.MAX_AUTO_ATTEMPTS - 1));
        verify(messageDao).markFailed(CLIENT_ID, "NETWORK");
        verify(transferDao).updateState(eq(CLIENT_ID), eq("FAILED"), eq(0), eq("NETWORK"), anyLong());
        assertTrue(temp.exists());
    }

    /** Preflight nhóm phát hiện đã rời nhóm: phải đánh FAILED cả tin nhắn lẫn attachment_transfer để không treo progress (M-04). */
    @Test
    public void groupPreflightNotMemberAlsoMarksTransferFailed() {
        ConversationEntity group = new ConversationEntity();
        group.setId(CONV);
        group.setType("GROUP");
        when(conversationDao.getConversationSync(CONV)).thenReturn(group);
        com.utechat.data.local.entity.ConversationMemberEntity leftMember = new com.utechat.data.local.entity.ConversationMemberEntity();
        leftMember.setConversationId(CONV);
        leftMember.setUserId(USER_A);
        leftMember.setLeft(true);
        when(conversationDao.getMemberSync(CONV, USER_A)).thenReturn(leftMember);
        when(messageDao.findByClientId(CLIENT_ID)).thenReturn(mediaMsg("IMAGE", null));

        assertEquals(ListenableWorker.Result.success(), run(0));
        verify(messageDao).markFailed(CLIENT_ID, "NOT_A_MEMBER");
        verify(transferDao).updateState(eq(CLIENT_ID), eq("FAILED"), eq(0), eq("NOT_A_MEMBER"), anyLong());
        verifyNoInteractions(mediaApi);
    }

    // ======================== HELPERS ========================

    private ListenableWorker.Result run(int attempt) {
        return SendMessageWorker.executeSend(
                db, api, mediaApi, session, workManager, Runnable::run, noSleep,
                CLIENT_ID, SESSION_A, attempt, () -> false);
    }

    private static MessageEntity mediaMsg(String type, String attachmentJson) {
        MessageEntity m = new MessageEntity();
        m.setLocalId(1L);
        m.setClientId(CLIENT_ID);
        m.setServerId(null);
        m.setConversationId(CONV);
        m.setSenderId(USER_A);
        m.setType(type);
        m.setText(null);
        m.setAttachmentJson(attachmentJson);
        m.setCreatedAt(1000L);
        m.setStatus("SENDING");
        return m;
    }

    private static AttachmentTransferEntity transfer(File file, String mediaUrl) {
        AttachmentTransferEntity t = new AttachmentTransferEntity();
        t.setClientId(CLIENT_ID);
        t.setLocalUri("content://picker/1");
        t.setState("PENDING");
        t.setProgress(0);
        t.setMediaUrl(mediaUrl);
        t.setDirection("UPLOAD");
        t.setLocalPath(file.getAbsolutePath());
        t.setKind("IMAGE");
        t.setMime("image/jpeg");
        t.setSizeBytes(file.length());
        t.setFileName("anh.jpg");
        return t;
    }

    private static ConversationEntity conv() {
        ConversationEntity c = new ConversationEntity();
        c.setId(CONV);
        c.setType("DIRECT");
        return c;
    }

    private static MediaDtos.MediaUploadResponse uploadResponse(String mediaUrl) {
        MediaDtos.MediaUploadResponse r = new MediaDtos.MediaUploadResponse();
        r.mediaUrl = mediaUrl;
        r.mime = "image/jpeg";
        r.sizeBytes = 1234L;
        r.width = 800;
        r.height = 600;
        r.fileName = "anh.jpg";
        return r;
    }

    private static ChatDtos.MessageDto sentDto(long id, String type) {
        ChatDtos.MessageDto d = new ChatDtos.MessageDto();
        d.id = id;
        d.clientId = CLIENT_ID;
        d.conversationId = CONV;
        d.senderId = USER_A;
        d.type = type;
        d.createdAt = "2026-11-20T12:00:00Z";
        return d;
    }

    private static <T> Response<T> errorResponse(int code, String errorCode) {
        String json = "{\"error\":{\"code\":\"" + errorCode + "\",\"message\":\"err\"}}";
        ResponseBody body = ResponseBody.create(json, MediaType.parse("application/json"));
        okhttp3.Response raw = new okhttp3.Response.Builder()
                .code(code)
                .message("Err")
                .protocol(Protocol.HTTP_1_1)
                .request(new Request.Builder().url("http://10.0.2.2:8080/api/media").build())
                .build();
        return Response.error(body, raw);
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
