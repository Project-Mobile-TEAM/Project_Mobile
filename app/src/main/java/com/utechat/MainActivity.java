package com.utechat;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;
import androidx.work.WorkManager;
import com.google.android.material.snackbar.Snackbar;
import com.utechat.data.MainThreadExecutor;
import com.utechat.data.call.CallSessionCoordinator;
import com.utechat.data.local.AppDatabase;
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.ConversationMemberEntity;
import com.utechat.data.notifications.NotificationHelper;
import com.utechat.data.session.SessionEvents;
import com.utechat.data.session.SessionManager;
import com.utechat.data.worker.SyncWorker;
import com.utechat.databinding.ActivityMainBinding;
import com.utechat.di.Injection;
import com.utechat.ui.call.CallFragment;
import com.utechat.ui.chat.ChatNavigation;
import java.lang.ref.WeakReference;

/**
 * Activity duy nhất (launchMode="singleTop"); điều hướng Fragment, xử lý vùng an toàn SDK 36,
 * vòng đời thông báo 2 kênh (D-05..D-08), lập lịch SyncWorker 15 phút và điều hướng deep link có kiểm phiên (T-06-24).
 */
public class MainActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "utechat_prefs";
    private static final String KEY_NOTIF_PROMPTED_ONCE = "notif_prompted_once";

    @Nullable private static volatile WeakReference<MainActivity> sResumedActivity;

    private ActivityMainBinding binding; // ViewBinding thay findViewById và kiểm tra ID khi build.
    private androidx.appcompat.app.AlertDialog deletionDialog; // Thông báo "tài khoản đang chờ xoá", chỉ hiện một cái.
    @Nullable private NavController navController;

    // Yêu cầu điều hướng từ thông báo đang chờ; gắn chặt với sessionKey nguồn để chống rò rỉ xuyên tài khoản (T-06-24).
    @Nullable private PendingNotificationRequest pendingNotification;

    private final ActivityResultLauncher<String> postNotificationsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                // Chỉ xin quyền 1 lần duy nhất; nếu từ chối thì tôn trọng quyết định người dùng (D-07).
            });

    /**
     * Điều hướng thẳng sang CallFragment nếu MainActivity đang ở foreground (resumed) và đúng phiên đăng nhập.
     * Gọi từ Injection.provideCallSessionCoordinator khi có cuộc gọi đến qua WebSocket.
     */
    public static void navigateToIncomingCallIfResumed(@NonNull String callId, @NonNull String sessionKey) {
        MainThreadExecutor.INSTANCE.execute(() -> {
            WeakReference<MainActivity> ref = sResumedActivity;
            MainActivity activity = ref != null ? ref.get() : null;
            if (activity == null || activity.isFinishing() || activity.isDestroyed() || activity.navController == null) {
                return;
            }
            if (!sessionKey.equals(SessionManager.get().sessionKey())) {
                return;
            }
            NavDestination current = activity.navController.getCurrentDestination();
            if (!isLoggedInDestination(current) || (current != null && current.getId() == R.id.callFragment)) {
                return;
            }
            Bundle args = new Bundle();
            args.putString(CallFragment.ARG_CALL_ID, callId);
            activity.navController.navigate(R.id.action_global_call, args);
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Áp dụng chế độ sáng/tối người dùng đã chọn (DayNight không tự nhớ giữa các lần mở app).
        com.utechat.ui.profile.AppSettings.applySavedTheme(this);
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Đảm bảo đúng 2 kênh thông báo V1 và đăng ký provider cho CallService/CallFragment.
        Injection.provideNotificationHelper(this).ensureChannels();
        CallSessionCoordinator.setProvider(Injection::provideCallSessionCoordinator);

        // Tránh nội dung nằm dưới status bar/gesture bar khi hệ thống bật edge-to-edge.
        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (view, insets) -> {
            // Cộng cả bàn phím (ime) để nút ở đáy màn auth không bị bàn phím che khi edge-to-edge.
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, 0, bars.right, bars.bottom);
            // Phần inset trên cùng do status_bg đảm nhiệm (xanh) để thanh tiêu đề liền mạch với status bar.
            binding.statusBg.getLayoutParams().height = bars.top;
            binding.statusBg.requestLayout();
            return insets;
        });

        NavHostFragment host = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
        NavController controller = host.getNavController();
        this.navController = controller;
        NavigationUI.setupWithNavController(binding.bottomNav, controller);

        captureNotificationIntent(getIntent());

        // F-07: server từ chối token (hết hạn/thu hồi) thì dọn phiên cục bộ và về màn Đăng nhập kèm thông báo.
        SessionEvents.expired().observe(this, expired -> {
            if (!Boolean.TRUE.equals(expired)) return;
            pendingNotification = null;
            var authRepository = Injection.provideAuthRepository(this);
            // Kiểm sự kiện và logout trong cùng khóa để phiên mới không chen vào giữa hai bước.
            synchronized (SessionManager.get()) {
                boolean currentExpiration = SessionEvents.isCurrentExpiration();
                SessionEvents.consume(); // Xoay màn hình không xử lý lại sự kiện cũ.
                if (!currentExpiration) return;
                authRepository.logout();
            }
            controller.navigate(R.id.nav_auth, null, new NavOptions.Builder().setPopUpTo(R.id.nav_graph, true).build());
            Snackbar.make(binding.getRoot(), R.string.auth_session_expired, Snackbar.LENGTH_LONG).show();
        });

        // F-08: đăng nhập lại trong 15 ngày chờ xoá thì hỏi giữ tài khoản hay đăng xuất (giống Facebook).
        Injection.provideAuthRepository(this).getProfile().observe(this, p -> {
            if (p != null && p.deletionScheduledAt != null) showPendingDeletion(controller, p.deletionScheduledAt);
            else if (deletionDialog != null) deletionDialog.dismiss();
        });

        controller.addOnDestinationChangedListener((nav, destination, arguments) -> {
            int id = destination.getId();
            // Auth/Splash/detail/call không hiển thị tab để người dùng không đi tắt qua xác thực.
            boolean topLevel = id == R.id.chatListFragment || id == R.id.contactsListFragment || id == R.id.profileFragment;
            binding.bottomNav.setVisibility(topLevel ? View.VISIBLE : View.GONE);

            boolean loggedInDest = isLoggedInDestination(destination);
            boolean hasToken = SessionManager.get().hasToken();

            // Vào vùng đã đăng nhập thì mở WebSocket, đồng bộ bù và lập lịch SyncWorker 15 phút theo phiên hiện hành.
            if (topLevel) {
                var chat = Injection.provideChatRepository(this);
                chat.connectRealtime();
                chat.triggerSync();
                if (hasToken) {
                    maybePromptNotificationPermissionOnce();
                }
            }
            if (loggedInDest && hasToken) {
                String sessionKey = SessionManager.get().sessionKey();
                if (sessionKey != null) {
                    try {
                        SyncWorker.enqueuePeriodic(WorkManager.getInstance(this), sessionKey);
                    } catch (IllegalStateException ignored) {
                    }
                }
            }

            // Tiêu thụ deep link thông báo ở BẤT KỲ màn nào sau đăng nhập (ChatRoom, GroupInfo, Devices...).
            if (loggedInDest) {
                tryConsumePendingNotification(controller);
            }

            // Splash tự có gradient phủ tới mép trên; các màn khác có status bar xanh nên icon luôn trắng.
            boolean splash = id == R.id.splashFragment;
            binding.statusBg.setVisibility(splash ? View.GONE : View.VISIBLE);
            binding.main.setBackgroundResource(splash ? R.drawable.bg_splash : R.color.ute_background);
            WindowCompat.getInsetsController(getWindow(), binding.main).setAppearanceLightStatusBars(false);
        });
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        captureNotificationIntent(intent);
        if (navController != null) {
            tryConsumePendingNotification(navController);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        sResumedActivity = new WeakReference<>(this);
        if (navController != null) {
            tryConsumePendingNotification(navController);
        }
    }

    @Override
    protected void onPause() {
        WeakReference<MainActivity> ref = sResumedActivity;
        if (ref != null && ref.get() == this) {
            sResumedActivity = null;
        }
        super.onPause();
    }

    /** Chụp thông tin deep link từ Intent thông báo và xóa ngay khỏi Intent để xoay màn hình không chạy lại. */
    private void captureNotificationIntent(@Nullable Intent intent) {
        if (intent == null) {
            return;
        }
        String convId = intent.getStringExtra(NotificationHelper.EXTRA_OPEN_CONVERSATION_ID);
        String callId = intent.getStringExtra(NotificationHelper.EXTRA_OPEN_CALL_ID);
        String sessionKey = intent.getStringExtra(NotificationHelper.EXTRA_NOTIFICATION_SESSION_KEY);
        if (convId == null && callId == null) {
            return;
        }
        intent.removeExtra(NotificationHelper.EXTRA_OPEN_CONVERSATION_ID);
        intent.removeExtra(NotificationHelper.EXTRA_OPEN_CALL_ID);
        intent.removeExtra(NotificationHelper.EXTRA_NOTIFICATION_SESSION_KEY);
        pendingNotification = new PendingNotificationRequest(convId, callId, sessionKey);
    }

    /**
     * Điều hướng an toàn khi chạm thông báo (T-06-24):
     * - Chỉ chạy khi đã xác thực và đứng ở destination sau đăng nhập (không vượt qua Splash/nav_auth).
     * - Khớp đúng sessionKey nguồn; loại bỏ cuộc gọi đã kết thúc hoặc hội thoại không còn là thành viên.
     */
    private void tryConsumePendingNotification(@NonNull NavController controller) {
        PendingNotificationRequest req = pendingNotification;
        if (req == null) {
            return;
        }
        if (!SessionManager.get().hasToken() || !isLoggedInDestination(controller.getCurrentDestination())) {
            return;
        }
        String currentSessionKey = SessionManager.get().sessionKey();
        pendingNotification = null;
        if (currentSessionKey == null || req.sessionKey == null || !currentSessionKey.equals(req.sessionKey)) {
            return;
        }

        // 1) Thông báo cuộc gọi đến / đang diễn ra: chỉ mở khi CallSessionCoordinator vẫn giữ đúng callId đang hoạt động.
        if (req.callId != null && !req.callId.trim().isEmpty()) {
            CallSessionCoordinator coord = Injection.provideCallSessionCoordinator(this);
            CallSessionCoordinator.CallUiState st = coord.getCurrentState();
            boolean callStillActive = req.callId.equals(st.callId)
                    && (st.state == CallSessionCoordinator.State.INCOMING_RINGING
                    || st.state == CallSessionCoordinator.State.OUTGOING_RINGING
                    || st.state == CallSessionCoordinator.State.CONNECTING
                    || st.state == CallSessionCoordinator.State.ACTIVE);
            if (callStillActive) {
                NavDestination cur = controller.getCurrentDestination();
                if (cur == null || cur.getId() != R.id.callFragment) {
                    Bundle args = new Bundle();
                    args.putString(CallFragment.ARG_CALL_ID, req.callId);
                    controller.navigate(R.id.action_global_call, args);
                }
            }
            return;
        }

        // 2) Thông báo tin nhắn / cuộc gọi nhỡ: xác minh quyền thành viên trong Room trên DISK trước khi mở phòng chat.
        if (req.conversationId != null && !req.conversationId.trim().isEmpty()) {
            final String targetConvId = req.conversationId.trim();
            final String capturedSessionKey = currentSessionKey;
            final String meId = SessionManager.get().getUserId();
            if (meId == null) {
                return;
            }
            AppDatabase db = AppDatabase.getInstance(this);
            Injection.getDiskExecutor().execute(() -> {
                final boolean[] allowed = {false};
                boolean stillCurrent = SessionManager.get().runIfCurrent(capturedSessionKey, () -> {
                    ConversationEntity conv = db.conversationDao().getConversationSync(targetConvId);
                    if (conv == null) {
                        return;
                    }
                    ConversationMemberEntity member = db.conversationDao().getMemberSync(targetConvId, meId);
                    if (member != null && !member.getLeft()) {
                        allowed[0] = true;
                    }
                });
                if (!stillCurrent || !allowed[0]) {
                    return;
                }
                MainThreadExecutor.INSTANCE.execute(() -> {
                    if (isFinishing() || isDestroyed() || navController == null) {
                        return;
                    }
                    if (!capturedSessionKey.equals(SessionManager.get().sessionKey())) {
                        return;
                    }
                    if (!isLoggedInDestination(navController.getCurrentDestination())) {
                        return;
                    }
                    ChatNavigation.openConversationFromNotification(navController, targetConvId);
                });
            });
        }
    }

    /** Trả true cho mọi destination sau đăng nhập (không phải Splash và không nằm trong nav_auth). */
    static boolean isLoggedInDestination(@Nullable NavDestination dest) {
        if (dest == null || dest.getId() == R.id.splashFragment) {
            return false;
        }
        NavDestination cursor = dest;
        while (cursor != null) {
            if (cursor.getId() == R.id.nav_auth) {
                return false;
            }
            cursor = cursor.getParent();
        }
        return true;
    }

    /** Xin quyền POST_NOTIFICATIONS đúng 1 lần duy nhất sau khi đăng nhập trên Android 13+ (D-07). */
    private void maybePromptNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_NOTIF_PROMPTED_ONCE, false)) {
            return;
        }
        prefs.edit().putBoolean(KEY_NOTIF_PROMPTED_ONCE, true).apply();
        postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    /** Không cho đóng bằng nút Back/chạm ngoài: phải chọn giữ tài khoản hoặc đăng xuất. */
    private void showPendingDeletion(NavController controller, String dueIso) {
        if (deletionDialog != null && deletionDialog.isShowing()) return;
        String date = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")
                .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(dueIso));
        deletionDialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.deletion_pending_title)
                .setMessage(getString(R.string.deletion_pending_message, date))
                .setCancelable(false)
                .setPositiveButton(R.string.deletion_pending_keep, null)
                .setNegativeButton(R.string.profile_logout, (d, w) -> {
                    Injection.provideAuthRepository(this).logout();
                    controller.navigate(R.id.nav_auth, null, new NavOptions.Builder().setPopUpTo(R.id.nav_graph, true).build());
                }).show();
        // Tự xử lý nút Giữ để hộp thoại chỉ đóng khi server đã huỷ xoá thành công.
        deletionDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            v.setEnabled(false);
            Injection.provideAuthRepository(this).cancelDeletion().observe(this, result -> {
                v.setEnabled(true);
                if (result.isSuccess()) {
                    deletionDialog.dismiss();
                    Snackbar.make(binding.getRoot(), R.string.deletion_canceled, Snackbar.LENGTH_LONG).show();
                } else {
                    Snackbar.make(binding.getRoot(), com.utechat.ui.auth.AuthErrors.message(result.errorCode), Snackbar.LENGTH_LONG).show();
                }
            });
        });
    }

    private static final class PendingNotificationRequest {
        @Nullable final String conversationId;
        @Nullable final String callId;
        @Nullable final String sessionKey;

        PendingNotificationRequest(
                @Nullable String conversationId,
                @Nullable String callId,
                @Nullable String sessionKey) {
            this.conversationId = conversationId;
            this.callId = callId;
            this.sessionKey = sessionKey;
        }
    }
}
