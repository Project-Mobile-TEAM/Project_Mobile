package com.utechat.data.remote.api;

import com.utechat.BuildConfig;
import com.utechat.data.session.SessionEvents;
import com.utechat.data.session.SessionManager;
import java.io.IOException;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/** Gắn "Authorization: Bearer" cho request tới backend và xử lý 401 ở một nơi duy nhất (F-07, slide Session 6). */
public final class AuthInterceptor implements Interceptor {
    private static final String BACKEND_HOST = HttpUrl.get(BuildConfig.BACKEND_BASE_URL).host();

    @Override public Response intercept(Chain chain) throws IOException {
        SessionManager session = SessionManager.get();
        Request request = chain.request();
        String token = null; // Token chính interceptor này gắn vào; null nếu request tự mang header (logout).
        String version = null; // Phân biệt hai lần đăng nhập dù token có cùng giá trị.
        // Chỉ gửi token tới đúng máy chủ của mình, không bao giờ tới host khác.
        if (session != null && request.header("Authorization") == null && BACKEND_HOST.equals(request.url().host())) {
            synchronized (session) { token = session.getToken(); version = session.versionKey(); }
            if (token != null) request = request.newBuilder().header("Authorization", "Bearer " + token).build();
        }
        Response response = chain.proceed(request);
        // 401 của đúng token đang lưu nghĩa là token hết hạn/bị thu hồi. Phản hồi trễ của token cũ (đã đăng xuất
        // rồi đăng nhập lại) hoặc 401 khi đăng nhập sai (chưa có token) thì không đụng tới phiên hiện tại.
        if (response.code() == 401 && token != null) {
            synchronized (session) {
                if (session.clearIfCurrent(token, version)) SessionEvents.notifyExpired(session.versionKey());
            }
        }
        return response;
    }
}
