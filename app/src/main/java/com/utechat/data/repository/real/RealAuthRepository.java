package com.utechat.data.repository.real;

import android.os.Build;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.utechat.data.MainThreadExecutor;
import com.google.gson.Gson;
import com.utechat.data.local.dao.UserDao;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.remote.api.AuthApi;
import com.utechat.data.remote.model.AuthDtos.*;
import com.utechat.data.repository.AuthRepository;
import com.utechat.data.repository.AuthResult;
import com.utechat.data.repository.DeviceInfo;
import com.utechat.data.repository.OtpChallenge;
import com.utechat.data.repository.UserProfile;
import com.utechat.data.session.SessionManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Function;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Xác thực thật qua REST (API_CONTRACT 2.1, 2.2): lưu JWT bằng SessionManager, hồ sơ cache trong Room (user_cache). */
public class RealAuthRepository implements AuthRepository {
    private static final Gson GSON = new Gson();
    private final AuthApi api;
    private final SessionManager session;
    private final UserDao userDao;
    private final Executor disk; // Một luồng đĩa duy nhất: ghi cache và xoá cache luôn chạy đúng thứ tự gọi.
    private final Executor main; // Cho test điều khiển hàng đợi; ứng dụng dùng main Looper thật.
    private final java.util.function.Consumer<String> onDepartingSession; // Hủy hàng đợi/WS đúng phiên cũ ngay khi chuyển phiên.
    private String trackedSessionKey;
    private final MutableLiveData<UserEntity> current = new MutableLiveData<>(null);
    private final MutableLiveData<UserProfile> profile = new MutableLiveData<>(null);

    public RealAuthRepository(AuthApi api, SessionManager session, UserDao userDao, Executor disk, Runnable clearCache) {
        this(api, session, userDao, disk, clearCache, MainThreadExecutor.INSTANCE, departing -> { });
    }

    public RealAuthRepository(AuthApi api, SessionManager session, UserDao userDao, Executor disk, Runnable clearCache, Executor main) {
        this(api, session, userDao, disk, clearCache, main, departing -> { });
    }

    public RealAuthRepository(
            AuthApi api,
            SessionManager session,
            UserDao userDao,
            Executor disk,
            Runnable clearCache,
            Executor main,
            java.util.function.Consumer<String> onDepartingSession) {
        this.api = api;
        this.session = session;
        this.userDao = userDao;
        this.disk = disk;
        this.main = java.util.Objects.requireNonNull(main);
        this.onDepartingSession = java.util.Objects.requireNonNull(onDepartingSession);
        this.trackedSessionKey = session.sessionKey();
        // Mọi đường đổi phiên (logout, deleteAccount, 401, WS 4401) dọn đúng phiên cũ rồi xếp xóa Room trên DISK.
        session.setOnSessionChanged(() -> {
            String departing = trackedSessionKey;
            String next = session.sessionKey();
            trackedSessionKey = next;
            if (departing != null && !departing.equals(next)) {
                this.onDepartingSession.accept(departing);
            }
            disk.execute(clearCache);
            String version = session.versionKey();
            main.execute(() -> {
                synchronized (session) {
                    if (!version.equals(session.versionKey())) return;
                    current.setValue(null);
                    profile.setValue(null);
                }
            });
        });
    }

    // ---------- đăng nhập / đăng xuất ----------
    @Override public LiveData<AuthResult<UserEntity>> login(String phoneE164, String password) {
        return call(api.loginPassword(new PasswordLoginRequest(phoneE164, password, session.getDeviceId(), Build.MODEL)),
                this::onAuthenticated);
    }

    @Override public LiveData<AuthResult<OtpChallenge>> requestLoginOtp(String phoneE164) {
        return call(api.loginOtpRequest(new PhoneDeviceRequest(phoneE164, session.getDeviceId())),
                r -> new OtpChallenge(r.challengeId, r.demoOtp));
    }
    @Override public LiveData<AuthResult<UserEntity>> verifyLoginOtp(String challengeId, String code) {
        return call(api.loginOtpVerify(new OtpLoginVerifyRequest(challengeId, code, Build.MODEL)), this::onAuthenticated);
    }

    @Override public LiveData<UserEntity> getCurrentUser() { return current; }

