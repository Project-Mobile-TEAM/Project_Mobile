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
    private static final String KEY_FLOW = "flow"; // Luồng OTP đang chạy: FLOW_REGISTER, FLOW_RESET hoặc FLOW_LOGIN.
    private static final String FLOW_REGISTER = "register";
    private static final String FLOW_RESET = "reset";
    private static final String FLOW_LOGIN = "login";

    /** Dựng ViewModel theo mẫu slide Session 4: lấy Application và SavedStateHandle từ CreationExtras. */
    public static final ViewModelProvider.Factory FACTORY = ViewModelProvider.Factory.from(
            new ViewModelInitializer<>(AuthViewModel.class, extras -> {
                Application app = extras.get(APPLICATION_KEY);
                return new AuthViewModel(createSavedStateHandle(extras), Injection.provideAuthRepository(app));
            }));

    private final SavedStateHandle state;
    private final AuthRepository repository;
    // Cố ý KHÔNG lưu vào SavedStateHandle: demoCode chỉ để hiển thị, flowToken (registrationToken hoặc resetToken) là thông tin xác thực dùng một lần.
    private String demoCode;
    private String flowToken;
    private boolean awaitingSecondFactor; // Đã đúng mật khẩu, đang chờ mã 2FA (token tạm nằm ở repository).

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
    @Nullable public String getFlowToken() { return flowToken; }
    /** Đang ở luồng quên mật khẩu; quyết định API và màn kế tiếp sau OTP. */
    public boolean isResetFlow() { return FLOW_RESET.equals(state.get(KEY_FLOW)); }
    /** Đang ở luồng đăng nhập bằng OTP: xác minh xong là vào app luôn. */
    public boolean isLoginFlow() { return FLOW_LOGIN.equals(state.get(KEY_FLOW)); }

    /** Số mili giây còn phải chờ trước khi được gửi lại OTP; 0 nghĩa là gửi lại được. */
    public long resendRemainingMillis() {
        Long sentAt = state.get(KEY_OTP_SENT_AT);
        if (sentAt == null) return 0;
        return Math.max(0, RESEND_MILLIS - (SystemClock.elapsedRealtime() - sentAt));
    }

    public boolean isAwaitingSecondFactor() { return awaitingSecondFactor; }

    public LiveData<AuthResult<UserEntity>> login(String phoneE164, String password) {
        return Transformations.map(repository.login(phoneE164, password), result -> {
            awaitingSecondFactor = "NEED_2FA".equals(result.errorCode);
            return result;
        });
    }

    /** Bước hai của đăng nhập mật khẩu: mã TOTP hoặc mã dự phòng. Mã hết hạn thì phải nhập lại mật khẩu. */
    public LiveData<AuthResult<UserEntity>> verifySecondFactor(String code) {
        return Transformations.map(repository.verifySecondFactor(code), result -> {
            if (result.isSuccess() || "OTP_EXPIRED".equals(result.errorCode)) awaitingSecondFactor = false;
            return result;
        });
    }

    /** Bắt đầu đăng ký: gửi OTP tới số mới. */
    public LiveData<AuthResult<OtpChallenge>> requestOtp(String phoneE164) {
        return sendOtp(phoneE164, FLOW_REGISTER);
    }

    /** Bắt đầu quên mật khẩu: gửi OTP đặt lại mật khẩu. */
    public LiveData<AuthResult<OtpChallenge>> requestResetOtp(String phoneE164) {
        return sendOtp(phoneE164, FLOW_RESET);
    }

    /** Bắt đầu đăng nhập bằng OTP. */
    public LiveData<AuthResult<OtpChallenge>> requestLoginOtp(String phoneE164) {
        return sendOtp(phoneE164, FLOW_LOGIN);
    }

    /** "Gửi lại" ở màn OTP: gửi lại theo đúng luồng đang chạy. */
    public LiveData<AuthResult<OtpChallenge>> resendOtp() {
        String flow = state.get(KEY_FLOW);
        return sendOtp(getPhone(), flow == null ? FLOW_REGISTER : flow);
    }

    // Thành công thì nhớ phiên, luồng đang chạy và bắt đầu đếm ngược 60 giây.
    private LiveData<AuthResult<OtpChallenge>> sendOtp(String phoneE164, String flow) {
        LiveData<AuthResult<OtpChallenge>> source = FLOW_RESET.equals(flow) ? repository.requestResetOtp(phoneE164)
                : FLOW_LOGIN.equals(flow) ? repository.requestLoginOtp(phoneE164)
                : repository.requestRegisterOtp(phoneE164);
        return Transformations.map(source, result -> {
            if (result.isSuccess()) {
                state.set(KEY_FLOW, flow);
                state.set(KEY_PHONE, phoneE164);
                state.set(KEY_CHALLENGE_ID, result.data.challengeId);
                state.set(KEY_OTP_SENT_AT, SystemClock.elapsedRealtime());
                demoCode = result.data.demoCode;
            }
            return result;
        });
    }

    public LiveData<AuthResult<String>> verifyOtp(String code) {
        String challengeId = state.get(KEY_CHALLENGE_ID);
        LiveData<AuthResult<String>> source = isResetFlow()
                ? repository.verifyResetOtp(challengeId, code) : repository.verifyRegisterOtp(challengeId, code);
        return Transformations.map(source, result -> {
            if (result.isSuccess()) flowToken = result.data;
            return result;
        });
    }

    /** Luồng đăng nhập bằng OTP: mã đúng thì đăng nhập luôn, không có bước 3. */
    public LiveData<AuthResult<UserEntity>> verifyLoginOtp(String code) {
        return repository.verifyLoginOtp(state.get(KEY_CHALLENGE_ID), code);
    }

    public LiveData<AuthResult<UserEntity>> completeRegister(String displayName, String password) {
        return repository.completeRegister(flowToken, displayName, password);
    }

    public LiveData<AuthResult<UserEntity>> completePasswordReset(String newPassword) {
        return repository.completePasswordReset(flowToken, newPassword);
    }
}
