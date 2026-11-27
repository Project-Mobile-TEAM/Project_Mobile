package com.utechat.data.repository.fake;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import com.utechat.data.local.entity.StickerEntity;
import com.utechat.data.local.entity.StickerPackEntity;
import com.utechat.data.repository.ChatResult;
import com.utechat.data.repository.StickerRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Danh mục mẫu dùng nội bộ Debug và kiểm thử giao diện, cùng mã bộ với backend. */
public final class FakeStickerRepository implements StickerRepository {
    private static final String DAILY_ID = "11111111-1111-4111-8111-111111111101";
    private static final String CAMPUS_ID = "11111111-1111-4111-8111-111111111102";

    private final MutableLiveData<List<StickerPackEntity>> packs = new MutableLiveData<>();
    private final Map<String, List<StickerEntity>> stickersByPack = new LinkedHashMap<>();

    public FakeStickerRepository() {
        StickerPackEntity daily = makePack(DAILY_ID, "ute_daily", "UTE Thường Ngày", "hi",
                true, true, 1);
        StickerPackEntity campus = makePack(CAMPUS_ID, "ute_campus", "UTE Học Đường", "deadline",
                false, false, 2);
        stickersByPack.put(DAILY_ID, seed(daily, List.of("hi", "ok", "thanks", "haha", "love", "fighting"),
                List.of("Xin chào", "OK luôn", "Cảm ơn", "Cười xỉu", "Thả tim", "Cố lên")));
        stickersByPack.put(CAMPUS_ID, seed(campus, List.of("deadline", "coffee", "study", "exam", "sleepy", "celebrate"),
                List.of("Chạy deadline", "Cà phê thôi", "Học bài nào", "Qua môn rồi", "Buồn ngủ quá", "Ăn mừng")));
        packs.setValue(List.of(daily, campus));
    }

    @Override public LiveData<List<StickerPackEntity>> observeAllPacks() {
        return packs;
    }

    @Override public LiveData<List<StickerPackEntity>> observeInstalledPacks() {
        return Transformations.map(packs, list ->
                list.stream().filter(StickerPackEntity::getInstalled).collect(Collectors.toList()));
    }

    @Override public LiveData<List<StickerEntity>> observeStickersByPack(String packId) {
        return new MutableLiveData<>(stickersByPack.getOrDefault(packId, List.of()));
    }

    @Override public LiveData<List<StickerEntity>> observeInstalledStickers() {
        return Transformations.map(packs, list -> {
            List<StickerEntity> result = new ArrayList<>();
            for (StickerPackEntity pack : list) {
                if (pack.getInstalled()) {
                    result.addAll(stickersByPack.get(pack.getPackId()));
                }
            }
            return result;
        });
    }

    @Override public LiveData<ChatResult<Boolean>> refreshPacks() {
        return new MutableLiveData<>(ChatResult.ok(true));
    }

    @Override public LiveData<ChatResult<Boolean>> installPack(String packId) {
        return changeInstallation(packId, true);
    }

    @Override public LiveData<ChatResult<Boolean>> uninstallPack(String packId) {
        return changeInstallation(packId, false);
    }

    private LiveData<ChatResult<Boolean>> changeInstallation(String packId, boolean installed) {
        List<StickerPackEntity> current = packs.getValue();
        if (current == null || !stickersByPack.containsKey(packId)) {
            return new MutableLiveData<>(ChatResult.error("NOT_FOUND"));
        }
        List<StickerPackEntity> updated = new ArrayList<>();
        for (StickerPackEntity pack : current) {
            if (packId.equals(pack.getPackId())) {
                // Tạo đối tượng mới để DiffUtil nhận thay đổi trạng thái cài/gỡ.
                StickerPackEntity replacement = new StickerPackEntity();
                replacement.setPackId(pack.getPackId());
                replacement.setCode(pack.getCode());
                replacement.setName(pack.getName());
                replacement.setDescription(pack.getDescription());
                replacement.setThumbnailUrl(pack.getThumbnailUrl());
                replacement.setIsDefault(pack.getIsDefault());
                replacement.setSortOrder(pack.getSortOrder());
                replacement.setInstalled(installed);
                updated.add(replacement);
            } else {
                updated.add(pack);
            }
        }
        packs.setValue(updated);
        return new MutableLiveData<>(ChatResult.ok(true));
    }

    private static StickerPackEntity makePack(String id, String code, String name, String cover,
                                              boolean installed, boolean defaultPack, int sortOrder) {
        StickerPackEntity pack = new StickerPackEntity();
        pack.setPackId(id);
        pack.setCode(code);
        pack.setName(name);
        pack.setThumbnailUrl("/api/stickers/assets/" + code + "/" + cover + ".png");
        pack.setInstalled(installed);
        pack.setIsDefault(defaultPack);
        pack.setSortOrder(sortOrder);
        return pack;
    }

    private static List<StickerEntity> seed(StickerPackEntity pack, List<String> codes, List<String> labels) {
        List<StickerEntity> result = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            String code = codes.get(i);
            StickerEntity sticker = new StickerEntity();
            sticker.setStickerId("22222222-2222-4222-8222-222222220" + pack.getSortOrder() + "0" + (i + 1));
            sticker.setPackId(pack.getPackId());
            sticker.setCode(code);
            sticker.setLabel(labels.get(i));
            sticker.setImageUrl("/api/stickers/assets/" + pack.getCode() + "/" + code + ".png");
            sticker.setWidth(256);
            sticker.setHeight(256);
            sticker.setSortOrder(i + 1);
            result.add(sticker);
        }
        return result;
    }
}
