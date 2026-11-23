package com.utechat.ui.call;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.SavedStateHandle;
import com.utechat.data.call.CallSessionCoordinator;
import java.util.Locale;

/**
 * ViewModel cho màn hình cuộc gọi thoại WebRTC 1-1 (ADR-021, D-11):
 * - Lấy CallSessionCoordinator qua CallSessionCoordinator.from(app) (độc lập với Injection ở Wave 3).
 * - Lưu mốc answeredAtElapsedRealtime và cờ khởi tạo cuộc gọi trong SavedStateHandle
 *   để xoay màn hình không reset đồng hồ mm:ss và không gọi lại POST /api/calls lần 2.
 */
public class CallViewModel extends AndroidViewModel {
    private static final String KEY_OUTGOING_DISPATCHED = "call_outgoing_dispatched";
    private static final String KEY_ANSWERED_ELAPSED = "call_answered_elapsed";
    private static final long TICK_INTERVAL_MS = 1_000L;

    private final SavedStateHandle savedStateHandle;
    @Nullable private final CallSessionCoordinator coordinator;
    private final MutableLiveData<CallSessionCoordinator.CallUiState> fallbackState =
            new MutableLiveData<>(CallSessionCoordinator.CallUiState.idle());
    private final MutableLiveData<String> formattedTimer = new MutableLiveData<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Observer<CallSessionCoordinator.CallUiState> stateObserver = this::onCoordinatorStateChanged;
    private final Runnable timerTicker = new Runnable() {
        @Override
        public void run() {
            CallSessionCoordinator.CallUiState st = getCurrentState();
            if (st.state == CallSessionCoordinator.State.ACTIVE && st.answeredAtElapsedRealtime > 0L) {
                long elapsedSec = Math.max(0L,
                        (SystemClock.elapsedRealtime() - st.answeredAtElapsedRealtime) / 1_000L);
                formattedTimer.setValue(formatMmSs(elapsedSec));
                mainHandler.postDelayed(this, TICK_INTERVAL_MS);
            } else {
                formattedTimer.setValue(null);
            }
        }
    };

    public CallViewModel(@NonNull Application application, @NonNull SavedStateHandle savedStateHandle) {
        super(application);
        this.savedStateHandle = savedStateHandle;
        this.coordinator = CallSessionCoordinator.from(application);
        if (this.coordinator != null) {
            this.coordinator.getUiState().observeForever(stateObserver);
            onCoordinatorStateChanged(this.coordinator.getCurrentState());
        }
    }

    @Nullable
    public CallSessionCoordinator getCoordinator() {
        return coordinator;
    }

    @NonNull
    public LiveData<CallSessionCoordinator.CallUiState> getUiState() {
        return coordinator != null ? coordinator.getUiState() : fallbackState;
    }

    @NonNull
    public CallSessionCoordinator.CallUiState getCurrentState() {
        return coordinator != null ? coordinator.getCurrentState() : CallSessionCoordinator.CallUiState.idle();
    }

    /** Chuỗi thời lượng cuộc gọi dạng mm:ss; null khi chưa ở trạng thái ACTIVE. */
    @NonNull
    public LiveData<String> getFormattedTimer() {
        return formattedTimer;
    }

    public boolean isOutgoingDispatched() {
        return Boolean.TRUE.equals(savedStateHandle.get(KEY_OUTGOING_DISPATCHED));
    }

    public void markOutgoingDispatched() {
        savedStateHandle.set(KEY_OUTGOING_DISPATCHED, true);
    }

    public void startOutgoingCall(
            @NonNull String conversationId,
            @Nullable String peerUserId,
            @Nullable String peerName,
            @Nullable String peerAvatarUrl,
            @Nullable String peerPhone) {
        if (coordinator == null) {
            return;
        }
        markOutgoingDispatched();
        coordinator.startOutgoingCall(conversationId, peerUserId, peerName, peerAvatarUrl, peerPhone);
    }

    public void acceptIncomingCall() {
        if (coordinator != null) {
            coordinator.acceptIncomingCall();
        }
    }

    public void rejectIncomingCall() {
        if (coordinator != null) {
            coordinator.rejectIncomingCall();
        }
    }

    public void endCall() {
        if (coordinator != null) {
            coordinator.endCall();
        }
    }

    public void toggleMute() {
        if (coordinator != null) {
            coordinator.toggleMute();
        }
    }

    public void toggleSpeaker() {
        if (coordinator != null) {
            coordinator.toggleSpeaker();
        }
    }

    public void onForegroundServiceStartFailed() {
        if (coordinator != null) {
            coordinator.onForegroundServiceStartFailed();
        }
    }

    public void resetIfEnded() {
        if (coordinator != null) {
            coordinator.resetToIdle();
        }
    }

    private void onCoordinatorStateChanged(@Nullable CallSessionCoordinator.CallUiState state) {
        mainHandler.removeCallbacks(timerTicker);
        if (state != null && state.state == CallSessionCoordinator.State.ACTIVE) {
            long baseElapsed = state.answeredAtElapsedRealtime;
            if (baseElapsed <= 0L) {
                Long saved = savedStateHandle.get(KEY_ANSWERED_ELAPSED);
                baseElapsed = saved != null && saved > 0L ? saved : SystemClock.elapsedRealtime();
            } else {
                savedStateHandle.set(KEY_ANSWERED_ELAPSED, baseElapsed);
            }
            long elapsedSec = Math.max(0L, (SystemClock.elapsedRealtime() - baseElapsed) / 1_000L);
            formattedTimer.setValue(formatMmSs(elapsedSec));
            mainHandler.postDelayed(timerTicker, TICK_INTERVAL_MS);
        } else {
            formattedTimer.setValue(null);
            if (state == null || state.state == CallSessionCoordinator.State.IDLE) {
                savedStateHandle.remove(KEY_ANSWERED_ELAPSED);
            }
        }
    }

    @Override
    protected void onCleared() {
        mainHandler.removeCallbacks(timerTicker);
        if (coordinator != null) {
            coordinator.getUiState().removeObserver(stateObserver);
        }
        super.onCleared();
    }

    @NonNull
    public static String formatMmSs(long totalSeconds) {
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }
}
