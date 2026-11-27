package com.utechat.data.remote.api;

import com.utechat.data.remote.model.StickerDtos;
import java.util.List;
import retrofit2.Call;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Path;

/** Kho nhãn dán đọc và cập nhật trạng thái cài theo JWT đã chụp lúc gọi repository. */
public interface StickerApi {
    @GET("api/stickers/packs")
    Call<List<StickerDtos.StickerPackDto>> getPacks(@Header("Authorization") String authorization);

    @POST("api/stickers/packs/{packId}/install")
    Call<Void> installPack(@Header("Authorization") String authorization, @Path("packId") String packId);

    @DELETE("api/stickers/packs/{packId}/install")
    Call<Void> uninstallPack(@Header("Authorization") String authorization, @Path("packId") String packId);
}
