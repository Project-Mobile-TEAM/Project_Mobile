package com.utechat.data.call;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.google.gson.JsonObject;
import com.utechat.data.remote.api.CallApi;
import com.utechat.data.remote.model.CallDtos;
import com.utechat.data.session.SessionManager;
import java.io.IOException;
import java.util.ArrayList;
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
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.ResponseBody;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Kiểm thử JVM 100% không cần phần cứng Android cho Plan 06-06 (CallSessionCoordinator + WebRtcCallEngine seam):
 * 1. Cuộc gọi đi thành công: 201 RINGING -> call.accepted -> offer/answer/ice -> ACTIVE + answeredAtElapsedRealtime -> endCall.
 * 2. Mã lỗi chính sách 409 CALL_BUSY, 403 BLOCKED, 403 CONTACT_REQUIRED, 400 CALL_UNSUPPORTED, 429 RATE_LIMITED.
 * 3. Cuộc gọi đến: 45s timeout (không gọi /end), call.ended (đúng & lệch callId), call.handled_elsewhere.
 * 4. Khóa 2 thiết bị loại khung lệch callId và đệm offer/ICE đến sớm trong lúc chờ POST /accept.
 * 5. Bật/tắt mic/loa ngoài và onSessionCleared() đóng engine, reset về IDLE ngay lập tức.
 * 6. Gia hạn call.keepalive mỗi 15s + reconnect khi ICE kết nối, dừng gia hạn khi mất ICE, và FGS thất bại không bao giờ thu âm.
 */
public class CallSessionCoordinatorTest {
    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();

    private static final String SESSION_1 = "1:u-alice";
    private static final String CONV_1 = "conv-direct-1";

    private CallApi callApi;
    private SessionManager session;
    private FakeDelayScheduler scheduler;
    private RecordingSignalingSender signaling;
    private AtomicLong fakeElapsedMs;
    private AtomicLong fakeWallMs;
    private AtomicReference<String> activeSessionKey;
    private List<FakeWebRtcCallEngine> createdEngines;
    private CallSessionCoordinator coordinator;

    @Before
    public void setUp() {
        callApi = mock(CallApi.class);
        session = mock(SessionManager.class);
        scheduler = new FakeDelayScheduler();
        signaling = new RecordingSignalingSender();
        fakeElapsedMs = new AtomicLong(10_000L);
        fakeWallMs = new AtomicLong(1_731_600_000_000L);
        activeSessionKey = new AtomicReference<>(SESSION_1);
        createdEngines = new ArrayList<>();

        when(session.sessionKey()).thenAnswer(inv -> activeSessionKey.get());

        coordinator = new CallSessionCoordinator(
                callApi,
                session,
                () -> {
                    FakeWebRtcCallEngine eng = new FakeWebRtcCallEngine();
                    createdEngines.add(eng);
                    return eng;
                },
                signaling,
                scheduler,
                fakeElapsedMs::get,
                fakeWallMs::get,
                Runnable::run,
                Runnable::run);
    }

