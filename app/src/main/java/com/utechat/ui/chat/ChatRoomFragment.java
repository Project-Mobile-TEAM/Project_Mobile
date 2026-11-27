package com.utechat.ui.chat;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.utechat.BuildConfig;
import com.utechat.R;
import com.utechat.data.local.entity.AttachmentTransferEntity;
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.ConversationMemberEntity;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.local.entity.MessageReactionEntity;
import com.utechat.data.local.entity.StickerEntity;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.media.LocationFetchHelper;
import com.utechat.data.media.MediaDownloadManager;
import com.utechat.data.media.MediaPreparationHelper;
import com.utechat.data.media.VoiceRecorderHelper;
import com.utechat.data.session.SessionManager;
import com.utechat.data.sync.WsConnectionState;
import com.utechat.databinding.DialogAttachmentPickerBinding;
import com.utechat.databinding.FragmentChatRoomBinding;
import com.utechat.di.Injection;
import com.utechat.ui.call.CallFragment;
import com.utechat.ui.media.ImageViewerFragment;
import com.utechat.ui.media.VideoPlayerFragment;
import com.utechat.ui.media.VoiceBubblePlayer;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phòng chat (Phase 5 & Phase 6):
 * - Tin nhắn thời gian thực/offline, mốc ngày, trạng thái gửi, gửi lại tin lỗi (D-05),
 *   biểu ngữ chặn/chỉ-bạn-bè (D-04), chỉ đọc khi đã rời nhóm (D-12), tìm trong phòng (D-13), báo đã đọc khi RESUMED (D-11).
 * - Đính kèm ảnh/video/camera/tệp/ghi âm thoại/vị trí (D-02, D-03), xem ảnh & video trong app,
 *   phát voice 1 instance duy nhất, gọi thoại 1-1 trên toolbar, ActiveRoomTracker và dịch EN <-> VI trên máy (D-13..D-15).
 */
public class ChatRoomFragment extends Fragment {
    private static final String STATE_PENDING_CAMERA_PATH = "pending_camera_file_path";
    private static final long VOICE_TIMER_TICK_MS = 250L;

    private FragmentChatRoomBinding binding;
    private ChatRoomViewModel vm;
    private MessageAdapter adapter;
    private LinearLayoutManager lm;
    private TextWatcher inputWatcher, queryWatcher;
    private boolean readOnly;

    @Nullable private VoiceRecorderHelper voiceRecorder;
    @Nullable private VoiceBubblePlayer voiceBubblePlayer;
    @Nullable private BottomSheetDialog attachmentSheet;
    @Nullable private BottomSheetDialog chatSheet;
    @Nullable private AlertDialog messageConfirmation;
    @Nullable private StickerTrayView stickerTray;
    private boolean stickerCatalogOpened;
    @Nullable private String pendingCameraFilePath;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable voiceRecordTicker = new Runnable() {
        @Override
        public void run() {
            if (binding == null || voiceRecorder == null || !voiceRecorder.isRecording()) {
                return;
            }
            long elapsed = voiceRecorder.elapsedMs();
            binding.voiceTimer.setText(getString(R.string.media_voice_recording, formatMmSs(elapsed)));
            if (elapsed >= VoiceRecorderHelper.MAX_DURATION_MS) {
                stopAndSendVoiceRecording();
                return;
            }
            mainHandler.postDelayed(this, VOICE_TIMER_TICK_MS);
        }
    };

