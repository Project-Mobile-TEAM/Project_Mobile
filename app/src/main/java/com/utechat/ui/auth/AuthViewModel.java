package com.utechat.ui.auth;

import static androidx.lifecycle.SavedStateHandleSupport.createSavedStateHandle;
import static androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY;

import android.app.Application;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.viewmodel.ViewModelInitializer;
import androidx.navigation.fragment.NavHostFragment;
import com.utechat.R;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.repository.AuthRepository;
import com.utechat.data.repository.AuthResult;
import com.utechat.data.repository.OtpChallenge;
import com.utechat.di.Injection;

/** Dùng chung cho các màn trong nav_auth: giữ số điện thoại, phiên OTP và token đăng ký qua xoay màn hình. */
public class AuthViewModel extends ViewModel {
    /** Thời gian chờ giữa hai lần gửi OTP (F-01: 60 giây). */
    public static final long RESEND_MILLIS = 60_000L;
    // Khoá lưu trong SavedStateHandle để sống sót khi tiến trình bị hệ điều hành thu hồi.
    private static final String KEY_PHONE = "phone";
    private static final String KEY_CHALLENGE_ID = "challengeId";
    private static final String KEY_OTP_SENT_AT = "otpSentAt";

    /** Dựng ViewModel theo mẫu slide Session 4: lấy Application và SavedStateHandle từ CreationExtras. */
    public static final ViewModelProvider.Factory FACTORY = ViewModelProvider.Factory.from(
            new ViewModelInitializer<>(AuthViewModel.class, extras -> {
                Application app = extras.get(APPLICATION_KEY);
                return new AuthViewModel(createSavedStateHandle(extras), Injection.provideAuthRepository(app));
            }));

    private final SavedStateHandle state;
    private final AuthRepository repository;
    // Cố ý KHÔNG lưu vào SavedStateHandle: demoCode chỉ để hiển thị, registrationToken là thông tin xác thực dùng một lần.
    private String demoCode;
    private String registrationToken;

    public AuthViewModel(SavedStateHandle state, AuthRepository repository) {
        this.state = state;
        this.repository = repository;
    }

    /** Lấy ViewModel gắn với graph nav_auth để các Fragment trong luồng đăng ký dùng chung một bản. */
    public static AuthViewModel of(Fragment fragment) {
        return new ViewModelProvider(
                NavHostFragment.findNavController(fragment).getViewModelStoreOwner(R.id.nav_auth),
                FACTORY).get(AuthViewModel.class);
    }

    @Nullable public String getPhone() { return state.get(KEY_PHONE); }
    @Nullable public String getDemoCode() { return demoCode; }
    @Nullable public String getRegistrationToken() { return registrationToken; }

    /** Số mili giây còn phải chờ trước khi được gửi lại OTP; 0 nghĩa là gửi lại được. */
    public long resendRemainingMillis() {
        Long sentAt = state.get(KEY_OTP_SENT_AT);
        if (sentAt == null) return 0;
        return Math.max(0, RESEND_MILLIS - (SystemClock.elapsedRealtime() - sentAt));
    }

    public LiveData<UserEntity> login(String phoneE164, String password) {
        return repository.login(phoneE164, password);
    }

    /** Gửi OTP (cũng dùng cho "Gửi lại"); thành công thì nhớ phiên và bắt đầu đếm ngược. */
    public LiveData<AuthResult<OtpChallenge>> requestOtp(String phoneE164) {
        return Transformations.map(repository.requestRegisterOtp(phoneE164), result -> {
            if (result.isSuccess()) {
                state.set(KEY_PHONE, phoneE164);
                state.set(KEY_CHALLENGE_ID, result.data.challengeId);
                state.set(KEY_OTP_SENT_AT, SystemClock.elapsedRealtime());
                demoCode = result.data.demoCode;
            }
            return result;
        });
    }

    public LiveData<AuthResult<String>> verifyOtp(String code) {
        return Transformations.map(repository.verifyRegisterOtp(state.get(KEY_CHALLENGE_ID), code), result -> {
            if (result.isSuccess()) registrationToken = result.data;
            return result;
        });
    }

    public LiveData<AuthResult<UserEntity>> completeRegister(String displayName, String password) {
        return repository.completeRegister(registrationToken, displayName, password);
    }
}
