package com.utechat.data.repository.real;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.utechat.data.local.AppDatabase;
import com.utechat.data.local.dao.StickerDao;
import com.utechat.data.local.entity.StickerEntity;
import com.utechat.data.local.entity.StickerPackEntity;
import com.utechat.data.remote.api.StickerApi;
import com.utechat.data.remote.model.StickerDtos;
import com.utechat.data.repository.ChatResult;
import com.utechat.data.repository.StickerRepository;
import com.utechat.data.session.SessionManager;
import com.utechat.data.sticker.StickerCacheManager;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import retrofit2.Response;

/** Dùng danh mục từ backend làm nguồn trạng thái cài và Room làm nguồn đọc khi offline. */
public final class RealStickerRepository implements StickerRepository {
    private final AppDatabase database;
    private final StickerDao dao;
    private final StickerApi api;
    private final StickerCacheManager cache;
    private final SessionManager session;
    private final Executor network;
    private final Executor disk;
    private final Executor main;

    public RealStickerRepository(AppDatabase database, StickerApi api, StickerCacheManager cache,
                                 SessionManager session, Executor network, Executor disk, Executor main) {
        this.database = database;
        this.dao = database.stickerDao();
        this.api = api;
        this.cache = cache;
        this.session = session;
        this.network = network;
        this.disk = disk;
        this.main = main;
    }

    @Override public LiveData<List<StickerPackEntity>> observeAllPacks() {
        return dao.observeAllPacks();
    }

    @Override public LiveData<List<StickerPackEntity>> observeInstalledPacks() {
        return dao.observeInstalledPacks();
    }

    @Override public LiveData<List<StickerEntity>> observeStickersByPack(String packId) {
        return dao.observeStickersByPack(packId);
    }

    @Override public LiveData<List<StickerEntity>> observeInstalledStickers() {
        return dao.observeInstalledStickers();
    }

    @Override public LiveData<ChatResult<Boolean>> refreshPacks() {
        MutableLiveData<ChatResult<Boolean>> result = new MutableLiveData<>();
        CapturedChatSession request = CapturedChatSession.capture(session);
        if (request == null) {
            main.execute(() -> result.setValue(ChatResult.error("UNAUTHENTICATED")));
            return result;
        }
        network.execute(() -> {
            if (!request.key().equals(session.sessionKey())) {
                return;
            }
            try {
                Response<List<StickerDtos.StickerPackDto>> response = api.getPacks(request.authorization()).execute();
                if (!response.isSuccessful()) {
                    handleError(result, request, response);
                    return;
                }
                List<StickerDtos.StickerPackDto> packs = response.body();
                if (packs == null) {
                    post(result, request, ChatResult.error("INVALID_STATE"));
                    return;
                }
                disk.execute(() -> {
                    boolean saved = session.runIfCurrent(request.key(),
                            () -> database.runInTransaction(() -> storeCatalog(packs, request)));
                    if (saved) {
                        post(result, request, ChatResult.ok(true));
                    }
                });
            } catch (IOException e) {
                post(result, request, ChatResult.error("NETWORK"));
            }
        });
        return result;
    }

    @Override public LiveData<ChatResult<Boolean>> installPack(String packId) {
        return changeInstallation(packId, true);
    }

    @Override public LiveData<ChatResult<Boolean>> uninstallPack(String packId) {
        return changeInstallation(packId, false);
    }

    private LiveData<ChatResult<Boolean>> changeInstallation(String packId, boolean installed) {
        MutableLiveData<ChatResult<Boolean>> result = new MutableLiveData<>();
        CapturedChatSession request = CapturedChatSession.capture(session);
        if (request == null) {
            main.execute(() -> result.setValue(ChatResult.error("UNAUTHENTICATED")));
            return result;
        }
        network.execute(() -> {
            if (!request.key().equals(session.sessionKey())) {
                return;
            }
            try {
                Response<Void> response = (installed ? api.installPack(request.authorization(), packId)
                        : api.uninstallPack(request.authorization(), packId)).execute();
                if (!response.isSuccessful()) {
                    handleError(result, request, response);
                    return;
                }
                disk.execute(() -> {
                    // Khi gỡ bộ, giữ tệp đã tải để vẫn xem được tin nhắn trong lịch sử.
                    boolean saved = session.runIfCurrent(request.key(), () -> {
                        dao.updatePackInstalled(packId, installed);
                        StickerPackEntity pack = dao.findPackByIdSync(packId);
                        if (installed && pack != null) {
                            cache.cachePackAsync(request.key(), request.authorization(), pack.getCode(),
                                    dao.findStickersByPackSync(packId));
                        }
                    });
                    if (saved) {
                        post(result, request, ChatResult.ok(true));
                    }
                });
            } catch (IOException e) {
                post(result, request, ChatResult.error("NETWORK"));
            }
        });
        return result;
    }

    /** Giữ đường dẫn tệp đã xác nhận còn tồn tại, cập nhật metadata và trạng thái cài cùng giao dịch. */
    private void storeCatalog(List<StickerDtos.StickerPackDto> packs, CapturedChatSession request) {
        for (StickerDtos.StickerPackDto dto : packs) {
            if (dto == null || dto.id == null || dto.code == null || dto.name == null || dto.thumbnailUrl == null) {
                continue;
            }
            StickerPackEntity pack = new StickerPackEntity();
            pack.setPackId(dto.id);
            pack.setCode(dto.code);
            pack.setName(dto.name);
            pack.setDescription(dto.description);
            pack.setThumbnailUrl(dto.thumbnailUrl);
            pack.setInstalled(dto.installed);
            pack.setIsDefault(dto.isDefault);
            pack.setSortOrder(dto.sortOrder);
            dao.upsertPacks(List.of(pack));

            List<StickerEntity> stickers = new ArrayList<>();
            if (dto.stickers != null) {
                for (StickerDtos.StickerDto item : dto.stickers) {
                    if (item == null || item.id == null || !dto.id.equals(item.packId)
                            || item.code == null || item.label == null || item.imageUrl == null) {
                        continue;
                    }
                    StickerEntity sticker = new StickerEntity();
                    sticker.setStickerId(item.id);
                    sticker.setPackId(dto.id);
                    sticker.setCode(item.code);
                    sticker.setLabel(item.label);
                    sticker.setImageUrl(item.imageUrl);
                    sticker.setWidth(item.width);
                    sticker.setHeight(item.height);
                    sticker.setSortOrder(item.sortOrder);
                    StickerEntity previous = dao.findStickerByIdSync(item.id);
                    if (previous != null && item.imageUrl.equals(previous.getImageUrl())
                            && previous.getLocalPath() != null && new File(previous.getLocalPath()).isFile()) {
                        sticker.setLocalPath(previous.getLocalPath());
                    }
                    stickers.add(sticker);
                }
            }
            dao.upsertStickers(stickers);
            if (dto.installed) {
                cache.cachePackAsync(request.key(), request.authorization(), pack.getCode(), stickers);
            }
        }
    }

    private void handleError(MutableLiveData<ChatResult<Boolean>> result, CapturedChatSession request,
                             Response<?> response) {
        if (response.code() == 401) {
            request.expire(session);
        }
        post(result, request, RealChatRepository.parseErrorResult(response));
    }

    private void post(MutableLiveData<ChatResult<Boolean>> result, CapturedChatSession request,
                      ChatResult<Boolean> value) {
        main.execute(() -> {
            if (request.key().equals(session.sessionKey())) {
                result.setValue(value);
            }
        });
    }
}
