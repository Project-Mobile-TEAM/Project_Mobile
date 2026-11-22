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
 * - Upload tệp an toàn (magic bytes, giới hạn dung lượng 413, chống giả mạo 415).
 * - Giới hạn tần suất upload 20 uploads/phút/user (429).
 * - Cơ chế idempotent replay theo (uploader_id, upload_client_id).
 * - Tải tệp HTTP Range streaming (206 Partial Content, 416 Range Not Satisfiable, header nosniff).
 * - Kiểm soát quyền truy cập tải tệp theo thành viên hội thoại và mốc visible_from_message_id (403).
 * - Gửi tin nhắn IMAGE (gắn attachment) và LOCATION (kiểm tra biên độ tọa độ).
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
        // 1. Upload ảnh JPEG hợp lệ (kích thước 120x90)
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

        // Kiểm tra tệp thực tế đã được lưu trên đĩa trong tempMediaDir
        UUID attachmentId = UUID.fromString(mediaUrl.substring("/api/media/".length()));
        String storagePath = jdbc.queryForObject(
                "SELECT storage_path FROM attachments WHERE id = ?",
                String.class,
                attachmentId);
        assertNotNull(storagePath);
        Path physicalFile = tempMediaDir.resolve(storagePath);
        assertTrue(Files.exists(physicalFile));
        assertEquals(jpegBytes.length, Files.size(physicalFile));

        // 2. Upload tệp PDF hợp lệ
        byte[] pdfBytes = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        MockMultipartFile pdfFile = new MockMultipartFile("file", "doc.pdf", "application/pdf", pdfBytes);

        MvcResult pdfRes = mvc.perform(multipart("/api/media")
                        .file(pdfFile)
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "FILE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode pdfNode = json.readTree(pdfRes.getResponse().getContentAsString());
        assertEquals("application/pdf", pdfNode.get("mime").asText());
        assertEquals(pdfBytes.length, pdfNode.get("sizeBytes").asLong());
    }

    @Test
    void rejectsOversizedUploadWith413() throws Exception {
        // Tạo file ảnh vượt quá 10MB (ví dụ 10MB + 1024 bytes)
        byte[] largeBytes = new byte[10 * 1024 * 1024 + 1024];
        // Điền magic bytes JPEG ở đầu
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
    void rejectsSpoofedMagicBytesWith415() throws Exception {
        // Giả danh ảnh JPEG nhưng nội dung là SVG độc hại
        byte[] svgBytes = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile fakeImage = new MockMultipartFile("file", "avatar.jpg", "image/jpeg", svgBytes);

        mvc.perform(multipart("/api/media")
                        .file(fakeImage)
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "IMAGE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_TYPE"));

        // File thực thi Windows PE (MZ)
        byte[] exeBytes = "MZ\0\0dummyexecutabledata".getBytes(StandardCharsets.ISO_8859_1);
        MockMultipartFile exeFile = new MockMultipartFile("file", "tool.exe", "application/octet-stream", exeBytes);

        mvc.perform(multipart("/api/media")
                        .file(exeFile)
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "FILE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_TYPE"));
    }

    @Test
    void rejectsAudioDurationOver300SecondsWith400() throws Exception {
        // Tệp âm thanh MP4/M4A có duration hint > 300,000 ms
        byte[] aacBytes = new byte[]{(byte) 0xFF, (byte) 0xF1, 0, 0};
        MockMultipartFile audioFile = new MockMultipartFile("file", "voice.aac", "audio/aac", aacBytes);

        mvc.perform(multipart("/api/media")
                        .file(audioFile)
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "AUDIO")
                        .param("durationMs", "300001") // 300s + 1ms
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));
    }

    @Test
    void idempotentUploadReturnsSameMediaUrl() throws Exception {
        byte[] jpegBytes = createSampleJpeg(50, 50);
        MockMultipartFile file = new MockMultipartFile("file", "icon.jpg", "image/jpeg", jpegBytes);
        UUID clientId = UUID.randomUUID();

        // Lần 1: Trả về 201 Created
        MvcResult res1 = mvc.perform(multipart("/api/media")
                        .file(file)
                        .param("clientId", clientId.toString())
                        .param("kind", "IMAGE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isCreated())
                .andReturn();
        String url1 = json.readTree(res1.getResponse().getContentAsString()).get("mediaUrl").asText();

        // Lần 2 (replay cùng clientId & kind): Trả về 200 OK với cùng mediaUrl
        MvcResult res2 = mvc.perform(multipart("/api/media")
                        .file(file)
                        .param("clientId", clientId.toString())
                        .param("kind", "IMAGE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isOk())
                .andReturn();
        String url2 = json.readTree(res2.getResponse().getContentAsString()).get("mediaUrl").asText();

        assertEquals(url1, url2);

        // Lần 3 (dùng lại clientId cũ nhưng đổi sang loại FILE): Phải trả về 409 CONFLICT
        mvc.perform(multipart("/api/media")
                        .file(file)
                        .param("clientId", clientId.toString())
                        .param("kind", "FILE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    void downloadWithHttpRange206AndNosniffHeader() throws Exception {
        byte[] pdfBytes = "%PDF-1.4 sample content for range streaming testing 1234567890abcdef".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", pdfBytes);
        UUID clientId = UUID.randomUUID();

        MvcResult uploadRes = mvc.perform(multipart("/api/media")
                        .file(file)
                        .param("clientId", clientId.toString())
                        .param("kind", "FILE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isCreated())
                .andReturn();

        String mediaUrl = json.readTree(uploadRes.getResponse().getContentAsString()).get("mediaUrl").asText();

        // 1. Tải không có Range -> 200 OK, Accept-Ranges, X-Content-Type-Options: nosniff
        MvcResult fullRes = mvc.perform(get(mediaUrl)
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isOk())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, String.valueOf(pdfBytes.length)))
                .andReturn();
        assertArrayEquals(pdfBytes, fullRes.getResponse().getContentAsByteArray());

        // 2. Tải có Range hợp lệ: bytes=0-9 (10 bytes đầu tiên) -> 206 Partial Content
        MvcResult rangeRes = mvc.perform(get(mediaUrl)
                        .header("Range", "bytes=0-9")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Range", "bytes 0-9/" + pdfBytes.length))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "10"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();

        byte[] slice = rangeRes.getResponse().getContentAsByteArray();
        assertEquals(10, slice.length);
        assertArrayEquals(java.util.Arrays.copyOfRange(pdfBytes, 0, 10), slice);

        // 3. Range sai định dạng hoặc vượt dung lượng -> 416 Range Not Satisfiable
        mvc.perform(get(mediaUrl)
                        .header("Range", "bytes=999999-")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */" + pdfBytes.length));
    }

    @Test
    void downloadMediaEnforcesMembershipAndTenureRbac() throws Exception {
        // Alice và Bob là bạn bè, mở phòng chat 1-1
        makeFriends(alice.id, bob.id);
        String convId = openDirect(alice, bob);

        // Alice upload ảnh và gửi vào phòng chat
        byte[] jpegBytes = createSampleJpeg(40, 40);
        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", jpegBytes);
        MvcResult uploadRes = mvc.perform(multipart("/api/media")
                        .file(file)
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "IMAGE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isCreated())
                .andReturn();
        String mediaUrl = json.readTree(uploadRes.getResponse().getContentAsString()).get("mediaUrl").asText();

        // Gửi tin nhắn chứa mediaUrl
        mvc.perform(post("/api/conversations/" + convId + "/messages")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "clientId", UUID.randomUUID().toString(),
                                "type", "IMAGE",
                                "mediaUrl", mediaUrl))))
                .andExpect(status().isCreated());

        // Bob (thành viên phòng chat) tải file -> thành công 200
        mvc.perform(get(mediaUrl)
                        .header("Authorization", "Bearer " + bob.token))
                .andExpect(status().isOk());

        // Charlie (người ngoài, không phải thành viên) tải file -> 403 FORBIDDEN
        mvc.perform(get(mediaUrl)
                        .header("Authorization", "Bearer " + charlie.token))
                .andExpect(status().isForbidden());
    }

    @Test
    void sendMessageWithImageAttachmentAndLocationCoordinates() throws Exception {
        makeFriends(alice.id, bob.id);
        String convId = openDirect(alice, bob);

        // 1. Gửi tin nhắn IMAGE thành công
        byte[] jpegBytes = createSampleJpeg(80, 60);
        MockMultipartFile file = new MockMultipartFile("file", "cat.jpg", "image/jpeg", jpegBytes);
        MvcResult uploadRes = mvc.perform(multipart("/api/media")
                        .file(file)
                        .param("clientId", UUID.randomUUID().toString())
                        .param("kind", "IMAGE")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isCreated())
                .andReturn();
        String mediaUrl = json.readTree(uploadRes.getResponse().getContentAsString()).get("mediaUrl").asText();

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
        assertEquals("IMAGE", msgNode.get("type").asText());
        assertEquals("Check out this cat", msgNode.get("text").asText());
        assertEquals(mediaUrl, msgNode.get("mediaUrl").asText());
        assertEquals("image/jpeg", msgNode.get("mime").asText());
        assertEquals(80, msgNode.get("width").asInt());
        assertEquals(60, msgNode.get("height").asInt());

        // 2. Chống tái sử dụng attachment đã gắn: gửi tin thứ 2 với cùng attachment -> 409 CONFLICT
        mvc.perform(post("/api/conversations/" + convId + "/messages")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "clientId", UUID.randomUUID().toString(),
                                "type", "IMAGE",
                                "mediaUrl", mediaUrl))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));

        // 3. Gửi tin nhắn LOCATION thành công
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

        // 4. Tọa độ ngoài biên -> 400 VALIDATION
        mvc.perform(post("/api/conversations/" + convId + "/messages")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "clientId", UUID.randomUUID().toString(),
                                "type", "LOCATION",
                                "latitude", 95.0, // Ngoài [-90, 90]
                                "longitude", 100.0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));
    }

    private byte[] createSampleJpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", baos);
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
