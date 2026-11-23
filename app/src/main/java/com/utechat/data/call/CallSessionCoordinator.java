package com.utechat.data.call;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.utechat.data.remote.api.CallApi;
import com.utechat.data.remote.model.CallDtos;
import com.utechat.data.session.SessionManager;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import okhttp3.ResponseBody;
import retrofit2.Response;

/**
 * Máy trạng thái điều phối cuộc gọi thoại WebRTC 1-1 thuần Java (D-10, D-11, D-12):
 * - Tách hoàn toàn khỏi SyncEngine, NotificationHelper và org.webrtc.* để kiểm thử 100% trên JVM.
 * - Quản lý chuyển trạng thái IDLE -> OUTGOING_RINGING/INCOMING_RINGING -> CONNECTING -> ACTIVE -> ENDED.
 * - Bảo vệ chặt chẽ theo (sessionKey, callId), đệm offer/ICE đến sớm trong lúc chờ POST /accept,
 *   gia hạn call.keepalive mỗi 15s khi ICE đang kết nối và ngừng gia hạn khi mất ICE hoặc kết thúc.
 * - Hạn 45s cục bộ chỉ tắt chuông/vô hiệu hóa thao tác cũ, tuyệt đối không tự gửi /end để chờ server chốt.
 */
public class CallSessionCoordinator {
    public static final long DEFAULT_RINGING_TIMEOUT_MS = 45_000L;
    public static final long KEEPALIVE_INTERVAL_MS = 15_000L;

    /** Bộ hẹn giờ trừu tượng để JVM test điều khiển thời gian không cần Thread.sleep. */
    public interface DelayScheduler {
        interface Cancellable {
            void cancel();
        }

        @NonNull
        Cancellable schedule(long delayMs, @NonNull Runnable task);
    }

    /** Kênh gửi khung WebSocket báo hiệu (khớp ChatWebSocketManager.sendCallSignalingFrame và API_CONTRACT §3). */
    public interface SignalingSender {
        void sendFrame(@NonNull String frameType, @NonNull JsonObject payload);
    }

    /** Callback kích hoạt/hủy thông báo cuộc gọi đến (nối dây với NotificationHelper ở Plan 06-08). */
    public interface IncomingCallAlertListener {
        void onIncomingCallAlert(@NonNull CallUiState state);

        void onCancelIncomingCallAlert(@NonNull String callId);
    }

    /**
     * Cổng khởi chạy Foreground Service microphone (RECORD_AUDIO == GRANTED):
     * nếu trả false (thiếu quyền hoặc Android 14–16 từ chối FGS), tuyệt đối không khởi tạo WebRtcCallEngine thu âm.
     */
    public interface ForegroundServiceStarter {
        boolean startForCall(@Nullable String callId, @Nullable String peerName, @Nullable String sessionKey);
    }

    /** Điểm nối tĩnh Wave 3 giúp CallService/CallViewModel/CallFragment lấy singleton trước khi 06-08 nối Injection. */
    public interface Provider {
        @Nullable
        CallSessionCoordinator get(@NonNull Context context);
    }

    private static volatile Provider sProvider;
    private static volatile CallSessionCoordinator sInstance;

    public static void setProvider(@Nullable Provider provider) {
        sProvider = provider;
    }

    public static void setInstance(@Nullable CallSessionCoordinator instance) {
        sInstance = instance;
    }

    @Nullable
    public static CallSessionCoordinator from(@NonNull Context context) {
        CallSessionCoordinator direct = sInstance;
        if (direct != null) {
            return direct;
        }
        Provider provider = sProvider;
        return provider != null ? provider.get(context.getApplicationContext()) : null;
    }

    /** Các trạng thái vòng đời cuộc gọi hiển thị lên CallFragment. */
    public enum State {
        IDLE,
        OUTGOING_RINGING,
        INCOMING_RINGING,
        CONNECTING,
        ACTIVE,
        ENDED
    }

    /** Ảnh chụp trạng thái bất biến cho UI, giữ answeredAtElapsedRealtime giúp đồng hồ mm:ss sống sót khi xoay màn hình. */
    public static final class CallUiState {
        @NonNull public final State state;
        @Nullable public final String callId;
        @Nullable public final String conversationId;
        @Nullable public final String peerUserId;
        @Nullable public final String peerName;
        @Nullable public final String peerAvatarUrl;
        @Nullable public final String peerPhone;
        public final boolean incoming;
        public final boolean micMuted;
        public final boolean speakerOn;
        public final long answeredAtElapsedRealtime;
        public final long ringingExpiresAtEpochMs;
        public final boolean ringingDeadlinePassed;
        public final boolean connectionLost;
        @Nullable public final String errorCode;
        @Nullable public final String endReason;

