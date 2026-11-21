package com.utechat.data.remote.api;

import androidx.annotation.Nullable;
import com.utechat.data.remote.model.ChatDtos;
import java.util.List;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * Ranh giới Retrofit cho toàn bộ API hội thoại, tin nhắn, biên nhận và đồng bộ của Phase 5.
 * Mọi đường dẫn đều tương đối ("api/...") để ghép chuẩn với BACKEND_BASE_URL kết thúc bằng '/'.
 */
public interface ChatApi {

    @GET("api/conversations")
    Call<List<ChatDtos.ConversationDto>> getConversations();

    @POST("api/conversations/direct")
    Call<ChatDtos.ConversationDto> openDirect(@Body ChatDtos.DirectConversationRequest request);

    @POST("api/conversations/group")
    Call<ChatDtos.ConversationDto> createGroup(@Body ChatDtos.CreateGroupRequest request);

    @GET("api/conversations/{id}")
    Call<ChatDtos.ConversationDto> getConversation(@Path("id") String id);

    @PATCH("api/conversations/{id}")
    Call<ChatDtos.ConversationDto> updateConversation(
            @Path("id") String id,
            @Body ChatDtos.UpdateConversationRequest request);

    @POST("api/conversations/{id}/members")
    Call<ChatDtos.ConversationDto> addMembers(
            @Path("id") String id,
            @Body ChatDtos.AddMembersRequest request);

    @DELETE("api/conversations/{id}/members/{userId}")
    Call<Void> removeMember(
            @Path("id") String id,
            @Path("userId") String userId,
            @Query("successorUserId") @Nullable String successorUserId);

    @PUT("api/conversations/{id}/members/{userId}/role")
    Call<ChatDtos.ConversationDto> updateMemberRole(
            @Path("id") String id,
            @Path("userId") String userId,
            @Body ChatDtos.UpdateRoleRequest request);

    @GET("api/conversations/{id}/messages")
    Call<List<ChatDtos.MessageDto>> getMessages(
            @Path("id") String id,
            @Query("before") @Nullable Long before,
            @Query("limit") int limit);

    /**
     * Gửi tin nhắn với header Authorization truyền tường minh từ SendMessageWorker
     * để khóa chặt đúng JWT của phiên xếp hàng; AuthInterceptor sẽ không ghi đè header này.
     */
    @POST("api/conversations/{id}/messages")
    Call<ChatDtos.MessageDto> sendMessage(
            @Header("Authorization") String authorization,
            @Path("id") String id,
            @Body ChatDtos.SendMessageRequest request);

    @POST("api/conversations/{id}/delivered")
    Call<Void> markDelivered(
            @Path("id") String id,
            @Body ChatDtos.WatermarkRequest request);

    @POST("api/conversations/{id}/read")
    Call<Void> markRead(
            @Path("id") String id,
            @Body ChatDtos.WatermarkRequest request);

    @GET("api/sync/bootstrap")
    Call<ChatDtos.BootstrapResponse> bootstrap();

    @GET("api/sync")
    Call<ChatDtos.SyncResponse> sync(
            @Query("after") long after,
            @Query("limit") int limit);
}
