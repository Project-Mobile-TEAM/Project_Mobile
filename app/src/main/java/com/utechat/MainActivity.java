package com.utechat;

import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;
import com.google.android.material.snackbar.Snackbar;
import com.utechat.data.session.SessionEvents;
import com.utechat.databinding.ActivityMainBinding;
import com.utechat.di.Injection;

/** Activity duy nhất; điều hướng Fragment và xử lý vùng an toàn của Android SDK36. */
public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding; // ViewBinding thay findViewById và kiểm tra ID khi build.
    private androidx.appcompat.app.AlertDialog deletionDialog; // Thông báo "tài khoản đang chờ xoá", chỉ hiện một cái.

    @Override protected void onCreate(Bundle savedInstanceState) {
        // Áp dụng chế độ sáng/tối người dùng đã chọn (DayNight không tự nhớ giữa các lần mở app).
        com.utechat.ui.profile.AppSettings.applySavedTheme(this);
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
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
        NavigationUI.setupWithNavController(binding.bottomNav, controller);
        // F-07: server từ chối token (hết hạn/thu hồi) thì dọn phiên cục bộ và về màn Đăng nhập kèm thông báo.
        SessionEvents.expired().observe(this, expired -> {
            if (!Boolean.TRUE.equals(expired)) return;
            SessionEvents.consume(); // Xoay màn hình không xử lý lại sự kiện cũ.
            Injection.provideAuthRepository(this).logout();
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
            // Auth/Splash/detail không hiển thị tab để người dùng không đi tắt qua xác thực.
            boolean topLevel = id == R.id.chatListFragment || id == R.id.contactsListFragment || id == R.id.profileFragment;
            binding.bottomNav.setVisibility(topLevel ? View.VISIBLE : View.GONE);
            // Splash tự có gradient phủ tới mép trên; các màn khác có status bar xanh nên icon luôn trắng.
            boolean splash = id == R.id.splashFragment;
            binding.statusBg.setVisibility(splash ? View.GONE : View.VISIBLE);
            binding.main.setBackgroundResource(splash ? R.drawable.bg_splash : R.color.ute_background);
            WindowCompat.getInsetsController(getWindow(), binding.main).setAppearanceLightStatusBars(false);
        });
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
}
