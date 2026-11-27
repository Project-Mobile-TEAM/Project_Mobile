package com.utechat.data.repository.real;

import androidx.annotation.Nullable;
import com.utechat.data.session.SessionEvents;
import com.utechat.data.session.SessionManager;

/** Giữ token, tài khoản và phiên của cùng lần đăng nhập cho yêu cầu chạy nền. */
record CapturedChatSession(String key, String userId, String token, String version) {

    @Nullable
    static CapturedChatSession capture(SessionManager session) {
        synchronized (session) {
            String key = session.sessionKey();
            String userId = session.getUserId();
            String token = session.getToken();
            return key == null || userId == null || token == null
                    ? null : new CapturedChatSession(key, userId, token, session.versionKey());
        }
    }

    String authorization() {
        return "Bearer " + token;
    }

    @Override public String toString() {
        // Không để token xuất hiện nếu đối tượng bị ghi vào log.
        return "CapturedChatSession";
    }

    void expire(SessionManager session) {
        // Header gắn sẵn nên chính nơi tạo request xử lý 401 cho đúng lần đăng nhập.
        synchronized (session) {
            if (session.clearIfCurrent(token, version)) {
                SessionEvents.notifyExpired(session.versionKey());
            }
        }
    }
}
