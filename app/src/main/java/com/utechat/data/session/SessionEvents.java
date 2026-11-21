package com.utechat.data.session;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/** Phát sự kiện "phiên hết hạn" từ tầng mạng tới MainActivity (F-07); tầng mạng không biết gì về giao diện. */
public final class SessionEvents {
    private static final MutableLiveData<Boolean> EXPIRED = new MutableLiveData<>();
    private static volatile String expiredVersion;
    private SessionEvents() { }

    public static LiveData<Boolean> expired() { return EXPIRED; }

    /** Gọi từ luồng bất kỳ (AuthInterceptor chạy trên luồng nền). */
    public static void notifyExpired(String version) { expiredVersion = version; EXPIRED.postValue(true); }

    /** Sự kiện nằm trong hàng đợi main không được đăng xuất phiên vừa đăng nhập sau 401. */
    public static boolean isCurrentExpiration() {
        SessionManager session = SessionManager.get();
        return session != null && expiredVersion != null && expiredVersion.equals(session.versionKey());
    }

    /** Người nhận gọi sau khi xử lý để xoay màn hình không xử lý lại sự kiện cũ; gọi trên luồng chính. */
    public static void consume() { expiredVersion = null; EXPIRED.setValue(null); }
}
