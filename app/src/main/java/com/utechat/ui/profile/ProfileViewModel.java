package com.utechat.ui.profile;

import static androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY;

import android.app.Application;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.viewmodel.ViewModelInitializer;
import com.utechat.data.repository.AuthRepository;
import com.utechat.data.repository.AuthResult;
import com.utechat.data.repository.DeviceInfo;
import com.utechat.data.repository.UserProfile;
import com.utechat.di.Injection;
import java.util.List;

/** Dùng chung cho các màn Hồ sơ/cài đặt; trạng thái nằm trong repository (một nguồn sự thật) nên không giữ trường riêng. */
public class ProfileViewModel extends ViewModel {
    /** Dựng ViewModel theo mẫu slide Session 4: lấy Application từ CreationExtras. */
    public static final ViewModelProvider.Factory FACTORY = ViewModelProvider.Factory.from(
            new ViewModelInitializer<>(ProfileViewModel.class, extras -> {
                Application app = extras.get(APPLICATION_KEY);
                return new ProfileViewModel(Injection.provideAuthRepository(app));
            }));

    private final AuthRepository repository;

    public ProfileViewModel(AuthRepository repository) { this.repository = repository; }

    public LiveData<UserProfile> getProfile() { return repository.getProfile(); }

    public LiveData<AuthResult<UserProfile>> updateProfile(String displayName, boolean allowMessages, boolean discoverable) {
        return repository.updateProfile(displayName, allowMessages, discoverable);
    }

    public LiveData<AuthResult<Boolean>> changePassword(String oldPassword, String newPassword) {
        return repository.changePassword(oldPassword, newPassword);
    }

    public LiveData<AuthResult<List<DeviceInfo>>> getDevices() { return repository.getDevices(); }

    public LiveData<AuthResult<Boolean>> revokeDevice(String deviceId) { return repository.revokeDevice(deviceId); }

    public void logout() { repository.logout(); }
}
