package com.utechat.data.media;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import android.content.Context;
import com.utechat.data.local.dao.TransferDao;
import com.utechat.data.local.entity.AttachmentTransferEntity;
import com.utechat.data.session.SessionManager;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Kiểm thử JVM cho MediaDownloadManager (06-04 & 06-VALIDATION dòng 97):
 * 1. Tải tệp kèm header Authorization: Bearer, công bố nguyên tử vào cacheDir/media_cache/,
 *    ghi COMPLETED vào TransferDao và tái sử dụng bản cache ở lần gọi sau.
 * 2. Đổi tài khoản giữa chừng lúc đang tải: hủy Call của phiên cũ, xóa tệp .part,
 *    KHÔNG công bố tệp sang cache chung, KHÔNG ghi TransferDao và KHÔNG gọi callback cho phiên mới.
 */
public class MediaDownloadManagerTest {
    private static final String ATT_ID_1 = "11111111-1111-1111-1111-111111111111";
    private static final String ATT_ID_2 = "22222222-2222-2222-2222-222222222222";
    private static final String SESSION_A = "1:user-a";
    private static final String SESSION_B = "2:user-b";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Context appContext;
    private File cacheDir;
    private TransferDao transferDao;
    private SessionManager session;
    private final AtomicReference<String> currentSessionKey = new AtomicReference<>(SESSION_A);
    private final AtomicReference<String> currentToken = new AtomicReference<>("token-user-a");