    @Test
    public void outgoingCallSuccessConnectsOfferAnswerIceAndTracksElapsedTime() {
        CallDtos.IceServerDto stun = new CallDtos.IceServerDto(
                Collections.singletonList("stun:stun.l.google.com:19302"), null, null);
        when(callApi.startCall(any())).thenReturn(syncCall(() -> Response.success(201,
                new CallDtos.StartCallResponse("call-1", "RINGING", null, Collections.singletonList(stun)))));
        when(callApi.endCall("call-1")).thenReturn(syncCall(() -> Response.success(204, null)));

        coordinator.startOutgoingCall(CONV_1, "u-bob", "Bob", null, "+84901234567");

        CallSessionCoordinator.CallUiState ringing = coordinator.getUiState().getValue();
        assertNotNull(ringing);
        assertEquals(CallSessionCoordinator.State.OUTGOING_RINGING, ringing.state);
        assertEquals("call-1", ringing.callId);
        assertEquals(1, createdEngines.size());
        FakeWebRtcCallEngine engine = createdEngines.get(0);
        assertTrue(engine.initialized);

        // Nhận call.accepted -> chuyển CONNECTING, gọi engine.startOffer() và gửi 2-arg SignalingSender ("call.offer", payload).
        JsonObject accepted = new JsonObject();
        accepted.addProperty("callId", "call-1");
        coordinator.onWsFrame("call.accepted", accepted);

        assertEquals(CallSessionCoordinator.State.CONNECTING, coordinator.getCurrentState().state);
        assertEquals(1, engine.startOfferCalls);
        engine.emitLocalOffer("v=0-offer-sdp");

        assertEquals(1, signaling.frames.size());
        assertEquals("call.offer", signaling.frames.get(0).type);
        assertEquals("call-1", signaling.frames.get(0).payload.get("callId").getAsString());
        assertEquals("v=0-offer-sdp", signaling.frames.get(0).payload.get("sdp").getAsString());

        // Nhận call.answer và call.ice từ đối phương.
        JsonObject answer = new JsonObject();
        answer.addProperty("callId", "call-1");
        answer.addProperty("sdp", "v=0-answer-sdp");
        coordinator.onWsFrame("call.answer", answer);
        assertEquals("v=0-answer-sdp", engine.appliedAnswerSdp);

        JsonObject ice = new JsonObject();
        ice.addProperty("callId", "call-1");
        ice.addProperty("candidate", "candidate:1");
        ice.addProperty("sdpMid", "audio");
        ice.addProperty("sdpMLineIndex", 0);
        coordinator.onWsFrame("call.ice", ice);
        assertEquals(1, engine.remoteIceCandidates.size());

        // Chỉ chuyển sang ACTIVE và chốt answeredAtElapsedRealtime sau khi ICE kết nối.
        assertEquals(0L, coordinator.getCurrentState().answeredAtElapsedRealtime);
        fakeElapsedMs.set(55_000L);
        engine.emitIceConnected();

        CallSessionCoordinator.CallUiState active = coordinator.getCurrentState();
        assertEquals(CallSessionCoordinator.State.ACTIVE, active.state);
        assertEquals(55_000L, active.answeredAtElapsedRealtime);

        // Kết thúc cuộc gọi -> POST /end + đóng engine.
        coordinator.endCall();
        assertEquals(CallSessionCoordinator.State.ENDED, coordinator.getCurrentState().state);
        assertEquals("HANGUP", coordinator.getCurrentState().endReason);
        assertTrue(engine.closed);
        verify(callApi).endCall("call-1");
    }

    @Test
    public void outgoingCallPolicyAnd409CallBusyTransitionToEnded() {
        Object[][] cases = new Object[][]{
                {409, "CALL_BUSY"},
                {403, "BLOCKED"},
                {403, "CONTACT_REQUIRED"},
                {400, "CALL_UNSUPPORTED"},
                {429, "RATE_LIMITED"}
        };
        for (Object[] c : cases) {
            int http = (int) c[0];
            String code = (String) c[1];
            when(callApi.startCall(any())).thenReturn(syncCall(() -> errorResponse(http, code)));

            coordinator.startOutgoingCall(CONV_1, "u-bob", "Bob", null, "+84901234567");

            CallSessionCoordinator.CallUiState st = coordinator.getCurrentState();
            assertEquals("HTTP " + http + " phải về ENDED", CallSessionCoordinator.State.ENDED, st.state);
            assertEquals(code, st.errorCode);
            assertEquals("ERROR", st.endReason);
            coordinator.resetToIdle();
            assertEquals(CallSessionCoordinator.State.IDLE, coordinator.getCurrentState().state);
        }
        assertTrue("Lỗi từ chối 4xx không bao giờ được khởi tạo WebRtcCallEngine", createdEngines.isEmpty());
    }