        public CallUiState(
                @NonNull State state,
                @Nullable String callId,
                @Nullable String conversationId,
                @Nullable String peerUserId,
                @Nullable String peerName,
                @Nullable String peerAvatarUrl,
                @Nullable String peerPhone,
                boolean incoming,
                boolean micMuted,
                boolean speakerOn,
                long answeredAtElapsedRealtime,
                long ringingExpiresAtEpochMs,
                boolean ringingDeadlinePassed,
                boolean connectionLost,
                @Nullable String errorCode,
                @Nullable String endReason) {
            this.state = state;
            this.callId = callId;
            this.conversationId = conversationId;
            this.peerUserId = peerUserId;
            this.peerName = peerName;
            this.peerAvatarUrl = peerAvatarUrl;
            this.peerPhone = peerPhone;
            this.incoming = incoming;
            this.micMuted = micMuted;
            this.speakerOn = speakerOn;
            this.answeredAtElapsedRealtime = answeredAtElapsedRealtime;
            this.ringingExpiresAtEpochMs = ringingExpiresAtEpochMs;
            this.ringingDeadlinePassed = ringingDeadlinePassed;
            this.connectionLost = connectionLost;
            this.errorCode = errorCode;
            this.endReason = endReason;
        }

        @NonNull
        public static CallUiState idle() {
            return new CallUiState(State.IDLE, null, null, null, null, null, null,
                    false, false, false, 0L, 0L, false, false, null, null);
        }
    }

    private static final class PendingIce {
        @NonNull final String candidate;
        @Nullable final String sdpMid;
        final int sdpMLineIndex;

        PendingIce(@NonNull String candidate, @Nullable String sdpMid, int sdpMLineIndex) {
            this.candidate = candidate;
            this.sdpMid = sdpMid;
            this.sdpMLineIndex = sdpMLineIndex;
        }
    }

    private final CallApi callApi;
    private final SessionManager session;
    private final Supplier<WebRtcCallEngine> engineFactory;
    private final SignalingSender signalingSender;
    private final DelayScheduler delayScheduler;
    private final LongSupplier elapsedClockMs;
    private final LongSupplier wallClockMs;
    private final Executor networkExecutor;
    private final Executor mainExecutor;

    private final Object stateLock = new Object();
    private final MutableLiveData<CallUiState> uiState = new MutableLiveData<>(CallUiState.idle());

    @Nullable private volatile IncomingCallAlertListener incomingCallAlertListener;
    @Nullable private volatile ForegroundServiceStarter foregroundServiceStarter;

    @NonNull private CallUiState current = CallUiState.idle();
    @Nullable private String activeSessionKey;
    @Nullable private String activeCallId;
    @Nullable private WebRtcCallEngine activeEngine;
    @Nullable private DelayScheduler.Cancellable ringingTimer;
    @Nullable private DelayScheduler.Cancellable keepaliveTimer;

    private boolean acceptPending = false;
    private boolean iceConnected = false;
    @Nullable private String pendingRemoteOfferSdp;
    private final List<PendingIce> pendingEarlyIce = new ArrayList<>();

    public CallSessionCoordinator(
            @NonNull CallApi callApi,
            @NonNull SessionManager session,
            @NonNull Supplier<WebRtcCallEngine> engineFactory,
            @NonNull SignalingSender signalingSender,
            @NonNull DelayScheduler delayScheduler,
            @NonNull LongSupplier elapsedClockMs,
            @NonNull Executor networkExecutor,
            @NonNull Executor mainExecutor) {
        this(callApi, session, engineFactory, signalingSender, delayScheduler,
                elapsedClockMs, System::currentTimeMillis, networkExecutor, mainExecutor);
    }

    public CallSessionCoordinator(
            @NonNull CallApi callApi,
            @NonNull SessionManager session,
            @NonNull Supplier<WebRtcCallEngine> engineFactory,
            @NonNull SignalingSender signalingSender,
            @NonNull DelayScheduler delayScheduler,
            @NonNull LongSupplier elapsedClockMs,
            @NonNull LongSupplier wallClockMs,
            @NonNull Executor networkExecutor,
            @NonNull Executor mainExecutor) {
        this.callApi = callApi;
        this.session = session;
        this.engineFactory = engineFactory;
        this.signalingSender = signalingSender;
        this.delayScheduler = delayScheduler;
        this.elapsedClockMs = elapsedClockMs;
        this.wallClockMs = wallClockMs;
        this.networkExecutor = networkExecutor;
        this.mainExecutor = mainExecutor;
    }

    @NonNull
    public LiveData<CallUiState> getUiState() {
        return uiState;
    }

    @NonNull
    public CallUiState getCurrentState() {
        synchronized (stateLock) {
            return current;
        }
    }

    public void setIncomingCallAlertListener(@Nullable IncomingCallAlertListener listener) {
        this.incomingCallAlertListener = listener;
    }

    public void setForegroundServiceStarter(@Nullable ForegroundServiceStarter starter) {
        this.foregroundServiceStarter = starter;
    }

    // ======================== KHỞI TẠO / THAO TÁC NGƯỜI DÙNG ========================

