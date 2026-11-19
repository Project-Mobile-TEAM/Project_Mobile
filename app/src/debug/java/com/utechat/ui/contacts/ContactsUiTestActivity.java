package com.utechat.ui.contacts;

import android.os.Bundle;
import android.widget.FrameLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.fragment.NavHostFragment;
import com.utechat.R;

/** Host Debug chỉ mở graph danh bạ thật để test không phụ thuộc splash, OTP hoặc backend. */
public class ContactsUiTestActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Host có ID riêng: NavHost tự dùng ID container, không được trùng android.R.id.content của Activity.
        FrameLayout content=new FrameLayout(this);content.setId(R.id.contacts_ui_test_host);
        setContentView(content);
        // FragmentManager tự phục hồi graph/ViewModel; không thêm host lần hai khi recreate.
        if(state==null) {
            NavHostFragment host=NavHostFragment.create(R.navigation.nav_contacts);
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.contacts_ui_test_host,host,"contacts.test.host").setPrimaryNavigationFragment(host).commitNow();
        }
    }
}
