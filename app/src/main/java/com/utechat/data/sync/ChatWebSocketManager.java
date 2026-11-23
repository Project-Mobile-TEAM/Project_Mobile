package com.utechat.data.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.utechat.BuildConfig;
import com.utechat.data.remote.api.ApiClient;
import com.utechat.data.remote.model.ChatDtos;
import com.utechat.data.session.SessionEvents;
import com.utechat.data.session.SessionManager;
import java.util.UUID;
import java.util.function.LongSupplier;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Quản lý kết nối WebSocket thuần JSON (/ws) trên Android (D-07, D-08, DEPLOYMENT_SECURITY):
 * - Chỉ truyền JWT qua header Authorization: Bearer (tuyệt đối không gắn lên URL).
 * - Lịch backoff kết nối lại: 1, 2, 4, 8, 16, 30 giây.
 * - Duy trì đúng 1 hẹn giờ heartbeat ứng dụng JSON ping mỗi 25s cho từng socket; quá 75s không có pong thì nối lại.
 * - Các khung dữ liệu (message.new, conversation.updated, message.status, contact.*) CHỈ gọi syncEngine.requestSync(),
 *   tuyệt đối không ghi trực tiếp vào Room hay nâng sync_state.lastSyncEventId.
 * - Chỉ báo trạng thái CONNECTED sau khi socket đã mở VÀ SyncEngine đã bắt kịp sạch dữ liệu.
 */
public class ChatWebSocketManager {
    public static final int CLOSE_CODE_SESSION_REVOKED = 4401;
    public static final long HEARTBEAT_INTERVAL_MS = 25_000L;
    public static final long HEARTBEAT_TIMEOUT_MS = 75_000L;
    static final long[] BACKOFF_SECONDS = {1L, 2L, 4L, 8L, 16L, 30L};

    private static final Gson GSON = new Gson();

    private final SessionManager session;
    private final SyncEngine syncEngine;
    private final TypingTracker typingTracker;
    private final WebSocket.Factory wsFactory;
    private final SyncEngine.DelayScheduler scheduler;
    private final LongSupplier clockMs;
    private final String wsUrl;
    private final boolean fakeMode;

    /** Listener chuyển tiếp khung call.* từ WebSocket tới CallSessionCoordinator mà không tạo vòng phụ thuộc. */
    public interface CallSignalListener {
        void onCallFrame(@NonNull String type, @NonNull JsonObject payload);

        /** Gọi khi kết nối WebSocket mở lại trên phiên hiện hành để gia hạn call.keepalive nếu ICE đang nối. */
        default void onSocketReconnected() { }
    }

    private final MutableLiveData<WsConnectionState> connectionState =
            new MutableLiveData<>(WsConnectionState.DISCONNECTED);

    @Nullable private volatile CallSignalListener callSignalListener;
    @Nullable private WebSocket activeSocket;
    @Nullable private String activeSessionKey;
    private boolean socketOpen;
    private boolean manualDisconnect;
    private int backoffIndex;
    private long lastPongAtMs;
    @Nullable private SyncEngine.DelayScheduler.Cancellable heartbeatTask;
    @Nullable private SyncEngine.DelayScheduler.Cancellable reconnectTask;

    public ChatWebSocketManager(
            @NonNull SessionManager session,
            @NonNull SyncEngine syncEngine,
            @NonNull TypingTracker typingTracker) {
        this(
                session,
                syncEngine,
                typingTracker,
                ApiClient.getWebSocketClient(),
                SyncEngine.defaultScheduler(),
                System::currentTimeMillis,
                BuildConfig.BACKEND_BASE_URL,
                BuildConfig.USE_FAKE_DATA);
    }

    public ChatWebSocketManager(
            @NonNull SessionManager session,
            @NonNull SyncEngine syncEngine,
            @NonNull TypingTracker typingTracker,
            @NonNull WebSocket.Factory wsFactory,
            @NonNull SyncEngine.DelayScheduler scheduler,
            @NonNull LongSupplier clockMs,
            @NonNull String baseUrl,
            boolean fakeMode) {
        this.session = session;
        this.syncEngine = syncEngine;
        this.typingTracker = typingTracker;
        this.wsFactory = wsFactory;
        this.scheduler = scheduler;
        this.clockMs = clockMs;
        this.wsUrl = buildWsUrl(baseUrl);
        this.fakeMode = fakeMode;
        this.syncEngine.setCatchUpListener(this::onSyncCatchUpStatus);
    }