    @Test
    public void incomingCallAcceptRejectHandledElsewhereCallEndedAnd45sTimeout() {
        List<String> alertedCalls = new ArrayList<>();
        List<String> canceledAlerts = new ArrayList<>();
        coordinator.setIncomingCallAlertListener(new CallSessionCoordinator.IncomingCallAlertListener() {
            @Override public void onIncomingCallAlert(CallSessionCoordinator.CallUiState state) {
                alertedCalls.add(state.callId);
            }
            @Override public void onCancelIncomingCallAlert(String callId) {
                canceledAlerts.add(callId);
            }
        });

        // 1) Cuộc gọi đến quá hạn 45s cục bộ -> ENDED (TIMEOUT), tắt chuông, KHÔNG gọi POST /end hay /reject.
        coordinator.onWsFrame("call.incoming", incomingPayload("call-timeout", "Bob"));
        assertEquals(CallSessionCoordinator.State.INCOMING_RINGING, coordinator.getCurrentState().state);
        assertEquals(Collections.singletonList("call-timeout"), alertedCalls);

        scheduler.advanceBy(45_000L);
        assertEquals(CallSessionCoordinator.State.ENDED, coordinator.getCurrentState().state);
        assertEquals("TIMEOUT", coordinator.getCurrentState().endReason);
        assertTrue(coordinator.getCurrentState().ringingDeadlinePassed);
        assertTrue(canceledAlerts.contains("call-timeout"));
        verify(callApi, never()).endCall(anyString());
        verify(callApi, never()).rejectCall(anyString());

        coordinator.resetToIdle();

        // 2) call.ended đúng callId -> ENDED (HANGUP/REJECT), còn call.ended lệch callId -> bỏ qua.
        coordinator.onWsFrame("call.incoming", incomingPayload("call-end-test", "Bob"));
        JsonObject wrongEnded = new JsonObject();
        wrongEnded.addProperty("callId", "call-other");
        wrongEnded.addProperty("state", "ENDED");
        wrongEnded.addProperty("endReason", "HANGUP");
        coordinator.onCallEndedFrame(wrongEnded, 100L);
        assertEquals(CallSessionCoordinator.State.INCOMING_RINGING, coordinator.getCurrentState().state);

        JsonObject rightEnded = new JsonObject();
        rightEnded.addProperty("callId", "call-end-test");
        rightEnded.addProperty("state", "REJECTED");
        rightEnded.addProperty("endReason", "REJECT");
        coordinator.onWsFrame("call.ended", rightEnded);
        assertEquals(CallSessionCoordinator.State.ENDED, coordinator.getCurrentState().state);
        assertEquals("REJECT", coordinator.getCurrentState().endReason);
        assertTrue(canceledAlerts.contains("call-end-test"));

        coordinator.resetToIdle();

        // 3) call.handled_elsewhere: lệch callId bị bỏ qua, đúng callId lập tức ENDED (HANDLED_ELSEWHERE) + hủy chuông.
        coordinator.onWsFrame("call.incoming", incomingPayload("call-multi", "Bob"));
        JsonObject wrongElsewhere = new JsonObject();
        wrongElsewhere.addProperty("callId", "call-other");
        coordinator.onWsFrame("call.handled_elsewhere", wrongElsewhere);
        assertEquals(CallSessionCoordinator.State.INCOMING_RINGING, coordinator.getCurrentState().state);

        JsonObject rightElsewhere = new JsonObject();
        rightElsewhere.addProperty("callId", "call-multi");
        coordinator.onWsFrame("call.handled_elsewhere", rightElsewhere);
        assertEquals(CallSessionCoordinator.State.ENDED, coordinator.getCurrentState().state);
        assertEquals("HANDLED_ELSEWHERE", coordinator.getCurrentState().endReason);
        assertTrue(canceledAlerts.contains("call-multi"));
    }

