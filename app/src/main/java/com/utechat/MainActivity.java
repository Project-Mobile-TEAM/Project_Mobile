package com.utechat;

import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
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
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
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
        controller.addOnDestinationChangedListener((nav, destination, arguments) -> {
            int id = destination.getId();
            // Auth/Splash/detail không hiển thị tab để người dùng không đi tắt qua xác thực.
            boolean topLevel = id == R.id.chatListFragment || id == R.id.contactsListFragment || id == R.id.profileFragment;
            binding.bottomNav.setVisibility(topLevel ? View.VISIBLE : View.GONE);
        });
    }
}
