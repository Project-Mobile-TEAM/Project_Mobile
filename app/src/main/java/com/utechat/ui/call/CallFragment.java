package com.utechat.ui.call;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.bumptech.glide.Glide;
import com.google.android.material.snackbar.Snackbar;
import com.utechat.BuildConfig;
import com.utechat.R;
import com.utechat.data.call.CallService;
import com.utechat.data.call.CallSessionCoordinator;
import com.utechat.data.remote.api.ApiClient;
import com.utechat.data.session.SessionManager;
import com.utechat.databinding.FragmentCallBinding;
import java.util.Locale;

/**
 * Màn hình cuộc gọi thoại WebRTC 1-1 chuyên biệt (D-11, ADR-021, T-06-19):
 * - Chỉ khởi chạy CallService microphone foreground khi Fragment đang resumed VÀ quyền RECORD_AUDIO đã được cấp.
 * - Khi có cuộc gọi đến (INCOMING_RINGING): phát chuông + rung; dừng ngay khi nghe máy, từ chối, xử lý thiết bị khác hoặc kết thúc.
 * - Hỗ trợ nút Tắt mic, Loa ngoài, Kết thúc, Nghe máy, Từ chối (>= 48dp), đồng hồ mm:ss sống sót khi xoay,
 *   và nút gọi dự phòng qua nhà mạng (ACTION_DIAL) khi WebRTC lỗi và đã biết SĐT đối phương.
 */
public class CallFragment extends Fragment {
    public static final String ARG_CALL_ID = "callId";
    public static final String ARG_CONVERSATION_ID = "conversationId";
    public static final String ARG_PEER_USER_ID = "peerUserId";
    public static final String ARG_PEER_NAME = "peerName";
    public static final String ARG_PEER_AVATAR_URL = "peerAvatarUrl";
    public static final String ARG_PEER_PHONE = "peerPhone";

    private enum PendingAudioAction {
        NONE,
        START_OUTGOING,
        ACCEPT_INCOMING
    }

    @Nullable private FragmentCallBinding binding;
    private CallViewModel viewModel;
    private PendingAudioAction pendingAudioAction = PendingAudioAction.NONE;

    @Nullable private Ringtone incomingRingtone;
    private boolean alertingIncoming = false;

