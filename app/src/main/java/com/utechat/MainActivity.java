package com.utechat;

import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;
import com.utechat.databinding.ActivityMainBinding;

/** Activity duy nhất; điều hướng Fragment và xử lý vùng an toàn của Android SDK35. */
public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding; // ViewBinding thay findViewById và kiểm tra ID khi build.

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        // Tránh nội dung nằm dưới status bar/gesture bar khi hệ thống bật edge-to-edge.
        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        NavHostFragment host = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
        NavController controller = host.getNavController();
        NavigationUI.setupWithNavController(binding.bottomNav, controller);
        controller.addOnDestinationChangedListener((nav, destination, arguments) -> {
            int id = destination.getId();
            // Auth/Splash/detail không hiển thị tab để người dùng không đi tắt qua xác thực.
            boolean topLevel = id == R.id.chatListFragment || id == R.id.contactsListFragment || id == R.id.profileFragment;
            binding.bottomNav.setVisibility(topLevel ? View.VISIBLE : View.GONE);
        });
    }
}
