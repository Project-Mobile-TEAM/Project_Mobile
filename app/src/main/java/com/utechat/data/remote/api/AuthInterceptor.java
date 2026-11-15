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
        String token = session == null ? null : session.getToken();
        Request request = chain.request();
        // Chỉ gửi token tới đúng máy chủ của mình, không bao giờ tới host khác.
        if (token != null && BACKEND_HOST.equals(request.url().host())) {
            request = request.newBuilder().header("Authorization", "Bearer " + token).build();
        }
        Response response = chain.proceed(request);
        // 401 khi đang có token nghĩa là token hết hạn/bị thu hồi; 401 khi đăng nhập sai (chưa có token) thì bỏ qua.
        if (response.code() == 401 && token != null) {
            session.clear();
            SessionEvents.notifyExpired();
        }
        return response;
    }
}