    /**
     * Xoá phiên cục bộ NGAY rồi mới báo server thu hồi token cũ (gửi kèm token tường minh). Không chờ phản hồi để
     * xoá: nếu người dùng kịp đăng nhập lại, callback trễ sẽ xoá nhầm phiên mới. Mất mạng thì token cũ tự hết hạn.
     */
    @Override public void logout() {
        String token = session.getToken();
        clearLocal();
        if (token == null) return;
        api.logout("Bearer " + token).enqueue(new Callback<Void>() {
            @Override public void onResponse(Call<Void> c, Response<Void> r) { }
            @Override public void onFailure(Call<Void> c, Throwable t) { }
        });
    }

    private void clearLocal() {
        session.clear();
    }

    // ---------- đăng ký ----------
    @Override public LiveData<AuthResult<OtpChallenge>> requestRegisterOtp(String phoneE164) {
        return call(api.registerOtp(new PhoneDeviceRequest(phoneE164, session.getDeviceId())),
                r -> new OtpChallenge(r.challengeId, r.demoOtp));
    }
    @Override public LiveData<AuthResult<String>> verifyRegisterOtp(String challengeId, String code) {
        return call(api.registerVerify(new VerifyRequest(challengeId, code)), r -> r.registrationToken);
    }
    @Override public LiveData<AuthResult<UserEntity>> completeRegister(String registrationToken, String displayName, String password) {
        return call(api.registerComplete(new RegisterCompleteRequest(registrationToken, displayName, password, Build.MODEL)),
                this::onAuthenticated);
    }

    // ---------- quên mật khẩu ----------
    @Override public LiveData<AuthResult<OtpChallenge>> requestResetOtp(String phoneE164) {
        return call(api.resetOtp(new PhoneDeviceRequest(phoneE164, session.getDeviceId())),
                r -> new OtpChallenge(r.challengeId, r.demoOtp));
    }
    @Override public LiveData<AuthResult<String>> verifyResetOtp(String challengeId, String code) {
        return call(api.resetVerify(new VerifyRequest(challengeId, code)), r -> r.resetToken);
    }
    @Override public LiveData<AuthResult<UserEntity>> completePasswordReset(String resetToken, String newPassword) {
        return call(api.resetComplete(new PasswordResetCompleteRequest(resetToken, newPassword, Build.MODEL)),
                this::onAuthenticated);
    }

    // ---------- hồ sơ, mật khẩu, thiết bị ----------
    /**
     * Offline-first: mở app chưa có hồ sơ trong bộ nhớ thì đọc bản cache Room trước, rồi làm mới từ GET /me.
     * Mọi kết quả chỉ được áp dụng nếu phiên vẫn là phiên lúc gửi (đã đăng xuất thì bỏ phản hồi trễ).
     */
    @Override public LiveData<UserProfile> getProfile() {
        String key = session.sessionKey();
        if (key == null) return profile;
        String meId = session.getMeId();
        if (profile.getValue() == null && meId != null) {
            boolean discoverable = session.isMeDiscoverable();
            disk.execute(() -> {
                session.runIfCurrent(key, () -> {
                    UserEntity cached = userDao.findById(meId);
                    // Kiểm lại trên main, tránh postValue cũ xuất hiện sau một lần đăng nhập mới.
                    main.execute(() -> session.runIfCurrent(key, () -> {
                        if (cached == null || profile.getValue() != null) return;
                        current.setValue(cached);
                        profile.setValue(new UserProfile(cached.getId(), cached.getPhone(), cached.getDisplayName(),
                                cached.getAvatarUrl(), discoverable, cached.getAllowMessagesFromNonContacts()));
                    }));
                });
            });
        }
        api.me().enqueue(new Callback<UserDto>() {
            @Override public void onResponse(Call<UserDto> c, Response<UserDto> r) {
                if (r.isSuccessful() && r.body() != null)
                    session.runIfCurrent(key, () -> { if (meId == null || meId.equals(r.body().id)) apply(r.body()); });
            }
            @Override public void onFailure(Call<UserDto> c, Throwable t) { }
        });
        return profile;
    }

