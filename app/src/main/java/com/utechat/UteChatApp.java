package com.utechat;

import android.app.Application;
import com.utechat.data.session.SessionManager;

/** Application: khởi tạo các đối tượng dùng chung một lần (slide Session 4). */
public class UteChatApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        SessionManager.init(this); // AuthInterceptor và Splash cần đọc phiên trước khi có Activity.
    }
}