    /**
     * Khởi tạo cuộc gọi thoại 1-1 đi ra (POST /api/calls):
     * - Kiểm tra ForegroundServiceStarter trước khi khởi động thu âm WebRTC.
     * - Khi 201: khởi tạo WebRtcCallEngine với iceServers, chuyển OUTGOING_RINGING và đặt đếm ngược hiển thị expiresAt.
     * - Khi lỗi chính sách (409 CALL_BUSY, 403 BLOCKED/CONTACT_REQUIRED, 400 CALL_UNSUPPORTED, 429): chuyển ENDED kèm errorCode.
     */
    public void startOutgoingCall(
            @NonNull String conversationId,
            @Nullable String peerUserId,
            @Nullable String peerName,
            @Nullable String peerAvatarUrl,
            @Nullable String peerPhone) {
        final String sessionKey = session.sessionKey();
        if (sessionKey == null) {
            return;
        }

        synchronized (stateLock) {
            if (current.state != State.IDLE && current.state != State.ENDED) {
                return;
            }
            resetInternalResourcesLocked();
            activeSessionKey = sessionKey;
            activeCallId = null;

            // Kiểm tra cổng Foreground Service microphone trước khi thu âm hoặc gọi API.
            ForegroundServiceStarter starter = foregroundServiceStarter;
            if (starter != null && !starter.startForCall(null, peerName, sessionKey)) {
                publishLocked(new CallUiState(
                        State.ENDED, null, conversationId, peerUserId, peerName, peerAvatarUrl, peerPhone,
                        false, false, false, 0L, 0L, false, false,
                        "FOREGROUND_SERVICE_FAILED", "ERROR"));
                return;
            }

            publishLocked(new CallUiState(
                    State.OUTGOING_RINGING, null, conversationId, peerUserId, peerName, peerAvatarUrl, peerPhone,
                    false, false, false, 0L, 0L, false, false, null, null));
        }

        networkExecutor.execute(() -> {
            try {
                Response<CallDtos.StartCallResponse> resp = callApi
                        .startCall(new CallDtos.StartCallRequest(conversationId, "VOICE"))
                        .execute();
                if (!resp.isSuccessful() || resp.body() == null || resp.body().callId == null) {
                    String code = parseErrorCode(resp, defaultStartErrorCode(resp.code()));
                    finishWithErrorIfSessionMatches(sessionKey, null, code, "ERROR");
                    return;
                }
                CallDtos.StartCallResponse body = resp.body();
                long expiresAtMs = parseExpiresAtEpochMs(body.expiresAt);
                long delayMs = computeRingingDelayMs(expiresAtMs);

                WebRtcCallEngine createdEngine = null;
                List<CallDtos.IceServerDto> servers = body.iceServers != null
                        ? body.iceServers : Collections.emptyList();
                final String callId = body.callId;

                synchronized (stateLock) {
                    if (!sessionKey.equals(session.sessionKey())
                            || !sessionKey.equals(activeSessionKey)
                            || current.state != State.OUTGOING_RINGING) {
                        return;
                    }
                    activeCallId = callId;
                    createdEngine = engineFactory.get();
                    activeEngine = createdEngine;
                    publishLocked(new CallUiState(
                            State.OUTGOING_RINGING, callId, conversationId, peerUserId, peerName,
                            peerAvatarUrl, peerPhone, false, current.micMuted, current.speakerOn,
                            0L, expiresAtMs, false, false, null, null));
                    scheduleRingingDeadlineLocked(sessionKey, callId, delayMs);
                }

                if (createdEngine != null) {
                    createdEngine.initialize(servers, new EngineCallback(sessionKey, callId));
                }
            } catch (IOException e) {
                finishWithErrorIfSessionMatches(sessionKey, null, "NETWORK", "ERROR");
            }
        });
    }

    /**
     * Người nhận bấm Nghe máy (POST /api/calls/{id}/accept):
     * - Kiểm tra ForegroundServiceStarter trước khi nhận máy và khởi tạo thu âm.
     * - Chuyển INCOMING_RINGING -> CONNECTING, đệm offer/ICE đến sớm trong lúc chờ HTTP 200.
     * - Sau 200 OK: khởi tạo WebRtcCallEngine(iceServers) và xả ngay offer/ICE đã đệm.
     */
    public void acceptIncomingCall() {
        final String sessionKey;
        final String callId;
        final String peerName;
        synchronized (stateLock) {
            if (current.state != State.INCOMING_RINGING
                    || current.ringingDeadlinePassed
                    || activeCallId == null
                    || activeSessionKey == null
                    || !activeSessionKey.equals(session.sessionKey())) {
                return;
            }
            sessionKey = activeSessionKey;
            callId = activeCallId;
            peerName = current.peerName;

            ForegroundServiceStarter starter = foregroundServiceStarter;
            if (starter != null && !starter.startForCall(callId, peerName, sessionKey)) {
                cancelIncomingAlert(callId);
                cancelTimersLocked();
                closeEngineLocked();
                publishLocked(withEnded(current, "FOREGROUND_SERVICE_FAILED", "ERROR"));
                return;
            }

            cancelRingingTimerLocked();
            cancelIncomingAlert(callId);
            acceptPending = true;
            publishLocked(new CallUiState(
                    State.CONNECTING, callId, current.conversationId, current.peerUserId,
                    current.peerName, current.peerAvatarUrl, current.peerPhone,
                    true, current.micMuted, current.speakerOn,
                    0L, current.ringingExpiresAtEpochMs, false, false, null, null));
        }

        networkExecutor.execute(() -> {
            try {
                Response<CallDtos.AcceptCallResponse> resp = callApi.acceptCall(callId).execute();
                if (!resp.isSuccessful() || resp.body() == null) {
                    String code = parseErrorCode(resp, resp.code() == 409 ? "INVALID_STATE" : "ERROR");
                    finishWithErrorIfSessionMatches(sessionKey, callId, code, "ERROR");
                    return;
                }
                List<CallDtos.IceServerDto> servers = resp.body().iceServers != null
                        ? resp.body().iceServers : Collections.emptyList();

                WebRtcCallEngine engine;
                String bufferedOffer;
                List<PendingIce> bufferedIce;
                boolean muted;
                boolean speaker;
                synchronized (stateLock) {
                    if (!sessionKey.equals(session.sessionKey())
                            || !sessionKey.equals(activeSessionKey)
                            || !callId.equals(activeCallId)
                            || current.state != State.CONNECTING) {
                        acceptPending = false;
                        return;
                    }
                    acceptPending = false;
                    engine = engineFactory.get();
                    activeEngine = engine;
                    muted = current.micMuted;
                    speaker = current.speakerOn;
                    bufferedOffer = pendingRemoteOfferSdp;
                    pendingRemoteOfferSdp = null;
                    bufferedIce = new ArrayList<>(pendingEarlyIce);
                    pendingEarlyIce.clear();
                }

                if (engine != null) {
                    engine.initialize(servers, new EngineCallback(sessionKey, callId));
                    engine.setMicMuted(muted);
                    engine.setSpeakerEnabled(speaker);
                    if (bufferedOffer != null) {
                        engine.acceptOffer(bufferedOffer);
                    }
                    for (PendingIce ice : bufferedIce) {
                        engine.addRemoteIceCandidate(ice.candidate, ice.sdpMid, ice.sdpMLineIndex);
                    }
                }
            } catch (IOException e) {
                finishWithErrorIfSessionMatches(sessionKey, callId, "NETWORK", "ERROR");
            }
        });
    }

