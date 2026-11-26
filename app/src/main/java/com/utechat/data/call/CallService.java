package com.utechat.data.call;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import com.utechat.MainActivity;
import com.utechat.R;

/**
 * Dịch vụ nền microphone giữ cuộc gọi thoại WebRTC sống sót khi xoay màn hình hoặc thu nhỏ app (D-11, T-06-19):
 * - TUYỆT ĐỐI không gọi startForeground(..., FOREGROUND_SERVICE_TYPE_MICROPHONE) khi chưa có quyền RECORD_AUDIO.
 * - Chỉ được khởi chạy từ CallFragment sau khi người dùng bấm Gọi hoặc Nghe máy và đã được cấp RECORD_AUDIO.
 * - Độc lập với NotificationHelper/Injection: tự đảm bảo kênh "calls" và lắng nghe CallSessionCoordinator.from(this).
 */
public class CallService extends Service {
    private static final String TAG = "CallService";

    public static final String CHANNEL_CALLS = "calls";
    public static final String ACTION_START_CALL = "com.utechat.action.CALL_START";
    public static final String ACTION_END_CALL = "com.utechat.action.CALL_END";
    public static final String EXTRA_CALL_ID = "extra_call_id";
    public static final String EXTRA_PEER_NAME = "extra_peer_name";
    public static final String EXTRA_SESSION_KEY = "extra_session_key";

    // Khớp trực tiếp khóa intent của MainActivity / NotificationHelper mà không import chéo giữa Wave 2 và Wave 3.
    private static final String MAIN_EXTRA_OPEN_CALL_ID = "extra_open_call_id";
    private static final String MAIN_EXTRA_SESSION_KEY = "extra_notification_session_key";
    private static final int ONGOING_NOTIFICATION_ID = 4201;

    @Nullable private CallSessionCoordinator coordinator;
    private final Observer<CallSessionCoordinator.CallUiState> stateObserver = this::onCallStateChanged;
    private boolean observing = false;
    private boolean foregroundStarted = false;
    @Nullable private String lastNotifiedCallId;
    @Nullable private String lastNotifiedPeerName;
    @Nullable private String lastSessionKey;