    @Test
    public void twoDeviceSignalingGuardDropsMismatchedCallIdAndEarlyOfferBuffered() throws Exception {
        ExecutorService netPool = Executors.newSingleThreadExecutor();
        CountDownLatch acceptEntered = new CountDownLatch(1);
        CountDownLatch releaseAccept = new CountDownLatch(1);
        CountDownLatch acceptDone = new CountDownLatch(1);
        try {
            CallSessionCoordinator asyncCoord = new CallSessionCoordinator(
                    callApi,
                    session,
                    () -> {
                        FakeWebRtcCallEngine eng = new FakeWebRtcCallEngine();
                        createdEngines.add(eng);
                        return eng;
                    },
                    signaling,
                    scheduler,
                    fakeElapsedMs::get,
                    fakeWallMs::get,
                    task -> netPool.execute(() -> {
                        try {
                            task.run();
                        } finally {
                            acceptDone.countDown();
                        }
                    }),
                    Runnable::run);

            when(callApi.acceptCall("call-accept-race")).thenReturn(syncCall(() -> {
                acceptEntered.countDown();
                assertTrue(releaseAccept.await(5, TimeUnit.SECONDS));
                return Response.success(200, new CallDtos.AcceptCallResponse(
                        "call-accept-race", "ACTIVE", Collections.emptyList()));
            }));

            asyncCoord.onWsFrame("call.incoming", incomingPayload("call-accept-race", "Bob"));
            asyncCoord.acceptIncomingCall();
            assertTrue("POST /accept phải đang chạy trên luồng mạng", acceptEntered.await(3, TimeUnit.SECONDS));
            assertEquals(CallSessionCoordinator.State.CONNECTING, asyncCoord.getCurrentState().state);

            // Gửi khung lệch callId -> phải bị loại hoàn toàn.
            JsonObject wrongOffer = new JsonObject();
            wrongOffer.addProperty("callId", "call-spoofed");
            wrongOffer.addProperty("sdp", "v=0-bad-offer");
            asyncCoord.onWsFrame("call.offer", wrongOffer);

            // Gửi offer và ICE đến sớm cho ĐÚNG callId trong lúc POST /accept chưa trả 200 -> phải được đệm lại.
            JsonObject earlyOffer = new JsonObject();
            earlyOffer.addProperty("callId", "call-accept-race");
            earlyOffer.addProperty("sdp", "v=0-early-offer");
            asyncCoord.onWsFrame("call.offer", earlyOffer);

            JsonObject earlyIce = new JsonObject();
            earlyIce.addProperty("callId", "call-accept-race");
            earlyIce.addProperty("candidate", "candidate:early");
            earlyIce.addProperty("sdpMid", "audio");
            earlyIce.addProperty("sdpMLineIndex", 0);
            asyncCoord.onWsFrame("call.ice", earlyIce);

            // Cho POST /accept trả 200 OK -> engine khởi tạo và lập tức nhận offer + ICE đã đệm.
            releaseAccept.countDown();
            assertTrue(acceptDone.await(5, TimeUnit.SECONDS));

            assertEquals(1, createdEngines.size());
            FakeWebRtcCallEngine engine = createdEngines.get(0);
            assertEquals("v=0-early-offer", engine.acceptedOfferSdp);
            assertEquals(Collections.singletonList("candidate:early"), engine.remoteIceCandidates);

            // Khung ICE lệch callId sau khi engine đã mở cũng phải bị bỏ qua.
            JsonObject wrongIce = new JsonObject();
            wrongIce.addProperty("callId", "call-spoofed");
            wrongIce.addProperty("candidate", "candidate:evil");
            asyncCoord.onWsFrame("call.ice", wrongIce);
            assertEquals(1, engine.remoteIceCandidates.size());
        } finally {
            netPool.shutdownNow();
        }
    }

    @Test
    public void muteSpeakerTogglesAndSessionClearedWipesActiveCall() {
        when(callApi.startCall(any())).thenReturn(syncCall(() -> Response.success(201,
                new CallDtos.StartCallResponse("call-toggle", "RINGING", null, Collections.emptyList()))));

        coordinator.startOutgoingCall(CONV_1, "u-bob", "Bob", null, null);
        FakeWebRtcCallEngine engine = createdEngines.get(0);

        coordinator.toggleMute();
        assertTrue(coordinator.getCurrentState().micMuted);
        assertTrue(engine.micMuted);

        coordinator.toggleSpeaker();
        assertTrue(coordinator.getCurrentState().speakerOn);
        assertTrue(engine.speakerEnabled);

        coordinator.toggleMute();
        assertFalse(coordinator.getCurrentState().micMuted);
        assertFalse(engine.micMuted);

        // Khi đăng xuất / 401 -> đóng engine lập tức và reset về IDLE.
        coordinator.onSessionCleared();
        assertTrue(engine.closed);
        assertEquals(CallSessionCoordinator.State.IDLE, coordinator.getCurrentState().state);
        assertNull(coordinator.getCurrentState().callId);
    }

