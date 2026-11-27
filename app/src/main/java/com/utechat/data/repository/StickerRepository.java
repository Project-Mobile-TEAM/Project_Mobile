package com.utechat.data.repository;

import androidx.lifecycle.LiveData;
import com.utechat.data.local.entity.StickerEntity;
import com.utechat.data.local.entity.StickerPackEntity;
import java.util.List;

/** Cung cấp bộ nhãn dán và ảnh đã đồng bộ cho khay offline và cửa hàng. */
public interface StickerRepository {
    LiveData<List<StickerPackEntity>> observeAllPacks();
    LiveData<List<StickerPackEntity>> observeInstalledPacks();
    LiveData<List<StickerEntity>> observeStickersByPack(String packId);
    LiveData<List<StickerEntity>> observeInstalledStickers();
    LiveData<ChatResult<Boolean>> refreshPacks();
    LiveData<ChatResult<Boolean>> installPack(String packId);
    LiveData<ChatResult<Boolean>> uninstallPack(String packId);
}
