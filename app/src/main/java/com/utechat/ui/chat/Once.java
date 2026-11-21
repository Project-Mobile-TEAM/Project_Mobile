package com.utechat.ui.chat;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import java.util.function.Consumer;

/** Repository chat/danh bạ trả LiveData phát kết quả một lần; nghe đúng một giá trị khác null rồi gỡ observer. */
final class Once {
    private Once() { }

    static <T> void observe(LiveData<T> source, Consumer<T> handler) {
        source.observeForever(new Observer<T>() {
            @Override public void onChanged(T value) {
                if (value == null) return;
                source.removeObserver(this);
                handler.accept(value);
            }
        });
    }
}
