package com.utechat.backend.media;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utechat.backend.security.JwtService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Bộ kiểm thử tích hợp trên EmbeddedPostgres cho Plan 06-01:
 * - Upload tệp an toàn (JPEG, WebP VP8X, M4A AUDIO, MP4 VIDEO, PDF, OOXML DOCX, UTF-8 TXT; làm sạch tên file nguy hiểm).
 * - Chặn vượt ngưỡng dung lượng (413), giả mạo/HTML/SVG/PE/ELF/APK (415), thời lượng audio ISOBMFF > 300s (400).
 * - Giới hạn tần suất 20 uploads/phút/user (429) và miễn trừ idempotent replay.
 * - Đua upload đồng thời cùng clientId chỉ công bố 1 tệp và cho phép người dùng khác dùng cùng clientId.
 * - Tải tệp HTTP Range streaming (200, 206, 416 out-of-bounds/malformed/multi-range, header nosniff).
 * - Kiểm soát quyền truy cập tải tệp RBAC: chưa gắn tin nhắn, ngoài hội thoại, rời nhóm, thêm lại nhóm (visible_from_message_id).
 * - Gửi tin nhắn media (idempotent replay, cross-user 403, mismatch kind 400, tái sử dụng 409, GROUP tenure 409) & LOCATION.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MediaStorageAndRangeApiTest {
    private static final EmbeddedPostgres PG;

    @TempDir
    static Path tempMediaDir;

    static {
        try {
            PG = EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "");
        r.add("spring.flyway.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        r.add("spring.flyway.user", () -> "postgres");
        r.add("spring.flyway.password", () -> "");
        r.add("jwt.secret", () -> "utechat-phase6-test-jwt-secret-0123456789abcdef");
        r.add("app.media.storage-dir", () -> tempMediaDir.toAbsolutePath().toString());
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwt;

    private TestUser alice;
    private TestUser bob;
    private TestUser charlie;

    @BeforeEach
    void resetUsers() {
        alice = createActiveUser("+8491" + randomDigits(7), "Alice");
        bob = createActiveUser("+8492" + randomDigits(7), "Bob");
        charlie = createActiveUser("+8493" + randomDigits(7), "Charlie");
    }

    @Test
    void uploadValidMediaTypesAndVerifyDiskStorage() throws Exception {
        // 1. Upload ảnh JPEG hợp lệ (120x90)
        byte[] jpegBytes = createSampleJpeg(120, 90);
        MockMultipartFile imageFile = new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegBytes);
        UUID clientUploadId = UUID.randomUUID();

        MvcResult imgRes = mvc.perform(multipart("/api/media")
                        .file(imageFile)
                        .param("clientId", clientUploadId.toString())
                        .param("kind", "IMAGE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode imgNode = json.readTree(imgRes.getResponse().getContentAsString());
        String mediaUrl = imgNode.get("mediaUrl").asText();
        assertTrue(mediaUrl.startsWith("/api/media/"));
        assertEquals("image/jpeg", imgNode.get("mime").asText());
        assertEquals(jpegBytes.length, imgNode.get("sizeBytes").asLong());
        assertEquals(120, imgNode.get("width").asInt());
        assertEquals(90, imgNode.get("height").asInt());

        UUID attachmentId = UUID.fromString(mediaUrl.substring("/api/media/".length()));
        String storagePath = jdbc.queryForObject(
                "SELECT storage_path FROM attachments WHERE id = ?",
                String.class,
                attachmentId);
        assertNotNull(storagePath);
        Path physicalFile = tempMediaDir.resolve(storagePath);
        assertTrue(Files.exists(physicalFile));
        assertEquals(jpegBytes.length, Files.size(physicalFile));

        // 2. Upload ảnh WebP VP8X kích thước 512x512 (low byte 0xFF kiểm chứng sửa lỗi ưu tiên toán tử)
        byte[] webpBytes = createSampleWebpVp8x(512, 512);
        MockMultipartFile webpFile = new MockMultipartFile("file", "banner.webp", "image/webp", webpBytes);
        MvcResult webpRes = mvc.perform(multipart("/api/media")
                        .file(webpFile)
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "IMAGE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode webpNode = json.readTree(webpRes.getResponse().getContentAsString());
        assertEquals("image/webp", webpNode.get("mime").asText());
        assertEquals(512, webpNode.get("width").asInt());
        assertEquals(512, webpNode.get("height").asInt());

        // 3. Upload AUDIO M4A hợp lệ (ISOBMFF ftyp + moov/mvhd 12_000 ms)
        byte[] m4aBytes = createSampleM4aWithDurationMs(12_000);
        MockMultipartFile audioFile = new MockMultipartFile("file", "voice.m4a", "audio/mp4", m4aBytes);
        MvcResult audioRes = mvc.perform(multipart("/api/media")
                        .file(audioFile)
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "AUDIO")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode audioNode = json.readTree(audioRes.getResponse().getContentAsString());
        assertEquals("audio/mp4", audioNode.get("mime").asText());
        assertEquals(12_000, audioNode.get("durationMs").asInt());

        // 4. Upload VIDEO MP4 hợp lệ (ftypisom)
        byte[] mp4Bytes = createSampleMp4Video();
        MockMultipartFile videoFile = new MockMultipartFile("file", "clip.mp4", "video/mp4", mp4Bytes);
        MvcResult videoRes = mvc.perform(multipart("/api/media")
                        .file(videoFile)
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "VIDEO")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode videoNode = json.readTree(videoRes.getResponse().getContentAsString());
        assertEquals("video/mp4", videoNode.get("mime").asText());

        // 5. Upload PDF kèm tên chứa path traversal & ký tự NUL -> làm sạch an toàn, không văng 500
        byte[] pdfBytes = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        MockMultipartFile pdfFile = new MockMultipartFile("file", "../../evil\0doc.pdf", "application/pdf", pdfBytes);
        MvcResult pdfRes = mvc.perform(multipart("/api/media")
                        .file(pdfFile)
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "FILE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode pdfNode = json.readTree(pdfRes.getResponse().getContentAsString());
        assertEquals("application/pdf", pdfNode.get("mime").asText());
        assertEquals("evil_doc.pdf", pdfNode.get("fileName").asText());

        // 6. Upload OOXML .docx -> nhận diện đúng MIME Word thay vì application/zip
        byte[] docxBytes = createZipBytes(Map.of(
                "[Content_Types].xml", "<Types/>",
                "word/document.xml", "<w:document/>"));
        MockMultipartFile docxFile = new MockMultipartFile("file", "report.docx", "application/octet-stream", docxBytes);
        MvcResult docxRes = mvc.perform(multipart("/api/media")
                        .file(docxFile)
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "FILE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode docxNode = json.readTree(docxRes.getResponse().getContentAsString());
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docxNode.get("mime").asText());

        // 7. Upload tệp UTF-8 thuần
        byte[] txtBytes = "Xin chào UTEChat Phase 6".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile txtFile = new MockMultipartFile("file", "note.txt", "text/plain", txtBytes);
        mvc.perform(multipart("/api/media")
                        .file(txtFile)
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "FILE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mime").value("text/plain"));
    }

    @Test
    void rejectsOversizedUploadWith413() throws Exception {
        byte[] largeBytes = new byte[10 * 1024 * 1024 + 1024];
        largeBytes[0] = (byte) 0xFF;
        largeBytes[1] = (byte) 0xD8;
        largeBytes[2] = (byte) 0xFF;

        MockMultipartFile largeFile = new MockMultipartFile("file", "huge.jpg", "image/jpeg", largeBytes);

        mvc.perform(multipart("/api/media")
                        .file(largeFile)
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "IMAGE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("FILE_TOO_LARGE"));
    }

    @Test
    void rejectsSpoofedMagicBytesHtmlSvgApkAndExecutablesWith415() throws Exception {
        // 1. SVG giả danh ảnh JPEG (IMAGE) -> 415
        byte[] svgBytes = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile fakeImage = new MockMultipartFile("file", "avatar.jpg", "image/jpeg", svgBytes);
        mvc.perform(multipart("/api/media")
                        .file(fakeImage)
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "IMAGE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_TYPE"));

        // 2. HTML gửi qua kind=FILE -> 415 (không được lọt qua kiểm tra UTF-8 text)
        byte[] htmlBytes = "<!DOCTYPE html><html><body><script>alert(1)</script></body></html>".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile htmlFile = new MockMultipartFile("file", "page.html", "text/plain", htmlBytes);
        mvc.perform(multipart("/api/media")
                        .file(htmlFile)
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "FILE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_TYPE"));

        // 3. SVG gửi qua kind=FILE (có kèm UTF-8 BOM) -> 415
        byte[] bomSvg = new byte[3 + svgBytes.length];
        bomSvg[0] = (byte) 0xEF;
        bomSvg[1] = (byte) 0xBB;
        bomSvg[2] = (byte) 0xBF;
        System.arraycopy(svgBytes, 0, bomSvg, 3, svgBytes.length);
        MockMultipartFile svgAsFile = new MockMultipartFile("file", "icon.svg", "text/plain", bomSvg);
        mvc.perform(multipart("/api/media")
                        .file(svgAsFile)
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "FILE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_TYPE"));

        // 4. File thực thi Windows PE (MZ) -> 415
        byte[] exeBytes = "MZ\0\0dummyexecutabledata".getBytes(StandardCharsets.ISO_8859_1);
        MockMultipartFile exeFile = new MockMultipartFile("file", "tool.exe", "application/octet-stream", exeBytes);
        mvc.perform(multipart("/api/media")
                        .file(exeFile)
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "FILE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_TYPE"));

        // 5. File thực thi Linux ELF -> 415
        byte[] elfBytes = new byte[]{0x7F, 'E', 'L', 'F', 2, 1, 1, 0};
        MockMultipartFile elfFile = new MockMultipartFile("file", "bin", "application/octet-stream", elfBytes);
        mvc.perform(multipart("/api/media")
                        .file(elfFile)
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "FILE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_TYPE"));

        // 6. Gói APK giả danh ZIP (chứa AndroidManifest.xml) -> 415
        byte[] apkBytes = createZipBytes(Map.of(
                "AndroidManifest.xml", "<manifest/>",
                "classes.dex", "dex"));
        MockMultipartFile apkFile = new MockMultipartFile("file", "app.zip", "application/zip", apkBytes);
        mvc.perform(multipart("/api/media")
                        .file(apkFile)
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "FILE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_TYPE"));
    }

    @Test
    void rejectsAudioDurationOver300SecondsWith400() throws Exception {
        // 1. ADTS AAC có durationMs hint > 300_000 ms -> 400
        byte[] aacBytes = new byte[]{(byte) 0xFF, (byte) 0xF1, 0, 0};
        MockMultipartFile audioFile = new MockMultipartFile("file", "voice.aac", "audio/aac", aacBytes);
        mvc.perform(multipart("/api/media")
                        .file(audioFile)
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "AUDIO")
                        .param("durationMs", "300001")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));

        // 2. ISOBMFF M4A có box mvhd duration = 301_000 ms (không gửi durationMs hint) -> server tự đọc mvhd và từ chối 400
        byte[] longM4a = createSampleM4aWithDurationMs(301_000);
        MockMultipartFile m4aFile = new MockMultipartFile("file", "long.m4a", "audio/mp4", longM4a);
        mvc.perform(multipart("/api/media")
                        .file(m4aFile)
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "AUDIO")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));
    }

    @Test
    void idempotentUploadConcurrentRaceAndCrossUserClientIdIsolation() throws Exception {
        byte[] jpegBytes = createSampleJpeg(50, 50);
        MockMultipartFile file = new MockMultipartFile("file", "icon.jpg", "image/jpeg", jpegBytes);
        UUID clientId = UUID.randomUUID();

        // Lần 1: 201 Created
        MvcResult res1 = mvc.perform(multipart("/api/media")
                        .file(file)
                        .param("clientId", clientId.toString())
                        .param("kind", "IMAGE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isCreated())
                .andReturn();
        String url1 = json.readTree(res1.getResponse().getContentAsString()).get("mediaUrl").asText();

        // Lần 2 (replay cùng clientId & kind): 200 OK cùng mediaUrl
        MvcResult res2 = mvc.perform(multipart("/api/media")
                        .file(file)
                        .param("clientId", clientId.toString())
                        .param("kind", "IMAGE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(url1, json.readTree(res2.getResponse().getContentAsString()).get("mediaUrl").asText());

        // Lần 3 (đổi sang loại FILE cùng clientId): 409 CONFLICT
        mvc.perform(multipart("/api/media")
                        .file(file)
                        .param("clientId", clientId.toString())
                        .param("kind", "FILE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));

        // Bob dùng đúng cùng clientId của Alice -> vẫn tạo mới độc lập (201) với mediaUrl khác
        MvcResult bobRes = mvc.perform(multipart("/api/media")
                        .file(file)
                        .param("clientId", clientId.toString())
                        .param("kind", "IMAGE")
                        .header("Authorization", "Bearer " + bob.token))
                .andExpect(status().isCreated())
                .andReturn();
        String bobUrl = json.readTree(bobRes.getResponse().getContentAsString()).get("mediaUrl").asText();
        assertNotEquals(url1, bobUrl);

        // Đua đồng thời 2 luồng upload cùng (charlie, raceClientId): đúng 1 hàng DB và trả cùng mediaUrl
        UUID raceClientId = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Future<MvcResult> f1 = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return mvc.perform(multipart("/api/media")
                                .file(new MockMultipartFile("file", "race.jpg", "image/jpeg", jpegBytes))
                                .param("clientId", raceClientId.toString())
                                .param("kind", "IMAGE")
                                .header("Authorization", "Bearer " + charlie.token))
                        .andReturn();
            });
            Future<MvcResult> f2 = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return mvc.perform(multipart("/api/media")
                                .file(new MockMultipartFile("file", "race.jpg", "image/jpeg", jpegBytes))
                                .param("clientId", raceClientId.toString())
                                .param("kind", "IMAGE")
                                .header("Authorization", "Bearer " + charlie.token))
                        .andReturn();
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();

            MvcResult r1 = f1.get(10, TimeUnit.SECONDS);
            MvcResult r2 = f2.get(10, TimeUnit.SECONDS);
            int s1 = r1.getResponse().getStatus();
            int s2 = r2.getResponse().getStatus();
            assertTrue((s1 == 201 && s2 == 200) || (s1 == 200 && s2 == 201),
                    "Kỳ vọng một luồng 201 và một luồng 200, thực tế: " + s1 + " và " + s2);
            String u1 = json.readTree(r1.getResponse().getContentAsString()).get("mediaUrl").asText();
            String u2 = json.readTree(r2.getResponse().getContentAsString()).get("mediaUrl").asText();
            assertEquals(u1, u2);
            Integer rowCount = jdbc.queryForObject(
                    "SELECT count(*) FROM public.attachments WHERE uploader_id = ? AND upload_client_id = ?",
                    Integer.class,
                    charlie.id, raceClientId);
            assertEquals(1, rowCount);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void enforcesUploadRateLimit20PerMinuteAndExemptsIdempotentReplay() throws Exception {
        byte[] pdfBytes = "%PDF-1.4 rate-limit-test".getBytes(StandardCharsets.US_ASCII);
        UUID firstClientId = UUID.randomUUID();

        // 20 lần upload mới liên tiếp đều thành công 201
        for (int i = 0; i < 20; i++) {
            UUID cid = (i == 0) ? firstClientId : UUID.randomUUID();
            mvc.perform(multipart("/api/media")
                            .file(new MockMultipartFile("file", "f" + i + ".pdf", "application/pdf", pdfBytes))
                            .param("clientId", cid.toString())
                            .param("kind", "FILE")
                            .header("Authorization", "Bearer " + alice.token))
                    .andExpect(status().isCreated());
        }

        // Replay lại clientId đã upload trước đó vẫn phải trả 200 OK (không bị chặn bởi rate limit)
        mvc.perform(multipart("/api/media")
                        .file(new MockMultipartFile("file", "f0.pdf", "application/pdf", pdfBytes))
                        .param("clientId", firstClientId.toString())
                        .param("kind", "FILE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isOk());

        // Lần upload mới thứ 21 trong cùng 60 giây -> 429 RATE_LIMITED
        mvc.perform(multipart("/api/media")
                        .file(new MockMultipartFile("file", "f21.pdf", "application/pdf", pdfBytes))
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "FILE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void downloadWithHttpRange206And416EdgeCasesAndNosniffHeader() throws Exception {
        byte[] pdfBytes = "%PDF-1.4 sample content for range streaming testing 1234567890abcdef".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", pdfBytes);

        MvcResult uploadRes = mvc.perform(multipart("/api/media")
                        .file(file)
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "FILE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isCreated())
                .andReturn();
        String mediaUrl = json.readTree(uploadRes.getResponse().getContentAsString()).get("mediaUrl").asText();

        // 1. Tải toàn bộ -> 200 OK
        MvcResult fullRes = mvc.perform(get(mediaUrl)
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isOk())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, String.valueOf(pdfBytes.length)))
                .andReturn();
        assertArrayEquals(pdfBytes, fullRes.getResponse().getContentAsByteArray());

        // 2. Range hợp lệ bytes=0-9 -> 206 Partial Content
        MvcResult rangeRes = mvc.perform(get(mediaUrl)
                        .header("Range", "bytes=0-9")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Range", "bytes 0-9/" + pdfBytes.length))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "10"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();
        assertArrayEquals(java.util.Arrays.copyOfRange(pdfBytes, 0, 10), rangeRes.getResponse().getContentAsByteArray());

        // 3. Range vượt dung lượng -> 416
        mvc.perform(get(mediaUrl)
                        .header("Range", "bytes=999999-")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */" + pdfBytes.length));

        // 4. Range sai cú pháp -> 416
        mvc.perform(get(mediaUrl)
                        .header("Range", "bytes=invalid")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */" + pdfBytes.length));

        // 5. Multi-range -> 416
        mvc.perform(get(mediaUrl)
                        .header("Range", "bytes=0-1, 2-3")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */" + pdfBytes.length));
    }

    @Test
    void downloadMediaEnforcesUnattachedDirectRemovedAndRejoinedGroupRbac() throws Exception {
        makeFriends(alice.id, bob.id);
        makeFriends(alice.id, charlie.id);
        String directConvId = openDirect(alice, bob);

        // 1. Tệp chưa gắn vào tin nhắn nào: chỉ uploader (Alice) tải được, Bob nhận 403
        String unattachedUrl = uploadSampleImage(alice);
        mvc.perform(get(unattachedUrl).header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isOk());
        mvc.perform(get(unattachedUrl).header("Authorization", "Bearer " + bob.token))
                .andExpect(status().isForbidden());

        // 2. Gắn tệp vào hội thoại 1-1 Alice-Bob -> Bob 200, Charlie (ngoài hội thoại) 403
        mvc.perform(post("/api/conversations/" + directConvId + "/messages")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "clientId", UUID.randomUUID().toString(),
                                "type", "IMAGE",
                                "mediaUrl", unattachedUrl))))
                .andExpect(status().isCreated());
        mvc.perform(get(unattachedUrl).header("Authorization", "Bearer " + bob.token))
                .andExpect(status().isOk());
        mvc.perform(get(unattachedUrl).header("Authorization", "Bearer " + charlie.token))
                .andExpect(status().isForbidden());

        // 3. Nhóm chat 3 người (Alice ADMIN, Bob, Charlie): kiểm tra rời nhóm + thêm lại nhóm (visible_from_message_id)
        String groupId = createGroup(alice, "Media RBAC Group", List.of(bob.id.toString(), charlie.id.toString()));
        String preRejoinImg = uploadSampleImage(alice);
        mvc.perform(post("/api/conversations/" + groupId + "/messages")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "clientId", UUID.randomUUID().toString(),
                                "type", "IMAGE",
                                "mediaUrl", preRejoinImg,
                                "expectedVisibleFromMessageId", 0))))
                .andExpect(status().isCreated());

        // Khi còn trong nhóm -> Bob tải được 200
        mvc.perform(get(preRejoinImg).header("Authorization", "Bearer " + bob.token))
                .andExpect(status().isOk());

        // Alice xóa Bob khỏi nhóm -> Bob không còn tải được (403)
        mvc.perform(delete("/api/conversations/" + groupId + "/members/" + bob.id)
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isNoContent());
        mvc.perform(get(preRejoinImg).header("Authorization", "Bearer " + bob.token))
                .andExpect(status().isForbidden());

        // Alice thêm Bob lại vào nhóm -> visible_from_message_id mới > preRejoinImg.messageId -> Bob vẫn nhận 403 cho ảnh cũ!
        mvc.perform(post("/api/conversations/" + groupId + "/members")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("userIds", List.of(bob.id.toString())))))
                .andExpect(status().isNoContent());
        mvc.perform(get(preRejoinImg).header("Authorization", "Bearer " + bob.token))
                .andExpect(status().isForbidden());

        // Ảnh mới gửi SAU khi Bob quay lại nhóm -> Bob tải được 200
        String postRejoinImg = uploadSampleImage(alice);
        mvc.perform(post("/api/conversations/" + groupId + "/messages")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "clientId", UUID.randomUUID().toString(),
                                "type", "IMAGE",
                                "mediaUrl", postRejoinImg,
                                "expectedVisibleFromMessageId", 0))))
                .andExpect(status().isCreated());
        mvc.perform(get(postRejoinImg).header("Authorization", "Bearer " + bob.token))
                .andExpect(status().isOk());
    }

    @Test
    void sendMessageMediaValidationReplayCrossUserKindMismatchGroupTenureAndLocation() throws Exception {
        makeFriends(alice.id, bob.id);
        makeFriends(alice.id, charlie.id);
        String convId = openDirect(alice, bob);

        // 1. Gửi tin nhắn IMAGE thành công và replay cùng clientId trả 200 với đúng id ban đầu
        String mediaUrl = uploadSampleImage(alice);
        UUID clientMsgId = UUID.randomUUID();
        MvcResult msgRes = mvc.perform(post("/api/conversations/" + convId + "/messages")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "clientId", clientMsgId.toString(),
                                "type", "IMAGE",
                                "text", "Check out this cat",
                                "mediaUrl", mediaUrl))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode msgNode = json.readTree(msgRes.getResponse().getContentAsString());
        long originalMsgId = msgNode.get("id").asLong();
        assertEquals("IMAGE", msgNode.get("type").asText());
        assertEquals("Check out this cat", msgNode.get("text").asText());
        assertEquals(mediaUrl, msgNode.get("mediaUrl").asText());

        MvcResult replayRes = mvc.perform(post("/api/conversations/" + convId + "/messages")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "clientId", clientMsgId.toString(),
                                "type", "IMAGE",
                                "text", "Check out this cat",
                                "mediaUrl", mediaUrl))))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(originalMsgId, json.readTree(replayRes.getResponse().getContentAsString()).get("id").asLong());

        // 2. Chống tái sử dụng attachment đã gắn với clientId mới -> 409 CONFLICT
        mvc.perform(post("/api/conversations/" + convId + "/messages")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "clientId", UUID.randomUUID().toString(),
                                "type", "IMAGE",
                                "mediaUrl", mediaUrl))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));

        // 3. Cross-user attachmentId: Bob không được gắn tệp do Alice upload -> 403 FORBIDDEN
        String aliceUnusedImg = uploadSampleImage(alice);
        mvc.perform(post("/api/conversations/" + convId + "/messages")
                        .header("Authorization", "Bearer " + bob.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "clientId", UUID.randomUUID().toString(),
                                "type", "IMAGE",
                                "mediaUrl", aliceUnusedImg))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        // 4. Lệch loại tệp (upload FILE PDF nhưng gửi type=IMAGE) -> 400 VALIDATION
        byte[] pdfBytes = "%PDF-1.4 mismatch".getBytes(StandardCharsets.US_ASCII);
        MvcResult pdfUpload = mvc.perform(multipart("/api/media")
                        .file(new MockMultipartFile("file", "doc.pdf", "application/pdf", pdfBytes))
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "FILE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isCreated())
                .andReturn();
        String pdfUrl = json.readTree(pdfUpload.getResponse().getContentAsString()).get("mediaUrl").asText();
        mvc.perform(post("/api/conversations/" + convId + "/messages")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "clientId", UUID.randomUUID().toString(),
                                "type", "IMAGE",
                                "mediaUrl", pdfUrl))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));

        // 5. GROUP expectedVisibleFromMessageId lỗi thời sau khi bị xóa và thêm lại -> 409 MEMBERSHIP_CHANGED
        String groupId = createGroup(alice, "Tenure Group", List.of(bob.id.toString(), charlie.id.toString()));
        mvc.perform(delete("/api/conversations/" + groupId + "/members/" + bob.id)
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/conversations/" + groupId + "/members")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("userIds", List.of(bob.id.toString())))))
                .andExpect(status().isNoContent());

        String bobImg = uploadSampleImage(bob);
        mvc.perform(post("/api/conversations/" + groupId + "/messages")
                        .header("Authorization", "Bearer " + bob.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "clientId", UUID.randomUUID().toString(),
                                "type", "IMAGE",
                                "mediaUrl", bobImg,
                                "expectedVisibleFromMessageId", 0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_CHANGED"));

        // 6. LOCATION hợp lệ -> 201 và tọa độ ngoài biên -> 400 VALIDATION
        MvcResult locRes = mvc.perform(post("/api/conversations/" + convId + "/messages")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "clientId", UUID.randomUUID().toString(),
                                "type", "LOCATION",
                                "latitude", 10.7769,
                                "longitude", 106.7009,
                                "text", "Ho Chi Minh City"))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode locNode = json.readTree(locRes.getResponse().getContentAsString());
        assertEquals("LOCATION", locNode.get("type").asText());
        assertEquals(10.7769, locNode.get("latitude").asDouble(), 0.0001);
        assertEquals(106.7009, locNode.get("longitude").asDouble(), 0.0001);

        mvc.perform(post("/api/conversations/" + convId + "/messages")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "clientId", UUID.randomUUID().toString(),
                                "type", "LOCATION",
                                "latitude", 95.0,
                                "longitude", 100.0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));
    }

    // ======================== HELPERS ========================

    private String uploadSampleImage(TestUser user) throws Exception {
        byte[] jpegBytes = createSampleJpeg(60, 40);
        MvcResult res = mvc.perform(multipart("/api/media")
                        .file(new MockMultipartFile("file", "img.jpg", "image/jpeg", jpegBytes))
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "IMAGE")
                        .header("Authorization", "Bearer " + user.token))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("mediaUrl").asText();
    }

    private byte[] createSampleJpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", baos);
        return baos.toByteArray();
    }

    /** Tạo header WebP VP8X hợp lệ 30 byte với width/height tùy chọn (kể cả low byte = 0xFF). */
    private static byte[] createSampleWebpVp8x(int width, int height) {
        byte[] data = new byte[30];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, data, 0, 4);
        data[4] = 22;
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, data, 8, 4);
        System.arraycopy("VP8X".getBytes(StandardCharsets.US_ASCII), 0, data, 12, 4);
        data[16] = 10;
        int w = width - 1;
        int h = height - 1;
        data[24] = (byte) (w & 0xFF);
        data[25] = (byte) ((w >> 8) & 0xFF);
        data[26] = (byte) ((w >> 16) & 0xFF);
        data[27] = (byte) (h & 0xFF);
        data[28] = (byte) ((h >> 8) & 0xFF);
        data[29] = (byte) ((h >> 16) & 0xFF);
        return data;
    }

    /** Tạo tệp M4A ISOBMFF tối thiểu gồm box ftyp(M4A ) và box moov/mvhd(version 0, timescale=1000). */
    private static byte[] createSampleM4aWithDurationMs(int durationMs) {
        ByteBuffer buf = ByteBuffer.allocate(20 + 8 + 36);
        // ftyp box (20 bytes)
        buf.putInt(20);
        buf.put("ftyp".getBytes(StandardCharsets.US_ASCII));
        buf.put("M4A ".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(0);
        buf.put("M4A ".getBytes(StandardCharsets.US_ASCII));
        // moov box (8 + 36 = 44 bytes)
        buf.putInt(44);
        buf.put("moov".getBytes(StandardCharsets.US_ASCII));
        // mvhd box (36 bytes: 8 header + 28 payload)
        buf.putInt(36);
        buf.put("mvhd".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(0); // version=0, flags=0
        buf.putInt(0); // creation_time
        buf.putInt(0); // modification_time
        buf.putInt(1000); // timescale = 1000 ticks/s
        buf.putInt(durationMs); // duration in ticks (= ms)
        buf.putInt(0);
        buf.putInt(0);
        return buf.array();
    }

    private static byte[] createSampleMp4Video() {
        ByteBuffer buf = ByteBuffer.allocate(20);
        buf.putInt(20);
        buf.put("ftyp".getBytes(StandardCharsets.US_ASCII));
        buf.put("isom".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(0);
        buf.put("isom".getBytes(StandardCharsets.US_ASCII));
        return buf.array();
    }

    private static byte[] createZipBytes(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private String openDirect(TestUser caller, TestUser peer) throws Exception {
        MvcResult res = mvc.perform(post("/api/conversations/direct")
                        .header("Authorization", "Bearer " + caller.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("userId", peer.id.toString()))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asText();
    }

    private String createGroup(TestUser admin, String title, List<String> memberIds) throws Exception {
        MvcResult res = mvc.perform(post("/api/conversations/group")
                        .header("Authorization", "Bearer " + admin.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", title, "memberIds", memberIds))))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asText();
    }

    private void makeFriends(UUID userA, UUID userB) {
        jdbc.update("INSERT INTO contacts (owner_id, contact_user_id, is_friend, blocked) VALUES (?, ?, TRUE, FALSE) ON CONFLICT (owner_id, contact_user_id) DO UPDATE SET is_friend = TRUE", userA, userB);
        jdbc.update("INSERT INTO contacts (owner_id, contact_user_id, is_friend, blocked) VALUES (?, ?, TRUE, FALSE) ON CONFLICT (owner_id, contact_user_id) DO UPDATE SET is_friend = TRUE", userB, userA);
    }

    private TestUser createActiveUser(String phone, String name) {
        UUID userId = UUID.randomUUID();
        String deviceHash = (userId.toString().replace("-", "") + userId.toString().replace("-", ""));
        Instant now = Instant.now();
        jdbc.update(
                "INSERT INTO users (id, phone_e164, password_hash, display_name, status, allow_messages_from_non_contacts) "
                        + "VALUES (?, ?, '$2a$10$dummyhashdummyhashdummyhashdummyhashdummyhashdummyha', ?, 'ACTIVE', TRUE)",
                userId, phone, name);
        jdbc.update(
                "INSERT INTO user_devices (user_id, device_id_hash, device_name, trusted, token_valid_after) "
                        + "VALUES (?, ?, 'TestPhone', TRUE, ?)",
                userId, deviceHash, java.sql.Timestamp.from(now.minusSeconds(5)));
        String token = jwt.issueAccessToken(userId, deviceHash);
        return new TestUser(userId, token);
    }

    private static String randomDigits(int len) {
        String s = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        while (s.length() < len) s = s + "7";
        return s.substring(0, len);
    }

    private record TestUser(UUID id, String token) {
    }
}