    // Đăng ký đầy đủ các ActivityResultLauncher ngay khi tạo Fragment để sống sót qua cấu hình thay đổi.
    private final ActivityResultLauncher<PickVisualMediaRequest> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    prepareAndEnqueueUri(uri, "IMAGE");
                }
            });

    private final ActivityResultLauncher<PickVisualMediaRequest> pickVideoLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    prepareAndEnqueueUri(uri, "VIDEO");
                }
            });

    private final ActivityResultLauncher<String[]> openDocumentLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    prepareAndEnqueueUri(uri, "FILE");
                }
            });

    private final ActivityResultLauncher<Uri> takePictureLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(),
                    success -> onCameraPictureResult(Boolean.TRUE.equals(success)));

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (Boolean.TRUE.equals(granted)) {
                    launchCameraCapture();
                } else {
                    showSnackbar(R.string.media_camera_permission_denied);
                }
            });

    private final ActivityResultLauncher<String> recordAudioPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (Boolean.TRUE.equals(granted)) {
                    startVoiceRecording();
                } else {
                    showSnackbar(R.string.media_voice_permission_denied);
                }
            });

    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean fine = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION));
                boolean coarse = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                if (fine || coarse) {
                    fetchAndSendLocation();
                } else {
                    showSnackbar(R.string.media_location_permission_denied);
                }
            });

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            pendingCameraFilePath = savedInstanceState.getString(STATE_PENDING_CAMERA_PATH);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pendingCameraFilePath != null) {
            outState.putString(STATE_PENDING_CAMERA_PATH, pendingCameraFilePath);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        binding = FragmentChatRoomBinding.inflate(inflater, parent, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        // SavedStateHandle nhận sẵn conversationId / peerUserId / targetMessageLocalId từ arguments của nav_chat.
        vm = new ViewModelProvider(this, ChatRoomViewModel.FACTORY).get(ChatRoomViewModel.class);
        String meId = vm.meId();
        voiceRecorder = new VoiceRecorderHelper(requireContext());

        binding.toolbar.setNavigationOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());
        binding.toolbar.inflateMenu(R.menu.menu_chat_room);
        binding.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_voice_call) {
                openVoiceCall();
                return true;
            }
            if (item.getItemId() == R.id.action_search_room) {
                openSearch();
                return true;
            }
            if (item.getItemId() == R.id.action_group_info) {
                Bundle args = new Bundle();
                args.putString("conversationId", vm.getConversationId().getValue());
                NavHostFragment.findNavController(this).navigate(R.id.action_chatRoom_to_groupInfo, args);
                return true;
            }
            return false;
        });

        adapter = new MessageAdapter(meId, this::showFailedDialog);
        voiceBubblePlayer = new VoiceBubblePlayer(
                Injection.provideMediaDownloadManager(requireContext()),
                playbackState -> {
                    if (adapter != null) {
                        adapter.setVoicePlaybackState(playbackState);
                    }
                });
        adapter.setInteractionListener(new MessageAdapter.InteractionListener() {
            @Override
            public void onImageClick(@NonNull MessageEntity message) {
                openMediaViewer(message, R.id.action_chatRoom_to_imageViewer);
            }

            @Override
            public void onVideoClick(@NonNull MessageEntity message) {
                openMediaViewer(message, R.id.action_chatRoom_to_videoPlayer);
            }

            @Override
            public void onVoiceToggleClick(@NonNull MessageEntity message) {
                toggleVoicePlayback(message);
            }

            @Override
            public void onVoiceSeek(@NonNull MessageEntity message, int positionMs) {
                if (voiceBubblePlayer != null && message.getClientId() != null) {
                    voiceBubblePlayer.seekTo(message.getClientId(), positionMs);
                }
            }

            @Override
            public void onFileClick(@NonNull MessageEntity message) {
                openDocumentAttachment(message);
            }

            @Override
            public void onLocationClick(@NonNull MessageEntity message) {
                openLocationInMaps(message);
            }

            @Override
            public void onTextLongClick(@NonNull MessageEntity message) {
                showMessageActionSheet(message);
            }

            @Override
            public void onReactionsClick(@NonNull MessageEntity message,
                    @NonNull List<MessageReactionEntity> reactions) {
                if (chatSheet != null) chatSheet.dismiss();
                Map<String, UserEntity> users = vm.getUsers().getValue();
                chatSheet = ReactionDetailsBottomSheet.show(
                        requireContext(), reactions, users == null ? Map.of() : users, vm.meId());
            }

            @Override
            public void onHideTranslationClick(@NonNull MessageEntity message) {
                if (message.getClientId() != null) {
                    vm.hideTranslation(message.getClientId());
                }
            }
        });

        lm = new LinearLayoutManager(requireContext());
        lm.setStackFromEnd(true); // Tin mới nhất nằm sát khung soạn, như mọi app chat.
        binding.messages.setLayoutManager(lm);
        binding.messages.setAdapter(adapter);
        binding.messages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy < 0 && lm.findFirstVisibleItemPosition() == 0) {
                    vm.loadOlder(readOnly);
                }
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    markVisibleRead();
                }
            }
        });

        // Khung soạn: gõ thì báo "đang nhập", gửi thì xóa ô; nút đính kèm & micro cho Phase 6.
        inputWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { vm.onTextChanged(s.toString()); }
            @Override public void afterTextChanged(Editable e) { }
        };
        binding.input.addTextChangedListener(inputWatcher);
        bindStickerTray();
        binding.send.setOnClickListener(v -> {
            String text = binding.input.getText().toString();
            if (text.isBlank()) return;
            vm.sendText(text);
            binding.input.setText("");
        });
        binding.attach.setOnClickListener(v -> showAttachmentPicker());
        binding.mic.setOnClickListener(v -> requestVoiceRecording());
        binding.voiceCancel.setOnClickListener(v -> cancelVoiceRecording());
        binding.voiceSend.setOnClickListener(v -> stopAndSendVoiceRecording());

        bindSearch();
        binding.policyAction.setOnClickListener(v -> vm.sendFriendRequest(
                ChatRoomViewModel.peerOf(vm.deepLinkPeer(), vm.getMembers().getValue(), vm.meId())));
        vm.requestSent.observe(getViewLifecycleOwner(), b -> renderComposer());
        vm.notice.observe(getViewLifecycleOwner(), res -> {
            if (res == null) return;
            vm.consumeNotice();
            showSnackbar(res);
        });
        vm.cellularConfirmPending.observe(getViewLifecycleOwner(), req -> {
            if (req == null) return;
            vm.consumeCellularConfirm();
            showCellularDownloadDialog(req);
        });

        vm.getWsState().observe(getViewLifecycleOwner(), this::renderBanner);
        vm.getUsers().observe(getViewLifecycleOwner(), users -> {
            adapter.setUsers(users);
            renderHeader();
        });
        vm.getConversation().observe(getViewLifecycleOwner(), c -> {
            adapter.setGroup(c != null && "GROUP".equals(c.getType()));
            renderHeader();
        });
        vm.getConversationId().observe(getViewLifecycleOwner(), cid -> {
            if (cid != null && isResumed()) {
                Injection.provideActiveRoomTracker().setActive(cid);
                Injection.provideNotificationHelper(requireContext()).cancelConversation(cid);
            }
        });
        vm.getMembers().observe(getViewLifecycleOwner(), m -> {
            readOnly = ChatRoomViewModel.isReadOnly(m, meId);
            renderHeader();
            renderComposer();
        });
        vm.getTyping().observe(getViewLifecycleOwner(), t -> renderHeader());
        vm.openError.observe(getViewLifecycleOwner(), e -> renderComposer());
        vm.loadingMore.observe(getViewLifecycleOwner(),
                b -> binding.loadingMore.setVisibility(Boolean.TRUE.equals(b) ? View.VISIBLE : View.GONE));
        vm.getTransfers().observe(getViewLifecycleOwner(), transfers -> adapter.setTransfersMap(transfers));
        vm.getTranslations().observe(getViewLifecycleOwner(), translations -> adapter.setTranslationState(translations));
        vm.observeReactionsMap().observe(getViewLifecycleOwner(), adapter::setReactionsMap);
        vm.getMessages().observe(getViewLifecycleOwner(), this::submit);
        vm.pendingScroll.observe(getViewLifecycleOwner(), id -> scrollToPending());
    }

    /** Nối khay với bộ đã cài trong Room và chia sẻ chỗ hiển thị với bàn phím. */
    private void bindStickerTray() {
        stickerTray = new StickerTrayView(binding.stickerTray, this::sendSticker, this::openStickerStore);
        vm.observeInstalledStickerPacks().observe(getViewLifecycleOwner(), stickerTray::setPacks);
        vm.observeInstalledStickers().observe(getViewLifecycleOwner(), stickerTray::setStickers);
        binding.sticker.setOnClickListener(view -> {
            if (stickerTray.isOpen()) {
                stickerTray.setOpen(false);
                return;
            }
            binding.input.clearFocus();
            WindowInsetsControllerCompat insets = ViewCompat.getWindowInsetsController(view);
            if (insets != null) insets.hide(WindowInsetsCompat.Type.ime());
            stickerTray.setOpen(true);
            stickerCatalogOpened = true;
            vm.refreshStickers(false);
        });
        binding.input.setOnFocusChangeListener((view, focused) -> {
            if (focused) closeStickerTray();
        });
        binding.input.setOnClickListener(view -> closeStickerTray());
    }

    private void openStickerStore() {
        if (chatSheet != null) chatSheet.dismiss();
        chatSheet = StickerStoreDialog.show(requireContext(), getViewLifecycleOwner(),
                vm.observeAllStickerPacks(), vm::installStickerPack, vm::uninstallStickerPack);
        vm.refreshStickers();
    }

    private void sendSticker(@NonNull StickerEntity sticker) {
        if (readOnly || binding.composer.getVisibility() != View.VISIBLE) return;
        // Tin gửi ngoại tuyến dùng ảnh đã tải trong bộ nhãn dán của ứng dụng.
        if (!BuildConfig.USE_FAKE_DATA && StickerTrayView.cachedStickerFile(requireContext(), sticker.getLocalPath()) == null) {
            showSnackbar(R.string.sticker_unavailable_offline);
            return;
        }
        vm.sendSticker(sticker);
        closeStickerTray();
    }

    private void closeStickerTray() {
        if (stickerTray != null) stickerTray.setOpen(false);
    }

    // ======================== ĐÍNH KÈM MEDIA, CAMERA, GHI ÂM, VỊ TRÍ (D-02) ========================

    private void showAttachmentPicker() {
        if (binding == null || readOnly) {
            return;
        }
        dismissAttachmentSheet();
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        DialogAttachmentPickerBinding picker = DialogAttachmentPickerBinding.inflate(getLayoutInflater());
        sheet.setContentView(picker.getRoot());

        picker.pickPhoto.setOnClickListener(v -> {
            sheet.dismiss();
            pickImageLauncher.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        });
        picker.pickVideo.setOnClickListener(v -> {
            sheet.dismiss();
            pickVideoLauncher.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly.INSTANCE)
                    .build());
        });
        picker.pickCamera.setOnClickListener(v -> {
            sheet.dismiss();
            requestCameraCapture();
        });
        picker.pickVoice.setOnClickListener(v -> {
            sheet.dismiss();
            requestVoiceRecording();
        });
        picker.pickFile.setOnClickListener(v -> {
            sheet.dismiss();
            openDocumentLauncher.launch(new String[]{"*/*"});
        });
        picker.pickLocation.setOnClickListener(v -> {
            sheet.dismiss();
            requestLocationShare();
        });

        attachmentSheet = sheet;
        sheet.show();
    }

    private void dismissAttachmentSheet() {
        if (attachmentSheet != null) {
            attachmentSheet.dismiss();
            attachmentSheet = null;
        }
    }

    private void prepareAndEnqueueUri(@NonNull Uri uri, @NonNull String kind) {
        Context ctx = getContext();
        if (ctx == null || vm == null) {
            return;
        }
        final Context appContext = ctx.getApplicationContext();
        final String capturedSessionKey = SessionManager.get() != null ? SessionManager.get().sessionKey() : null;
        final String caption = ("IMAGE".equals(kind) || "VIDEO".equals(kind))
                && binding != null && binding.input.getText() != null
                && !binding.input.getText().toString().isBlank()
                ? binding.input.getText().toString().trim()
                : null;
        final String clientId = UUID.randomUUID().toString();

        // Chạy nén ảnh / sao chép video & tài liệu trên MEDIA_IO để không bao giờ khóa main thread.
        Injection.getMediaIoExecutor().execute(() -> {
            try {
                MediaPreparationHelper.PreparedMedia prepared;
                if ("IMAGE".equals(kind)) {
                    prepared = MediaPreparationHelper.prepareImage(appContext, uri, clientId);
                } else if ("VIDEO".equals(kind)) {
                    prepared = MediaPreparationHelper.prepareVideo(appContext, uri, clientId);
                } else {
                    prepared = MediaPreparationHelper.prepareFile(appContext, uri, clientId);
                }
                mainHandler.post(() -> {
                    String liveKey = SessionManager.get() != null ? SessionManager.get().sessionKey() : null;
                    boolean sameSession = capturedSessionKey == null || capturedSessionKey.equals(liveKey);
                    if (!isAdded() || vm == null || !sameSession) {
                        Injection.getMediaIoExecutor().execute(() -> deleteQuietly(new File(prepared.localPath)));
                        return;
                    }
                    vm.enqueuePreparedMedia(prepared, caption);
                    if (caption != null && binding != null) {
                        binding.input.setText("");
                    }
                });
            } catch (MediaPreparationHelper.PrepareException e) {
                mainHandler.post(() -> {
                    if (isAdded() && binding != null) {
                        showSnackbar(ChatErrors.message(e.code));
                    }
                });
            }
        });
    }

    private void requestCameraCapture() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            launchCameraCapture();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    /** Ghi ảnh chụp tạm vào cacheDir/camera/ qua FileProvider rồi nén xóa EXIF ngay sau khi chụp xong (T-06-26). */
    private void launchCameraCapture() {
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        File camDir = new File(ctx.getCacheDir(), "camera");
        if (!camDir.isDirectory() && !camDir.mkdirs() && !camDir.isDirectory()) {
            showSnackbar(R.string.media_load_failed);
            return;
        }
        File rawFile = new File(camDir, "cam_" + UUID.randomUUID() + ".jpg");
        Uri outputUri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", rawFile);
        pendingCameraFilePath = rawFile.getAbsolutePath();
        try {
            takePictureLauncher.launch(outputUri);
        } catch (ActivityNotFoundException e) {
            pendingCameraFilePath = null;
            deleteQuietly(rawFile);
            showSnackbar(R.string.media_no_external_app);
        }
    }

    private void onCameraPictureResult(boolean success) {
        String path = pendingCameraFilePath;
        pendingCameraFilePath = null;
        if (path == null) {
            return;
        }
        File rawFile = new File(path);
        if (!success || !rawFile.isFile() || rawFile.length() == 0L) {
            deleteQuietly(rawFile);
            return;
        }
        Context ctx = getContext();
        if (ctx == null) {
            deleteQuietly(rawFile);
            return;
        }
        final Context appContext = ctx.getApplicationContext();
        final String capturedSessionKey = SessionManager.get() != null ? SessionManager.get().sessionKey() : null;
        final String clientId = UUID.randomUUID().toString();
        Injection.getMediaIoExecutor().execute(() -> {
            try {
                Uri contentUri = FileProvider.getUriForFile(
                        appContext, appContext.getPackageName() + ".fileprovider", rawFile);
                MediaPreparationHelper.PreparedMedia prepared =
                        MediaPreparationHelper.prepareImage(appContext, contentUri, clientId);
                mainHandler.post(() -> {
                    String liveKey = SessionManager.get() != null ? SessionManager.get().sessionKey() : null;
                    boolean sameSession = capturedSessionKey == null || capturedSessionKey.equals(liveKey);
                    if (isAdded() && vm != null && sameSession) {
                        vm.enqueuePreparedMedia(prepared, null);
                    } else {
                        Injection.getMediaIoExecutor().execute(() -> deleteQuietly(new File(prepared.localPath)));
                    }
                });
            } catch (MediaPreparationHelper.PrepareException e) {
                mainHandler.post(() -> {
                    if (isAdded() && binding != null) {
                        showSnackbar(ChatErrors.message(e.code));
                    }
                });
            } catch (IllegalArgumentException e) {
                mainHandler.post(() -> {
                    if (isAdded() && binding != null) {
                        showSnackbar(R.string.media_load_failed);
                    }
                });
            } finally {
                // Luôn xóa ảnh gốc chứa EXIF khỏi cacheDir/camera/ sau khi nén xong sang pending_media/.
                deleteQuietly(rawFile);
            }
        });
    }

    private void requestVoiceRecording() {
        if (readOnly) {
            return;
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecording();
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void startVoiceRecording() {
        if (binding == null || readOnly || voiceRecorder == null || voiceRecorder.isRecording()) {
            return;
        }
        if (voiceBubblePlayer != null) {
            voiceBubblePlayer.release();
        }
        try {
            voiceRecorder.start(UUID.randomUUID().toString());
        } catch (MediaPreparationHelper.PrepareException e) {
            showSnackbar(ChatErrors.message(e.code));
            return;
        }
        renderComposer();
        binding.voiceTimer.setText(getString(R.string.media_voice_recording, "00:00"));
        mainHandler.removeCallbacks(voiceRecordTicker);
        mainHandler.postDelayed(voiceRecordTicker, VOICE_TIMER_TICK_MS);
    }

    private void cancelVoiceRecording() {
        mainHandler.removeCallbacks(voiceRecordTicker);
        if (voiceRecorder != null && voiceRecorder.isRecording()) {
            voiceRecorder.cancelAndDelete();
        }
        renderComposer();
    }

    private void stopAndSendVoiceRecording() {
        mainHandler.removeCallbacks(voiceRecordTicker);
        if (voiceRecorder == null || !voiceRecorder.isRecording()) {
            renderComposer();
            return;
        }
        try {
            MediaPreparationHelper.PreparedMedia prepared = voiceRecorder.stopAndFinalize();
            renderComposer();
            vm.enqueuePreparedMedia(prepared, null);
        } catch (MediaPreparationHelper.PrepareException e) {
            renderComposer();
            if ("VALIDATION".equals(e.code)) {
                showSnackbar(R.string.media_voice_too_short);
            } else {
                showSnackbar(ChatErrors.message(e.code));
            }
        }
    }

    private void requestLocationShare() {
        if (readOnly) {
            return;
        }
        Context ctx = requireContext();
        boolean fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (fine || coarse) {
            fetchAndSendLocation();
        } else {
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    private void fetchAndSendLocation() {
        if (binding == null) {
            return;
        }
        showSnackbar(R.string.media_location_fetching);
        LocationFetchHelper helper = vm.getLocationFetchHelper() != null
                ? vm.getLocationFetchHelper()
                : Injection.provideLocationFetchHelper();
        helper.fetchCurrent(requireContext(), new LocationFetchHelper.Callback() {
            @Override
            public void onSuccess(double latitude, double longitude) {
                if (isAdded() && vm != null) {
                    vm.enqueueLocation(latitude, longitude);
                }
            }

            @Override
            public void onError(@NonNull String reason) {
                if (isAdded() && binding != null) {
                    showSnackbar("PERMISSION_DENIED".equals(reason)
                            ? R.string.media_location_permission_denied
                            : R.string.media_location_failed);
                }
            }
        });
    }

    // ======================== MỞ MEDIA, PHÁT VOICE, MỞ BẢN ĐỒ, DỊCH TIN NHẮN (D-03, D-13..D-15) ========================

    private void openMediaViewer(@NonNull MessageEntity message, int navActionId) {
        JsonObject meta = MessageAdapter.parseJsonObject(message.getAttachmentJson());
        AttachmentTransferEntity transfer = findTransfer(message, meta);
        String localPath = firstNonBlank(MessageAdapter.str(meta, "localPath"),
                transfer != null ? transfer.getLocalPath() : null);
        String mediaUrl = firstNonBlank(MessageAdapter.str(meta, "mediaUrl"),
                transfer != null ? transfer.getMediaUrl() : null);
        String attachmentId = resolveAttachmentId(MessageAdapter.str(meta, "attachmentId"), mediaUrl);
        String fileName = firstNonBlank(MessageAdapter.str(meta, "fileName"),
                transfer != null ? transfer.getFileName() : null);
        String mime = firstNonBlank(MessageAdapter.str(meta, "mime"),
                transfer != null ? transfer.getMime() : null);

        Bundle args = new Bundle();
        args.putString(ImageViewerFragment.ARG_LOCAL_PATH, localPath);
        args.putString(ImageViewerFragment.ARG_MEDIA_URL, mediaUrl);
        args.putString(ImageViewerFragment.ARG_ATTACHMENT_ID, attachmentId);
        args.putString(ImageViewerFragment.ARG_FILE_NAME, fileName);
        args.putString(ImageViewerFragment.ARG_MIME, mime);
        NavHostFragment.findNavController(this).navigate(navActionId, args);
    }

    private void toggleVoicePlayback(@NonNull MessageEntity message) {
        if (voiceBubblePlayer == null || message.getClientId() == null) {
            return;
        }
        if (voiceRecorder != null && voiceRecorder.isRecording()) {
            cancelVoiceRecording();
        }
        JsonObject meta = MessageAdapter.parseJsonObject(message.getAttachmentJson());
        AttachmentTransferEntity transfer = findTransfer(message, meta);
        String localPath = firstNonBlank(MessageAdapter.str(meta, "localPath"),
                transfer != null ? transfer.getLocalPath() : null);
        String mediaUrl = firstNonBlank(MessageAdapter.str(meta, "mediaUrl"),
                transfer != null ? transfer.getMediaUrl() : null);
        String attachmentId = resolveAttachmentId(MessageAdapter.str(meta, "attachmentId"), mediaUrl);
        String fileName = firstNonBlank(MessageAdapter.str(meta, "fileName"), "voice.m4a");
        String mime = firstNonBlank(MessageAdapter.str(meta, "mime"), "audio/mp4");

        voiceBubblePlayer.togglePlay(
                message.getClientId(), localPath, attachmentId, mediaUrl, fileName, mime,
                code -> {
                    if (isAdded() && binding != null) {
                        showSnackbar(ChatErrors.message(code));
                    }
                });
    }

    private void openDocumentAttachment(@NonNull MessageEntity message) {
        JsonObject meta = MessageAdapter.parseJsonObject(message.getAttachmentJson());
        AttachmentTransferEntity transfer = findTransfer(message, meta);
        String localPath = firstNonBlank(MessageAdapter.str(meta, "localPath"),
                transfer != null ? transfer.getLocalPath() : null);
        String mime = firstNonBlank(MessageAdapter.str(meta, "mime"),
                transfer != null ? transfer.getMime() : null);

        File localFile = localPath != null ? new File(localPath) : null;
        if (localFile != null && localFile.isFile()) {
            launchExternalFileIntent(localFile, mime);
            return;
        }

        String mediaUrl = firstNonBlank(MessageAdapter.str(meta, "mediaUrl"),
                transfer != null ? transfer.getMediaUrl() : null);
        String attachmentId = resolveAttachmentId(MessageAdapter.str(meta, "attachmentId"), mediaUrl);
        String fileName = firstNonBlank(MessageAdapter.str(meta, "fileName"), "document");
        if (attachmentId == null || mediaUrl == null) {
            showSnackbar(R.string.media_load_failed);
            return;
        }

        showSnackbar(R.string.media_downloading);
        Injection.provideMediaDownloadManager(requireContext()).downloadToCache(
                attachmentId, mediaUrl, fileName, mime,
                new MediaDownloadManager.Callback() {
                    @Override
                    public void onSuccess(@NonNull File file) {
                        if (isAdded() && binding != null) {
                            launchExternalFileIntent(file, mime);
                        }
                    }

                    @Override
                    public void onError(@NonNull String code) {
                        if (isAdded() && binding != null) {
                            showSnackbar(ChatErrors.message(code));
                        }
                    }
                });
    }

    /** Chia sẻ tệp cục bộ hoặc cache sang ứng dụng ngoài qua FileProvider (tuyệt đối không gắn JWT, T-06-25). */
    private void launchExternalFileIntent(@NonNull File file, @Nullable String mime) {
        try {
            Intent intent = MediaDownloadManager.buildExternalViewIntent(requireContext(), file, mime);
            startActivity(intent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            showSnackbar(R.string.media_no_external_app);
        }
    }

    /** Mở tọa độ bằng ứng dụng bản đồ ngoài qua Intent geo: (D-02, T-06-25). */
    private void openLocationInMaps(@NonNull MessageEntity message) {
        JsonObject meta = MessageAdapter.parseJsonObject(message.getAttachmentJson());
        if (meta == null) {
            showSnackbar(R.string.media_load_failed);
            return;
        }
        JsonElement latEl = meta.get("latitude");
        JsonElement lngEl = meta.get("longitude");
        if (latEl == null || lngEl == null || !latEl.isJsonPrimitive() || !lngEl.isJsonPrimitive()) {
            showSnackbar(R.string.media_load_failed);
            return;
        }
        try {
            double lat = latEl.getAsDouble();
            double lng = lngEl.getAsDouble();
            Intent geoIntent = LocationFetchHelper.buildExternalGeoIntent(lat, lng, null);
            startActivity(geoIntent);
        } catch (ActivityNotFoundException e) {
            showSnackbar(R.string.media_no_external_app);
        } catch (RuntimeException malformed) {
            showSnackbar(R.string.media_load_failed);
        }
    }

    /** Dùng chung một menu cho dịch, thả cảm xúc và thao tác tin nhắn. */
    private void showMessageActionSheet(@NonNull MessageEntity message) {
        Map<Long, List<MessageReactionEntity>> reactions = vm.observeReactionsMap().getValue();
        String userId = vm.meId();
        String currentEmoji = null;
        if (reactions != null && message.getServerId() != null) {
            for (MessageReactionEntity reaction : reactions.getOrDefault(message.getServerId(), List.of())) {
                if (reaction.getUserId().equals(userId)) {
                    currentEmoji = reaction.getEmoji();
                    break;
                }
            }
        }

        String policy = ChatRoomViewModel.policyBanner(vm.openError.getValue(), vm.getMessages().getValue(), userId);
        if (chatSheet != null) chatSheet.dismiss();
        chatSheet = MessageActionBottomSheet.show(requireContext(), message, userId, currentEmoji,
                readOnly, "BLOCKED".equals(policy), vm.hasActiveTranslation(message.getClientId()),
                new MessageActionBottomSheet.Listener() {
                    @Override public void onEmojiSelected(String emoji) {
                        vm.toggleReaction(message, emoji);
                    }

                    @Override public void onTranslate(String language) {
                        vm.requestTranslate(message, language, false);
                    }

                    @Override public void onHideTranslation() {
                        if (message.getClientId() != null) vm.hideTranslation(message.getClientId());
                    }

                    @Override public void onRecall() {
                        confirmMessageAction(R.string.chat_recall_confirm_title, R.string.chat_recall_confirm_message,
                                R.string.chat_action_recall, () -> vm.recallMessage(message));
                    }

                    @Override public void onDeleteForMe() {
                        confirmMessageAction(R.string.chat_delete_for_me_confirm_title,
                                R.string.chat_delete_for_me_confirm_message, R.string.chat_action_delete_for_me,
                                () -> vm.deleteMessageForMe(message));
                    }
                });
    }

    private void confirmMessageAction(@StringRes int title, @StringRes int message,
            @StringRes int action, Runnable confirm) {
        if (messageConfirmation != null) messageConfirmation.dismiss();
        messageConfirmation = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(action, (dialog, button) -> confirm.run())
                .show();
    }

    /** Xác nhận trước khi tải mô hình ML Kit (~30MB) qua mạng di động đo lưu lượng (D-15). */
    private void showCellularDownloadDialog(@NonNull ChatRoomViewModel.CellularConfirmRequest req) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.chat_translate_cellular_title)
                .setMessage(R.string.chat_translate_cellular_message)
                .setPositiveButton(R.string.chat_translate_cellular_confirm,
                        (d, w) -> vm.requestTranslate(req.message, req.targetLang, true))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ======================== DANH SÁCH TIN NHẮN, HEADER, TÌM KIẾM & VÒNG ĐỜI ========================

    private void submit(List<MessageEntity> list) {
        vm.rehydrateSavedTranslations(list);
        boolean atBottom = adapter.getItemCount() == 0 || lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 2;
        String me = vm.meId();
        boolean newMine = list != null && !list.isEmpty() && me != null
                && me.equals(list.get(list.size() - 1).getSenderId())
                && adapter.getItemCount() < list.size();
        adapter.submitList(list, () -> {
            if (binding == null) return;
            // Ưu tiên nhảy tới kết quả tìm kiếm; nếu không, chỉ tự cuộn xuống khi đang ở đáy hoặc mình vừa gửi.
            if (!scrollToPending() && (atBottom || newMine) && adapter.getItemCount() > 0) {
                binding.messages.scrollToPosition(adapter.getItemCount() - 1);
            }
            renderComposer();
            binding.messages.post(this::markVisibleRead);
        });
    }

    /** Cuộn tới tin đang chờ nếu nó đã có trong danh sách; chưa có thì ViewModel đang nới cửa sổ. */
    private boolean scrollToPending() {
        Long target = vm.pendingScroll.getValue();
        if (binding == null || target == null) return false;
        int i = adapter.indexOf(target);
        if (i < 0) return false;
        lm.scrollToPositionWithOffset(i, binding.messages.getHeight() / 3);
        adapter.setHighlightedLocalId(target);
        vm.consumeScroll();
        return true;
    }

    /**
     * Báo đã đọc tới serverId lớn nhất trong các dòng đang thật sự hiện trên màn hình.
     * Đang cuộn lên xem tin cũ thì các tin mới bên dưới vẫn là chưa đọc.
     */
    private void markVisibleRead() {
        if (binding == null || adapter.getItemCount() == 0) return;
        int first = lm.findFirstVisibleItemPosition(), last = lm.findLastVisibleItemPosition();
        if (first < 0 || last < 0) return;
        long max = 0;
        for (int i = first; i <= last && i < adapter.getItemCount(); i++) {
            Long id = adapter.getCurrentList().get(i).getServerId();
            if (id != null && id > max) max = id;
        }
        vm.markVisibleRead(max, isResumed(), readOnly);
    }

    private void renderHeader() {
        if (binding == null) return;
        ConversationEntity c = vm.getConversation().getValue();
        Map<String, UserEntity> users = vm.getUsers().getValue();
        List<ConversationMemberEntity> members = vm.getMembers().getValue();
        String me = vm.meId();
        boolean group = c != null && "GROUP".equals(c.getType());
        String title = c != null && c.getTitle() != null && !c.getTitle().isBlank() ? c.getTitle() : null;
        int active = 0;
        if (members != null) {
            for (ConversationMemberEntity m : members) {
                if (!m.getLeft()) active++;
                // Chat 1-1 không có tiêu đề: lấy tên người còn lại.
                if (title == null && !group && !m.getUserId().equals(me)
                        && users != null && users.get(m.getUserId()) != null) {
                    title = users.get(m.getUserId()).getDisplayName();
                }
            }
        }
        binding.toolbar.setTitle(title == null ? "" : title);
        Set<String> typing = vm.getTyping().getValue();
        boolean othersTyping = false;
        if (typing != null) {
            for (String id : typing) {
                if (!id.equals(me)) othersTyping = true;
            }
        }
        binding.toolbar.setSubtitle(othersTyping ? getString(R.string.chat_typing)
                : group ? getResources().getQuantityString(R.plurals.group_member_count, active, active) : null);
        binding.toolbar.getMenu().findItem(R.id.action_group_info).setVisible(group);
        boolean canVoiceCall = !group && c != null && !readOnly && vm.openError.getValue() == null;
        binding.toolbar.getMenu().findItem(R.id.action_voice_call).setVisible(canVoiceCall);
    }

    /** Mở màn hình cuộc gọi thoại 1-1 qua global action gốc; CallFragment tự lo quyền micro và CallService. */
    private void openVoiceCall() {
        String convId = vm.getConversationId().getValue();
        if (convId == null || convId.trim().isEmpty()) {
            return;
        }
        String peerId = ChatRoomViewModel.peerOf(vm.deepLinkPeer(), vm.getMembers().getValue(), vm.meId());
        Map<String, UserEntity> users = vm.getUsers().getValue();
        UserEntity peer = (peerId != null && users != null) ? users.get(peerId) : null;
        Bundle args = new Bundle();
        args.putString(CallFragment.ARG_CONVERSATION_ID, convId);
        args.putString(CallFragment.ARG_PEER_USER_ID, peerId);
        if (peer != null) {
            args.putString(CallFragment.ARG_PEER_NAME, peer.getDisplayName());
            args.putString(CallFragment.ARG_PEER_AVATAR_URL, peer.getAvatarUrl());
            args.putString(CallFragment.ARG_PEER_PHONE, peer.getPhone());
        }
        NavHostFragment.findNavController(this).navigate(R.id.action_global_call, args);
    }

    private void renderComposer() {
        if (binding == null) return;
        String policy = ChatRoomViewModel.policyBanner(vm.openError.getValue(), vm.getMessages().getValue(), vm.meId());
        binding.policyBanner.setVisibility(policy == null ? View.GONE : View.VISIBLE);
        boolean contactRequired = "CONTACT_REQUIRED".equals(policy);
        boolean sent = Boolean.TRUE.equals(vm.requestSent.getValue());
        if (policy != null) {
            binding.policyText.setText(!contactRequired ? R.string.chat_blocked_banner
                    : sent ? R.string.chat_friend_request_sent : R.string.chat_contact_required);
        }
        binding.policyAction.setVisibility(contactRequired && !sent ? View.VISIBLE : View.GONE);
        // Chưa mở được hội thoại (bị chặn/cần kết bạn) hoặc đã rời nhóm thì không cho soạn tin / ghi âm.
        boolean noConversation = vm.getConversationId().getValue() == null && vm.openError.getValue() != null;
        boolean disabled = readOnly || noConversation;
        binding.readOnlyBar.setVisibility(readOnly ? View.VISIBLE : View.GONE);
        if (disabled) {
            closeStickerTray();
            if (voiceRecorder != null && voiceRecorder.isRecording()) {
                mainHandler.removeCallbacks(voiceRecordTicker);
                voiceRecorder.cancelAndDelete();
            }
            binding.recordingBar.setVisibility(View.GONE);
            binding.composer.setVisibility(View.GONE);
        } else {
            boolean recording = voiceRecorder != null && voiceRecorder.isRecording();
            if (recording) closeStickerTray();
            binding.recordingBar.setVisibility(recording ? View.VISIBLE : View.GONE);
            binding.composer.setVisibility(recording ? View.GONE : View.VISIBLE);
        }
    }

    private void renderBanner(@Nullable WsConnectionState s) {
        if (binding == null) return;
        binding.ws.wsBanner.setVisibility(s != null && s != WsConnectionState.CONNECTED ? View.VISIBLE : View.GONE);
        binding.ws.wsBannerText.setText(s == WsConnectionState.DISCONNECTED
                ? R.string.chat_ws_disconnected : R.string.chat_ws_reconnecting);
    }

    private void bindSearch() {
        if (!vm.getQuery().isEmpty()) binding.roomSearch.setVisibility(View.VISIBLE);
        binding.roomQuery.setText(vm.getQuery());
        queryWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { vm.setSearchQuery(s.toString()); }
            @Override public void afterTextChanged(Editable e) { }
        };
        binding.roomQuery.addTextChangedListener(queryWatcher);
        binding.roomPrev.setOnClickListener(v -> vm.stepMatch(1));
        binding.roomNext.setOnClickListener(v -> vm.stepMatch(-1));
        binding.roomClose.setOnClickListener(v -> {
            binding.roomQuery.setText("");
            binding.roomSearch.setVisibility(View.GONE);
            adapter.setHighlightedLocalId(-1);
        });
        vm.matches.observe(getViewLifecycleOwner(), ids -> {
            boolean any = ids != null && !ids.isEmpty();
            binding.roomCounter.setText(vm.getQuery().isBlank() ? ""
                    : getString(R.string.chat_search_counter, any ? vm.getMatchIndex() + 1 : 0, any ? ids.size() : 0));
            binding.roomPrev.setEnabled(any);
            binding.roomNext.setEnabled(any);
        });
    }

    private void openSearch() {
        closeStickerTray();
        binding.roomSearch.setVisibility(View.VISIBLE);
        binding.roomQuery.requestFocus();
    }

    /** D-05: chạm tin lỗi thì chọn Gửi lại (giữ clientId, server không tạo bản trùng) hoặc Xóa tin lỗi. */
    private void showFailedDialog(MessageEntity m) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.chat_failed_title)
                .setMessage(ChatErrors.message(m.getErrorCode()))
                .setPositiveButton(R.string.chat_resend, (d, w) -> vm.retryFailed(m.getClientId()))
                .setNegativeButton(R.string.chat_delete_failed, (d, w) -> vm.deleteFailed(m.getClientId()))
                .setNeutralButton(android.R.string.cancel, null)
                .show();
    }

    private void showSnackbar(@StringRes int resId) {
        if (binding != null) {
            Snackbar.make(binding.getRoot(), resId, Snackbar.LENGTH_LONG).show();
        }
    }

    @Nullable
    private AttachmentTransferEntity findTransfer(@NonNull MessageEntity message, @Nullable JsonObject meta) {
        Map<String, AttachmentTransferEntity> map = vm.getTransfers().getValue();
        if (map == null) return null;
        if (message.getClientId() != null && map.containsKey(message.getClientId())) {
            return map.get(message.getClientId());
        }
        String mediaUrl = MessageAdapter.str(meta, "mediaUrl");
        String id = resolveAttachmentId(MessageAdapter.str(meta, "attachmentId"), mediaUrl);
        return id != null ? map.get("dl:" + id) : null;
    }

    @Nullable
    private static String resolveAttachmentId(@Nullable String explicitId, @Nullable String mediaUrl) {
        if (explicitId != null && !explicitId.trim().isEmpty()) {
            return explicitId.trim();
        }
        if (mediaUrl != null) {
            int slash = mediaUrl.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < mediaUrl.length()) {
                return mediaUrl.substring(slash + 1).trim();
            }
        }
        return null;
    }

    @Nullable
    private static String firstNonBlank(@Nullable String primary, @Nullable String fallback) {
        if (primary != null && !primary.trim().isEmpty()) {
            return primary.trim();
        }
        return fallback != null && !fallback.trim().isEmpty() ? fallback.trim() : null;
    }

    private static String formatMmSs(long elapsedMs) {
        long sec = Math.max(0L, elapsedMs / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d", sec / 60L, sec % 60L);
    }

    private static void deleteQuietly(@Nullable File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        String activeConvId = vm.getConversationId().getValue();
        if (activeConvId != null) {
            Injection.provideActiveRoomTracker().setActive(activeConvId);
            Injection.provideNotificationHelper(requireContext()).cancelConversation(activeConvId);
        }
        if (stickerCatalogOpened) vm.refreshStickers(false);
        if (binding != null) binding.messages.post(this::markVisibleRead);
    }

    @Override
    public void onPause() {
        String activeConvId = vm.getConversationId().getValue();
        if (activeConvId != null) {
            Injection.provideActiveRoomTracker().clearIfMatches(activeConvId);
        }
        vm.stopTyping(); // Rời màn hình thì người kia không còn thấy "Đang nhập…".
        super.onPause();
    }

    @Override
    public void onStop() {
        // Giải phóng MediaPlayer phát voice và hủy ghi âm nếu người dùng thu nhỏ app (T-06-25).
        if (voiceBubblePlayer != null) {
            voiceBubblePlayer.release();
        }
        if (voiceRecorder != null && voiceRecorder.isRecording()) {
            cancelVoiceRecording();
        }
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        dismissAttachmentSheet();
        if (chatSheet != null) chatSheet.dismiss();
        if (messageConfirmation != null) messageConfirmation.dismiss();
        chatSheet = null;
        messageConfirmation = null;
        stickerTray = null;
        mainHandler.removeCallbacks(voiceRecordTicker);
        if (voiceRecorder != null) {
            voiceRecorder.cancelAndDelete();
            voiceRecorder = null;
        }
        if (voiceBubblePlayer != null) {
            voiceBubblePlayer.release();
            voiceBubblePlayer = null;
        }
        binding.input.removeTextChangedListener(inputWatcher);
        binding.roomQuery.removeTextChangedListener(queryWatcher);
        binding.messages.setAdapter(null);
        inputWatcher = null;
        queryWatcher = null;
        adapter = null;
        lm = null;
        super.onDestroyView();
        binding = null;
    }
}