    /** Từ chối cuộc gọi đến (POST /api/calls/{id}/reject). */
    public void rejectIncomingCall() {
        final String callId;
        synchronized (stateLock) {
            if (current.state != State.INCOMING_RINGING
                    || current.ringingDeadlinePassed
                    || activeCallId == null) {
                return;
            }
            callId = activeCallId;
            cancelIncomingAlert(callId);
            cancelTimersLocked();
            closeEngineLocked();
            clearSignalingBuffersLocked();
            publishLocked(withEnded(current, null, "REJECT"));
        }
        networkExecutor.execute(() -> {
            try {
                callApi.rejectCall(callId).execute();
            } catch (IOException ignored) {
            }
        });
    }

    /** Kết thúc hoặc hủy cuộc gọi hiện tại (POST /api/calls/{id}/end). */
    public void endCall() {
        final String callIdToEnd;
        synchronized (stateLock) {
            if (current.state == State.IDLE || current.state == State.ENDED) {
                return;
            }
            if (current.state == State.INCOMING_RINGING) {
                rejectIncomingCall();
                return;
            }
            boolean allowRestEnd = activeCallId != null
                    && !(current.state == State.OUTGOING_RINGING && current.ringingDeadlinePassed);
            callIdToEnd = allowRestEnd ? activeCallId : null;
            if (activeCallId != null) {
                cancelIncomingAlert(activeCallId);
            }
            cancelTimersLocked();
            closeEngineLocked();
            clearSignalingBuffersLocked();
            publishLocked(withEnded(current, null, "HANGUP"));
        }
        if (callIdToEnd != null) {
            networkExecutor.execute(() -> {
                try {
                    callApi.endCall(callIdToEnd).execute();
                } catch (IOException ignored) {
                }
            });
        }
    }

    /** Báo lỗi khởi chạy Foreground Service từ CallService/CallFragment để dọn trạng thái mà không thu âm. */
    public void onForegroundServiceStartFailed() {
        synchronized (stateLock) {
            if (current.state == State.IDLE || current.state == State.ENDED) {
                return;
            }
            if (activeCallId != null) {
                cancelIncomingAlert(activeCallId);
            }
            cancelTimersLocked();
            closeEngineLocked();
            clearSignalingBuffersLocked();
            publishLocked(withEnded(current, "FOREGROUND_SERVICE_FAILED", "ERROR"));
        }
    }

    public void toggleMute() {
        setMicMuted(!getCurrentState().micMuted);
    }

    public void setMicMuted(boolean muted) {
        WebRtcCallEngine engine;
        synchronized (stateLock) {
            if (current.state == State.IDLE || current.state == State.ENDED) {
                return;
            }
            engine = activeEngine;
            publishLocked(new CallUiState(
                    current.state, current.callId, current.conversationId, current.peerUserId,
                    current.peerName, current.peerAvatarUrl, current.peerPhone,
                    current.incoming, muted, current.speakerOn,
                    current.answeredAtElapsedRealtime, current.ringingExpiresAtEpochMs,
                    current.ringingDeadlinePassed, current.connectionLost,
                    current.errorCode, current.endReason));
        }
        if (engine != null) {
            engine.setMicMuted(muted);
        }
    }

    public void toggleSpeaker() {
        setSpeakerEnabled(!getCurrentState().speakerOn);
    }