    /** Chuyển đổi BACKEND_BASE_URL sang ws(s)://<host>/ws sạch, không chứa query hay token. */
    @NonNull
    public static String buildWsUrl(@NonNull String baseUrl) {
        HttpUrl http = HttpUrl.get(baseUrl);
        String scheme = "https".equalsIgnoreCase(http.scheme()) ? "wss" : "ws";
        HttpUrl normalized = http.newBuilder()
                .encodedPath("/ws")
                .query(null)
                .fragment(null)
                .build();
        return scheme + normalized.toString().substring(http.scheme().length());
    }

    public LiveData<WsConnectionState> getConnectionState() {
        return connectionState;
    }

    /** Đăng ký CallSessionCoordinator nhận các sự kiện call.* (Wave 4 Injection). */
    public void setCallSignalListener(@Nullable CallSignalListener listener) {
        this.callSignalListener = listener;
    }

    /**
     * Gửi khung báo hiệu WebRTC hoặc call.keepalive qua socket của phiên hiện hành (API_CONTRACT §3):
     * kiểm tra phiên hiện hành khớp với activeSessionKey trước khi gửi để tránh lọt khung của phiên cũ.
     */
    public synchronized boolean sendCallSignalingFrame(@NonNull String frameType, @NonNull JsonObject payload) {
        if (!socketOpen
                || activeSocket == null
                || activeSessionKey == null
                || !activeSessionKey.equals(session.sessionKey())) {
            return false;
        }
        JsonObject envelope = new JsonObject();
        envelope.addProperty("v", 1);
        envelope.addProperty("type", frameType);
        envelope.addProperty("id", UUID.randomUUID().toString());
        envelope.add("payload", payload);
        return activeSocket.send(GSON.toJson(envelope));
    }

    /** Mở kết nối WebSocket nếu người dùng đã đăng nhập và không ở chế độ dữ liệu giả. */
    public synchronized void connectIfAuthenticated() {
        if (fakeMode) {
            connectionState.postValue(WsConnectionState.DISCONNECTED);
            return;
        }
        String token;
        String version;
        String sessionKey;
        synchronized (session) {
            token = session.getToken();
            version = session.versionKey();
            sessionKey = session.sessionKey();
        }
        if (token == null || sessionKey == null) {
            disconnectLocked();
            return;
        }
        if (activeSocket != null && sessionKey.equals(activeSessionKey)) {
            return;
        }

        // Dọn sạch kết nối và timer cũ trước khi mở kết nối mới.
        closeActiveSocketLocked();
        cancelHeartbeatLocked();
        cancelReconnectLocked();

        manualDisconnect = false;
        socketOpen = false;
        activeSessionKey = sessionKey;
        syncEngine.setForegroundActive(true);
        connectionState.postValue(WsConnectionState.RECONNECTING);

        // Chỉ gửi JWT qua header Authorization, tuyệt đối không gắn vào URL hay subprotocol.
        Request request = new Request.Builder()
                .url(wsUrl)
                .header("Authorization", "Bearer " + token)
                .build();

        activeSocket = wsFactory.newWebSocket(request, new SocketListener(sessionKey, token, version));
    }

    /** Ngắt kết nối chủ động (khi app vào nền, đăng xuất hoặc đổi tài khoản) và hủy toàn bộ hẹn giờ. */
    public synchronized void disconnect() {
        disconnectLocked();
    }

    /**
     * Ngắt kết nối bất đồng bộ cho phiên vừa rời đi mà không ngắt nhầm phiên mới vừa đăng nhập sau đó (tránh deadlock AB-BA với SessionManager).
     */
    public synchronized void disconnectIfSession(@Nullable String departingSessionKey) {
        if (departingSessionKey != null && activeSessionKey != null
                && !departingSessionKey.equals(activeSessionKey)) {
            return;
        }
        if (activeSessionKey == null && session.hasToken()) {
            return;
        }
        disconnectLocked();
    }

