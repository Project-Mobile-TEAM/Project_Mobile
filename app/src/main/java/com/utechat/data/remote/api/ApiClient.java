package com.utechat.data.remote.api;

import androidx.annotation.NonNull;
import com.utechat.BuildConfig;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/** Một Retrofit dùng chung để tái sử dụng connection pool và không tạo client ở từng Fragment. */
public final class ApiClient {
    // Khớp tuyệt đối /api/media/{uuid} (chặn ?, #, %, .. và đường dẫn ngoài để không bao giờ rò Bearer token).
    private static final Pattern STRICT_MEDIA_PATH = Pattern.compile(
            "^/api/media/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private ApiClient() { }

    private static class Holder {
        // BASIC chỉ log URL/status; BODY có thể làm lộ password, OTP hay nội dung tin nhắn. Release luôn tắt hoàn toàn.
        private static final HttpLoggingInterceptor LOGGING = new HttpLoggingInterceptor()
                .setLevel(BuildConfig.DEBUG ? HttpLoggingInterceptor.Level.BASIC : HttpLoggingInterceptor.Level.NONE);

        private static final Retrofit INSTANCE = new Retrofit.Builder()
                .baseUrl(BuildConfig.BACKEND_BASE_URL)
                .client(new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
                        .addInterceptor(new AuthInterceptor()).addInterceptor(LOGGING).build())
                .addConverterFactory(GsonConverterFactory.create()).build();

        // OkHttpClient chuyên dụng cho upload/download/streaming media (Glide, Media3, WorkManager):
        // dùng chung AuthInterceptor (chỉ gửi Bearer tới đúng BACKEND_HOST), timeout dài 60s đọc / 120s ghi và không giới hạn callTimeout.
        private static final OkHttpClient MEDIA_CLIENT = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .addInterceptor(new AuthInterceptor())
                .addInterceptor(LOGGING)
                .build();

        // Retrofit dùng MEDIA_CLIENT cho các API tải lên multipart (POST /api/media).
        private static final Retrofit MEDIA_RETROFIT = new Retrofit.Builder()
                .baseUrl(BuildConfig.BACKEND_BASE_URL)
                .client(MEDIA_CLIENT)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        // OkHttpClient riêng cho WebSocket: tắt ping giao thức và readTimeout để dùng heartbeat JSON 25s của ứng dụng.
        private static final OkHttpClient WS_CLIENT = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .pingInterval(0, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .addInterceptor(LOGGING)
                .build();
    }

    public static Retrofit getRetrofit() { return Holder.INSTANCE; }

    /** Trả về OkHttpClient chuyên dụng cho tải lên/tải xuống/phát media có Bearer token an toàn (D-01, D-03). */
    public static OkHttpClient getMediaOkHttpClient() { return Holder.MEDIA_CLIENT; }

    /** Trả về Retrofit chuyên dụng với timeout lớn cho multipart media upload. */
    public static Retrofit getMediaRetrofit() { return Holder.MEDIA_RETROFIT; }

    /** Trả về OkHttpClient chuyên dụng cho ChatWebSocketManager, giữ chung chính sách logging an toàn. */
    public static OkHttpClient getWebSocketClient() { return Holder.WS_CLIENT; }

    /**
     * Ghép đường dẫn tương đối /api/media/{id} với BACKEND_BASE_URL thành URL đầy đủ cùng origin backend.
     * Từ chối mọi URL ngoài hoặc đường dẫn không bắt đầu bằng /api/media/ để tránh lộ Bearer token (DEPLOYMENT_SECURITY).
     */
    @NonNull
    public static String resolveBackendMediaUrl(@NonNull String baseUrl, @NonNull String relativeMediaUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        if (relativeMediaUrl == null || !STRICT_MEDIA_PATH.matcher(relativeMediaUrl).matches()) {
            throw new IllegalArgumentException("Invalid relative media URL: " + relativeMediaUrl);
        }
        String normalizedBase = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        String resolved = normalizedBase + relativeMediaUrl;
        HttpUrl baseParsed = HttpUrl.parse(baseUrl);
        HttpUrl resolvedParsed = HttpUrl.parse(resolved);
        if (baseParsed == null || resolvedParsed == null
                || !baseParsed.scheme().equals(resolvedParsed.scheme())
                || !baseParsed.host().equals(resolvedParsed.host())
                || baseParsed.port() != resolvedParsed.port()) {
            throw new IllegalArgumentException("Resolved media URL does not match backend origin");
        }
        return resolved;
    }
}