    public void setSpeakerEnabled(boolean enabled) {
        WebRtcCallEngine engine;
        synchronized (stateLock) {
            if (current.state == State.IDLE || current.state == State.ENDED) {
                return;
            }
            engine = activeEngine;
            publishLocked(new CallUiState(
                    current.state, current.callId, current.conversationId, current.peerUserId,
                    current.peerName, current.peerAvatarUrl, current.peerPhone,
                    current.incoming, current.micMuted, enabled,
                    current.answeredAtElapsedRealtime, current.ringingExpiresAtEpochMs,
                    current.ringingDeadlinePassed, current.connectionLost,
                    current.errorCode, current.endReason));
        }
        if (engine != null) {
            engine.setSpeakerEnabled(enabled);
        }
    }

    /** Đưa coordinator về IDLE sau khi CallFragment đóng màn hình ENDED. */
    public void resetToIdle() {
        synchronized (stateLock) {
            if (current.state == State.ENDED) {
                resetInternalResourcesLocked();
                publishLocked(CallUiState.idle());
            }
        }
    }

    /** Dọn sạch mọi tài nguyên WebRTC, chuông, timer và đưa về IDLE khi đăng xuất / 401 / 4401 (T-06-20). */
    public void onSessionCleared() {
        String oldCallId;
        synchronized (stateLock) {
            oldCallId = activeCallId;
            resetInternalResourcesLocked();
            publishLocked(CallUiState.idle());
        }
        if (oldCallId != null) {
            cancelIncomingAlert(oldCallId);
        }
    }

    // ======================== BỘ PHÂN PHỐI KHUNG WEBSOCKET ========================

    /** Bộ phân phối thống nhất cho mọi khung WebSocket call.* từ ChatWebSocketManager (Plan 06-08). */
    public void onWsFrame(@NonNull String type, @NonNull JsonObject payload) {
        switch (type) {
            case "call.incoming":
                onIncomingCallFrame(payload);
                break;
            case "call.accepted":
                onCallAcceptedFrame(payload);
                break;
            case "call.ended":
                onCallEndedFrame(payload, null);
                break;
            case "call.handled_elsewhere":
                onHandledElsewhereFrame(payload);
                break;
            case "call.offer":
                onOfferFrame(payload);
                break;
            case "call.answer":
                onAnswerFrame(payload);
                break;
            case "call.ice":
                onIceFrame(payload);
                break;
            default:
                // Bỏ qua an toàn các loại sự kiện không thuộc cuộc gọi.
                break;
        }
    }

    /**
     * Gọi khi WebSocket kết nối lại trên cùng phiên:
     * nếu cuộc gọi đang ACTIVE và ICE vẫn đang kết nối thì gửi ngay call.keepalive và đặt lại chu kỳ 15s.
     */
    public void onWebSocketReconnected() {
        final String callIdToRenew;
        synchronized (stateLock) {
            String currentSession = session.sessionKey();
            if (current.state != State.ACTIVE
                    || !iceConnected
                    || activeCallId == null
                    || activeSessionKey == null
                    || !activeSessionKey.equals(currentSession)) {
                return;
            }
            callIdToRenew = activeCallId;
            scheduleKeepaliveLocked(activeSessionKey, callIdToRenew);
        }
        sendKeepaliveFrame(callIdToRenew);
    }

    public void onIncomingCallFrame(@NonNull JsonObject payload) {
        String sessionKey = session.sessionKey();
        if (sessionKey == null) {
            return;
        }
        String callId = optString(payload, "callId");
        if (callId == null) {
            return;
        }
        String conversationId = optString(payload, "conversationId");
        String callerId = null;
        String callerName = null;
        String callerAvatar = null;
        if (payload.has("caller") && payload.get("caller").isJsonObject()) {
            JsonObject caller = payload.getAsJsonObject("caller");
            callerId = optString(caller, "id");
            callerName = optString(caller, "displayName");
            callerAvatar = optString(caller, "avatarUrl");
        }
        long expiresAtMs = parseExpiresAtEpochMs(optString(payload, "expiresAt"));
        long delayMs = computeRingingDelayMs(expiresAtMs);

        CallUiState incomingState;
        synchronized (stateLock) {
            // Đang trong cuộc gọi khác -> bỏ qua lặng lẽ, không làm hỏng phiên đang chạy (T-06-18).
            if (current.state != State.IDLE && current.state != State.ENDED) {
                return;
            }
            resetInternalResourcesLocked();
            activeSessionKey = sessionKey;
            activeCallId = callId;
            incomingState = new CallUiState(
                    State.INCOMING_RINGING, callId, conversationId, callerId, callerName, callerAvatar, null,
                    true, false, false, 0L, expiresAtMs, false, false, null, null);
            publishLocked(incomingState);
            scheduleRingingDeadlineLocked(sessionKey, callId, delayMs);
        }

        IncomingCallAlertListener alertListener = incomingCallAlertListener;
        if (alertListener != null) {
            alertListener.onIncomingCallAlert(incomingState);
        }
    }

    public void onCallAcceptedFrame(@NonNull JsonObject payload) {
        String callId = optString(payload, "callId");
        if (callId == null) {
            return;
        }
        WebRtcCallEngine engine;
        synchronized (stateLock) {
            if (!matchesActiveCallLocked(callId) || current.state != State.OUTGOING_RINGING) {
                return;
            }
            cancelRingingTimerLocked();
            engine = activeEngine;
            publishLocked(new CallUiState(
                    State.CONNECTING, callId, current.conversationId, current.peerUserId,
                    current.peerName, current.peerAvatarUrl, current.peerPhone,
                    false, current.micMuted, current.speakerOn,
                    0L, current.ringingExpiresAtEpochMs, false, false, null, null));
        }
        if (engine != null) {
            engine.startOffer();
        }
    }