    /** Gửi khung typing.start nếu socket đang mở và vượt qua giới hạn 1 lần / 3 giây. */
    public synchronized void sendTypingStart(@NonNull String conversationId) {
        if (!socketOpen || activeSocket == null) return;
        if (!typingTracker.shouldSendTypingStart(conversationId)) return;
        sendTypingFrameLocked(conversationId, "typing.start");
    }

    /** Gửi khung typing.stop ngay khi người dùng ngừng gõ hoặc đóng phòng chat. */
    public synchronized void sendTypingStop(@NonNull String conversationId) {
        typingTracker.resetSendThrottle(conversationId);
        if (!socketOpen || activeSocket == null) return;
        sendTypingFrameLocked(conversationId, "typing.stop");
    }

    private void sendTypingFrameLocked(String conversationId, String frameType) {
        JsonObject payload = new JsonObject();
        payload.addProperty("conversationId", conversationId);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("v", 1);
        envelope.addProperty("type", frameType);
        envelope.addProperty("id", UUID.randomUUID().toString());
        envelope.add("payload", payload);
        activeSocket.send(GSON.toJson(envelope));
    }

    private synchronized void onSyncCatchUpStatus(@NonNull String sessionKey, boolean caughtUp) {
        if (caughtUp && socketOpen && sessionKey.equals(activeSessionKey) && sessionKey.equals(session.sessionKey())) {
            backoffIndex = 0;
            connectionState.postValue(WsConnectionState.CONNECTED);
        } else if (!caughtUp && socketOpen && sessionKey.equals(activeSessionKey)) {
            // Socket vẫn mở nhưng còn sự kiện bị barrier giữ hoặc đồng bộ chưa xong -> giữ RECONNECTING.
            connectionState.postValue(WsConnectionState.RECONNECTING);
        }
    }

    private void disconnectLocked() {
        manualDisconnect = true;
        socketOpen = false;
        activeSessionKey = null;
        backoffIndex = 0;
        cancelHeartbeatLocked();
        cancelReconnectLocked();
        closeActiveSocketLocked();
        syncEngine.setForegroundActive(false);
        syncEngine.cancelPendingRetry();
        typingTracker.clearAll();
        connectionState.postValue(WsConnectionState.DISCONNECTED);
    }

    private void closeActiveSocketLocked() {
        if (activeSocket != null) {
            try {
                activeSocket.close(1000, "client_disconnect");
            } catch (Exception ignored) {
                activeSocket.cancel();
            }
            activeSocket = null;
        }
    }