    @Before
    public void setUp() throws IOException {
        cacheDir = tmp.newFolder("cache");
        appContext = mock(Context.class);
        when(appContext.getApplicationContext()).thenReturn(appContext);
        when(appContext.getCacheDir()).thenReturn(cacheDir);

        transferDao = mock(TransferDao.class);
        session = mock(SessionManager.class);
        when(session.sessionKey()).thenAnswer(i -> currentSessionKey.get());
        when(session.getToken()).thenAnswer(i -> currentToken.get());
        when(session.runIfCurrent(anyString(), any(Runnable.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Runnable action = inv.getArgument(1);
            if (!key.equals(currentSessionKey.get())) return false;
            action.run();
            return true;
        });
    }

    @Test
    public void downloadsWithBearerPublishesAtomicallyAndReusesCache() throws Exception {
        byte[] payload = "%PDF-1.4 fake-pdf-body".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> seenAuthHeader = new AtomicReference<>();
        int[] networkHits = {0};

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    networkHits[0]++;
                    seenAuthHeader.set(chain.request().header("Authorization"));
                    return new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ResponseBody.create(payload, MediaType.parse("application/pdf")))
                            .build();
                })
                .build();

        AtomicReference<AttachmentTransferEntity> stored = new AtomicReference<>();
        doAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return null;
        }).when(transferDao).insert(any(AttachmentTransferEntity.class));
        doAnswer(inv -> {
            AttachmentTransferEntity row = stored.get();
            if (row != null) {
                row.setState(inv.getArgument(1));
                row.setProgress(inv.getArgument(2));
            }
            return null;
        }).when(transferDao).updateState(anyString(), anyString(), anyInt(), any(), anyLong());
        when(transferDao.findByClientIdSync("dl:" + ATT_ID_1)).thenAnswer(i -> stored.get());

        MediaDownloadManager manager = new MediaDownloadManager(
                appContext, client, transferDao, Runnable::run, Runnable::run, Runnable::run, session);

        AtomicReference<File> firstFile = new AtomicReference<>();
        manager.downloadToCache(ATT_ID_1, "/api/media/" + ATT_ID_1, "../../report.pdf", "application/pdf",
                new MediaDownloadManager.Callback() {
                    @Override public void onSuccess(File file) { firstFile.set(file); }
                    @Override public void onError(String code) { fail("Unexpected error: " + code); }
                });

        assertNotNull(firstFile.get());
        assertTrue(firstFile.get().isFile());
        assertEquals(ATT_ID_1 + "_report.pdf", firstFile.get().getName());
        assertArrayEquals(payload, Files.readAllBytes(firstFile.get().toPath()));
        assertEquals("Bearer token-user-a", seenAuthHeader.get());
        assertEquals("COMPLETED", stored.get().getState());
        assertEquals(1, networkHits[0]);

        // Lần 2: Room ghi COMPLETED và tệp còn trên đĩa -> trả cache ngay, không gọi lại mạng.
        AtomicReference<File> secondFile = new AtomicReference<>();
        manager.downloadToCache(ATT_ID_1, "/api/media/" + ATT_ID_1, "../../report.pdf", "application/pdf",
                new MediaDownloadManager.Callback() {
                    @Override public void onSuccess(File file) { secondFile.set(file); }
                    @Override public void onError(String code) { fail("Unexpected error: " + code); }
                });
        assertEquals(firstFile.get(), secondFile.get());
        assertEquals(1, networkHits[0]);
    }

    @Test
    public void accountSwitchMidDownloadAbortsPublishAndNeverNotifiesNewSession() throws Exception {
        byte[] payload = "secret-user-a-document".getBytes(StandardCharsets.UTF_8);
        CountDownLatch requestEntered = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        CountDownLatch downloadFinished = new CountDownLatch(1);

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    requestEntered.countDown();
                    try {
                        assertTrue(releaseResponse.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", e);
                    }
                    return new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ResponseBody.create(payload, MediaType.parse("application/pdf")))
                            .build();
                })
                .build();

        ExecutorService netPool = Executors.newSingleThreadExecutor();
        try {
            MediaDownloadManager manager = new MediaDownloadManager(
                    appContext, client, transferDao,
                    task -> netPool.execute(() -> {
                        try {
                            task.run();
                        } finally {
                            downloadFinished.countDown();
                        }
                    }),
                    Runnable::run,
                    Runnable::run,
                    session);

            AtomicReference<String> callbackHit = new AtomicReference<>();
            manager.downloadToCache(ATT_ID_2, "/api/media/" + ATT_ID_2, "secret.pdf", "application/pdf",
                    new MediaDownloadManager.Callback() {
                        @Override public void onSuccess(File file) { callbackHit.set("SUCCESS:" + file); }
                        @Override public void onError(String code) { callbackHit.set("ERROR:" + code); }
                    });

            assertTrue("Yêu cầu tải phải bắt đầu trên networkExecutor", requestEntered.await(3, TimeUnit.SECONDS));

            // Đổi tài khoản sang User B giữa lúc response đang trên đường về và hủy download phiên A.
            currentSessionKey.set(SESSION_B);
            currentToken.set("token-user-b");
            manager.cancelDownloadsForSession(SESSION_A);
            releaseResponse.countDown();

            assertTrue("Tác vụ tải phiên A phải kết thúc gọn", downloadFinished.await(5, TimeUnit.SECONDS));

            // Tuyệt đối không công bố tệp vào media_cache/, không ghi COMPLETED, không gọi callback cho User B.
            File leakedTarget = new File(new File(cacheDir, "media_cache"), ATT_ID_2 + "_secret.pdf");
            assertFalse("Tệp của phiên A không được công bố sau khi đổi sang phiên B", leakedTarget.exists());
            assertNull("Callback không được gọi khi phiên đã đổi", callbackHit.get());
            verify(transferDao, never()).updateState(anyString(), eq("COMPLETED"), anyInt(), any(), anyLong());
        } finally {
            netPool.shutdownNow();
        }
    }

    @Test
    public void deduplicatesConcurrentDownloadsAndRejectsSmuggledMediaUrls() throws Exception {
        byte[] payload = "%PDF-1.7 dedup-payload".getBytes(StandardCharsets.UTF_8);
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        int[] networkHits = {0};

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    networkHits[0]++;
                    inFlight.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", e);
                    }
                    return new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ResponseBody.create(payload, MediaType.parse("application/pdf")))
                            .build();
                })
                .build();

        ExecutorService netPool = Executors.newFixedThreadPool(2);
        try {
            MediaDownloadManager manager = new MediaDownloadManager(
                    appContext, client, transferDao, netPool, Runnable::run, Runnable::run, session);

            CountDownLatch bothDone = new CountDownLatch(2);
            AtomicReference<File> first = new AtomicReference<>();
            AtomicReference<File> second = new AtomicReference<>();

            manager.downloadToCache(ATT_ID_1, "/api/media/" + ATT_ID_1, "dup.pdf", "application/pdf",
                    new MediaDownloadManager.Callback() {
                        @Override public void onSuccess(File file) { first.set(file); bothDone.countDown(); }
                        @Override public void onError(String code) { fail("Unexpected error 1: " + code); }
                    });
            assertTrue(inFlight.await(3, TimeUnit.SECONDS));

            // Lượt tải thứ 2 cùng attachmentId trong cùng phiên -> ghép vào callback đang chạy, không gọi HTTP lần 2.
            manager.downloadToCache(ATT_ID_1, "/api/media/" + ATT_ID_1, "dup.pdf", "application/pdf",
                    new MediaDownloadManager.Callback() {
                        @Override public void onSuccess(File file) { second.set(file); bothDone.countDown(); }
                        @Override public void onError(String code) { fail("Unexpected error 2: " + code); }
                    });

            release.countDown();
            assertTrue(bothDone.await(5, TimeUnit.SECONDS));
            assertEquals("Chỉ được gọi mạng đúng 1 lần khi tải trùng song song", 1, networkHits[0]);
            assertEquals(first.get(), second.get());

            // Kiểm tra chặn URL lách đường dẫn/query/fragment/non-UUID (M-08).
            String[] badUrls = {
                    "/api/media/" + ATT_ID_1 + "?token=leak",
                    "/api/media/" + ATT_ID_1 + "#frag",
                    "/api/media/%2e%2e/users/me",
                    "/api/media/not-a-uuid"
            };
            for (String bad : badUrls) {
                try {
                    com.utechat.data.remote.api.ApiClient.resolveBackendMediaUrl("http://10.0.2.2:8080/", bad);
                    fail("Phải từ chối đường dẫn media không hợp lệ: " + bad);
                } catch (IllegalArgumentException expected) {
                    // Đúng kỳ vọng.
                }
            }
        } finally {
            netPool.shutdownNow();
        }
    }
}