    public void onCallEndedFrame(@NonNull JsonObject payload, @Nullable Long syncEventId) {
        String callId = optString(payload, "callId");
        if (callId == null) {
            return;
        }
        String rawState = optString(payload, "state");
        String endReason = optString(payload, "endReason");
        if (endReason == null) {
            endReason = fallbackEndReasonFromState(rawState);
        }
        String errorCode = "FAILED".equalsIgnoreCase(rawState) ? "OFFLINE" : null;

        synchronized (stateLock) {
            if (!matchesActiveCallLocked(callId)) {
                return;
            }
            if (current.state == State.IDLE) {
                return;
            }
            cancelTimersLocked();
            closeEngineLocked();
            clearSignalingBuffersLocked();
            publishLocked(withEnded(current, errorCode, endReason));
        }
        cancelIncomingAlert(callId);
    }

    public void onHandledElsewhereFrame(@NonNull JsonObject payload) {
        String callId = optString(payload, "callId");
        if (callId == null) {
            return;
        }
        synchronized (stateLock) {
            if (!matchesActiveCallLocked(callId)) {
                return;
            }
            if (current.state != State.INCOMING_RINGING && current.state != State.CONNECTING) {
                return;
            }
            cancelTimersLocked();
            closeEngineLocked();
            clearSignalingBuffersLocked();
            publishLocked(withEnded(current, null, "HANDLED_ELSEWHERE"));
        }
        cancelIncomingAlert(callId);
    }

    public void onOfferFrame(@NonNull JsonObject payload) {
        String callId = optString(payload, "callId");
        String sdp = optString(payload, "sdp");
        if (callId == null || sdp == null) {
            return;
        }
        WebRtcCallEngine engine = null;
        synchronized (stateLock) {
            if (!matchesActiveCallLocked(callId)) {
                return;
            }
            if (current.state == State.CONNECTING || current.state == State.ACTIVE) {
                if (activeEngine != null) {
                    engine = activeEngine;
                } else if (acceptPending) {
                    // Đệm offer đến sớm trong lúc POST /accept đang chờ phản hồi 200 OK.
                    pendingRemoteOfferSdp = sdp;
                }
            }
        }
        if (engine != null) {
            engine.acceptOffer(sdp);
        }
    }

    public void onAnswerFrame(@NonNull JsonObject payload) {
        String callId = optString(payload, "callId");
        String sdp = optString(payload, "sdp");
        if (callId == null || sdp == null) {
            return;
        }
        WebRtcCallEngine engine = null;
        synchronized (stateLock) {
            if (!matchesActiveCallLocked(callId)) {
                return;
            }
            if (current.state == State.CONNECTING || current.state == State.ACTIVE) {
                engine = activeEngine;
            }
        }
        if (engine != null) {
            engine.applyAnswer(sdp);
        }
    }