    private final ActivityResultLauncher<String> audioPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                PendingAudioAction action = pendingAudioAction;
                pendingAudioAction = PendingAudioAction.NONE;
                if (!Boolean.TRUE.equals(granted)) {
                    showPermissionDeniedError();
                    if (action == PendingAudioAction.START_OUTGOING && viewModel != null) {
                        viewModel.onForegroundServiceStartFailed();
                    }
                    return;
                }
                // Kiểm tra lại trạng thái resumed + quyền thực tế trước khi khởi chạy FGS microphone (targetSdk 36).
                if (!isResumed() || !hasRecordAudioPermission()) {
                    return;
                }
                if (action == PendingAudioAction.START_OUTGOING) {
                    launchOutgoingWithService();
                } else if (action == PendingAudioAction.ACCEPT_INCOMING) {
                    acceptIncomingWithService();
                }
            });

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = FragmentCallBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this).get(CallViewModel.class);

        setupButtonListeners();
        observeViewModel();
        maybeInitiateOutgoingCallFromArgs();
    }

    @Override
    public void onDestroyView() {
        stopIncomingAlert();
        binding = null;
        super.onDestroyView();
    }

    private void setupButtonListeners() {
        if (binding == null) {
            return;
        }
        binding.btnAcceptCall.setOnClickListener(v -> {
            stopIncomingAlert();
            requestAudioAndRun(PendingAudioAction.ACCEPT_INCOMING);
        });
        binding.btnRejectCall.setOnClickListener(v -> {
            stopIncomingAlert();
            viewModel.rejectIncomingCall();
        });
        binding.btnToggleMute.setOnClickListener(v -> viewModel.toggleMute());
        binding.btnToggleSpeaker.setOnClickListener(v -> viewModel.toggleSpeaker());
        binding.btnEndCall.setOnClickListener(v -> {
            stopIncomingAlert();
            viewModel.endCall();
        });
        binding.btnCloseCall.setOnClickListener(v -> {
            stopIncomingAlert();
            viewModel.resetIfEnded();
            navigateBack();
        });
        binding.btnDialFallback.setOnClickListener(v -> openCarrierDialFallback());
    }

    private void observeViewModel() {
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::renderState);
        viewModel.getFormattedTimer().observe(getViewLifecycleOwner(), timerText -> {
            if (binding == null) {
                return;
            }
            if (timerText != null && !timerText.isEmpty()) {
                binding.tvCallTimer.setText(timerText);
                binding.tvCallTimer.setVisibility(View.VISIBLE);
            } else {
                binding.tvCallTimer.setVisibility(View.GONE);
            }
        });
    }

    private void maybeInitiateOutgoingCallFromArgs() {
        Bundle args = getArguments();
        if (args == null) {
            return;
        }
        String existingCallId = args.getString(ARG_CALL_ID);
        String conversationId = args.getString(ARG_CONVERSATION_ID);
        if (existingCallId != null || conversationId == null || conversationId.trim().isEmpty()) {
            return;
        }
        CallSessionCoordinator.CallUiState current = viewModel.getCurrentState();
        if (!viewModel.isOutgoingDispatched()
                && (current.state == CallSessionCoordinator.State.IDLE
                || current.state == CallSessionCoordinator.State.ENDED)) {
            requestAudioAndRun(PendingAudioAction.START_OUTGOING);
        }
    }

    private void requestAudioAndRun(@NonNull PendingAudioAction action) {
        if (hasRecordAudioPermission()) {
            if (action == PendingAudioAction.START_OUTGOING) {
                launchOutgoingWithService();
            } else if (action == PendingAudioAction.ACCEPT_INCOMING) {
                acceptIncomingWithService();
            }
            return;
        }
        pendingAudioAction = action;
        if (binding != null && shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            Snackbar.make(binding.getRoot(), R.string.call_permission_audio_rationale, Snackbar.LENGTH_SHORT).show();
        }
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
    }

    private void launchOutgoingWithService() {
        Bundle args = getArguments();
        String conversationId = args != null ? args.getString(ARG_CONVERSATION_ID) : null;
        if (conversationId == null || conversationId.trim().isEmpty()) {
            return;
        }
        String peerUserId = args.getString(ARG_PEER_USER_ID);
        String peerName = args.getString(ARG_PEER_NAME);
        String peerAvatarUrl = args.getString(ARG_PEER_AVATAR_URL);
        String peerPhone = args.getString(ARG_PEER_PHONE);

        Context ctx = requireContext();
        String sessionKey = SessionManager.get().sessionKey();
        if (!isResumed() || !CallService.startForActiveCall(ctx, null, peerName, sessionKey)) {
            viewModel.onForegroundServiceStartFailed();
            return;
        }
        viewModel.startOutgoingCall(conversationId, peerUserId, peerName, peerAvatarUrl, peerPhone);
    }

    private void acceptIncomingWithService() {
        CallSessionCoordinator.CallUiState current = viewModel.getCurrentState();
        if (current.state != CallSessionCoordinator.State.INCOMING_RINGING || current.ringingDeadlinePassed) {
            return;
        }
        Context ctx = requireContext();
        String sessionKey = SessionManager.get().sessionKey();
        if (!isResumed() || !CallService.startForActiveCall(ctx, current.callId, current.peerName, sessionKey)) {
            viewModel.onForegroundServiceStartFailed();
            return;
        }
        viewModel.acceptIncomingCall();
    }

    private void renderState(@Nullable CallSessionCoordinator.CallUiState state) {
        if (binding == null || state == null) {
            return;
        }

        // Hiển thị tên & ảnh đại diện (ưu tiên từ coordinator, fallback sang arguments lúc mới mở).
        Bundle args = getArguments();
        String peerName = state.peerName != null
                ? state.peerName
                : (args != null ? args.getString(ARG_PEER_NAME) : null);
        String peerAvatar = state.peerAvatarUrl != null
                ? state.peerAvatarUrl
                : (args != null ? args.getString(ARG_PEER_AVATAR_URL) : null);
        String peerPhone = state.peerPhone != null
                ? state.peerPhone
                : (args != null ? args.getString(ARG_PEER_PHONE) : null);

        String displayName = (peerName != null && !peerName.trim().isEmpty())
                ? peerName.trim()
                : getString(R.string.call_unknown_peer);
        binding.tvPeerName.setText(displayName);
        binding.tvPeerInitials.setText(buildInitials(displayName));
        renderAvatar(peerAvatar);

        // Chuông và rung chỉ chạy khi đang INCOMING_RINGING và chưa quá hạn 45s.
        if (state.state == CallSessionCoordinator.State.INCOMING_RINGING && !state.ringingDeadlinePassed) {
            startIncomingAlert();
        } else {
            stopIncomingAlert();
        }

        binding.tvCallBanner.setVisibility(state.connectionLost ? View.VISIBLE : View.GONE);
        binding.tvCallStatus.setText(resolveStatusText(state));

        boolean incomingRinging = state.state == CallSessionCoordinator.State.INCOMING_RINGING;
        boolean inCallControls = state.state == CallSessionCoordinator.State.OUTGOING_RINGING
                || state.state == CallSessionCoordinator.State.CONNECTING
                || state.state == CallSessionCoordinator.State.ACTIVE;
        boolean ended = state.state == CallSessionCoordinator.State.ENDED
                || state.state == CallSessionCoordinator.State.IDLE;

        binding.layoutIncomingControls.setVisibility(incomingRinging ? View.VISIBLE : View.GONE);
        binding.btnAcceptCall.setEnabled(!state.ringingDeadlinePassed);
        binding.btnRejectCall.setEnabled(!state.ringingDeadlinePassed);

        binding.layoutActiveControls.setVisibility(inCallControls ? View.VISIBLE : View.GONE);
        binding.btnToggleMute.setText(state.micMuted ? R.string.call_action_unmute : R.string.call_action_mute);
        binding.btnToggleMute.setSelected(state.micMuted);
        binding.btnToggleSpeaker.setSelected(state.speakerOn);

        binding.btnCloseCall.setVisibility(ended ? View.VISIBLE : View.GONE);

        // Nút gọi nhà mạng dự phòng (ACTION_DIAL) chỉ hiện khi cuộc gọi lỗi và có sẵn SĐT bạn bè.
        boolean showDialFallback = state.state == CallSessionCoordinator.State.ENDED
                && state.errorCode != null
                && peerPhone != null
                && !peerPhone.trim().isEmpty();
        binding.btnDialFallback.setVisibility(showDialFallback ? View.VISIBLE : View.GONE);
    }

    private void renderAvatar(@Nullable String avatarUrl) {
        if (binding == null) {
            return;
        }
        if (avatarUrl == null || avatarUrl.trim().isEmpty()) {
            binding.ivPeerAvatar.setVisibility(View.GONE);
            binding.tvPeerInitials.setVisibility(View.VISIBLE);
            return;
        }
        String resolvedUrl = avatarUrl.trim();
        if (resolvedUrl.startsWith("/")) {
            try {
                resolvedUrl = ApiClient.resolveBackendMediaUrl(BuildConfig.BACKEND_BASE_URL, resolvedUrl);
            } catch (IllegalArgumentException e) {
                binding.ivPeerAvatar.setVisibility(View.GONE);
                binding.tvPeerInitials.setVisibility(View.VISIBLE);
                return;
            }
        }
        binding.ivPeerAvatar.setVisibility(View.VISIBLE);
        binding.tvPeerInitials.setVisibility(View.GONE);
        Glide.with(this)
                .load(resolvedUrl)
                .circleCrop()
                .into(binding.ivPeerAvatar);
    }

    @NonNull
    private String resolveStatusText(@NonNull CallSessionCoordinator.CallUiState state) {
        switch (state.state) {
            case OUTGOING_RINGING:
                return getString(R.string.call_status_calling);
            case INCOMING_RINGING:
                return getString(R.string.call_status_incoming);
            case CONNECTING:
                return getString(R.string.call_status_connecting);
            case ACTIVE:
                return state.connectionLost
                        ? getString(R.string.call_status_reconnecting)
                        : getString(R.string.call_ongoing_text);
            case ENDED:
                return resolveEndedReasonText(state.errorCode, state.endReason);
            case IDLE:
            default:
                return getString(R.string.call_status_ended);
        }
    }

    @NonNull
    private String resolveEndedReasonText(@Nullable String errorCode, @Nullable String endReason) {
        if (errorCode != null) {
            switch (errorCode) {
                case "CALL_BUSY":
                    return getString(R.string.call_status_busy);
                case "BLOCKED":
                    return getString(R.string.call_error_blocked);
                case "CONTACT_REQUIRED":
                    return getString(R.string.call_error_contact_required);
                case "CALL_UNSUPPORTED":
                    return getString(R.string.call_error_unsupported);
                case "RATE_LIMITED":
                    return getString(R.string.call_error_rate_limited);
                case "FOREGROUND_SERVICE_FAILED":
                    return getString(R.string.call_error_fgs_failed);
                case "OFFLINE":
                    return getString(R.string.call_status_offline);
                default:
                    return getString(R.string.call_error_generic);
            }
        }
        if (endReason != null) {
            switch (endReason) {
                case "TIMEOUT":
                    return getString(R.string.call_status_no_answer);
                case "REJECT":
                    return getString(R.string.call_status_rejected);
                case "BUSY":
                    return getString(R.string.call_status_busy);
                case "HANDLED_ELSEWHERE":
                    return getString(R.string.call_status_handled_elsewhere);
                case "OFFLINE":
                    return getString(R.string.call_status_offline);
                default:
                    break;
            }
        }
        return getString(R.string.call_status_ended);
    }

    private void openCarrierDialFallback() {
        CallSessionCoordinator.CallUiState st = viewModel.getCurrentState();
        Bundle args = getArguments();
        String phone = st.peerPhone != null
                ? st.peerPhone
                : (args != null ? args.getString(ARG_PEER_PHONE) : null);
        if (phone == null || phone.trim().isEmpty()) {
            return;
        }
        Intent dialIntent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone.trim())));
        startActivity(dialIntent);
    }

    private void startIncomingAlert() {
        if (alertingIncoming || getContext() == null) {
            return;
        }
        alertingIncoming = true;
        Context ctx = requireContext();
        try {
            Uri ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (ringtoneUri != null) {
                incomingRingtone = RingtoneManager.getRingtone(ctx, ringtoneUri);
                if (incomingRingtone != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        incomingRingtone.setLooping(true);
                    }
                    incomingRingtone.play();
                }
            }
        } catch (RuntimeException ignored) {
        }
        try {
            Vibrator vibrator = resolveVibrator(ctx);
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = new long[]{0L, 600L, 900L};
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void stopIncomingAlert() {
        if (!alertingIncoming) {
            return;
        }
        alertingIncoming = false;
        if (incomingRingtone != null) {
            try {
                if (incomingRingtone.isPlaying()) {
                    incomingRingtone.stop();
                }
            } catch (RuntimeException ignored) {
            }
            incomingRingtone = null;
        }
        Context ctx = getContext();
        if (ctx != null) {
            try {
                Vibrator vibrator = resolveVibrator(ctx);
                if (vibrator != null) {
                    vibrator.cancel();
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Nullable
    private static Vibrator resolveVibrator(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return vm != null ? vm.getDefaultVibrator() : null;
        }
        return getLegacyVibrator(context);
    }

    @SuppressWarnings("deprecation")
    @Nullable
    private static Vibrator getLegacyVibrator(@NonNull Context context) {
        return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    private boolean hasRecordAudioPermission() {
        Context ctx = getContext();
        return ctx != null
                && ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void showPermissionDeniedError() {
        if (binding != null) {
            Snackbar.make(binding.getRoot(), R.string.call_permission_audio_denied, Snackbar.LENGTH_LONG).show();
        }
    }

    private void navigateBack() {
        if (!isAdded()) {
            return;
        }
        NavHostFragment.findNavController(this).popBackStack();
    }

    @NonNull
    private static String buildInitials(@NonNull String name) {
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return "?";
        }
        if (parts.length == 1) {
            return parts[0].substring(0, 1).toUpperCase(Locale.ROOT);
        }
        String first = parts[0].substring(0, 1);
        String last = parts[parts.length - 1].substring(0, 1);
        return (first + last).toUpperCase(Locale.ROOT);
    }
}