    @Test
    public void keepaliveRenewalOnConnectedIceStopsOnDisconnectAndFailedFgsNeverStartsCapture() {
        // Phần A: Foreground Service thất bại -> tuyệt đối không gọi API start/accept và không khởi tạo WebRtcCallEngine thu âm.
        AtomicInteger fgsAttempts = new AtomicInteger(0);
        coordinator.setForegroundServiceStarter((callId, peerName, sKey) -> {
            fgsAttempts.incrementAndGet();
            return false;
        });

        coordinator.startOutgoingCall(CONV_1, "u-bob", "Bob", null, "+84901234567");
        assertEquals(1, fgsAttempts.get());
        assertEquals(CallSessionCoordinator.State.ENDED, coordinator.getCurrentState().state);
        assertEquals("FOREGROUND_SERVICE_FAILED", coordinator.getCurrentState().errorCode);
        assertTrue("FGS thất bại không bao giờ được khởi tạo WebRtcCallEngine", createdEngines.isEmpty());
        verify(callApi, never()).startCall(any());

        coordinator.resetToIdle();
        coordinator.onWsFrame("call.incoming", incomingPayload("call-inc-fgs", "Bob"));
        coordinator.acceptIncomingCall();
        assertEquals(2, fgsAttempts.get());
        assertEquals(CallSessionCoordinator.State.ENDED, coordinator.getCurrentState().state);
        assertEquals("FOREGROUND_SERVICE_FAILED", coordinator.getCurrentState().errorCode);
        assertTrue(createdEngines.isEmpty());
        verify(callApi, never()).acceptCall(anyString());

        // Phần B: FGS thành công -> kết nối ICE kích hoạt keepalive mỗi 15s + reconnect; mất ICE dừng keepalive ngay.
        coordinator.resetToIdle();
        coordinator.setForegroundServiceStarter((callId, peerName, sKey) -> true);
        when(callApi.startCall(any())).thenReturn(syncCall(() -> Response.success(201,
                new CallDtos.StartCallResponse("call-ka", "RINGING", null, Collections.emptyList()))));

        coordinator.startOutgoingCall(CONV_1, "u-bob", "Bob", null, null);
        JsonObject acc = new JsonObject();
        acc.addProperty("callId", "call-ka");
        coordinator.onWsFrame("call.accepted", acc);

        FakeWebRtcCallEngine engine = createdEngines.get(0);
        engine.emitIceConnected();
        assertEquals(CallSessionCoordinator.State.ACTIVE, coordinator.getCurrentState().state);

        // Tua 15s -> phát đúng 1 khung call.keepalive mang callId.
        scheduler.advanceBy(15_000L);
        assertEquals(1, signaling.countType("call.keepalive"));
        assertEquals("call-ka", signaling.lastPayloadOf("call.keepalive").get("callId").getAsString());

        // WS reconnect khi ICE đang kết nối -> phát ngay call.keepalive lập tức.
        coordinator.onWebSocketReconnected();
        assertEquals(2, signaling.countType("call.keepalive"));

        // Mất kết nối ICE tạm thời (DISCONNECTED) -> báo connectionLost, ngừng gia hạn kể cả khi tua 30s hoặc WS reconnect.
        engine.emitIceDisconnected("DISCONNECTED");
        assertEquals(CallSessionCoordinator.State.ACTIVE, coordinator.getCurrentState().state);
        assertTrue(coordinator.getCurrentState().connectionLost);

        scheduler.advanceBy(30_000L);
        coordinator.onWebSocketReconnected();
        assertEquals("Không được gửi thêm call.keepalive khi ICE đang mất kết nối",
                2, signaling.countType("call.keepalive"));
    }

    // ======================== HELPERS & FAKES ========================

    private static JsonObject incomingPayload(String callId, String callerName) {
        JsonObject caller = new JsonObject();
        caller.addProperty("id", "u-bob");
        caller.addProperty("displayName", callerName);
        JsonObject payload = new JsonObject();
        payload.addProperty("callId", callId);
        payload.addProperty("conversationId", CONV_1);
        payload.addProperty("state", "RINGING");
        payload.add("caller", caller);
        return payload;
    }

