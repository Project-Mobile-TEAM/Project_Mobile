package com.utechat.data.remote.api;

import com.utechat.BuildConfig;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/** Một Retrofit dùng chung để tái sử dụng connection pool và không tạo client ở từng Fragment. */
public final class ApiClient {
    private ApiClient() { }

    private static class Holder {
        // BASIC chỉ log URL/status; BODY có thể làm lộ password, OTP hay nội dung tin nhắn.
        private static final HttpLoggingInterceptor LOGGING = new HttpLoggingInterceptor()
                .setLevel(BuildConfig.DEBUG ? HttpLoggingInterceptor.Level.BASIC : HttpLoggingInterceptor.Level.NONE);
        private static final Retrofit INSTANCE = new Retrofit.Builder()
                .baseUrl(BuildConfig.BACKEND_BASE_URL)
                .client(new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
                        .addInterceptor(LOGGING).build())
                .addConverterFactory(GsonConverterFactory.create()).build();
    }

    public static Retrofit getRetrofit() { return Holder.INSTANCE; }
}
