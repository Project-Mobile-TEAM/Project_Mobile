package com.utechat.data.sticker;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Observer;
import com.utechat.data.local.dao.StickerDao;
import com.utechat.data.local.entity.StickerEntity;
import com.utechat.data.local.entity.StickerPackEntity;
import com.utechat.data.remote.api.ApiClient;
import com.utechat.data.repository.fake.FakeStickerRepository;
import com.utechat.data.session.SessionManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Kiểm tra cache, khóa phiên lúc công bố ảnh và trạng thái cài của dữ liệu giả. */
public class StickerCacheAndRepoTest {
    private static final String BASE = "https://utechat.example/";
    private static final String RELATIVE = "/api/stickers/assets/ute_daily/hi.png";

    @Rule public final TemporaryFolder temp = new TemporaryFolder();
    @Rule public final InstantTaskExecutorRule mainThread = new InstantTaskExecutorRule();

    @Test
    public void backendStickerUrlOnlyAcceptsCatalogAssetPaths() {
        assertEquals("https://utechat.example" + RELATIVE, ApiClient.resolveBackendStickerUrl(BASE, RELATIVE));
        assertEquals("https://utechat.example/api/stickers/assets/ute_daily/hi.webp",
                ApiClient.resolveBackendStickerUrl(BASE, "/api/stickers/assets/ute_daily/hi.webp"));
        for (String invalid : List.of("https://other.example/" + RELATIVE, "/api/stickers/assets/../hi.png",
                RELATIVE + "?token=test", RELATIVE + "#fragment", "/api/stickers/assets/ute_daily/hi.svg")) {
            assertThrows(IllegalArgumentException.class, () -> ApiClient.resolveBackendStickerUrl(BASE, invalid));
        }
    }

    @Test
    public void cachePublishesBytesAndRoomPathOnlyForTheCapturedSession() throws Exception {
        Path directory = temp.newFolder("stickers").toPath();
        StickerDao dao = mock(StickerDao.class);
        SessionManager session = mock(SessionManager.class);
        AtomicReference<String> active = new AtomicReference<>("session-a");
        when(session.sessionKey()).thenAnswer(invocation -> active.get());
        when(session.runIfCurrent(any(), any())).thenAnswer(invocation -> {
            if (!active.get().equals(invocation.getArgument(0))) {
                return false;
            }
            ((Runnable) invocation.getArgument(1)).run();
            return true;
        });
        byte[] bytes = {1, 2, 3, 4};
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            var request = chain.request();
            assertEquals("test-session-token", request.header("Authorization"));
            assertEquals("https://utechat.example" + RELATIVE, request.url().toString());
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(bytes, MediaType.get("image/png"))).build();
        }).build();
        ArrayDeque<Runnable> disk = new ArrayDeque<>();
        StickerCacheManager cache = new StickerCacheManager(directory, client, BASE, dao, session, Runnable::run, disk::add);

        StickerEntity sticker = new StickerEntity();
        sticker.setStickerId("test-id");
        sticker.setCode("hi");
        sticker.setImageUrl(RELATIVE);
        Path expected = directory.resolve("ute_daily").resolve("hi.png");

        // Chuyển tài khoản sau khi tải xong nhưng trước khi executor đĩa công bố tệp.
        cache.cachePackAsync("session-a", "test-session-token", "ute_daily", List.of(sticker));
        assertNotNull(disk.peek());
        active.set("session-b");
        disk.remove().run();
        assertFalse(Files.exists(expected));
        verifyNoInteractions(dao);

        cache.cachePackAsync("session-b", "test-session-token", "ute_daily", List.of(sticker));
        disk.remove().run();
        assertArrayEquals(bytes, Files.readAllBytes(expected));
        verify(dao).updateStickerLocalPath(eq("test-id"), eq(expected.toString()));
    }

    @Test
    public void mockCatalogInstallAndRemovalAffectTrayButPreserveStickerMetadata() {
        FakeStickerRepository repository = new FakeStickerRepository();
        List<StickerPackEntity> catalog = repository.observeAllPacks().getValue();
        assertNotNull(catalog);
        assertEquals(2, catalog.size());
        assertTrue(catalog.get(0).getIsDefault());
        String storePack = catalog.get(1).getPackId();
        var tray = repository.observeInstalledStickers();
        Observer<List<StickerEntity>> observer = rows -> {};
        tray.observeForever(observer);
        try {
            assertEquals(6, tray.getValue().size());
            assertTrue(repository.installPack(storePack).getValue().isSuccess());
            assertEquals(12, tray.getValue().size());
            assertTrue(repository.uninstallPack(storePack).getValue().isSuccess());
            assertEquals(6, tray.getValue().size());
            assertEquals(6, repository.observeStickersByPack(storePack).getValue().size());
        } finally {
            tray.removeObserver(observer);
        }
    }
}