    @Override public LiveData<AuthResult<UserProfile>> updateProfile(String displayName, boolean allowMessagesFromNonContacts, boolean discoverable) {
        return call(api.updateMe(new UpdateProfileRequest(displayName, allowMessagesFromNonContacts, discoverable)),
                dto -> { apply(dto); return profile.getValue(); });
    }

    @Override public LiveData<AuthResult<Boolean>> deleteAccount(String password) {
        return call(api.deleteMe(new DeleteAccountRequest(password)), v -> { clearLocal(); return true; });
    }

    @Override public LiveData<AuthResult<Boolean>> cancelDeletion() {
        return call(api.cancelDeletion(), v -> {
            UserProfile p = profile.getValue();
            if (p != null) profile.setValue(p.withoutDeletion());
            return true;
        });
    }

    @Override public LiveData<AuthResult<Boolean>> changePassword(String oldPassword, String newPassword) {
        return call(api.changePassword(new ChangePasswordRequest(oldPassword, newPassword)), v -> true);
    }

    @Override public LiveData<AuthResult<List<DeviceInfo>>> getDevices() {
        return call(api.devices(), list -> {
            List<DeviceInfo> out = new ArrayList<>();
            for (DeviceDto d : list) {
                // Server chưa lưu phiên bản hệ điều hành nên hiện nền tảng ("Android").
                String os = d.platform == null ? "" : d.platform.charAt(0) + d.platform.substring(1).toLowerCase();
                out.add(new DeviceInfo(d.id, d.name, os, Instant.parse(d.lastSeenAt).toEpochMilli(), d.current));
            }
            return out;
        });
    }

    @Override public LiveData<AuthResult<Boolean>> revokeDevice(String deviceId) {
        return call(api.revokeDevice(deviceId), v -> true);
    }

    // ---------- hàm phụ ----------
    /** Đăng nhập/đăng ký/đặt lại thành công: lưu token vào Keystore và cập nhật hồ sơ. */
    private UserEntity onAuthenticated(AuthSuccessResponse r) {
        session.saveAuthenticated(r.token, r.user.id, r.user.discoverable);
        apply(r.user);
        return current.getValue();
    }

    private void apply(UserDto dto) {
        UserEntity user = new UserEntity();
        user.setId(dto.id);
        user.setPhone(dto.phone);
        user.setDisplayName(dto.displayName);
        user.setAvatarUrl(dto.avatarUrl);
        user.setAllowMessagesFromNonContacts(dto.allowMessagesFromNonContacts);
        current.setValue(user);
        session.saveMe(dto.id, dto.discoverable);
        String key = session.sessionKey();
        disk.execute(() -> session.runIfCurrent(key, () -> userDao.insert(user)));
        profile.setValue(new UserProfile(dto.id, dto.phone, dto.displayName, dto.avatarUrl,
                dto.discoverable, dto.allowMessagesFromNonContacts, dto.deletionScheduledAt));
    }

    /** Chạy một Call bất đồng bộ: thành công thì map ra kết quả, lỗi HTTP thì lấy mã lỗi của server, mất mạng thì NETWORK. */
    private <T, R> LiveData<AuthResult<R>> call(Call<T> call, Function<T, R> map) {
        MutableLiveData<AuthResult<R>> out = new MutableLiveData<>();
        String version = session.versionKey();
        call.enqueue(new Callback<T>() {
            @Override public void onResponse(Call<T> c, Response<T> r) {
                synchronized (session) {
                    if (!version.equals(session.versionKey())) return;
                    out.setValue(r.isSuccessful() ? AuthResult.ok(map.apply(r.body())) : AuthResult.error(errorCode(r)));
                }
            }
            @Override public void onFailure(Call<T> c, Throwable t) {
                synchronized (session) {
                    if (version.equals(session.versionKey())) out.setValue(AuthResult.error("NETWORK"));
                }
            }
        });
        return out;
    }

    private static String errorCode(Response<?> r) {
        try {
            if (r.errorBody() != null) {
                ErrorBody body = GSON.fromJson(r.errorBody().string(), ErrorBody.class);
                if (body != null && body.error != null && body.error.code != null) return body.error.code;
            }
        } catch (Exception ignored) { /* Thân lỗi không phải JSON chuẩn: dùng mã HTTP bên dưới. */ }
        return "HTTP_" + r.code();
    }
}