    private static <T> Response<T> errorResponse(int httpCode, String errorCode) {
        String body = "{\"error\":{\"code\":\"" + errorCode + "\",\"message\":\"error\"}}";
        return Response.error(httpCode, ResponseBody.create(body, MediaType.parse("application/json")));
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
            @Override public Request request() { return new Request.Builder().url("http://10.0.2.2:8080/api/calls").build(); }
            @Override public okio.Timeout timeout() { return okio.Timeout.NONE; }
        };
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /** Fake WebRtcCallEngine thuần JVM ghi lại mọi lệnh từ CallSessionCoordinator. */
    private static final class FakeWebRtcCallEngine implements WebRtcCallEngine {
        boolean initialized;
        boolean closed;
        boolean micMuted;
        boolean speakerEnabled;
        int startOfferCalls;
        String acceptedOfferSdp;
        String appliedAnswerSdp;
        final List<String> remoteIceCandidates = new ArrayList<>();
        Listener listener;

        @Override
        public void initialize(List<CallDtos.IceServerDto> iceServers, Listener listener) {
            this.initialized = true;
            this.listener = listener;
        }

        @Override
        public void startOffer() {
            startOfferCalls++;
        }

        @Override
        public void acceptOffer(String remoteOfferSdp) {
            this.acceptedOfferSdp = remoteOfferSdp;
        }

        @Override
        public void applyAnswer(String remoteAnswerSdp) {
            this.appliedAnswerSdp = remoteAnswerSdp;
        }

        @Override
        public void addRemoteIceCandidate(String candidate, String sdpMid, int sdpMLineIndex) {
            remoteIceCandidates.add(candidate);
        }

        @Override
        public void setMicMuted(boolean muted) {
            this.micMuted = muted;
        }

        @Override
        public void setSpeakerEnabled(boolean enabled) {
            this.speakerEnabled = enabled;
        }

        @Override
        public void close() {
            this.closed = true;
        }

        void emitLocalOffer(String sdp) {
            if (listener != null) listener.onLocalOffer(sdp);
        }

        void emitIceConnected() {
            if (listener != null) listener.onIceConnected();
        }

        void emitIceDisconnected(String reason) {
            if (listener != null) listener.onIceDisconnectedOrFailed(reason);
        }
    }

    private static final class RecordedFrame {
        final String type;
        final JsonObject payload;

        RecordedFrame(String type, JsonObject payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    private static final class RecordingSignalingSender implements CallSessionCoordinator.SignalingSender {
        final List<RecordedFrame> frames = new ArrayList<>();

        @Override
        public void sendFrame(String frameType, JsonObject payload) {
            frames.add(new RecordedFrame(frameType, payload));
        }

        int countType(String type) {
            int count = 0;
            for (RecordedFrame f : frames) {
                if (type.equals(f.type)) count++;
            }
            return count;
        }

        JsonObject lastPayloadOf(String type) {
            JsonObject last = null;
            for (RecordedFrame f : frames) {
                if (type.equals(f.type)) last = f.payload;
            }
            return last;
        }
    }

    /** Bộ hẹn giờ giả cho phép tua thời gian chính xác từng mili-giây trên JVM. */
    private static final class FakeDelayScheduler implements CallSessionCoordinator.DelayScheduler {
        private static final class ScheduledTask implements Cancellable {
            final long dueAtMs;
            final Runnable task;
            boolean canceled;
            boolean executed;

            ScheduledTask(long dueAtMs, Runnable task) {
                this.dueAtMs = dueAtMs;
                this.task = task;
            }

            @Override
            public void cancel() {
                canceled = true;
            }
        }

        private long nowMs = 0L;
        private final List<ScheduledTask> tasks = new ArrayList<>();

        @Override
        public Cancellable schedule(long delayMs, Runnable task) {
            ScheduledTask item = new ScheduledTask(nowMs + Math.max(0L, delayMs), task);
            tasks.add(item);
            return item;
        }

        void advanceBy(long deltaMs) {
            long target = nowMs + deltaMs;
            while (true) {
                ScheduledTask next = null;
                for (ScheduledTask t : tasks) {
                    if (!t.canceled && !t.executed && t.dueAtMs <= target) {
                        if (next == null || t.dueAtMs < next.dueAtMs) {
                            next = t;
                        }
                    }
                }
                if (next == null) {
                    break;
                }
                nowMs = next.dueAtMs;
                next.executed = true;
                next.task.run();
            }
            nowMs = target;
        }
    }
}
