package com.utechat.data.session;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/** Phát sự kiện "phiên hết hạn" từ tầng mạng tới MainActivity (F-07); tầng mạng không biết gì về giao diện. */
public final class SessionEvents {
    private static final MutableLiveData<Boolean> EXPIRED = new MutableLiveData<>();
    private SessionEvents() { }

    public static LiveData<Boolean> expired() { return EXPIRED; }

    /** Gọi từ luồng bất kỳ (AuthInterceptor chạy trên luồng nền). */
    public static void notifyExpired() { EXPIRED.postValue(true); }

    /** Người nhận gọi sau khi xử lý để xoay màn hình không xử lý lại sự kiện cũ; gọi trên luồng chính. */
    public static void consume() { EXPIRED.setValue(null); }
}
