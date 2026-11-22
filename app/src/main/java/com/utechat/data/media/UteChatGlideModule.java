package com.utechat.data.media;

import android.content.Context;
import androidx.annotation.NonNull;
import com.bumptech.glide.Glide;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;
import com.utechat.data.remote.api.ApiClient;
import java.io.InputStream;

/**
 * Cấu hình Glide dùng chung OkHttpClient media của ứng dụng (D-01, D-03):
 * ảnh /api/media/{id} được tải với header Authorization: Bearer (AuthInterceptor chỉ gắn cho đúng BACKEND_HOST),
 * tuyệt đối không dùng ticket hay JWT trong query URL.
 */
@GlideModule
public final class UteChatGlideModule extends AppGlideModule {
    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        registry.replace(GlideUrl.class, InputStream.class,
                new OkHttpUrlLoader.Factory(ApiClient.getMediaOkHttpClient()));
    }

    /** Tắt quét manifest để Glide nạp module nhanh hơn và không phụ thuộc metadata cũ. */
    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
