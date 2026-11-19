package com.utechat.data.remote.api;

import com.utechat.data.remote.model.ContactDtos;
import java.util.List;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

/** Hợp đồng danh bạ; tạo bằng ApiClient dùng chung để lấy base URL và Bearer hiện có. */
public interface ContactApi {
    // Retrofit mã hóa dấu + trong SĐT thành query hợp lệ; chuẩn hóa đầu vào ở lớp gọi.
    @GET("api/users/lookup")
    Call<ContactDtos.Lookup> lookup(@Query("phone") String phone);

    // Body chỉ chứa người nhận; backend xác định người gửi bằng JWT.
    @POST("api/contact-requests")
    Call<ContactDtos.Request> send(@Body ContactDtos.Send body);

    // Lấy toàn bộ lời mời được phép xem để Repository đối soát cả chiều nhận và gửi.
    @GET("api/contact-requests")
    Call<List<ContactDtos.Request>> requests();

    // ID ở đây là ID lời mời, không phải ID tài khoản của đối phương.
    @POST("api/contact-requests/{id}/accept")
    Call<ContactDtos.Request> accept(@Path("id") String id);

    @POST("api/contact-requests/{id}/reject")
    Call<ContactDtos.Request> reject(@Path("id") String id);

    // Các thao tác trả 204 dùng Void vì server không gửi JSON kết quả.
    @DELETE("api/contact-requests/{id}")
    Call<Void> cancel(@Path("id") String id);

    // Hai danh sách tách riêng: một người bị chặn không được suy ra vẫn là bạn bè.
    @GET("api/contacts")
    Call<List<ContactDtos.Contact>> friends();

    @GET("api/contacts/blocked")
    Call<List<ContactDtos.Contact>> blocked();

    // ID của ba thao tác quan hệ là ID người dùng; quyền thao tác do backend kiểm tra.
    @DELETE("api/contacts/{id}")
    Call<Void> unfriend(@Path("id") String id);

    @POST("api/contacts/{id}/block")
    Call<Void> block(@Path("id") String id);

    @DELETE("api/contacts/{id}/block")
    Call<Void> unblock(@Path("id") String id);
}
