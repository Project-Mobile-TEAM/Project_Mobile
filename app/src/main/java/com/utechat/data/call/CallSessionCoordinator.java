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
 * - Bảo vệ chặt chẽ theo (sessionKey, callId), giữ nguyên ký tự kết dòng \r\n của SDP, khởi tạo xong engine mới công bố
 *   activeEngine (đệm offer/answer/ICE đến sớm), không bao giờ gọi engine.close() khi đang giữ stateLock,
 *   tự gửi POST /end nếu người dùng hủy trong lúc POST /calls hoặc /accept đang bay, và chống lệch đồng hồ expiresAt.
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

    /** Ảnh chụp trạng thái bất biến cho UI, giữ answeredAtElapsedRealtime và sessionKey nguồn của cuộc gọi. */
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
        @Nullable public final String sessionKey;

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
            this(state, callId, conversationId, peerUserId, peerName, peerAvatarUrl, peerPhone,
                    incoming, micMuted, speakerOn, answeredAtElapsedRealtime, ringingExpiresAtEpochMs,
                    ringingDeadlinePassed, connectionLost, errorCode, endReason, null);
        }

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
                @Nullable String endReason,
                @Nullable String sessionKey) {
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
            this.sessionKey = sessionKey;
        }

        @NonNull
        public static CallUiState idle() {
            return new CallUiState(State.IDLE, null, null, null, null, null, null,
                    false, false, false, 0L, 0L, false, false, null, null, null);
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

    private boolean outgoingStartPending = false;
    private boolean acceptPending = false;
    private boolean pendingStartOfferAfterInit = false;
    private boolean iceConnected = false;
    @Nullable private String pendingRemoteOfferSdp;
    @Nullable private String pendingRemoteAnswerSdp;
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
     * - Nếu người dùng hủy trước khi POST /api/calls trả 201 thì tự động gửi POST /end để giải phóng server.
     * - Khởi tạo xong WebRtcCallEngine + áp dụng mute/speaker trước khi công bố activeEngine (chống rơi offer/answer).
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

        WebRtcCallEngine oldEngine;
        synchronized (stateLock) {
            if (current.state != State.IDLE && current.state != State.ENDED) {
                return;
            }
            oldEngine = resetInternalResourcesLocked();
            activeSessionKey = sessionKey;
            activeCallId = null;

            ForegroundServiceStarter starter = foregroundServiceStarter;
            if (starter != null && !starter.startForCall(null, peerName, sessionKey)) {
                publishLocked(new CallUiState(
                        State.ENDED, null, conversationId, peerUserId, peerName, peerAvatarUrl, peerPhone,
                        false, false, false, 0L, 0L, false, false,
                        "FOREGROUND_SERVICE_FAILED", "ERROR", sessionKey));
                closeEngineQuietly(oldEngine);
                return;
            }

            outgoingStartPending = true;
            publishLocked(new CallUiState(
                    State.OUTGOING_RINGING, null, conversationId, peerUserId, peerName, peerAvatarUrl, peerPhone,
                    false, false, false, 0L, 0L, false, false, null, null, sessionKey));
        }
        closeEngineQuietly(oldEngine);

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
                List<CallDtos.IceServerDto> servers = body.iceServers != null
                        ? body.iceServers : Collections.emptyList();
                final String callId = body.callId;

                boolean canceledInFlight = false;
                boolean initialMuted = false;
                boolean initialSpeaker = false;
                synchronized (stateLock) {
                    if (!sessionKey.equals(session.sessionKey())
                            || !sessionKey.equals(activeSessionKey)
                            || !outgoingStartPending
                            || current.state != State.OUTGOING_RINGING) {
                        canceledInFlight = true;
                    } else {
                        activeCallId = callId;
                        initialMuted = current.micMuted;
                        initialSpeaker = current.speakerOn;
                        publishLocked(new CallUiState(
                                State.OUTGOING_RINGING, callId, conversationId, peerUserId, peerName,
                                peerAvatarUrl, peerPhone, false, initialMuted, initialSpeaker,
                                0L, expiresAtMs, false, false, null, null, sessionKey));
                        scheduleRingingDeadlineLocked(sessionKey, callId, delayMs);
                    }
                }

                if (canceledInFlight) {
                    // Người dùng đã bấm Hủy trong lúc POST /api/calls đang bay: lập tức gửi POST /end giải phóng CALL_BUSY.
                    if (sessionKey.equals(session.sessionKey())) {
                        sendEndCallQuietly(callId);
                    }
                    return;
                }

                // Khởi tạo hoàn chỉnh WebRtcCallEngine ngoài stateLock trước khi gán activeEngine (tránh race rơi startOffer).
                WebRtcCallEngine createdEngine = engineFactory.get();
                if (createdEngine == null) {
                    return;
                }
                createdEngine.initialize(servers, new EngineCallback(sessionKey, callId));
                createdEngine.setMicMuted(initialMuted);
                createdEngine.setSpeakerEnabled(initialSpeaker);

                boolean stillValid = false;
                boolean latestMuted = initialMuted;
                boolean latestSpeaker = initialSpeaker;
                boolean shouldStartOffer = false;
                String bufferedAnswer = null;
                List<PendingIce> bufferedIce = Collections.emptyList();
                synchronized (stateLock) {
                    if (sessionKey.equals(session.sessionKey())
                            && sessionKey.equals(activeSessionKey)
                            && callId.equals(activeCallId)
                            && (current.state == State.OUTGOING_RINGING || current.state == State.CONNECTING)) {
                        stillValid = true;
                        activeEngine = createdEngine;
                        outgoingStartPending = false;
                        latestMuted = current.micMuted;
                        latestSpeaker = current.speakerOn;
                        shouldStartOffer = pendingStartOfferAfterInit;
                        pendingStartOfferAfterInit = false;
                        bufferedAnswer = pendingRemoteAnswerSdp;
                        pendingRemoteAnswerSdp = null;
                        if (!pendingEarlyIce.isEmpty()) {
                            bufferedIce = new ArrayList<>(pendingEarlyIce);
                            pendingEarlyIce.clear();
                        }
                    }
                }

                if (!stillValid) {
                    closeEngineQuietly(createdEngine);
                    return;
                }
                if (latestMuted != initialMuted) {
                    createdEngine.setMicMuted(latestMuted);
                }
                if (latestSpeaker != initialSpeaker) {
                    createdEngine.setSpeakerEnabled(latestSpeaker);
                }
                if (shouldStartOffer) {
                    createdEngine.startOffer();
                }
                if (bufferedAnswer != null) {
                    createdEngine.applyAnswer(bufferedAnswer);
                }
                for (PendingIce ice : bufferedIce) {
                    createdEngine.addRemoteIceCandidate(ice.candidate, ice.sdpMid, ice.sdpMLineIndex);
                }
            } catch (IOException e) {
                finishWithErrorIfSessionMatches(sessionKey, null, "NETWORK", "ERROR");
            }
        });
    }

    /**
     * Người nhận bấm Nghe máy (POST /api/calls/{id}/accept):
     * - Kiểm tra ForegroundServiceStarter trước khi nhận máy và khởi tạo thu âm.
     * - Đệm offer/ICE đến sớm trong suốt lúc chờ HTTP 200 VÀ suốt lúc engine.initialize().
     * - Nếu người nhận bấm Hủy trong lúc POST /accept đang bay thì tự gửi POST /end khi 200 trả về.
     */
    public void acceptIncomingCall() {
        final String sessionKey;
        final String callId;
        final String peerName;
        WebRtcCallEngine toCloseOnFgsFail = null;
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
                cancelTimersLocked();
                toCloseOnFgsFail = detachEngineLocked();
                publishLocked(withEnded(current, "FOREGROUND_SERVICE_FAILED", "ERROR"));
            } else {
                cancelRingingTimerLocked();
                acceptPending = true;
                publishLocked(new CallUiState(
                        State.CONNECTING, callId, current.conversationId, current.peerUserId,
                        current.peerName, current.peerAvatarUrl, current.peerPhone,
                        true, current.micMuted, current.speakerOn,
                        0L, current.ringingExpiresAtEpochMs, false, false, null, null, sessionKey));
            }
        }
        cancelIncomingAlert(callId);
        if (toCloseOnFgsFail != null) {
            closeEngineQuietly(toCloseOnFgsFail);
            return;
        }
        if (getCurrentState().state != State.CONNECTING) {
            return;
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

                boolean canceledBeforeInit;
                synchronized (stateLock) {
                    canceledBeforeInit = !sessionKey.equals(session.sessionKey())
                            || !sessionKey.equals(activeSessionKey)
                            || !callId.equals(activeCallId)
                            || current.state != State.CONNECTING;
                    if (canceledBeforeInit) {
                        acceptPending = false;
                    }
                }
                if (canceledBeforeInit) {
                    // Đã chấp nhận trên server nhưng người nhận vừa bấm Hủy/FGS lỗi -> lập tức gửi /end giải phóng ACTIVE.
                    if (sessionKey.equals(session.sessionKey())) {
                        sendEndCallQuietly(callId);
                    }
                    return;
                }

                // Khởi tạo xong engine TRƯỚC khi công bố activeEngine để call.offer đến đúng lúc này vẫn được đệm an toàn.
                WebRtcCallEngine engine = engineFactory.get();
                if (engine == null) {
                    return;
                }
                engine.initialize(servers, new EngineCallback(sessionKey, callId));

                boolean stillValid = false;
                String bufferedOffer = null;
                List<PendingIce> bufferedIce = Collections.emptyList();
                boolean muted = false;
                boolean speaker = false;
                synchronized (stateLock) {
                    if (sessionKey.equals(session.sessionKey())
                            && sessionKey.equals(activeSessionKey)
                            && callId.equals(activeCallId)
                            && current.state == State.CONNECTING) {
                        stillValid = true;
                        acceptPending = false;
                        activeEngine = engine;
                        muted = current.micMuted;
                        speaker = current.speakerOn;
                        bufferedOffer = pendingRemoteOfferSdp;
                        pendingRemoteOfferSdp = null;
                        if (!pendingEarlyIce.isEmpty()) {
                            bufferedIce = new ArrayList<>(pendingEarlyIce);
                            pendingEarlyIce.clear();
                        }
                    } else {
                        acceptPending = false;
                    }
                }

                if (!stillValid) {
                    closeEngineQuietly(engine);
                    if (sessionKey.equals(session.sessionKey())) {
                        sendEndCallQuietly(callId);
                    }
                    return;
                }

                engine.setMicMuted(muted);
                engine.setSpeakerEnabled(speaker);
                if (bufferedOffer != null) {
                    engine.acceptOffer(bufferedOffer);
                }
                for (PendingIce ice : bufferedIce) {
                    engine.addRemoteIceCandidate(ice.candidate, ice.sdpMid, ice.sdpMLineIndex);
                }
            } catch (IOException e) {
                finishWithErrorIfSessionMatches(sessionKey, callId, "NETWORK", "ERROR");
            }
        });
    }

    /** Từ chối cuộc gọi đến (POST /api/calls/{id}/reject). */
    public void rejectIncomingCall() {
        final String callId;
        final WebRtcCallEngine toClose;
        synchronized (stateLock) {
            if (current.state != State.INCOMING_RINGING
                    || current.ringingDeadlinePassed
                    || activeCallId == null) {
                return;
            }
            callId = activeCallId;
            cancelTimersLocked();
            toClose = detachEngineLocked();
            clearSignalingBuffersLocked();
            publishLocked(withEnded(current, null, "REJECT"));
        }
        cancelIncomingAlert(callId);
        closeEngineQuietly(toClose);
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
        final String alertCallId;
        final WebRtcCallEngine toClose;
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
            alertCallId = activeCallId;
            cancelTimersLocked();
            toClose = detachEngineLocked();
            clearSignalingBuffersLocked();
            publishLocked(withEnded(current, null, "HANGUP"));
        }
        if (alertCallId != null) {
            cancelIncomingAlert(alertCallId);
        }
        closeEngineQuietly(toClose);
        if (callIdToEnd != null) {
            networkExecutor.execute(() -> sendEndCallQuietly(callIdToEnd));
        }
    }

    /** Báo lỗi khởi chạy Foreground Service từ CallService/CallFragment để dọn trạng thái mà không thu âm. */
    public void onForegroundServiceStartFailed() {
        final String alertCallId;
        final String callIdToEnd;
        final WebRtcCallEngine toClose;
        synchronized (stateLock) {
            if (current.state == State.IDLE || current.state == State.ENDED) {
                return;
            }
            alertCallId = activeCallId;
            callIdToEnd = (current.state == State.OUTGOING_RINGING || current.state == State.CONNECTING
                    || current.state == State.ACTIVE) ? activeCallId : null;
            cancelTimersLocked();
            toClose = detachEngineLocked();
            clearSignalingBuffersLocked();
            publishLocked(withEnded(current, "FOREGROUND_SERVICE_FAILED", "ERROR"));
        }
        if (alertCallId != null) {
            cancelIncomingAlert(alertCallId);
        }
        closeEngineQuietly(toClose);
        if (callIdToEnd != null) {
            networkExecutor.execute(() -> sendEndCallQuietly(callIdToEnd));
        }
    }

    /**
     * Báo lỗi người dùng từ chối quyền RECORD_AUDIO khi khởi tạo cuộc gọi đi
     * để màn hình hiện thông báo rõ ràng kèm nút dự phòng gọi nhà mạng (ACTION_DIAL).
     */
    public void reportPermissionDenied(
            @Nullable String conversationId,
            @Nullable String peerUserId,
            @Nullable String peerName,
            @Nullable String peerAvatarUrl,
            @Nullable String peerPhone) {
        WebRtcCallEngine toClose;
        synchronized (stateLock) {
            if (current.state != State.IDLE && current.state != State.ENDED) {
                return;
            }
            toClose = resetInternalResourcesLocked();
            publishLocked(new CallUiState(
                    State.ENDED, null, conversationId, peerUserId, peerName, peerAvatarUrl, peerPhone,
                    false, false, false, 0L, 0L, false, false,
                    "RECORD_AUDIO_DENIED", "ERROR", session.sessionKey()));
        }
        closeEngineQuietly(toClose);
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
                    current.errorCode, current.endReason, current.sessionKey));
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
                    current.errorCode, current.endReason, current.sessionKey));
        }
        if (engine != null) {
            engine.setSpeakerEnabled(enabled);
        }
    }

    /** Đưa coordinator về IDLE sau khi CallFragment đóng màn hình ENDED. */
    public void resetToIdle() {
        WebRtcCallEngine toClose = null;
        synchronized (stateLock) {
            if (current.state == State.ENDED) {
                toClose = resetInternalResourcesLocked();
                publishLocked(CallUiState.idle());
            }
        }
        closeEngineQuietly(toClose);
    }

    /** Dọn sạch mọi tài nguyên WebRTC, chuông, timer và đưa về IDLE khi đăng xuất / 401 / 4401 (T-06-20). */
    public void onSessionCleared() {
        onSessionClearedForSession(null);
    }

    /**
     * Phiên bản theo sessionKey rời đi để dọn bất đồng bộ ngoài khóa SessionManager mà không chạm nhầm cuộc gọi của phiên mới.
     */
    public void onSessionClearedForSession(@Nullable String departingSessionKey) {
        String oldCallId;
        WebRtcCallEngine toClose;
        synchronized (stateLock) {
            if (departingSessionKey != null
                    && activeSessionKey != null
                    && !departingSessionKey.equals(activeSessionKey)) {
                return;
            }
            oldCallId = activeCallId;
            toClose = resetInternalResourcesLocked();
            publishLocked(CallUiState.idle());
        }
        if (oldCallId != null) {
            cancelIncomingAlert(oldCallId);
        }
        closeEngineQuietly(toClose);
    }

    /**
     * Đồng bộ trạng thái kết thúc cuộc gọi từ /api/sync (CALL_UPDATED) khi mất kết nối WebSocket tạm thời,
     * đảm bảo UI không bao giờ bị kẹt ở RINGING/ACTIVE sau khi đối phương đã kết thúc hoặc từ chối.
     */
    public void onSyncedTerminalCallUpdate(
            @NonNull String callId, @Nullable String rawState, @Nullable String endReason) {
        if ("HANDLED_ELSEWHERE".equalsIgnoreCase(endReason)) {
            JsonObject p = new JsonObject();
            p.addProperty("callId", callId);
            onHandledElsewhereFrame(p);
            return;
        }
        if (rawState == null) {
            return;
        }
        String upper = rawState.trim().toUpperCase(java.util.Locale.ROOT);
        if ("ENDED".equals(upper) || "MISSED".equals(upper)
                || "REJECTED".equals(upper) || "BUSY".equals(upper) || "FAILED".equals(upper)) {
            JsonObject p = new JsonObject();
            p.addProperty("callId", callId);
            p.addProperty("state", upper);
            if (endReason != null) {
                p.addProperty("endReason", endReason);
            }
            onCallEndedFrame(p, null);
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
        WebRtcCallEngine oldEngine;
        synchronized (stateLock) {
            // Đang trong cuộc gọi khác -> bỏ qua lặng lẽ, không làm hỏng phiên đang chạy (T-06-18).
            if (current.state != State.IDLE && current.state != State.ENDED) {
                return;
            }
            oldEngine = resetInternalResourcesLocked();
            activeSessionKey = sessionKey;
            activeCallId = callId;
            incomingState = new CallUiState(
                    State.INCOMING_RINGING, callId, conversationId, callerId, callerName, callerAvatar, null,
                    true, false, false, 0L, expiresAtMs, false, false, null, null, sessionKey);
            publishLocked(incomingState);
            scheduleRingingDeadlineLocked(sessionKey, callId, delayMs);
        }
        closeEngineQuietly(oldEngine);

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
            if (engine == null && outgoingStartPending) {
                // Nếu call.accepted đến ngay khi WebRtcCallEngine đang initialize(), đánh dấu để tạo offer ngay sau đó.
                pendingStartOfferAfterInit = true;
            }
            publishLocked(new CallUiState(
                    State.CONNECTING, callId, current.conversationId, current.peerUserId,
                    current.peerName, current.peerAvatarUrl, current.peerPhone,
                    false, current.micMuted, current.speakerOn,
                    0L, current.ringingExpiresAtEpochMs, false, false, null, null, current.sessionKey));
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

        WebRtcCallEngine toClose;
        synchronized (stateLock) {
            if (!matchesActiveCallLocked(callId)) {
                return;
            }
            if (current.state == State.IDLE) {
                return;
            }
            cancelTimersLocked();
            toClose = detachEngineLocked();
            clearSignalingBuffersLocked();
            publishLocked(withEnded(current, errorCode, endReason));
        }
        cancelIncomingAlert(callId);
        closeEngineQuietly(toClose);
    }

    public void onHandledElsewhereFrame(@NonNull JsonObject payload) {
        String callId = optString(payload, "callId");
        if (callId == null) {
            return;
        }
        WebRtcCallEngine toClose;
        synchronized (stateLock) {
            if (!matchesActiveCallLocked(callId)) {
                return;
            }
            if (current.state != State.INCOMING_RINGING && current.state != State.CONNECTING) {
                return;
            }
            cancelTimersLocked();
            toClose = detachEngineLocked();
            clearSignalingBuffersLocked();
            publishLocked(withEnded(current, null, "HANDLED_ELSEWHERE"));
        }
        cancelIncomingAlert(callId);
        closeEngineQuietly(toClose);
    }

    public void onOfferFrame(@NonNull JsonObject payload) {
        String callId = optString(payload, "callId");
        // Tuyệt đối không gọi .trim() lên SDP để giữ nguyên ký tự kết dòng \r\n chuẩn RFC 4566 cho libwebrtc.
        String sdp = optRawNonBlankString(payload, "sdp");
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
                    // Đệm offer đến sớm trong lúc chờ POST /accept hoặc engine.initialize().
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
        // Giữ nguyên \r\n cuối chuỗi SDP để setRemoteDescription không báo lỗi định dạng.
        String sdp = optRawNonBlankString(payload, "sdp");
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
                } else if (outgoingStartPending) {
                    pendingRemoteAnswerSdp = sdp;
                }
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
                } else if (acceptPending || outgoingStartPending) {
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
            boolean sendImmediateKeepalive = false;
            synchronized (stateLock) {
                if (!sessionKey.equals(session.sessionKey())
                        || !sessionKey.equals(activeSessionKey)
                        || !callId.equals(activeCallId)) {
                    return;
                }
                if (current.state != State.CONNECTING && current.state != State.ACTIVE) {
                    return;
                }
                sendImmediateKeepalive = current.connectionLost;
                iceConnected = true;
                long answeredAt = current.answeredAtElapsedRealtime > 0L
                        ? current.answeredAtElapsedRealtime
                        : elapsedClockMs.getAsLong();
                publishLocked(new CallUiState(
                        State.ACTIVE, callId, current.conversationId, current.peerUserId,
                        current.peerName, current.peerAvatarUrl, current.peerPhone,
                        current.incoming, current.micMuted, current.speakerOn,
                        answeredAt, current.ringingExpiresAtEpochMs, false, false, null, null, current.sessionKey));
                scheduleKeepaliveLocked(sessionKey, callId);
            }
            // Nếu vừa phục hồi từ DISCONNECTED, gia hạn keepalive ngay lập tức để kịp cửa sổ 60s của server.
            if (sendImmediateKeepalive) {
                sendKeepaliveFrame(callId);
            }
        }

        @Override
        public void onIceDisconnectedOrFailed(@NonNull String reason) {
            WebRtcCallEngine toClose = null;
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
                            false, true, null, null, current.sessionKey));
                    return;
                }

                if (current.state == State.OUTGOING_RINGING
                        || current.state == State.CONNECTING
                        || current.state == State.ACTIVE) {
                    cancelTimersLocked();
                    toClose = detachEngineLocked();
                    clearSignalingBuffersLocked();
                    publishLocked(withEnded(current, reason, "ERROR"));
                }
            }
            closeEngineQuietly(toClose);
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
        WebRtcCallEngine toClose;
        synchronized (stateLock) {
            if (!sessionKey.equals(activeSessionKey) || !callId.equals(activeCallId)) {
                return;
            }
            if (current.state != State.OUTGOING_RINGING && current.state != State.INCOMING_RINGING) {
                return;
            }
            ringingTimer = null;
            toClose = detachEngineLocked();
            clearSignalingBuffersLocked();
            publishLocked(new CallUiState(
                    State.ENDED, callId, current.conversationId, current.peerUserId,
                    current.peerName, current.peerAvatarUrl, current.peerPhone,
                    current.incoming, current.micMuted, current.speakerOn,
                    0L, current.ringingExpiresAtEpochMs, true, false, null, "TIMEOUT", current.sessionKey));
        }
        cancelIncomingAlert(callId);
        closeEngineQuietly(toClose);
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

    private void sendEndCallQuietly(@NonNull String callId) {
        try {
            callApi.endCall(callId).execute();
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private void finishWithErrorIfSessionMatches(
            @NonNull String sessionKey,
            @Nullable String expectedCallId,
            @NonNull String errorCode,
            @NonNull String endReason) {
        WebRtcCallEngine toClose;
        synchronized (stateLock) {
            if (!sessionKey.equals(session.sessionKey()) || !sessionKey.equals(activeSessionKey)) {
                return;
            }
            if (expectedCallId != null && !expectedCallId.equals(activeCallId)) {
                return;
            }
            cancelTimersLocked();
            toClose = detachEngineLocked();
            clearSignalingBuffersLocked();
            publishLocked(withEnded(current, errorCode, endReason));
        }
        closeEngineQuietly(toClose);
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

    @Nullable
    private WebRtcCallEngine resetInternalResourcesLocked() {
        cancelTimersLocked();
        WebRtcCallEngine toClose = detachEngineLocked();
        clearSignalingBuffersLocked();
        activeSessionKey = null;
        activeCallId = null;
        return toClose;
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

    @Nullable
    private WebRtcCallEngine detachEngineLocked() {
        iceConnected = false;
        WebRtcCallEngine toClose = activeEngine;
        activeEngine = null;
        return toClose;
    }

    private static void closeEngineQuietly(@Nullable WebRtcCallEngine engine) {
        if (engine != null) {
            engine.close();
        }
    }

    private void clearSignalingBuffersLocked() {
        outgoingStartPending = false;
        acceptPending = false;
        pendingStartOfferAfterInit = false;
        pendingRemoteOfferSdp = null;
        pendingRemoteAnswerSdp = null;
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
                base.ringingDeadlinePassed, false, errorCode, endReason, base.sessionKey);
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

    /**
     * Tính thời gian chờ cục bộ an toàn trước lệch đồng hồ thiết bị/server:
     * nếu đồng hồ máy chạy nhanh hơn server (remaining <= 0 ngay lúc vừa đổ chuông), fallback về 45s thay vì cắt cuộc gọi sau 1s.
     */
    private long computeRingingDelayMs(long expiresAtEpochMs) {
        long remaining = expiresAtEpochMs - wallClockMs.getAsLong();
        if (remaining <= 0L) {
            return DEFAULT_RINGING_TIMEOUT_MS;
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

    /** Đọc chuỗi nguyên vẹn (không .trim()) dành riêng cho SDP WebRTC để bảo toàn ký tự \r\n cuối cùng. */
    @Nullable
    private static String optRawNonBlankString(@NonNull JsonObject obj, @NonNull String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return null;
        }
        String val = el.getAsString();
        return val != null && !val.trim().isEmpty() ? val : null;
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