    public void onIceFrame(@NonNull JsonObject payload) {
        String callId = optString(payload, "callId");
        String candidate = optString(payload, "candidate");
        if (callId == null || candidate == null) {
            return;
        }
        String sdpMid = optString(payload, "sdpMid");
        int sdpMLineIndex = optInt(payload, "sdpMLineIndex", 0);
        WebRtcCallEngine engine = null;
        synchronized (stateLock) {
            if (!matchesActiveCallLocked(callId)) {
                return;
            }
            if (current.state == State.CONNECTING || current.state == State.ACTIVE) {
                if (activeEngine != null) {
                    engine = activeEngine;
                } else if (acceptPending) {
                    pendingEarlyIce.add(new PendingIce(candidate, sdpMid, sdpMLineIndex));
                }
            }
        }
        if (engine != null) {
            engine.addRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex);
        }
    }

    // ======================== CALLBACK TỪ WEBRTC ENGINE ========================

    private final class EngineCallback implements WebRtcCallEngine.Listener {
        private final String sessionKey;
        private final String callId;

        EngineCallback(@NonNull String sessionKey, @NonNull String callId) {
            this.sessionKey = sessionKey;
            this.callId = callId;
        }

        @Override
        public void onLocalOffer(@NonNull String sdp) {
            if (!isStillMatchingSessionAndCall(sessionKey, callId)) {
                return;
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("callId", callId);
            payload.addProperty("sdp", sdp);
            signalingSender.sendFrame("call.offer", payload);
        }

        @Override
        public void onLocalAnswer(@NonNull String sdp) {
            if (!isStillMatchingSessionAndCall(sessionKey, callId)) {
                return;
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("callId", callId);
            payload.addProperty("sdp", sdp);
            signalingSender.sendFrame("call.answer", payload);
        }

        @Override
        public void onLocalIceCandidate(@NonNull String candidate, @Nullable String sdpMid, int sdpMLineIndex) {
            if (!isStillMatchingSessionAndCall(sessionKey, callId)) {
                return;
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("callId", callId);
            payload.addProperty("candidate", candidate);
            if (sdpMid != null) {
                payload.addProperty("sdpMid", sdpMid);
            }
            payload.addProperty("sdpMLineIndex", sdpMLineIndex);
            signalingSender.sendFrame("call.ice", payload);
        }

        @Override
        public void onIceConnected() {
            synchronized (stateLock) {
                if (!sessionKey.equals(session.sessionKey())
                        || !sessionKey.equals(activeSessionKey)
                        || !callId.equals(activeCallId)) {
                    return;
                }
                if (current.state != State.CONNECTING && current.state != State.ACTIVE) {
                    return;
                }
                iceConnected = true;
                long answeredAt = current.answeredAtElapsedRealtime > 0L
                        ? current.answeredAtElapsedRealtime
                        : elapsedClockMs.getAsLong();
                publishLocked(new CallUiState(
                        State.ACTIVE, callId, current.conversationId, current.peerUserId,
                        current.peerName, current.peerAvatarUrl, current.peerPhone,
                        current.incoming, current.micMuted, current.speakerOn,
                        answeredAt, current.ringingExpiresAtEpochMs, false, false, null, null));
                scheduleKeepaliveLocked(sessionKey, callId);
            }
        }

        @Override
        public void onIceDisconnectedOrFailed(@NonNull String reason) {
            synchronized (stateLock) {
                if (!sessionKey.equals(session.sessionKey())
                        || !sessionKey.equals(activeSessionKey)
                        || !callId.equals(activeCallId)) {
                    return;
                }
                iceConnected = false;
                cancelKeepaliveTimerLocked();

                if ("DISCONNECTED".equals(reason) && current.state == State.ACTIVE) {
                    // Mất kết nối ICE tạm thời: ngừng gửi call.keepalive, giữ UI ACTIVE + connectionLost trong hạn 60s server.
                    publishLocked(new CallUiState(
                            State.ACTIVE, callId, current.conversationId, current.peerUserId,
                            current.peerName, current.peerAvatarUrl, current.peerPhone,
                            current.incoming, current.micMuted, current.speakerOn,
                            current.answeredAtElapsedRealtime, current.ringingExpiresAtEpochMs,
                            false, true, null, null));
                    return;
                }

                if (current.state == State.CONNECTING || current.state == State.ACTIVE) {
                    cancelTimersLocked();
                    closeEngineLocked();
                    clearSignalingBuffersLocked();
                    publishLocked(withEnded(current, reason, "ERROR"));
                }
            }
        }
    }

    // ======================== HỖ TRỢ NỘI BỘ ========================

    private void scheduleRingingDeadlineLocked(@NonNull String sessionKey, @NonNull String callId, long delayMs) {
        cancelRingingTimerLocked();
        ringingTimer = delayScheduler.schedule(delayMs, () -> onLocalRingingDeadlineReached(sessionKey, callId));
    }

    /**
     * Khi hết hạn đổ chuông 45s cục bộ:
     * - Tuyệt đối KHÔNG gọi POST /end hay /reject (server sweeper là nguồn sự thật).
     * - Tắt chuông/thông báo đến, vô hiệu hóa thao tác cũ, đóng engine và chuyển ENDED (TIMEOUT).
     */
    private void onLocalRingingDeadlineReached(@NonNull String sessionKey, @NonNull String callId) {
        synchronized (stateLock) {
            if (!sessionKey.equals(activeSessionKey) || !callId.equals(activeCallId)) {
                return;
            }
            if (current.state != State.OUTGOING_RINGING && current.state != State.INCOMING_RINGING) {
                return;
            }
            ringingTimer = null;
            closeEngineLocked();
            clearSignalingBuffersLocked();
            publishLocked(new CallUiState(
                    State.ENDED, callId, current.conversationId, current.peerUserId,
                    current.peerName, current.peerAvatarUrl, current.peerPhone,
                    current.incoming, current.micMuted, current.speakerOn,
                    0L, current.ringingExpiresAtEpochMs, true, false, null, "TIMEOUT"));
        }
        cancelIncomingAlert(callId);
    }

    private void scheduleKeepaliveLocked(@NonNull String sessionKey, @NonNull String callId) {
        cancelKeepaliveTimerLocked();
        keepaliveTimer = delayScheduler.schedule(KEEPALIVE_INTERVAL_MS, () -> {
            boolean shouldSend = false;
            synchronized (stateLock) {
                if (sessionKey.equals(session.sessionKey())
                        && sessionKey.equals(activeSessionKey)
                        && callId.equals(activeCallId)
                        && current.state == State.ACTIVE
                        && iceConnected) {
                    shouldSend = true;
                    scheduleKeepaliveLocked(sessionKey, callId);
                }
            }
            if (shouldSend) {
                sendKeepaliveFrame(callId);
            }
        });
    }

    private void sendKeepaliveFrame(@NonNull String callId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("callId", callId);
        signalingSender.sendFrame("call.keepalive", payload);
    }

    private void finishWithErrorIfSessionMatches(
            @NonNull String sessionKey,
            @Nullable String expectedCallId,
            @NonNull String errorCode,
            @NonNull String endReason) {
        synchronized (stateLock) {
            if (!sessionKey.equals(session.sessionKey()) || !sessionKey.equals(activeSessionKey)) {
                return;
            }
            if (expectedCallId != null && !expectedCallId.equals(activeCallId)) {
                return;
            }
            cancelTimersLocked();
            closeEngineLocked();
            clearSignalingBuffersLocked();
            publishLocked(withEnded(current, errorCode, endReason));
        }
    }

    private boolean isStillMatchingSessionAndCall(@NonNull String sessionKey, @NonNull String callId) {
        synchronized (stateLock) {
            return sessionKey.equals(session.sessionKey())
                    && sessionKey.equals(activeSessionKey)
                    && callId.equals(activeCallId)
                    && (current.state == State.CONNECTING || current.state == State.ACTIVE);
        }
    }

    private boolean matchesActiveCallLocked(@NonNull String callId) {
        String currentSession = session.sessionKey();
        return currentSession != null
                && currentSession.equals(activeSessionKey)
                && callId.equals(activeCallId);
    }

    private void resetInternalResourcesLocked() {
        cancelTimersLocked();
        closeEngineLocked();
        clearSignalingBuffersLocked();
        activeSessionKey = null;
        activeCallId = null;
    }

    private void cancelTimersLocked() {
        cancelRingingTimerLocked();
        cancelKeepaliveTimerLocked();
    }

    private void cancelRingingTimerLocked() {
        if (ringingTimer != null) {
            ringingTimer.cancel();
            ringingTimer = null;
        }
    }

    private void cancelKeepaliveTimerLocked() {
        if (keepaliveTimer != null) {
            keepaliveTimer.cancel();
            keepaliveTimer = null;
        }
    }

    private void closeEngineLocked() {
        iceConnected = false;
        if (activeEngine != null) {
            WebRtcCallEngine toClose = activeEngine;
            activeEngine = null;
            toClose.close();
        }
    }

    private void clearSignalingBuffersLocked() {
        acceptPending = false;
        pendingRemoteOfferSdp = null;
        pendingEarlyIce.clear();
    }

    private void publishLocked(@NonNull CallUiState next) {
        current = next;
        mainExecutor.execute(() -> uiState.setValue(next));
    }

    private void cancelIncomingAlert(@NonNull String callId) {
        IncomingCallAlertListener listener = incomingCallAlertListener;
        if (listener != null) {
            listener.onCancelIncomingCallAlert(callId);
        }
    }

    @NonNull
    private static CallUiState withEnded(
            @NonNull CallUiState base, @Nullable String errorCode, @Nullable String endReason) {
        return new CallUiState(
                State.ENDED, base.callId, base.conversationId, base.peerUserId,
                base.peerName, base.peerAvatarUrl, base.peerPhone,
                base.incoming, base.micMuted, base.speakerOn,
                base.answeredAtElapsedRealtime, base.ringingExpiresAtEpochMs,
                base.ringingDeadlinePassed, false, errorCode, endReason);
    }

    private long parseExpiresAtEpochMs(@Nullable String iso) {
        if (iso == null || iso.trim().isEmpty()) {
            return wallClockMs.getAsLong() + DEFAULT_RINGING_TIMEOUT_MS;
        }
        try {
            return Instant.parse(iso.trim()).toEpochMilli();
        } catch (Exception e) {
            return wallClockMs.getAsLong() + DEFAULT_RINGING_TIMEOUT_MS;
        }
    }

    private long computeRingingDelayMs(long expiresAtEpochMs) {
        long remaining = expiresAtEpochMs - wallClockMs.getAsLong();
        if (remaining <= 0L) {
            return 1_000L;
        }
        return Math.min(remaining, DEFAULT_RINGING_TIMEOUT_MS);
    }

    @NonNull
    private static String parseErrorCode(@NonNull Response<?> resp, @NonNull String fallback) {
        try (ResponseBody err = resp.errorBody()) {
            if (err != null) {
                String body = err.string();
                if (body != null && !body.trim().isEmpty()) {
                    JsonElement parsed = JsonParser.parseString(body);
                    if (parsed.isJsonObject()) {
                        JsonObject root = parsed.getAsJsonObject();
                        if (root.has("error") && root.get("error").isJsonObject()) {
                            String nested = optString(root.getAsJsonObject("error"), "code");
                            if (nested != null) {
                                return nested;
                            }
                        }
                        String direct = optString(root, "code");
                        if (direct != null) {
                            return direct;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    @NonNull
    private static String defaultStartErrorCode(int httpCode) {
        switch (httpCode) {
            case 400: return "CALL_UNSUPPORTED";
            case 401: return "UNAUTHORIZED";
            case 403: return "FORBIDDEN";
            case 409: return "CALL_BUSY";
            case 429: return "RATE_LIMITED";
            default: return "ERROR";
        }
    }

    @NonNull
    private static String fallbackEndReasonFromState(@Nullable String state) {
        if ("MISSED".equalsIgnoreCase(state)) return "TIMEOUT";
        if ("REJECTED".equalsIgnoreCase(state)) return "REJECT";
        if ("BUSY".equalsIgnoreCase(state)) return "BUSY";
        if ("FAILED".equalsIgnoreCase(state)) return "OFFLINE";
        return "HANGUP";
    }

    @Nullable
    private static String optString(@NonNull JsonObject obj, @NonNull String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return null;
        }
        String val = el.getAsString();
        return val != null && !val.trim().isEmpty() ? val.trim() : null;
    }

    private static int optInt(@NonNull JsonObject obj, @NonNull String key, int fallback) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return el.getAsInt();
        } catch (Exception e) {
            return fallback;
        }
    }
}