    /**
     * Khởi chạy CallService an toàn trên API 26–36:
     * trả false ngay nếu thiếu RECORD_AUDIO hoặc hệ điều hành từ chối FGS (tránh crash ForegroundServiceStartNotAllowedException).
     */
    public static boolean startForActiveCall(
            @NonNull Context context,
            @Nullable String callId,
            @Nullable String peerName,
            @Nullable String sessionKey) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        Intent intent = new Intent(context, CallService.class);
        intent.setAction(ACTION_START_CALL);
        if (callId != null) {
            intent.putExtra(EXTRA_CALL_ID, callId);
        }
        if (peerName != null) {
            intent.putExtra(EXTRA_PEER_NAME, peerName);
        }
        if (sessionKey != null) {
            intent.putExtra(EXTRA_SESSION_KEY, sessionKey);
        }
        try {
            ContextCompat.startForegroundService(context, intent);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Không thể khởi chạy CallService foreground microphone: " + e.getMessage());
            return false;
        }
    }

    /** Dừng CallService khi không còn cuộc gọi hoạt động. */
    public static void stop(@NonNull Context context) {
        context.stopService(new Intent(context, CallService.class));
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        coordinator = CallSessionCoordinator.from(this);
        ensureCallsChannel();
        if (coordinator != null) {
            coordinator.getUiState().observeForever(stateObserver);
            observing = true;
        }
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (coordinator == null) {
            coordinator = CallSessionCoordinator.from(this);
            if (coordinator != null && !observing) {
                coordinator.getUiState().observeForever(stateObserver);
                observing = true;
            }
        }

        String action = intent != null ? intent.getAction() : null;
        if (ACTION_END_CALL.equals(action)) {
            if (coordinator != null) {
                coordinator.endCall();
            }
            stopForegroundAndSelf();
            return START_NOT_STICKY;
        }

        // Bắt buộc kiểm tra lại RECORD_AUDIO trước khi gọi startForeground(FOREGROUND_SERVICE_TYPE_MICROPHONE) (targetSdk 36).
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Thiếu quyền RECORD_AUDIO, từ chối startForeground microphone");
            if (coordinator != null) {
                coordinator.onForegroundServiceStartFailed();
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        String callId = intent != null ? intent.getStringExtra(EXTRA_CALL_ID) : null;
        String peerName = intent != null ? intent.getStringExtra(EXTRA_PEER_NAME) : null;
        String sessionKey = intent != null ? intent.getStringExtra(EXTRA_SESSION_KEY) : null;
        if (coordinator != null) {
            CallSessionCoordinator.CallUiState st = coordinator.getCurrentState();
            if (callId == null) {
                callId = st.callId;
            }
            if (peerName == null) {
                peerName = st.peerName;
            }
        }

        Notification notification = buildOngoingNotification(callId, peerName, sessionKey);
        int serviceType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                : 0;
        try {
            ServiceCompat.startForeground(this, ONGOING_NOTIFICATION_ID, notification, serviceType);
            foregroundStarted = true;
            lastNotifiedCallId = callId;
            lastNotifiedPeerName = peerName;
            lastSessionKey = sessionKey;
        } catch (RuntimeException e) {
            Log.w(TAG, "startForeground microphone thất bại: " + e.getMessage());
            if (coordinator != null) {
                coordinator.onForegroundServiceStartFailed();
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        // Kiểm tra lại trạng thái sau startForeground để không để lại thông báo ma nếu cuộc gọi đã kết thúc nhanh (409/403).
        if (coordinator != null) {
            CallSessionCoordinator.State cur = coordinator.getCurrentState().state;
            if (cur == CallSessionCoordinator.State.ENDED) {
                stopForegroundAndSelf();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        foregroundStarted = false;
        if (coordinator != null && observing) {
            coordinator.getUiState().removeObserver(stateObserver);
            observing = false;
        }
        super.onDestroy();
    }

    private void onCallStateChanged(@Nullable CallSessionCoordinator.CallUiState state) {
        if (state == null
                || state.state == CallSessionCoordinator.State.IDLE
                || state.state == CallSessionCoordinator.State.ENDED) {
            // Chỉ dừng sau khi onStartCommand đã gọi startForeground (tránh ForegroundServiceDidNotStartInTimeException).
            if (foregroundStarted) {
                stopForegroundAndSelf();
            }
            return;
        }
        // Cập nhật thông báo cuộc gọi đi ngay khi POST /api/calls trả về callId thật để bấm vào quay lại CallFragment được.
        if (foregroundStarted && state.callId != null && !state.callId.equals(lastNotifiedCallId)) {
            lastNotifiedCallId = state.callId;
            if (state.peerName != null) {
                lastNotifiedPeerName = state.peerName;
            }
            String effectiveSessionKey = state.sessionKey != null ? state.sessionKey : lastSessionKey;
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(ONGOING_NOTIFICATION_ID,
                        buildOngoingNotification(lastNotifiedCallId, lastNotifiedPeerName, effectiveSessionKey));
            }
        }
    }

    private void stopForegroundAndSelf() {
        foregroundStarted = false;
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void ensureCallsChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (nm.getNotificationChannel(CHANNEL_CALLS) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_CALLS,
                    getString(R.string.call_channel_name),
                    NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(channel);
        }
    }

    @NonNull
    private Notification buildOngoingNotification(
            @Nullable String callId,
            @Nullable String peerName,
            @Nullable String sessionKey) {
        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (callId != null) {
            tapIntent.putExtra(MAIN_EXTRA_OPEN_CALL_ID, callId);
        }
        if (sessionKey != null) {
            tapIntent.putExtra(MAIN_EXTRA_SESSION_KEY, sessionKey);
        }
        PendingIntent tapPending = PendingIntent.getActivity(
                this,
                ONGOING_NOTIFICATION_ID,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent endIntent = new Intent(this, CallService.class);
        endIntent.setAction(ACTION_END_CALL);
        PendingIntent endPending = PendingIntent.getService(
                this,
                ONGOING_NOTIFICATION_ID + 1,
                endIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = (peerName != null && !peerName.trim().isEmpty())
                ? peerName
                : getString(R.string.call_ongoing_title);

        return new NotificationCompat.Builder(this, CHANNEL_CALLS)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle(title)
                .setContentText(getString(R.string.call_ongoing_text))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(tapPending)
                .addAction(R.drawable.ic_call_end, getString(R.string.call_action_end), endPending)
                .build();
    }
}
