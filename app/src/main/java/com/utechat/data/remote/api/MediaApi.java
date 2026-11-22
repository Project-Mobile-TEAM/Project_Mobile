package com.utechat.data.remote.api;

import androidx.annotation.Nullable;
import com.utechat.data.remote.model.MediaDtos;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;
import retrofit2.http.Streaming;
import retrofit2.http.Url;

/**
 * Ranh giới Retrofit cho tệp đa phương tiện (API_CONTRACT mục 2.5). Phải tạo từ ApiClient.getMediaRetrofit()
 * để dùng timeout dài 60s/120s thay vì timeout 15s của Retrofit thường.
 */
public interface MediaApi {

    /**
     * Upload tệp theo multipart. Header Authorization truyền tường minh từ SendMessageWorker để khóa chặt
     * JWT của đúng phiên xếp hàng; clientId là UUID của tin nhắn nên upload lại luôn lấy về đúng URL cũ (idempotent).
     */
    @Multipart
    @POST("api/media")
    Call<MediaDtos.MediaUploadResponse> uploadMedia(
            @Header("Authorization") String authorization,
            @Part("clientId") RequestBody clientId,
            @Part MultipartBody.Part kind,
            @Part MultipartBody.Part file,
            @Part("durationMs") @Nullable RequestBody durationMs);

    /**
     * Tải tệp dạng stream theo đường dẫn tương đối "api/media/{id}"; AuthInterceptor chỉ gắn Bearer
     * khi host trùng BACKEND_HOST nên token không bao giờ rò sang máy chủ khác.
     */
    @Streaming
    @GET
    Call<ResponseBody> downloadByRelativeUrl(@Url String relativeUrl);
}
