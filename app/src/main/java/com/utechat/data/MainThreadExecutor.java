package com.utechat.data;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.Executor;

/** Dùng API công khai; chạy ngay trên main để không đảo thứ tự đổi phiên và cập nhật hồ sơ. */
public final class MainThreadExecutor implements Executor {
    public static final MainThreadExecutor INSTANCE = new MainThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private MainThreadExecutor() { }

    @Override public void execute(Runnable action) {
        // Từ luồng nền thì xếp FIFO; kiểm tra phiên vẫn nằm trong callback của bên gọi.
        if (Looper.myLooper() == handler.getLooper()) action.run();
        else handler.post(action);
    }
}