    private void cancelHeartbeatLocked() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
    }

    private void cancelReconnectLocked() {
        if (reconnectTask != null) {
            reconnectTask.cancel();
            reconnectTask = null;
        }
    }

    private void scheduleHeartbeatLocked(WebSocket socket, String sessionKey) {
        cancelHeartbeatLocked();
        heartbeatTask = scheduler.schedule(HEARTBEAT_INTERVAL_MS, () -> onHeartbeatTick(socket, sessionKey));
    }

    private synchronized void onHeartbeatTick(WebSocket socket, String sessionKey) {
        heartbeatTask = null;
        if (manualDisconnect
                || socket != activeSocket
                || !socketOpen
                || !sessionKey.equals(activeSessionKey)
                || !sessionKey.equals(session.sessionKey())) {
            return;
        }

        long now = clockMs.getAsLong();
        if ((now - lastPongAtMs) > HEARTBEAT_TIMEOUT_MS) {
            // Quá 75 giây không nhận phản hồi pong -> đóng socket treo và lên lịch nối lại.
            socketOpen = false;
            closeActiveSocketLocked();
            syncEngine.cancelPendingRetry();
            scheduleReconnectLocked(sessionKey);
            return;
        }

        JsonObject ping = new JsonObject();
        ping.addProperty("v", 1);
        ping.addProperty("type", "ping");
        ping.addProperty("id", UUID.randomUUID().toString());
        boolean sent = socket.send(GSON.toJson(ping));
        if (!sent) {
            socketOpen = false;
            closeActiveSocketLocked();
            syncEngine.cancelPendingRetry();
            scheduleReconnectLocked(sessionKey);
            return;
        }

        // Luôn duy trì đúng 1 hẹn giờ heartbeat kế tiếp sau 25 giây cho socket hiện tại.
        scheduleHeartbeatLocked(socket, sessionKey);
    }

    private void scheduleReconnectLocked(String sessionKey) {
        if (manualDisconnect || !sessionKey.equals(session.sessionKey())) {
            connectionState.postValue(WsConnectionState.DISCONNECTED);
            return;
        }
        cancelReconnectLocked();
        long delaySeconds = BACKOFF_SECONDS[Math.min(backoffIndex, BACKOFF_SECONDS.length - 1)];
        if (backoffIndex < BACKOFF_SECONDS.length - 1) {
            backoffIndex++;
        }
        connectionState.postValue(WsConnectionState.RECONNECTING);
        reconnectTask = scheduler.schedule(delaySeconds * 1_000L, () -> {
            synchronized (ChatWebSocketManager.this) {
                reconnectTask = null;
                if (manualDisconnect || !sessionKey.equals(session.sessionKey())) {
                    return;
                }
                connectIfAuthenticated();
            }
        });
    }

    private boolean prepareRevokeLocked(WebSocket socket) {
        if (socket != activeSocket && activeSocket != null) {
            return false;
        }
        manualDisconnect = true;
        socketOpen = false;
        activeSessionKey = null;
        cancelHeartbeatLocked();
        cancelReconnectLocked();
        closeActiveSocketLocked();
        syncEngine.setForegroundActive(false);
        syncEngine.cancelPendingRetry();
        typingTracker.clearAll();
        connectionState.postValue(WsConnectionState.DISCONNECTED);
        return true;
    }

    /** Xóa phiên bị thu hồi BÊN NGOÀI khóa ChatWebSocketManager để không tạo vòng khóa AB-BA với SessionManager. */
    private void finalizeRevokeOutsideLock(String capturedToken, String capturedVersion) {
        synchronized (session) {
            if (session.clearIfCurrent(capturedToken, capturedVersion)) {
                SessionEvents.notifyExpired(session.versionKey());
            }
        }
    }

    private final class SocketListener extends WebSocketListener {
        private final String sessionKey;
        private final String token;
        private final String version;

        SocketListener(String sessionKey, String token, String version) {
            this.sessionKey = sessionKey;
            this.token = token;
            this.version = version;
        }

        @Override
        public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
            boolean shouldSync = false;
            CallSignalListener callListener = null;
            synchronized (ChatWebSocketManager.this) {
                if (manualDisconnect || webSocket != activeSocket || !sessionKey.equals(session.sessionKey())) {
                    webSocket.close(1000, "stale_socket");
                    return;
                }
                socketOpen = true;
                lastPongAtMs = clockMs.getAsLong();
                scheduleHeartbeatLocked(webSocket, sessionKey);
                // Chưa đặt CONNECTED ngay; giữ RECONNECTING cho tới khi SyncEngine bắt kịp sạch.
                connectionState.postValue(WsConnectionState.RECONNECTING);
                shouldSync = true;
                callListener = callSignalListener;
            }
            if (callListener != null && sessionKey.equals(session.sessionKey())) {
                callListener.onSocketReconnected();
            }
            if (shouldSync) {
                syncEngine.requestSync();
            }
        }

        @Override
        public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
            ChatDtos.WsEnvelope env;
            try {
                env = GSON.fromJson(text, ChatDtos.WsEnvelope.class);
            } catch (Exception ignored) {
                return;
            }
            if (env == null || env.type == null) {
                return;
            }

            boolean shouldRequestSync = false;
            boolean shouldFinalizeRevoke = false;
            CallSignalListener callListener = null;
            String callFrameType = null;
            JsonObject callPayload = null;
            synchronized (ChatWebSocketManager.this) {
                if (manualDisconnect || webSocket != activeSocket || !sessionKey.equals(session.sessionKey())) {
                    return;
                }
                switch (env.type) {
                    case "pong":
                        lastPongAtMs = clockMs.getAsLong();
                        break;
                    case "typing":
                        if (env.payload != null && env.payload.isJsonObject()) {
                            ChatDtos.TypingPayload tp = GSON.fromJson(env.payload, ChatDtos.TypingPayload.class);
                            if (tp != null) {
                                typingTracker.onTypingEvent(tp.conversationId, tp.userId, tp.state);
                            }
                        }
                        break;
                    case "auth.revoked":
                        shouldFinalizeRevoke = prepareRevokeLocked(webSocket);
                        break;
                    case "message.new":
                    case "conversation.updated":
                    case "message.status":
                    case "contact_request.new":
                    case "contact_request.updated":
                    case "contact.updated":
                        // Khung dữ liệu WS chỉ báo có thay đổi mới: KHÔNG ghi Room, KHÔNG nâng sync cursor.
                        shouldRequestSync = true;
                        break;
                    case "call.incoming":
                    case "call.accepted":
                    case "call.ended":
                    case "call.handled_elsewhere":
                        // Sự kiện vòng đời cuộc gọi mang syncEventId -> vừa kích hoạt /sync vừa chuyển sang CallSessionCoordinator.
                        shouldRequestSync = true;
                        if (env.payload != null && env.payload.isJsonObject()) {
                            callListener = callSignalListener;
                            callFrameType = env.type;
                            callPayload = env.payload.getAsJsonObject();
                        }
                        break;
                    case "call.offer":
                    case "call.answer":
                    case "call.ice":
                        // Báo hiệu WebRTC tạm thời: chuyển trực tiếp ngoài monitor lock, không kích hoạt /sync.
                        if (env.payload != null && env.payload.isJsonObject()) {
                            callListener = callSignalListener;
                            callFrameType = env.type;
                            callPayload = env.payload.getAsJsonObject();
                        }
                        break;
                    default:
                        break;
                }
            }
            if (shouldFinalizeRevoke) {
                finalizeRevokeOutsideLock(token, version);
                return;
            }
            // Kiểm tra lại phiên hiện hành ngoài monitor lock trước khi giao khung cuộc gọi (T-06-18).
            if (callListener != null
                    && callFrameType != null
                    && callPayload != null
                    && sessionKey.equals(session.sessionKey())) {
                callListener.onCallFrame(callFrameType, callPayload);
            }
            if (shouldRequestSync) {
                syncEngine.requestSync();
            }
        }

        @Override
        public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            boolean shouldFinalizeRevoke = false;
            synchronized (ChatWebSocketManager.this) {
                if (webSocket != activeSocket) return;
                if (code == CLOSE_CODE_SESSION_REVOKED) {
                    shouldFinalizeRevoke = prepareRevokeLocked(webSocket);
                } else {
                    webSocket.close(1000, null);
                }
            }
            if (shouldFinalizeRevoke) {
                finalizeRevokeOutsideLock(token, version);
            }
        }

        @Override
        public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            boolean shouldFinalizeRevoke = false;
            synchronized (ChatWebSocketManager.this) {
                if (webSocket != activeSocket) return;
                if (code == CLOSE_CODE_SESSION_REVOKED) {
                    shouldFinalizeRevoke = prepareRevokeLocked(webSocket);
                } else {
                    socketOpen = false;
                    activeSocket = null;
                    cancelHeartbeatLocked();
                    syncEngine.cancelPendingRetry();
                    if (!manualDisconnect) {
                        scheduleReconnectLocked(sessionKey);
                    }
                }
            }
            if (shouldFinalizeRevoke) {
                finalizeRevokeOutsideLock(token, version);
            }
        }

        @Override
        public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
            boolean shouldFinalizeRevoke = false;
            synchronized (ChatWebSocketManager.this) {
                if (webSocket != activeSocket) return;
                if (response != null && response.code() == 401) {
                    shouldFinalizeRevoke = prepareRevokeLocked(webSocket);
                } else {
                    socketOpen = false;
                    activeSocket = null;
                    cancelHeartbeatLocked();
                    syncEngine.cancelPendingRetry();
                    if (!manualDisconnect) {
                        scheduleReconnectLocked(sessionKey);
                    }
                }
            }
            if (shouldFinalizeRevoke) {
                finalizeRevokeOutsideLock(token, version);
            }
        }
    }
}
