# Phase 6: Media Attachments & Creative Features - Pattern Mapping

**Phase:** 06 — Media Attachments, Notifications & Periodic Sync, 1-1 WebRTC Voice Call + Coturn, On-Device ML Kit Translation  
**Requirement IDs:** `REQ-messaging-media-attachments`, `REQ-notifications-sync`, `REQ-voice-call-webrtc`, `REQ-message-translation`  
**Date:** 2026-11-22  
**Author:** GSD Pattern Mapper  
**Status:** COMPLETE

> **Ghi chú nhánh Git (`feature/phase-5-core` vs `planning`):** Toàn bộ mã nguồn Phase 5 đối chiếu trong tài liệu này được kiểm chứng trực tiếp từ nhánh cục bộ `feature/phase-5-core` bằng lệnh chỉ đọc (`git show feature/phase-5-core:...`). Nhánh làm việc hiện tại là `planning` (chỉ chứa tài liệu kế hoạch và hợp đồng); trước khi lập trình Phase 6, nhánh triển khai cần gộp/rebase trên `feature/phase-5-core` để có sẵn `AppDatabase` v2, `SendMessageWorker`, `SyncEngine`, `ChatWebSocketManager`, `MessageService` và `MessageAdapter`.

---

## 1. Component Inventory (Danh sách đầy đủ Tệp Tạo mới & Chỉnh sửa)

Danh sách rút trích đầy đủ từ `06-CONTEXT.md` (`D-01`..`D-15`) và `06-RESEARCH.md`, phân loại theo vai trò, luồng dữ liệu và tệp mẫu đối chiếu trên `feature/phase-5-core`.

### 1.1 Tài liệu Hợp đồng & Cấu hình Hạ tầng (`docs/`, `backend/deploy/`)

| Tệp mục tiêu | Trạng thái | Vai trò & Luồng dữ liệu | Tệp mẫu đối chiếu (`feature/phase-5-core` / `planning`) | Ghi chú triển khai |
|---|---|---|---|---|
| `docs/API_CONTRACT.md` | Modified | Khóa hợp đồng Phase 6 trước khi code (`D-10` *one-way*): mục 2.4 (loại `IMAGE`/`AUDIO`/`VIDEO`/`FILE`/`LOCATION`, `attachmentId`, `expectedVisibleFromMessageId` cho nhóm), mục 2.5 (`POST /media`, `GET /media/{id}` `Range` + `FileProvider`), mục 2.6 (`POST /calls` trả `409 CALL_BUSY` thay vì tạo `MISSED`/`BUSY`, atomic `accept`/`reject`/`end`, 45s timeout), mục 3 (`call.offer`/`answer`/`ice` 2-device lock, `call.handled_elsewhere`) | `docs/API_CONTRACT.md#L116-L234` | Phải chốt ở Wave 1 trước khi code song song Backend & Android |
| `docs/FLOWS.md` | Modified | Cập nhật `F-11` (upload-before-send + `mediaUrl` persistence), `F-18` (thẻ tọa độ `LOCATION` + `geo:` Intent thay `SupportMapFragment`, `D-02`), `F-20` (chọn trực tiếp EN↔VI không Language ID, Wi-Fi vs cellular confirm, progress vô định không %, `D-13`..`D-15`), `F-21` & `F-22` (`CALL_BUSY`, 45s timeout, `call.handled_elsewhere`, 1 tin `CALL` idempotent, `D-10`) | `docs/FLOWS.md#L228-L406` | Đồng bộ 1-1 với `06-CONTEXT.md` |
| `docs/ERD.md` | Modified | Bổ sung 4 cột mới của bảng `calls` (`V6`) + partial unique index `uq_messages_call_id`, và 11 cột v3 của bảng Room `attachment_transfer` | `docs/ERD.md#L162-L250` | Giữ đồng bộ giữa ERD và migration `V6` + Room `3.json` |
| `backend/deploy/coturn/turnserver.conf` | Created | File cấu hình mẫu coturn EC2 chạy trên Secondary IP: TLS 443 (`turns:`), UDP/TCP 3478/443, `use-auth-secret`, `static-auth-secret`, chặn SSRF dải IP nội bộ/link-local `169.254.0.0/16` | *No analog (greenfield)* → `06-RESEARCH.md#L302-L309` | Tuân thủ `ADR-013` & `DEPLOYMENT_SECURITY.md#L20` |
| `backend/deploy/coturn/README.md` | Created | Hướng dẫn chạy coturn trên EC2 secondary Elastic IP, cấp chứng chỉ TLS, kiểm thử relay `turnutils_uclient` và biến môi trường `.env` | *No analog (greenfield)* → `06-RESEARCH.md#L302-L309` | Phục vụ demo LAN/4G & nghiệm thu `DEPLOYMENT_SECURITY.md` |

---

### 1.2 Backend Spring Boot 3 (`backend/` & `.env.example`)

| Tệp mục tiêu | Trạng thái | Vai trò & Luồng dữ liệu | Tệp mẫu đối chiếu (`feature/phase-5-core`) | Ghi chú triển khai |
|---|---|---|---|---|
| `.env.example` | Modified | Bổ sung `MEDIA_STORAGE_DIR`, `COTURN_URLS`, `COTURN_SHARED_SECRET`, `COTURN_TTL_SECONDS`, `COTURN_FALLBACK_STUN_URLS` | `.env.example#L1-L27` | Secret chỉ đặt ở `.env`/CI, không vào APK |
| `backend/src/main/resources/application.yml` | Modified | Tăng `spring.servlet.multipart.max-file-size: 55MB` & `max-request-size: 60MB`; thêm `app.media.*` và `app.coturn.*` | `backend/src/main/resources/application.yml#L46-L56` | Tránh lỗi Spring chặn 1MB mặc định trước khi vào `MediaService` |
| `backend/src/main/resources/application-dev.yml` | Modified | Cấu hình mặc định cục bộ `MEDIA_STORAGE_DIR=./data/media` và fallback STUN `stun:stun.l.google.com:19302` | `backend/src/main/resources/application-dev.yml#L1-L10` | Dùng cho dev/LAN khi chưa bật coturn |
| `backend/src/main/resources/db/migration/V6__media_and_calls.sql` | Created | Thêm `caller_device_id_hash`, `callee_device_id_hash`, `expires_at`, `updated_at`, `caller_alive_until`, `callee_alive_until` vào `calls`; thêm `attachments.upload_client_id` và `uq_attachments_upload_client`; partial unique index `uq_calls_active_caller`, `uq_calls_active_callee`, `idx_calls_ringing_expires`, `uq_messages_call_id` | *No analog (greenfield)*; đối chiếu cấu trúc RLS/index từ `V1__initial_schema.sql#L117-L200` & `V5__sync_and_messaging.sql#L1-L52` | *One-way checkpoint*; các bảng sửa đều đã bật RLS từ V1 |
| `backend/src/main/java/com/utechat/backend/exception/GlobalExceptionHandler.java` | Modified | Bổ sung `@ExceptionHandler(MaxUploadSizeExceededException.class)` trả HTTP `413` với mã `FILE_TOO_LARGE` chuẩn envelope | `GlobalExceptionHandler.java#L20-L51` | Ngăn request >60MB rơi vào `500 INTERNAL` |
| `backend/src/main/java/com/utechat/backend/dto/MediaDtos.java` | Created | Java `record` bất biến: `MediaUploadResponse(mediaUrl, mime, sizeBytes, width, height, durationMs, fileName)` và `MediaDownloadMeta(...)` | `AuthDtos.java#L12-L38`, `MessagingDtos.java#L77-L98` | Tên trường `camelCase`, `@JsonInclude(NON_NULL)` |
| `backend/src/main/java/com/utechat/backend/dto/CallDtos.java` | Created | Java `record` bất biến: `StartCallRequest`, `IceServerDto`, `StartCallResponse`, `AcceptCallResponse`, `CallUpdatedEventPayload`, `CallSignalingPayload` | `MessagingDtos.java#L68-L74,L179-L186` | Tương thích `SyncEventApplier.CallUpdatePayload` (`callId, state, endReason`) + mở rộng `conversationId, caller, media, handledByDeviceIdHash` |
| `backend/src/main/java/com/utechat/backend/dto/MessagingDtos.java` | Modified | Cập nhật Javadoc `SendMessageRequest` (chấp nhận `TEXT/IMAGE/AUDIO/VIDEO/FILE/LOCATION`); kiểm chứng `MessageDto`/`CallSummaryDto` giữ đủ trường Phase 6 | `MessagingDtos.java#L39-L98` | Giữ nguyên cấu trúc tương thích ngược với Phase 5 |
| `backend/src/main/java/com/utechat/backend/service/MediaUploadRateLimiter.java` | Created | Sliding Window Log `20 uploads / 60 giây / userId` -> ném `429 RATE_LIMITED` + `retryAfterSeconds` | `MessageRateLimiter.java#L20-L75` | Inject `Clock` để unit test/integration test chạy xác định |
| `backend/src/main/java/com/utechat/backend/service/MediaService.java` | Created | Stream upload đếm byte theo `kind` (`10/10/50/25 MB`), kiểm magic-byte MIME, đọc `ImageIO` width/height & ISOBMFF duration, lưu `<MEDIA_STORAGE_DIR>/yyyy/MM/<uuid>.ext` chống path traversal, truy vấn kiểm quyền `GET /api/media/{id}` (`active member` AND `message.id > visible_from_message_id`, hoặc unattached owner) | `AccountService.java#L44-L81`, `MessageService.java#L165-L242` | Không tin `Content-Type` hay `originalFilename` do client gửi |
| `backend/src/main/java/com/utechat/backend/controller/MediaController.java` | Created | `POST /api/media` (`201 Created`) và `GET /api/media/{id}` hỗ trợ `HttpRange` (`200`, `206 Partial Content`, `416 Range Not Satisfiable`) qua `StreamingResponseBody` + `FileChannel`, gắn `X-Content-Type-Options: nosniff` | `MessageController.java#L24-L55`, `ConversationController.java#L144-L151` | Chỉ nhận `Authorization: Bearer`, tuyệt đối không query ticket |
| `backend/src/main/java/com/utechat/backend/service/MessageService.java` | Modified | Mở rộng `sendMessage` nhận `TEXT`, `IMAGE`, `AUDIO`, `VIDEO`, `FILE`, `LOCATION`; bóc `attachmentId` từ `mediaUrl`, khóa `attachments FOR UPDATE` kiểm `uploader_id == actorId`, `kind == type`, chưa gắn tin khác; kiểm `latitude`/`longitude` hợp lệ cho `LOCATION`; giữ `FOR UPDATE` + `expectedVisibleFromMessageId` cho nhóm | `MessageService.java#L93-L243` | Từ chối `SYSTEM` & `CALL` từ client (`400 VALIDATION`) |
| `backend/src/main/java/com/utechat/backend/service/TurnCredentialsService.java` | Created | Sinh credential tạm coturn chuẩn REST HMAC-SHA1 (`expiry:userId`, `HmacSHA1` Base64) từ `COTURN_URLS`, `COTURN_SHARED_SECRET`, `COTURN_TTL_SECONDS`; fallback STUN công khai khi local dev chưa cấu hình | *No analog (greenfield)* → `06-RESEARCH.md#L284-L301` | Inject `Clock` để test deterministic |
| `backend/src/main/java/com/utechat/backend/service/CallRateLimiter.java` | Created | Sliding Window Log `5 calls / 60 giây / userId` -> `429 RATE_LIMITED` | `MessageRateLimiter.java#L20-L75` | Tái sử dụng đúng mẫu `ArrayDeque<Instant>` + `pruneEmptyBuckets` |
| `backend/src/main/java/com/utechat/backend/service/CallService.java` | Created | `startCall`: `lockPair` -> khóa 2 dòng `users` theo thứ tự UUID `FOR UPDATE` -> kiểm `400 CALL_UNSUPPORTED` (nhóm), `403 BLOCKED` trước `403 CONTACT_REQUIRED`, `409 CALL_BUSY`, tạo `calls (RINGING, expires_at=+45s)` + `CALL_UPDATED`; `acceptCall` nguyên tử `RINGING -> ACTIVE` + lưu `callee_device_id_hash` + `call.handled_elsewhere`; `rejectCall` / `endCall` / `@Scheduled` 45s timeout nguyên tử + chèn đúng 1 tin `CALL` idempotent (`uq_messages_call_id` + `ON CONFLICT DO NOTHING`) | `ConversationService.java#L58-L100,L652-L760`, `AccountService.java#L160-L189`, `SyncRetentionService.java#L28-L56` | Không giữ transaction dài; xử lý timeout từng call hoặc batch nhỏ |
| `backend/src/main/java/com/utechat/backend/controller/CallController.java` | Created | `POST /api/calls` (`201`), `POST /api/calls/{id}/accept` (`200`), `POST /api/calls/{id}/reject` (`204`), `POST /api/calls/{id}/end` (`204`) | `ConversationController.java#L34-L151` | Lấy `actor.userId()` & `actor.deviceIdHash()` từ `@AuthenticationPrincipal` |
| `backend/src/main/java/com/utechat/backend/websocket/WebSocketSessionRegistry.java` | Modified | Bổ sung `sendToUserExceptDevice(UUID userId, String excludedDeviceHash, String jsonFrame)` hỗ trợ đẩy `call.handled_elsewhere` chỉ tới các thiết bị còn lại của callee | `WebSocketSessionRegistry.java#L97-L154` | Tái sử dụng `sendSafe` và cấu trúc `ConcurrentHashMap<UUID, ConcurrentHashMap<String, ...>>` |
| `backend/src/main/java/com/utechat/backend/websocket/SyncEventRealtimeDispatcher.java` | Modified | Xử lý `CALL_UPDATED`: đọc action/state trong payload để ánh xạ sang WS `call.incoming`, `call.accepted`, `call.ended`, `call.handled_elsewhere` và điều phối đúng thiết bị nhận | `SyncEventRealtimeDispatcher.java#L41-L91` | Giữ `syncEventId` trên mọi khung `call.incoming/accepted/ended/handled_elsewhere` |
| `backend/src/main/java/com/utechat/backend/websocket/ChatWebSocketHandler.java` | Modified | Bổ sung `case "call.offer"`, `"call.answer"`, `"call.ice"`: kiểm `calls.state = 'ACTIVE'`, xác thực `(managed.userId, managed.deviceIdHash)` khớp `caller_device_id_hash` hoặc `callee_device_id_hash` và chuyển tiếp DUY NHẤT tới `(peerUserId, peerDeviceIdHash)` qua `registry.sendToDevice(...)` | `ChatWebSocketHandler.java#L85-L181` | Thực thi khóa signaling 2 thiết bị (`D-10`), không ghi `sync_events` |
| `backend/src/test/java/com/utechat/backend/config/FlywayMigrationTest.java` | Modified | Bổ sung xác minh V6 trên `EmbeddedPostgres`: 6 cột mới ở `calls`, cột `attachments.upload_client_id`, `uq_attachments_upload_client`, `uq_calls_active_caller`, `uq_calls_active_callee`, `idx_calls_ringing_expires`, `uq_messages_call_id` | `FlywayMigrationTest.java#L34-L78` | Không dùng Docker |
| `backend/src/test/java/com/utechat/backend/media/MediaApiAndRangeTest.java` | Created | Integration test `EmbeddedPostgres` + `MockMvc`: upload 4 `kind`, từ chối vượt dung lượng (`413`), sai magic byte / SVG / EXE (`415`), rate limit 20/min (`429`), HTTP `Range` (`206`, `416`), kiểm quyền `403` thành viên cũ / pre-join `visible_from_message_id` / unattached owner, gửi tin `IMAGE`/`LOCATION` qua `/messages` | `MessagingConcurrencyAndPolicyTest.java#L37-L185` | Dùng `@TempDir` hoặc thư mục tạm cấu hình qua `@DynamicPropertySource` |
| `backend/src/test/java/com/utechat/backend/calls/TurnCredentialsServiceTest.java` | Created | Unit test thuật toán HMAC-SHA1 coturn (`username = expiry:userId`, chữ ký Base64 khớp RFC), STUN không kèm credential, fallback STUN khi cấu hình rỗng | `SyncEngineAndApplierTest` / JUnit 5 pure unit test | Tiêm `Clock.fixed(...)` |
| `backend/src/test/java/com/utechat/backend/calls/CallLifecycleAndSignalingTest.java` | Created | Integration test `@SpringBootTest(RANDOM_PORT)` + `EmbeddedPostgres`: `403 BLOCKED` trước `CONTACT_REQUIRED`, `400 CALL_UNSUPPORTED`, `409 CALL_BUSY` đua song song, atomic `accept`/`reject`/`45s timeout`, `call.handled_elsewhere` chỉ gửi tới thiết bị khác, 2-device lock `call.offer`/`answer`/`ice` (thiết bị thứ 3 bị chặn), idempotent 1 tin `CALL` duy nhất | `WebSocketAuthAndRealtimeTest.java#L53-L266`, `MessagingConcurrencyAndPolicyTest.java#L75-L121` | Dùng `MutableClock` + `TestWsClient` |

---

### 1.3 Android Client (`app/`)

#### 1.3.1 Gradle, Manifest, `FileProvider` & ProGuard

| Tệp mục tiêu | Trạng thái | Vai trò & Luồng dữ liệu | Tệp mẫu đối chiếu (`feature/phase-5-core`) | Ghi chú triển khai |
|---|---|---|---|---|
| `gradle/libs.versions.toml` | Modified | Khai báo `media3 = "1.11.1"`, `playServicesLocation = "21.4.0"`, `streamWebrtc = "1.3.10"`, `mlkitTranslate = "17.0.3"`, `exifinterface = "1.4.2"`, `glide = "5.0.10"`, `core = "1.17.0"`, `activity = "1.10.1"` | `gradle/libs.versions.toml#L1-L47` | **KHÔNG** nâng `androidx.core` lên `1.18.0+`, **KHÔNG** thêm `play-services-maps` hay `mlkit:language-id` |
| `app/build.gradle.kts` | Modified | Thêm implementation `media3-*`, `play-services-location`, `stream-webrtc-android`, `mlkit:translate`, `exifinterface`, `glide` + `glide:okhttp3-integration` + `annotationProcessor(glide-compiler)` | `app/build.gradle.kts#L60-L86` | Giữ `JavaVersion.VERSION_17`, `compileSdk = 36`, `annotationProcessor` (không KSP/Kapt) |
| `app/src/main/AndroidManifest.xml` | Modified | Thêm quyền (`POST_NOTIFICATIONS`, `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `MODIFY_AUDIO_SETTINGS`, `BLUETOOTH_CONNECT`, `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `CAMERA` + `<uses-feature required="false">`), `<queries>` cho `geo:` & `*/*`, `<provider FileProvider>`, `MainActivity launchMode="singleTop"`, `<service CallService foregroundServiceType="microphone">` | `app/src/main/AndroidManifest.xml#L1-L25` | Bỏ qua `READ_EXTERNAL_STORAGE`/`READ_MEDIA_*` vì dùng Photo Picker & SAF |
| `app/src/main/res/xml/file_paths.xml` | Created | Khai báo `<files-path name="pending_media" path="pending_media/" />`, `<cache-path name="media_cache" path="media_cache/" />`, `<cache-path name="camera" path="camera/" />` | *No analog (greenfield)*; đối chiếu thư mục XML `app/src/main/res/xml/network_security_config.xml#L1-L7` | Dùng `${applicationId}.fileprovider` |
| `app/src/main/keepRules/rules.keep` | Modified | Giữ lớp reflection cho WebRTC (`-keep class org.webrtc.** { *; }`), ML Kit (`-keep class com.google.mlkit.** { *; }`), `@GlideModule` và DTO `MediaDtos`/`CallDtos` | `app/src/main/keepRules/rules.keep#L1-L13` | Bảo vệ Release R8 build |

#### 1.3.2 Room Database v3 (`AppDatabase`, Entities, DAOs)

| Tệp mục tiêu | Trạng thái | Vai trò & Luồng dữ liệu | Tệp mẫu đối chiếu (`feature/phase-5-core`) | Ghi chú triển khai |
|---|---|---|---|---|
| `app/src/main/java/com/utechat/data/local/entity/AttachmentTransferEntity.java` | Modified | Bổ sung 11 trường (`direction`, `localPath`, `kind`, `mime`, `sizeBytes`, `fileName`, `durationMs`, `width`, `height`, `errorCode`, `updatedAt`) phục vụ bền vững hàng đợi upload (`filesDir/pending_media/`) & cache tải xuống (`cacheDir/media_cache/`) | `AttachmentTransferEntity.java#L7-L51`, `MessageEntity.java#L53-L116` | Giữ nguyên 5 cột cũ (`clientId`, `localUri`, `state`, `progress`, `mediaUrl`) |
| `app/src/main/java/com/utechat/data/local/dao/TransferDao.java` | Modified | Thêm `findByClientIdSync`, `observeAllTransfersMap` (hoặc `observeAll()`), `updateProgress`, `markUploaded`, `markState`, `deleteByClientId`, `listPendingCleanup` | `TransferDao.java#L9-L15`, `MessageDao.java#L222-L282` | Dùng cho `SendMessageWorker` & `MediaDownloadManager` |
| `app/src/main/java/com/utechat/data/local/dao/TranslationDao.java` | Modified | Thêm `findSync(key, targetLang)`, `observeAll()`, `observeByConversation(conversationId)` (join `message`), `deleteByKey(key, lang)`, `deleteOrphaned()` | `TranslationDao.java#L9-L16`, `MessageDao.java#L50-L55` | Không cần đổi cột bảng `message_translation` |
| `app/src/main/java/com/utechat/data/local/AppDatabase.java` | Modified | Nâng `version = 3`, thêm `MIGRATION_2_3` (`11 ALTER TABLE attachment_transfer ADD COLUMN ...`) và đăng ký `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)` | `AppDatabase.java#L14-L57` | *One-way checkpoint*; tuyệt đối không dùng `fallbackToDestructiveMigration()` |
| `app/schemas/com.utechat.data.local.AppDatabase/3.json` | Created (Auto) | Schema JSON Room v3 do `room-compiler` sinh ra | `app/schemas/com.utechat.data.local.AppDatabase/2.json` | Commit cùng code để `MigrationTestHelper` kiểm chứng |

#### 1.3.3 Remote API, DTOs, Glide, Offline Upload Worker, Repository & DI

| Tệp mục tiêu | Trạng thái | Vai trò & Luồng dữ liệu | Tệp mẫu đối chiếu (`feature/phase-5-core`) | Ghi chú triển khai |
|---|---|---|---|---|
| `app/src/main/java/com/utechat/data/remote/model/MediaDtos.java` | Created | Gson DTO `MediaUploadResponse` (`mediaUrl`, `mime`, `sizeBytes`, `width`, `height`, `durationMs`, `fileName`) | `ChatDtos.java#L115-L136` | Trường public `final`/mutable chuẩn Gson như `ChatDtos` |
| `app/src/main/java/com/utechat/data/remote/model/CallDtos.java` | Created | Gson DTO: `StartCallRequest`, `IceServerDto`, `StartCallResponse`, `AcceptCallResponse`, `CallIncomingPayload`, `CallStatePayload`, `CallSignalingPayload` | `ChatDtos.java#L107-L113,L255-L260` | Tách DTO mạng khỏi UI state |
| `app/src/main/java/com/utechat/data/remote/api/MediaApi.java` | Modified | Thay stub Phase 2 bằng `@Multipart @POST("api/media") uploadMedia(@Header("Authorization") String auth, @Part("clientId") RequestBody clientId, @Part MultipartBody.Part kind, @Part MultipartBody.Part file, @Part("durationMs") @Nullable RequestBody durationMs)` và `@Streaming @GET("api/media/{id}") downloadMedia(...)` | `MediaApi.java#L1-L6`, `ChatApi.java#L60-L72` | Dùng `@Header("Authorization")` ở Worker để khóa đúng token phiên xếp hàng |
| `app/src/main/java/com/utechat/data/remote/api/CallApi.java` | Created | `@POST("api/calls")`, `@POST("api/calls/{id}/accept")`, `@POST("api/calls/{id}/reject")`, `@POST("api/calls/{id}/end")` | `ChatApi.java#L23-L55` | Đường dẫn tương đối `"api/..."` không có `/` đầu |
| `app/src/main/java/com/utechat/data/remote/api/ApiClient.java` | Modified | Bổ sung `MEDIA_CLIENT` (`getMediaOkHttpClient()`, read/write timeout dài hơn 60s, `callTimeout(0)` hoặc `120s`, gắn `AuthInterceptor` + `LOGGING`) cho upload/download/ExoPlayer/Glide | `ApiClient.java#L14-L38`, `AuthInterceptor.java#L13-L36` | Release giữ `HttpLoggingInterceptor.Level.NONE` |
| `app/src/main/java/com/utechat/data/media/UteChatGlideModule.java` | Created | `@GlideModule AppGlideModule` đăng ký `OkHttpUrlLoader.Factory(ApiClient.getMediaOkHttpClient())` để Glide tải thumbnail `/api/media/{id}` kèm Bearer token | *No analog (greenfield)* → `06-RESEARCH.md#L66` | Cần `annotationProcessor` Glide `5.0.10` |
| `app/src/main/java/com/utechat/data/worker/SendMessageWorker.java` | Modified | Mở rộng `executeSend` cho 2 pha: nếu `type ∈ {IMAGE,AUDIO,VIDEO,FILE}` và `transfer.mediaUrl == null` thì upload qua `MediaApi` (đếm byte tiến độ vào `TransferDao`) -> **lưu `mediaUrl` + metadata ngay lập tức** vào Room; retry skip bước upload nếu đã có `mediaUrl`; `413`/`415` đánh dấu `FAILED` ngay + xóa file tạm + trả `Result.success()`; gửi `POST /messages` (kèm `LOCATION` lat/lng); xóa `pending_media` temp file khi `200`/`201` | `SendMessageWorker.java#L155-L360` | Giữ tương thích 100% với `SendMessageWorkerTest` (constructor/overload cũ cho `TEXT`) |
| `app/src/main/java/com/utechat/data/repository/ChatRepository.java` | Modified | Giữ nguyên method Phase 2 & 5; bổ sung `enqueueMediaMessage(...)`, `enqueueLocationMessage(...)`, `observeTransfersMap()`, `retry/delete` hỗ trợ media cleanup | `ChatRepository.java#L20-L65` | Bảo toàn `FakeRepositoriesTest` |
| `app/src/main/java/com/utechat/data/repository/fake/FakeChatRepository.java` | Modified | Implement giả in-memory cho `enqueueMediaMessage`, `enqueueLocationMessage`, `observeTransfersMap` để build Fake (`USE_FAKE_DATA=true`) hoạt động trọn vẹn | `FakeChatRepository.java#L145-L385` | Không làm gãy test Phase 2/5 |
| `app/src/main/java/com/utechat/data/repository/real/RealChatRepository.java` | Modified | Atomic `runInTransaction` chèn `MessageEntity` + `AttachmentTransferEntity` cho media hoặc `MessageEntity` cho `LOCATION` -> `SendMessageWorker.enqueue`; `deleteFailedMessage` xóa thêm file `pending_media/` và dòng `attachment_transfer` trên `DISK` | `RealChatRepository.java#L469-L570` | Toàn bộ callback UI qua `mainExecutor`, cấm `ArchTaskExecutor` |
| `app/src/main/java/com/utechat/di/Injection.java` | Modified | Cung cấp singleton `MediaPreparationHelper`, `MediaDownloadManager`, `NotificationHelper`, `ActiveRoomTracker`, `CallSessionCoordinator`, `MessageTranslationManager`; mở rộng `cleanupDepartingSession` để hủy `SyncWorker.queueName(key)`, gọi `NotificationHelper.cancelAll`, `CallSessionCoordinator.onSessionCleared()`, và dọn `pending_media/`, `media_cache/`, `camera/` trên `DISK` | `Injection.java#L36-L225` | Service Locator thuần Java |

#### 1.3.4 Media Pickers, Compressors, Players, ExoPlayer & Location

| Tệp mục tiêu | Trạng thái | Vai trò & Luồng dữ liệu | Tệp mẫu đối chiếu (`feature/phase-5-core`) | Ghi chú triển khai |
|---|---|---|---|---|
| `app/src/main/java/com/utechat/data/media/MediaPreparationHelper.java` | Created | Chuẩn bị file trước khi xếp hàng: ảnh đọc `ExifInterface`, xoay/scale cạnh dài `<= 1600px`, nén JPEG `85/70` (`<= 10MB`); video/file copy sang `filesDir/pending_media/<clientId>.<ext>` kiểm giới hạn `50MB` / `25MB` & MIME allowlist | *No analog (greenfield)* → `06-RESEARCH.md#L316-L319` | Chạy trên executor nền, tuyệt đối không block main thread |
| `app/src/main/java/com/utechat/data/media/VoiceRecorderHelper.java` | Created | Bọc `MediaRecorder` (`MPEG_4`, `AAC`, `64kbps`, `44.1kHz`, tối đa `300_000ms` / `10MB`) ghi trực tiếp vào `filesDir/pending_media/<clientId>.m4a`, trả thời lượng `durationMs` | *No analog (greenfield)* → `06-RESEARCH.md#L318` | Kiểm `RECORD_AUDIO` trước khi start; giải phóng an toàn ở `onStop` |
| `app/src/main/java/com/utechat/data/media/MediaDownloadManager.java` | Created | Tải tệp `/api/media/{id}` bằng `ApiClient.getMediaOkHttpClient()` có Bearer token về `cacheDir/media_cache/<id>_<safeName>`, ghi tiến độ `DOWNLOAD` vào `TransferDao`, tạo URI `FileProvider.getUriForFile(...)` kèm `FLAG_GRANT_READ_URI_PERMISSION` | *No analog (greenfield)* → `06-RESEARCH.md#L343-L344` | Chạy trên `MEDIA_IO` với `SessionManager` kiểm tra phiên trước khi xuất tệp hoặc trả callback |
| `app/src/main/java/com/utechat/data/media/LocationFetchHelper.java` | Created | Lấy tọa độ 1 lần qua `FusedLocationProviderClient.getCurrentLocation(PRIORITY_HIGH_ACCURACY, CancellationTokenSource)` (`play-services-location:21.4.0`) và tạo Intent `geo:<lat>,<lng>?q=<lat>,<lng>` mở Google Maps ngoài | *No analog (greenfield)* → `06-RESEARCH.md#L63,L320` | Không dùng `SupportMapFragment` (`D-02`) |
| `app/src/main/java/com/utechat/ui/media/VoiceBubblePlayer.java` | Created | Trình phát audio đơn dùng chung (`MediaPlayer`) cho bong bóng voice: chỉ 1 bong bóng phát tại 1 thời điểm, cập nhật seekbar định kỳ bằng `Handler(Looper.getMainLooper())`, tự giải phóng ở `onStop` | *No analog (greenfield)* → `06-RESEARCH.md#L342` | Tải cache qua `MediaDownloadManager` nếu chưa có file cục bộ |
| `app/src/main/java/com/utechat/ui/media/ZoomableImageView.java` | Created | Custom `AppCompatImageView` thuần Java (~120 dòng) dùng `ScaleGestureDetector` + `GestureDetector` + `Matrix` hỗ trợ pinch-zoom & double-tap | *No analog (greenfield)* → `06-RESEARCH.md#L66` | Không phụ thuộc thư viện JitPack `PhotoView` |
| `app/src/main/java/com/utechat/ui/media/ImageViewerFragment.java` | Created | Xem ảnh toàn màn hình từ `localPath` hoặc `/api/media/{id}` qua `Glide` + `ZoomableImageView` + nút lưu/mở ngoài qua `FileProvider` | `ChatRoomFragment.java#L40-L59`, `DevicesFragment.java#L19-L40` | Destination trong `nav_chat.xml` |
| `app/src/main/java/com/utechat/ui/media/VideoPlayerFragment.java` | Created | Phát video bằng `Media3 ExoPlayer` + `PlayerView` + `OkHttpDataSource.Factory(ApiClient.getMediaOkHttpClient())` (HTTP `Range` + Bearer) + nút *"Mở bằng app ngoài"* qua `MediaDownloadManager` + `FileProvider` | *No analog (greenfield)* → `06-RESEARCH.md#L62,L343` | Giải phóng `ExoPlayer` ở `onStop`/`onDestroyView` |

#### 1.3.5 Notifications & 15-Minute Periodic Sync

| Tệp mục tiêu | Trạng thái | Vai trò & Luồng dữ liệu | Tệp mẫu đối chiếu (`feature/phase-5-core`) | Ghi chú triển khai |
|---|---|---|---|---|
| `app/src/main/java/com/utechat/data/notifications/ActiveRoomTracker.java` | Created | Giữ `volatile String activeConversationId` đang mở trên màn hình (`onResume` đặt, `onPause` xóa) để triệt tiêu thông báo đúng phòng đang xem (`D-05`) | *No analog (greenfield)* → `06-RESEARCH.md#L359` | Thread-safe giữa main thread & `SyncEngine` thread |
| `app/src/main/java/com/utechat/data/notifications/NotificationHelper.java` | Created | Tạo đúng **2 `NotificationChannel`s** (`messages`, `calls`); lọc tin tự gửi / hội thoại `muted` / phòng đang mở; gom nhóm `NotificationCompat.MessagingStyle` theo `conversationId`; thông báo cuộc gọi đến/nhỡ trên `calls`; `PendingIntent.FLAG_IMMUTABLE`; `cancelAll` khi đổi phiên | *No analog (greenfield)* → `06-RESEARCH.md#L347-L362` | Hỗ trợ interface/abstraction để JVM test kiểm chứng logic lọc (`D-05`, `D-06`) |
| `app/src/main/java/com/utechat/data/worker/SyncWorker.java` | Created | `PeriodicWorkRequest` 15 phút theo phiên (`"periodic-sync:" + sessionKey`, `KEEP`, `NetworkType.CONNECTED`), đối chiếu `enqueuedSessionKey.equals(session.sessionKey())` rồi gọi `syncEngine.syncBlocking(enqueuedSessionKey)` | `SendMessageWorker.java#L45-L140` | Tái sử dụng mẫu `queueName(sessionKey)` + `TAG_SESSION_PREFIX` |
| `app/src/main/java/com/utechat/data/sync/SyncEngine.java` | Modified | Bổ sung `syncBlocking(String expectedSessionKey)` dùng chung mutex single-flight (`running`/`resyncRequested`) cho `SyncWorker`; kích hoạt `NotificationHelper` **chỉ** từ batch `/sync` gia tăng (tuyệt đối không phát khi `runBootstrap`) | `SyncEngine.java#L149-L286` | `D-08`: không tranh chấp `sync_state` với foreground/WS |
| `app/src/main/java/com/utechat/data/sync/SyncEventApplier.java` | Modified | Mở rộng kết quả `applyBatch` trả thêm danh sách tin nhắn mới hợp lệ & cuộc gọi `MISSED` mới cho `NotificationHelper`; xử lý payload `CALL_UPDATED` mở rộng (`callId`, `conversationId`, `state`, `endReason`) | `SyncEventApplier.java#L225-L308,L475-L500` | Giữ nguyên idempotency và tương thích ngược `SyncEngineAndApplierTest` |
| `app/src/main/java/com/utechat/MainActivity.java` | Modified | Xin quyền `POST_NOTIFICATIONS` đúng 1 lần sau đăng nhập trên API 33+ (`D-07`), đăng ký `SyncWorker.enqueuePeriodic(...)`, xử lý `onNewIntent` / extra `extra_open_conversation_id` từ notification tap, mở `CallFragment` khi Activity đang resumed hoặc qua thao tác nhấn thông báo đúng phiên | `MainActivity.java#L24-L80` | Dùng `Handler`/`ActivityResultContracts.RequestPermission` |

#### 1.3.6 1-1 WebRTC Voice Call

| Tệp mục tiêu | Trạng thái | Vai trò & Luồng dữ liệu | Tệp mẫu đối chiếu (`feature/phase-5-core`) | Ghi chú triển khai |
|---|---|---|---|---|
| `app/src/main/java/com/utechat/data/call/WebRtcCallEngine.java` | Created | Interface seam trừu tượng hóa `PeerConnection` (`initialize`, `startOffer`, `acceptOffer`, `applyAnswer`, `addRemoteIceCandidate`, `setMicMuted`, `setSpeakerEnabled`, `close`) để tách khỏi native WebRTC khi chạy unit test JVM | *No analog (greenfield)* → `06-RESEARCH.md#L366-L370` | Khóa bởi `D-12` (*costly*) |
| `app/src/main/java/com/utechat/data/call/StreamWebRtcCallEngine.java` | Created | Triển khai `WebRtcCallEngine` dùng `io.getstream:stream-webrtc-android:1.3.10`: `PeerConnectionFactory`, `JavaAudioDeviceModule`, bộ đệm `pendingRemoteIce` trước khi `setRemoteDescription` xong, định tuyến âm thanh API 26–30 vs API 31+ (`setCommunicationDevice`) | *No analog (greenfield)* → `06-RESEARCH.md#L64,L370-L372` | Pure Java `org.webrtc.*` |
| `app/src/main/java/com/utechat/data/call/CallSessionCoordinator.java` | Created | State machine cuộc gọi thoại (`IDLE`, `OUTGOING_RINGING`, `INCOMING_RINGING`, `CONNECTING`, `ACTIVE`, `ENDED`) + khóa signaling `callId` + gọi `CallApi` + phát/nhận `call.offer`/`answer`/`ice` qua `ChatWebSocketManager` + xử lý `call.handled_elsewhere` & 45s timeout + reset khi đổi phiên | `TypingTracker.java#L20-L145` (pattern `DelayScheduler` + `LongSupplier clockMs` + `MutableLiveData`) | 100% JVM-testable không cần Android device |
| `app/src/main/java/com/utechat/data/call/CallService.java` | Created | Foreground Service (`foregroundServiceType="microphone"`) giữ `WebRtcCallEngine` + audio focus sống bền qua xoay màn hình, gắn thông báo cuộc gọi đang diễn ra trên kênh `calls` | *No analog (greenfield)* → `06-RESEARCH.md#L373` | Chỉ khởi chạy từ thao tác Gọi hoặc Nhận trên `CallFragment` đang hiển thị sau khi có quyền `RECORD_AUDIO` |
| `app/src/main/java/com/utechat/data/sync/ChatWebSocketManager.java` | Modified | Chuyển tiếp frame nhận (`call.incoming`, `call.accepted`, `call.ended`, `call.handled_elsewhere`, `call.offer`, `call.answer`, `call.ice`) tới `CallSessionCoordinator` (nhóm sync event vẫn gọi `syncEngine.requestSync()`, riêng `call.incoming` còn kích hoạt ring/UI ngay!); thêm `sendCallSignalingFrame(type, callId, jsonPayload)` | `ChatWebSocketManager.java#L159-L183,L350-L398` | Giữ `synchronized (ChatWebSocketManager.this)` & kiểm `sessionKey` |
| `app/src/main/java/com/utechat/ui/call/CallViewModel.java` | Created | Bọc `CallSessionCoordinator.getUiState()` + `SavedStateHandle`, xử lý `RECORD_AUDIO` gate, đếm giờ `mm:ss` dựa trên `answeredAtElapsedRealtime`, toggle Mute / Speaker / End / Accept / Reject | `ChatRoomViewModel.java#L34-L98`, `AuthViewModel.java#L37-L43` | Dùng `ViewModelInitializer` |
| `app/src/main/java/com/utechat/ui/call/CallFragment.java` | Created | Màn hình gọi riêng (tên/avatar đối phương, trạng thái/đồng hồ `mm:ss`, 3 nút Mute/Speaker/End `>=48dp`, Accept/Reject khi đổ chuông, nút `ACTION_DIAL` fallback nếu có SĐT & lỗi WebRTC, xin quyền `RECORD_AUDIO`, rung/chuông `Ringtone`) | `MainActivity.java#L20-L42` | Khai báo đích và global action trong `nav_graph.xml`, giữ trạng thái khi xoay màn hình |

#### 1.3.7 On-Device ML Kit Translation

| Tệp mục tiêu | Trạng thái | Vai trò & Luồng dữ liệu | Tệp mẫu đối chiếu (`feature/phase-5-core`) | Ghi chú triển khai |
|---|---|---|---|---|
| `app/src/main/java/com/utechat/data/translate/MessageTranslator.java` | Created | Interface seam `isModelDownloaded(lang)`, `downloadModel(lang, requireWifi, callback)`, `translate(sourceLang, targetLang, text, callback)` | `ChatRepository.java` (interface seam pattern) | Tách ML Kit SDK khỏi business logic để unit test 100% trên JVM |
| `app/src/main/java/com/utechat/data/translate/RealMlKitTranslator.java` | Created | Triển khai `com.google.mlkit:translate:17.0.3` (`TranslateLanguage.ENGLISH` ↔ `TranslateLanguage.VIETNAMESE`), `RemoteModelManager`, `DownloadConditions` (cho phép cellular khi user xác nhận), quản lý đóng `Translator` tránh leak | *No analog (greenfield)* → `06-RESEARCH.md#L65,L379-L392` | Tuyệt đối **KHÔNG** dùng `mlkit:language-id` (`D-13`) |
| `app/src/main/java/com/utechat/data/translate/FakeMessageTranslator.java` | Created | Bản giả dùng khi `USE_FAKE_DATA=true` trả bản dịch mẫu tức thì phục vụ offline UI dev/demo | `FakeChatRepository.java#L26-L54` | Không cần tải model 30MB khi chạy chế độ Fake |
| `app/src/main/java/com/utechat/data/translate/MessageTranslationManager.java` | Created | Phối hợp `TranslationDao` (cache Room), `ConnectivityManager` (kiểm tra mạng & `NET_CAPABILITY_NOT_METERED` để báo cần xác nhận dữ liệu di động), phát trạng thái `DOWNLOADING_MODEL` (indeterminate, không %), `TRANSLATED`, `ERROR` qua `mainExecutor` | `RealChatRepository.java#L726-L739`, `MainThreadExecutor.java#L8-L19` | Bảo toàn kiểm tra `sessionKey` trước khi ghi cache |

#### 1.3.8 Chat Room Integration, Layouts, Drawables, Strings & Tests

| Tệp mục tiêu | Trạng thái | Vai trò & Luồng dữ liệu | Tệp mẫu đối chiếu (`feature/phase-5-core`) | Ghi chú triển khai |
|---|---|---|---|---|
| `app/src/main/java/com/utechat/ui/chat/ChatRoomViewModel.java` | Modified | Thêm gửi media/location (`enqueueMedia`, `enqueueLocation`), gọi `MessageTranslationManager` (lưu `visibleTranslations` + `downloadingKeys` qua `SavedStateHandle`), `hideTranslation(clientId)`, khởi tạo cuộc gọi 1-1 | `ChatRoomViewModel.java#L49-L250` | Giữ tương thích ngược `ChatViewModelTest` |
| `app/src/main/java/com/utechat/ui/chat/ChatRoomFragment.java` | Modified | Gắn nút đính kèm (Ảnh/Camera/Voice/Video/File/Vị trí), thanh ghi âm voice, nút Gọi thoại trên toolbar chat 1-1, `ActiveRoomTracker.setActive(id)` ở `onResume`/`onPause`, nhấn giữ tin `TEXT` chọn *"Dịch sang Tiếng Việt"* / *"Dịch sang Tiếng Anh"* + hộp thoại xác nhận tải ~30MB qua 3G/4G, mở `ImageViewerFragment`/`VideoPlayerFragment`/`FileProvider`/`geo:` | `ChatRoomFragment.java#L45-L275` | Xin đúng quyền runtime tại thời điểm bấm nút (`RECORD_AUDIO`, `CAMERA`, `ACCESS_FINE_LOCATION`) |
| `app/src/main/java/com/utechat/ui/chat/MessageAdapter.java` | Modified | Thay summary stub bằng bind thật: ảnh (`Glide`), voice bubble (`VoiceBubblePlayer` + seekbar), video badge play, file card + progress/retry, location card (`lat, lng` + bấm mở `geo:`), call card (`CALL` state/reason); thêm khối dịch trong bong bóng `TEXT` (`1dp` divider + *"Đã dịch"* + *"Ẩn bản dịch"* + progress vô định *"Đang tải gói ngôn ngữ…"*) | `MessageAdapter.java#L37-L276` | Giữ `UNKNOWN_FALLBACK` cho loại tin tương lai lạ |
| `app/src/main/java/com/utechat/ui/chat/ChatErrors.java` | Modified | Bổ sung ánh xạ `FILE_TOO_LARGE`, `UNSUPPORTED_TYPE`, `FILE_MISSING`, `CALL_BUSY`, `CALL_UNSUPPORTED`, `CALL_NOT_FOUND`, `INVALID_STATE` | `ChatErrors.java#L11-L27` | Giữ nguyên switch-case tĩnh trả `@StringRes` |
| `app/src/main/res/navigation/nav_chat.xml` | Modified | Thêm fragment destinations `imageViewerFragment` & `videoPlayerFragment` và `<action>` từ `chatRoomFragment` | `nav_chat.xml#L21-L32` | Không sửa `nav_graph.xml` của nhóm trưởng (`ADR-025`) |
| `app/src/main/res/menu/menu_chat_room.xml` | Modified | Thêm item `action_voice_call` (`android:visible="false"`, chỉ hiện khi là chat `DIRECT` đang hoạt động) | `menu_chat_room.xml#L3-L8` | Icon tint `@color/ute_on_bar` |
| `app/src/main/res/layout/fragment_chat_room.xml` | Modified | Thêm nút đính kèm cạnh ô nhập, nút micro ghi voice & thanh trạng thái ghi âm (`00:00` + hủy/gửi) | `fragment_chat_room.xml#L65-L75` | Tương thích DayNight & kích thước chạm `>=48dp` |
| `app/src/main/res/layout/item_message_in.xml` & `item_message_out.xml` | Modified | Thêm container media (ảnh bo góc, thanh voice play/pause + seekbar, progress upload/download) và container bản dịch (divider `1dp`, nhãn *"Đã dịch"*, nội dung dịch, nút *"Ẩn bản dịch"*, progress vô định) | `item_message_in.xml#L12-L31`, `item_message_out.xml#L7-L23` | Giữ ID cũ (`bubble`, `text`, `meta`, `summary`, `failed`) để tránh vỡ binding |
| `app/src/main/res/layout/fragment_image_viewer.xml`, `fragment_video_player.xml`, `fragment_call.xml`, `dialog_attachment_picker.xml` | Created | Layout XML Material 3 DayNight cho màn xem ảnh pinch-zoom, trình phát `PlayerView` Media3, màn hình gọi thoại (`Mute`/`Speaker`/`End` `>=48dp`, `mm:ss`), bottom sheet chọn tệp | `fragment_devices.xml#L3-L33`, `themes.xml#L30-L41`, `colors.xml#L8-L40` | Dùng `@color/ute_*` và `?attr/colorSurface` |
| `app/src/main/res/drawable/ic_*.xml` | Created | Vector drawables DayNight-safe: `ic_attach.xml`, `ic_call.xml`, `ic_call_end.xml`, `ic_play.xml`, `ic_pause.xml`, `ic_speaker.xml`, `ic_mic_off.xml`, `ic_location.xml`, `ic_translate.xml`, `ic_download.xml` | `app/src/main/res/drawable/ic_mic.xml`, `ic_phone.xml`, `ic_camera.xml` | Kích thước 24dp vector chuẩn Material |
| `app/src/main/res/values/strings_chat.xml` & `values-vi/strings_chat.xml` | Modified | Chuỗi Media, Location, Progress upload/download, Thông báo kênh `messages`, Dịch ML Kit (*"Dịch sang Tiếng Việt"*, *"Dịch sang Tiếng Anh"*, *"Đang tải gói ngôn ngữ…"*, *"Đã dịch"*, *"Ẩn bản dịch"*, xác nhận dữ liệu di động ~30MB) và lỗi mới | `strings_chat.xml#L114-L142`, `values-vi/strings_chat.xml#L105-L124` | Mọi chuỗi đều có đủ 2 bản EN + VI (`ADR-021`, `ADR-025`) |
| `app/src/main/res/values/strings_call.xml` & `values-vi/strings_call.xml` | Created | Chuỗi màn gọi thoại, kênh thông báo `calls`, cuộc gọi nhỡ, trạng thái `RINGING`/`CONNECTED`/`ENDED`, fallback `ACTION_DIAL`, xin quyền `RECORD_AUDIO` | `strings_profile.xml#L1-L47` | Tách file `strings_call.xml` giảm xung đột tài nguyên |
| `app/src/test/java/com/utechat/data/worker/SendMessageWorkerMediaTest.java` | Created | Unit test JVM 2 kịch bản demo `D-04`: (1) chọn media khi offline -> có mạng gửi 2 pha; (2) rớt mạng giữa `POST /media` và `POST /messages` -> retry **không** upload lại; `413 FILE_TOO_LARGE` & `415 UNSUPPORTED_TYPE` -> `FAILED` + `Result.success()`; dọn file tạm | `SendMessageWorkerTest.java#L54-L405` | Chạy song song cùng `SendMessageWorkerTest` hiện hữu |
| `app/src/test/java/com/utechat/data/notifications/NotificationAndSyncWorkerTest.java` | Created | Unit test JVM: lọc tin của mình / `muted = true` / phòng đang mở, bỏ qua thông báo khi `bootstrap`, hủy thông báo & bỏ qua kết quả khi đổi `sessionKey`, `SyncWorker` gọi `SyncEngine` theo phiên | `SyncEngineAndApplierTest.java#L65-L120` | Dùng `InstantTaskExecutorRule` + Mockito |
| `app/src/test/java/com/utechat/data/call/CallSessionCoordinatorTest.java` | Created | Unit test JVM bọc `WebRtcCallEngine` giả: `RINGING -> ACTIVE -> ENDED`, `409 CALL_BUSY`, `403 BLOCKED`/`CONTACT_REQUIRED`, 45s timeout `MISSED`, `call.handled_elsewhere`, 2-device signaling guard, giữ state khi xoay màn hình | `SendMessageWorkerTest.java#L54-L122` | Không cần thiết bị thật |
| `app/src/test/java/com/utechat/data/translate/MessageTranslationManagerTest.java` | Created | Unit test JVM: cache hit trong `TranslationDao`, tự tải model trên Wi-Fi, hỏi xác nhận trên mạng di động, trạng thái chờ vô định không %, rớt mạng giữ nguyên tin gốc + thử lại, dịch offline sau khi có model | `RealAuthRepositoryTest.java#L25-L45` | Tiêm `Runnable::run` cho `disk`/`main` |
| `app/src/test/java/com/utechat/ui/chat/ChatViewModelTest.java` | Modified | Bổ sung kiểm tra bản dịch theo `(clientId, targetLang)` sống sót qua `SavedStateHandle` khi xoay màn hình và ẩn bản dịch | `ChatViewModelTest.java#L31-L133` | Giữ xanh toàn bộ test Phase 5 |
| `app/src/androidTest/java/com/utechat/data/local/AppDatabaseMigrationTest.java` | Modified | Bổ sung test `migrate2To3PreservesDataAndAddsTransferColumns` và `migrate1To3EndToEnd` qua `MigrationTestHelper` | `AppDatabaseMigrationTest.java#L24-L87` | Đọc cả `1.json`, `2.json`, `3.json` |
| `app/src/androidTest/java/com/utechat/data/local/Phase6RoomDaoTest.java` | Created | Instrumentation test SQLite thật cho `TransferDao` (`findByClientIdSync`, `updateProgress`, `markUploaded`) và `TranslationDao` (`observeByConversation`, `deleteOrphaned`) | `MessagingRoomAndFtsTest.java#L33-L109` | `Room.inMemoryDatabaseBuilder` |
| `app/src/androidTest/java/com/utechat/ui/chat/MediaProviderAndUiTest.java` | Created | Espresso UI test render thẻ ảnh/voice/file/location/call, bật/ẩn bản dịch trong bong bóng `TEXT`, kiểm tra nút `CallFragment` | `FoundationNavigationTest.java#L17-L48` | Chạy cùng `Injection.overrideChatRepositoryForTest` |

---

## 2. Backend Spring Boot 3 Patterns (`com.utechat.backend`)

### 2.1 Flyway `V6__media_and_calls.sql` & RLS Pattern
- **Phân loại:** *No analog (greenfield schema migration V6)*; đối chiếu quy chuẩn bảng, partial index và RLS tại:
  - `backend/src/main/resources/db/migration/V1__initial_schema.sql#L104-L200`
  - `backend/src/main/resources/db/migration/V5__sync_and_messaging.sql#L1-L52`
- **Bẫy & Quy chuẩn áp dụng (`D-10`, `DEPLOYMENT_SECURITY.md`):**
  1. Giữ nguyên `ENABLE ROW LEVEL SECURITY` đã có trên `attachments`, `calls`, `messages` (`V1__initial_schema.sql#L197-L199`). Nếu bổ sung bảng phụ bất kỳ, bắt buộc thêm `ALTER TABLE ... ENABLE ROW LEVEL SECURITY;`.
  2. Partial unique index `uq_calls_active_caller` & `uq_calls_active_callee` bảo vệ DB-level chống tạo 2 cuộc gọi `RINGING`/`ACTIVE` cùng lúc; `uq_messages_call_id` bảo đảm tuyệt đối 1 tin nhắn `CALL` duy nhất cho mỗi `call_id` (`D-10`).
  ```sql
  -- Phase 6: Khóa signaling 2 thiết bị, chống gọi song song (CALL_BUSY), timeout 45s và 1 tin CALL duy nhất.
  ALTER TABLE public.calls
      ADD COLUMN IF NOT EXISTS caller_device_id_hash VARCHAR(64),
      ADD COLUMN IF NOT EXISTS callee_device_id_hash VARCHAR(64),
      ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '45 seconds'),
      ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
      ADD COLUMN IF NOT EXISTS caller_alive_until TIMESTAMPTZ,
      ADD COLUMN IF NOT EXISTS callee_alive_until TIMESTAMPTZ;

  -- Giữ tệp cũ và gộp các lượt thử lại của cùng một người gửi.
  ALTER TABLE public.attachments ADD COLUMN IF NOT EXISTS upload_client_id UUID;
  CREATE UNIQUE INDEX IF NOT EXISTS uq_attachments_upload_client
      ON public.attachments (uploader_id, upload_client_id)
      WHERE upload_client_id IS NOT NULL;

  -- Chỉ mục bán phần giới hạn cuộc gọi đồng thời trong từng vai trò.
  CREATE UNIQUE INDEX IF NOT EXISTS uq_calls_active_caller
      ON public.calls (caller_id) WHERE state IN ('RINGING', 'ACTIVE');
  CREATE UNIQUE INDEX IF NOT EXISTS uq_calls_active_callee
      ON public.calls (callee_id) WHERE state IN ('RINGING', 'ACTIVE');

  -- Tăng tốc quét hết hạn 45 giây của CallService.
  CREATE INDEX IF NOT EXISTS idx_calls_ringing_expires
      ON public.calls (expires_at) WHERE state = 'RINGING';

  -- Bắt buộc mỗi cuộc gọi chỉ sinh đúng 1 tin nhắn CALL khi kết thúc (idempotent).
  CREATE UNIQUE INDEX IF NOT EXISTS uq_messages_call_id
      ON public.messages (call_id) WHERE call_id IS NOT NULL;
  ```

### 2.2 Media Controller, Disk Storage & HTTP `Range` Streaming (`MediaController`, `MediaService`)
- **Tệp mẫu đối chiếu:**
  - `backend/src/main/java/com/utechat/backend/controller/MessageController.java#L24-L55` (pattern `@AuthenticationPrincipal`, `parsePathUuid`, trả `201`)
  - `backend/src/main/java/com/utechat/backend/controller/ConversationController.java#L144-L151` (`parsePathUuid` ném `404 NOT_FOUND`)
  - `backend/src/main/java/com/utechat/backend/exception/GlobalExceptionHandler.java#L20-L51`
  - `backend/src/main/java/com/utechat/backend/service/MessageRateLimiter.java#L20-L75` (cho `MediaUploadRateLimiter`)
- **Phần Greenfield (HTTP `Range` + Magic-Byte Detection + `MaxUploadSizeExceededException`):**
  1. **Nâng giới hạn Multipart & chuẩn hóa `413`:** Spring Boot mặc định giới hạn `1MB` / `10MB`. Cần nâng lên `55MB` / `60MB` trong `application.yml` và bắt `MaxUploadSizeExceededException` ở `GlobalExceptionHandler`:
     ```java
     /** Bắt lỗi vượt trần Multipart tổng của Spring Boot để vẫn trả chuẩn JSON 413 FILE_TOO_LARGE. */
     @ExceptionHandler(MaxUploadSizeExceededException.class)
     public ResponseEntity<Map<String, Object>> handleMaxUpload(MaxUploadSizeExceededException e) {
         return body(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "File exceeds upload size limit", Map.of());
     }
     ```
  2. **Đường dẫn lưu trữ an toàn & kiểm quyền `GET /api/media/{id}` (`D-01`):**
     ```java
     // Ghi file an toàn theo yyyy/MM/<uuid>.ext; không bao giờ nối originalFilename từ client vào Path.
     Path resolved = rootDir.resolve(relativePath).normalize();
     if (!resolved.startsWith(rootDir.normalize())) {
         throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Invalid storage path");
     }

     // Kiểm tra quyền tải tệp: thành viên đang hoạt động VÀ tin nhắn nằm sau mốc visible_from_message_id (>).
     // Nếu tệp vừa upload chưa gắn vào tin nhắn nào: chỉ uploader_id mới được phép đọc.
     ```
  3. **Xử lý `HttpRange` (`206 Partial Content` / `416`):** Dùng `HttpRange.parseRanges(headers)` của Spring Web + `StreamingResponseBody` đọc từ `FileChannel.open(path, StandardOpenOption.READ)` theo đúng dải `[start, end]`, kèm header `Accept-Ranges: bytes`, `X-Content-Type-Options: nosniff`, `Content-Range: bytes start-end/total`. Từ chối multi-range hoặc vượt kích thước bằng `416` (`Content-Range: bytes */total`).

### 2.3 Mở rộng `MessageService` cho Media & Location
- **Tệp mẫu đối chiếu:** `backend/src/main/java/com/utechat/backend/service/MessageService.java#L93-L210`
- **Trích dẫn mẫu hiện hữu (`MessageService.java#L123-L137`):**
  ```java
  if ("GROUP".equals(convType)) {
      // Khóa hàng hội thoại trước khi đọc membership và cấp phát ID tin nhắn (tuần tự hóa với add/remove/re-add).
      jdbc.queryForList("SELECT id FROM conversations WHERE id = ? FOR UPDATE", conversationId);
      MemberGuard guard = loadMemberGuard(conversationId, actorId);
      if (guard == null || guard.left) {
          throw new ApiException(HttpStatus.FORBIDDEN, "NOT_A_MEMBER", "Not a member of this group");
      }
      ...
  ```
- **Áp dụng cho Phase 6:**
  - Thay kiểm tra `!"TEXT".equals(req.type())` tại `MessageService.java#L100-L102` bằng danh sách cho phép `TEXT`, `IMAGE`, `AUDIO`, `VIDEO`, `FILE`, `LOCATION` (tiếp tục chặn `SYSTEM`, `CALL` bằng `400 VALIDATION`).
  - Với `IMAGE`/`AUDIO`/`VIDEO`/`FILE`: parse UUID từ `req.mediaUrl()` theo regex `^/api/media/([0-9a-fA-F-]{36})$`, khóa dòng `SELECT uploader_id, kind FROM attachments WHERE id = ? FOR UPDATE`, bắt buộc `uploader_id.equals(actorId)` (`403 FORBIDDEN`) và `kind.equals(req.type())` (`400 VALIDATION`). Nếu `INSERT INTO messages ...` đụng `uq_messages_attachment` (`DuplicateKeyException`), kiểm tra nếu khác `(actorId, clientId)` thì ném `409 CONFLICT`.
  - Với `LOCATION`: bắt buộc `latitude != null && longitude != null` hữu hạn (`-90..90`, `-180..180`), `mediaUrl == null`.
  - Giữ nguyên `SELECT_MESSAGE_WITH_METADATA` (`MessageService.java#L24-L32`) và `mapMessageRow` (`#L245-L276`) — Phase 5 đã JOIN sẵn `attachments` và `calls`!

### 2.4 Call Lifecycle, Coturn HMAC-SHA1 & WebSocket Signaling (`CallService`, `TurnCredentialsService`, `ChatWebSocketHandler`)
- **Tệp mẫu đối chiếu:**
  - `backend/src/main/java/com/utechat/backend/service/ConversationService.java#L58-L74,L745-L760` (`lockPair` + `enforceDirectPolicy`)
  - `backend/src/main/java/com/utechat/backend/service/ConversationService.java#L658-L693` (insert tin nhắn hệ thống + cập nhật `conversations.last_message_id` + `SyncEventWriter`)
  - `backend/src/main/java/com/utechat/backend/service/SyncRetentionService.java#L28-L56` (`@Scheduled` + `Clock`)
  - `backend/src/main/java/com/utechat/backend/websocket/SyncEventRealtimeDispatcher.java#L41-L91`
  - `backend/src/main/java/com/utechat/backend/websocket/ChatWebSocketHandler.java#L68-L181`
  - `backend/src/main/java/com/utechat/backend/websocket/WebSocketSessionRegistry.java#L110-L154`
- **Quy chuẩn áp dụng cho `CallService` (`D-10`):**
  1. **Thứ tự khóa chống deadlock trong `startCall`:** Luôn gọi `policy.lockPair(callerId, calleeId)` TRƯỚC, sau đó khóa 2 dòng `users` theo thứ tự UUID tăng dần (`SELECT id FROM public.users WHERE id IN (?, ?) ORDER BY id FOR UPDATE`) để tuần tự hóa kiểm tra `409 CALL_BUSY` chéo vai trò (caller ở cuộc gọi này đồng thời được người thứ ba gọi).
  2. **Cập nhật nguyên tử (`accept`/`reject`/`end`/`timeout`):** Khóa `SELECT * FROM calls WHERE id = ? FOR UPDATE`, kiểm tra trạng thái hiện tại. Chèn tin `CALL` khi kết thúc qua `INSERT INTO messages (...) ON CONFLICT (call_id) WHERE call_id IS NOT NULL DO NOTHING RETURNING id` để replay/concurrent `end` chỉ tạo **đúng 1 tin `CALL`**.
     Dùng hạn đổ chuông của server để từ chối thao tác trễ. Mỗi thiết bị ACTIVE chỉ gia hạn mốc của mình qua `call.keepalive`. Khi một mốc 60 giây hết hạn, server kết thúc `FAILED/OFFLINE`.
  3. **Khóa signaling 2 thiết bị trên `ChatWebSocketHandler` (`D-10`):**
     ```java
     // Chỉ cho phép gửi call.offer / call.answer / call.ice khi cuộc gọi đang ACTIVE
     // và (userId, deviceIdHash) của kết nối khớp đúng thiết bị gọi hoặc nhận đã khóa.
     if (!"ACTIVE".equals(call.state())) return;
     if (managed.userId.equals(call.callerId()) && managed.deviceIdHash.equals(call.callerDeviceHash())) {
         registry.sendToDevice(call.calleeId(), call.calleeDeviceHash(), forwardJson);
     } else if (managed.userId.equals(call.calleeId()) && managed.deviceIdHash.equals(call.calleeDeviceHash())) {
         registry.sendToDevice(call.callerId(), call.callerDeviceHash(), forwardJson);
     }
     ```
  4. **Sinh credential coturn (`TurnCredentialsService`, *greenfield*, `D-09`):**
     ```java
     // Tạo cặp username = "<expiryEpoch>:<userId>" và credential = Base64(HMAC-SHA1(sharedSecret, username)).
     long expiry = clock.instant().getEpochSecond() + ttlSeconds;
     String username = expiry + ":" + userId;
     Mac mac = Mac.getInstance("HmacSHA1");
     mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
     String credential = Base64.getEncoder().encodeToString(mac.doFinal(username.getBytes(StandardCharsets.UTF_8)));
     ```

### 2.5 Backend Integration Tests (`EmbeddedPostgres` — KHÔNG Docker)
- **Tệp mẫu đối chiếu:**
  - `backend/src/test/java/com/utechat/backend/messaging/MessagingConcurrencyAndPolicyTest.java#L37-L121` (`EmbeddedPostgres` + `MockMvc` + `ExecutorService/CountDownLatch`)
  - `backend/src/test/java/com/utechat/backend/ws/WebSocketAuthAndRealtimeTest.java#L53-L109,L354-L420` (`RANDOM_PORT`, `MutableClock`, `TestWsClient`)
  - `backend/src/test/java/com/utechat/backend/config/FlywayMigrationTest.java#L34-L78`
- **Quy chuẩn:** Mọi test backend Phase 6 (`MediaApiAndRangeTest`, `CallLifecycleAndSignalingTest`) dùng `EmbeddedPostgres.builder().start()` trong `static {}` + `@DynamicPropertySource` y hệt `MessagingConcurrencyAndPolicyTest.java#L40-L59`. Tuyệt đối không dùng Testcontainers/Docker.

---

## 3. Android Data, Room v3, Media Pipeline & Notifications (`com.utechat.data`)

### 3.1 Room Schema v2 -> v3 (`MIGRATION_2_3`, `AttachmentTransferEntity`, `TransferDao`, `TranslationDao`)
- **Tệp mẫu đối chiếu:**
  - `app/src/main/java/com/utechat/data/local/AppDatabase.java#L14-L57`
  - `app/src/main/java/com/utechat/data/local/entity/AttachmentTransferEntity.java#L7-L51`
  - `app/src/main/java/com/utechat/data/local/dao/TransferDao.java#L9-L15`
  - `app/src/main/java/com/utechat/data/local/dao/TranslationDao.java#L9-L16`
  - `app/src/androidTest/java/com/utechat/data/local/AppDatabaseMigrationTest.java#L24-L87`
- **Mẫu `MIGRATION_2_3` cho `AppDatabase.java` (mở rộng theo `MIGRATION_1_2` ở `AppDatabase.java#L20-L30`):**
  ```java
  // Nâng cấp lược đồ Room từ v2 lên v3: mở rộng bảng attachment_transfer để lưu metadata media,
  // đường dẫn tệp riêng tư bền vững qua restart và phân biệt chiều UPLOAD / DOWNLOAD (D-03, D-04).
  public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
      @Override
      public void migrate(@NonNull SupportSQLiteDatabase db) {
          // Chiều truyền tệp: UPLOAD (gửi lên) hoặc DOWNLOAD (tải về cache).
          db.execSQL("ALTER TABLE attachment_transfer ADD COLUMN direction TEXT NOT NULL DEFAULT 'UPLOAD'");
          // Đường dẫn tuyệt đối trong filesDir/pending_media hoặc cacheDir/media_cache.
          db.execSQL("ALTER TABLE attachment_transfer ADD COLUMN localPath TEXT");
          // Loại media (IMAGE, AUDIO, VIDEO, FILE) gửi kèm form POST /api/media.
          db.execSQL("ALTER TABLE attachment_transfer ADD COLUMN kind TEXT");
          db.execSQL("ALTER TABLE attachment_transfer ADD COLUMN mime TEXT");
          db.execSQL("ALTER TABLE attachment_transfer ADD COLUMN sizeBytes INTEGER");
          db.execSQL("ALTER TABLE attachment_transfer ADD COLUMN fileName TEXT");
          db.execSQL("ALTER TABLE attachment_transfer ADD COLUMN durationMs INTEGER");
          db.execSQL("ALTER TABLE attachment_transfer ADD COLUMN width INTEGER");
          db.execSQL("ALTER TABLE attachment_transfer ADD COLUMN height INTEGER");
          // Mã lỗi bền vững khi upload/download thất bại (FILE_TOO_LARGE, UNSUPPORTED_TYPE, FILE_MISSING...).
          db.execSQL("ALTER TABLE attachment_transfer ADD COLUMN errorCode TEXT");
          db.execSQL("ALTER TABLE attachment_transfer ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0");
      }
  };
  ```
- **Mở rộng `TransferDao` & `TranslationDao`:**
  - `TransferDao`: thêm `@Query("SELECT * FROM attachment_transfer WHERE clientId = :id LIMIT 1") AttachmentTransferEntity findByClientIdSync(String id);`, `@Query("SELECT * FROM attachment_transfer") LiveData<List<AttachmentTransferEntity>> observeAll();`, `@Query("UPDATE attachment_transfer SET state = :state, progress = :progress, errorCode = :errorCode, updatedAt = :updatedAt WHERE clientId = :clientId") void updateState(String clientId, String state, int progress, @Nullable String errorCode, long updatedAt);`, `@Query("UPDATE attachment_transfer SET mediaUrl = :mediaUrl, state = 'UPLOADED', progress = 100, mime = COALESCE(:mime, mime), sizeBytes = COALESCE(:sizeBytes, sizeBytes), durationMs = COALESCE(:durationMs, durationMs), width = COALESCE(:width, width), height = COALESCE(:height, height), errorCode = NULL, updatedAt = :updatedAt WHERE clientId = :clientId") void markUploaded(...);`, `@Query("DELETE FROM attachment_transfer WHERE clientId = :clientId") void deleteByClientId(String clientId);`.
  - `TranslationDao`: giữ `insert` & `get`, thêm `@Query("SELECT * FROM message_translation WHERE messageKey = :key AND targetLang = :language LIMIT 1") MessageTranslationEntity findSync(String key, String language);`, `@Query("DELETE FROM message_translation WHERE messageKey = :key AND targetLang = :language") void deleteByKey(String key, String language);`, `@Query("DELETE FROM message_translation WHERE messageKey NOT IN (SELECT clientId FROM message)") void deleteOrphaned();`.

### 3.2 `FileProvider`, `MediaPreparationHelper` & `Media3 ExoPlayer` (Greenfield Standard Java 17)
- **Tệp mẫu đối chiếu:** *No analog (greenfield)* cho `FileProvider`, `Media3 ExoPlayer`, `GlideModule`, `FusedLocationProviderClient`; đối chiếu API & logging policy ở `ApiClient.java#L14-L38` và `AuthInterceptor.java#L13-L35`.
- **Mẫu chuẩn Java 17:**
  1. **Photo Picker & SAF trong Java 17 (`PickVisualMedia`):**
     ```java
     // Trong Java, enum VisualMediaType nằm ở singleton ImageOnly.INSTANCE / VideoOnly.INSTANCE của Kotlin object.
     pickImageLauncher.launch(new PickVisualMediaRequest.Builder()
             .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
             .build());
     ```
  2. **Chia sẻ file cache ra ứng dụng ngoài qua `FileProvider` (`D-03`, `DEPLOYMENT_SECURITY.md#L29`):**
     ```java
     // Tạo URI content:// an toàn từ cacheDir/media_cache/ và cấp quyền đọc tạm thời, không truyền JWT.
     Uri contentUri = FileProvider.getUriForFile(
             context,
             context.getPackageName() + ".fileprovider",
             cachedFile);
     Intent viewIntent = new Intent(Intent.ACTION_VIEW)
             .setDataAndType(contentUri, mime)
             .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
     ```
  3. **Stream video có `Range` & `Authorization: Bearer` với `Media3 ExoPlayer` (`D-03`):**
     ```java
     // Dùng OkHttpDataSource.Factory gắn Media OkHttpClient (đã có AuthInterceptor) để phát trực tiếp /api/media/{id}.
     DataSource.Factory dataSourceFactory = new OkHttpDataSource.Factory(ApiClient.getMediaOkHttpClient());
     ExoPlayer player = new ExoPlayer.Builder(requireContext())
             .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
             .build();
     String fullUrl = resolveBackendMediaUrl(BuildConfig.BACKEND_BASE_URL, relativeMediaUrl);
     player.setMediaItem(MediaItem.fromUri(fullUrl));
     player.prepare();
     ```
     *(Lưu ý bảo mật: `resolveBackendMediaUrl` kiểm tra `relativeMediaUrl.startsWith("/api/media/")` và ghép với `BuildConfig.BACKEND_BASE_URL` để `AuthInterceptor.BACKEND_HOST` luôn khớp, không bao giờ gửi Bearer sang host ngoài).*

### 3.3 Hàng đợi gửi Media ngoại tuyến (`SendMessageWorker` + `RealChatRepository` + `Injection`)
- **Tệp mẫu đối chiếu:**
  - `app/src/main/java/com/utechat/data/worker/SendMessageWorker.java#L155-L360`
  - `app/src/main/java/com/utechat/data/repository/real/RealChatRepository.java#L469-L570`
  - `app/src/main/java/com/utechat/di/Injection.java#L113-L129`
- **Trích dẫn mẫu hiện hữu (`SendMessageWorker.java#L254-L307`):**
  - Đã có sẵn: khóa `enqueuedSessionKey`, `runOnDisk`, `PreflightCheck`, kiểm `GROUP` `queuedVisibleFromMessageId`, xử lý `401` hủy đúng `queueName(enqueuedSessionKey)`, xử lý `429 Retry-After`, giới hạn `MAX_AUTO_ATTEMPTS = 3`.
- **Mở rộng 2 pha cho Phase 6 (`D-04`):**
  1. Nếu `type` là `IMAGE`/`AUDIO`/`VIDEO`/`FILE`:
     - Đọc `AttachmentTransferEntity transfer = db.transferDao().findByClientIdSync(clientId)`.
     - **Pha 1 (Upload):** Nếu `transfer.getMediaUrl() == null`: kiểm tra tệp tại `transfer.getLocalPath()` còn tồn tại (`FILE_MISSING` -> đánh `FAILED` + `Result.success()`). Tạo `CountingFileRequestBody` cập nhật `TransferDao.updateProgress(...)` theo ngưỡng (mỗi >=5% hoặc >=250ms) và gọi `mediaApi.uploadMedia("Bearer " + token, clientIdPart, kindPart, filePart, durationPart).execute()`.
       - Nếu `200`/`201`: **ghi ngay `mediaUrl` + metadata** vào `AttachmentTransferEntity` (`state = "UPLOADED"`) VÀ `MessageEntity.attachmentJson` bên trong `session.runIfCurrent(enqueuedSessionKey, ...)` **trước khi** gọi `POST /messages`! Nhờ vậy nếu mất mạng ngay sau khi upload xong, lần retry kế tiếp thấy `mediaUrl != null` và bỏ qua bước upload (`D-04`).
       - Nếu `413 FILE_TOO_LARGE` hoặc `415 UNSUPPORTED_TYPE` (hoặc `400`/`403`/`404`): đánh dấu `FAILED` cả `MessageEntity` & `AttachmentTransferEntity`, xóa tệp tạm `pending_media/` nếu là `413`/`415`, trả **`Result.success()`** để các tin sau trong hàng đợi không bị kẹt!
     - **Pha 2 (Send Message):** Dùng `mediaUrl` đã lưu gọi `chatApi.sendMessage("Bearer " + token, convId, new SendMessageRequest(clientId, msg.getType(), msg.getText(), mediaUrl, lat, lng, msg.getQueuedVisibleFromMessageId())).execute()`. Khi `200`/`201`, cập nhật `markSentFromWorker(...)`, đánh dấu `transfer` `COMPLETED` và **xóa tệp tạm `localPath`**.
  2. **Cô lập phiên trong `Injection.cleanupDepartingSession` (`Injection.java#L114-L129`):**
     Bổ sung hủy `SyncWorker.queueName(departingSessionKey)`, gọi `NotificationHelper.cancelAll(appContext)`, gọi `CallSessionCoordinator.onSessionCleared()`, và dọn sạch 3 thư mục `filesDir/pending_media/`, `cacheDir/media_cache/`, `cacheDir/camera/` trên luồng `DISK`.

### 3.4 Thông báo 2 kênh (`NotificationHelper`), `ActiveRoomTracker` & `SyncWorker`
- **Tệp mẫu đối chiếu:**
  - `app/src/main/java/com/utechat/data/worker/SendMessageWorker.java#L69-L104` (mẫu `queueName(sessionKey)` + `TAG_SESSION_PREFIX`)
  - `app/src/main/java/com/utechat/data/sync/SyncEngine.java#L152-L286` (single-flight mutex `running` + `resyncRequested`)
  - `app/src/main/java/com/utechat/MainActivity.java#L62-L77` (vào vùng đã đăng nhập + `NavController`)
- **Mẫu áp dụng (`D-05`..`D-08`):**
  1. `SyncWorker.enqueuePeriodic(WorkManager wm, String sessionKey)`:
     ```java
     // Lên lịch đồng bộ nền mỗi 15 phút (best-effort, không FCM), cô lập theo sessionKey.
     PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(SyncWorker.class, 15, TimeUnit.MINUTES)
             .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
             .addTag(TAG_SESSION_PREFIX + sessionKey)
             .build();
     wm.enqueueUniquePeriodicWork(queueName(sessionKey), ExistingPeriodicWorkPolicy.KEEP, req);
     ```
  2. `SyncEngine.syncBlocking(String expectedSessionKey)`: tái sử dụng `drainLoop()` / single-flight mutex của `SyncEngine` và trả về `true` nếu phiên còn khớp và đồng bộ xong sạch (để `SyncWorker` trả `Result.success()` hoặc `Result.retry()`). Tuyệt đối không tạo cơ chế đọc/ghi `sync_state` riêng trong `SyncWorker`.
  3. **Quy tắc lọc thông báo (`D-05`, `D-06`):** `SyncEngine.runBootstrap(...)` không kích hoạt `NotificationHelper`; chỉ `applyBatch(...)` từ `/sync` mới thu thập danh sách tin nhắn mới hợp lệ (`!myUserId.equals(senderId)`, `!conv.getMuted()`, `!convId.equals(activeRoomTracker.getActiveConversationId())`, `sessionKey.equals(session.sessionKey())`) để dựng `NotificationCompat.MessagingStyle` theo `conversationId.hashCode()`.

---

## 4. Android UI, WebRTC Call & ML Kit Translation Patterns (`com.utechat.ui`)

### 4.1 `CallService`, `WebRtcCallEngine` & `CallSessionCoordinator` (`D-09`..`D-12`)
- **Tệp mẫu đối chiếu:**
  - `app/src/main/java/com/utechat/data/sync/TypingTracker.java#L20-L125` (`DelayScheduler` + `LongSupplier clockMs` + `MutableLiveData`)
  - `app/src/main/java/com/utechat/data/sync/ChatWebSocketManager.java#L350-L398` (nhánh `switch (env.type)`)
  - `app/src/main/java/com/utechat/MainActivity.java#L24-L42` (`WindowInsetsCompat` edge-to-edge + `ViewBinding`)
- **Mẫu áp dụng & Chống crash Android 14+/16 (`targetSdk = 36`):**
  1. **Thứ tự xin `RECORD_AUDIO` trước `startForeground(..., MICROPHONE)`:**
     ```java
     // Android 14+ (API 34+) ném SecurityException nếu gọi startForeground(type=MICROPHONE) khi chưa có quyền RECORD_AUDIO.
     // Báo cuộc gọi đến qua thông báo hoặc mở CallFragment khi MainActivity đang hiển thị.
     // Đoạn này chạy trong thao tác Gọi hoặc Nhận trên CallFragment đang hiển thị.
     if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
         ContextCompat.startForegroundService(context, CallService.intentStart(context, callId));
     }
     ```
  2. **Early ICE Candidate Buffer trong `StreamWebRtcCallEngine`:**
     ```java
     // Lưu tạm remote ICE candidates nếu call.ice tới trước khi setRemoteDescription hoàn tất;
     // xả toàn bộ hàng đợi ngay bên trong SdpObserver.onSetSuccess() của setRemoteDescription.
     synchronized (iceLock) {
         if (!remoteDescriptionSet) {
             pendingRemoteIce.add(candidate);
             return;
         }
     }
     peerConnection.addIceCandidate(candidate);
     ```
  3. **Sống bền qua xoay màn hình (`D-11`):** `CallFragment` quan sát `CallSessionCoordinator.getUiState()` (thuộc `Injection`, sống cùng `CallService`), tính `mm:ss` từ `SystemClock.elapsedRealtime() - state.answeredAtElapsedRealtime`.

### 4.2 On-Device ML Kit Translation (`MessageTranslationManager` & `ChatRoomFragment` / `MessageAdapter`)
- **Tệp mẫu đối chiếu:**
  - `app/src/main/java/com/utechat/ui/chat/ChatRoomFragment.java#L62-L125,L239-L255` (`MaterialAlertDialogBuilder`, `Snackbar`)
  - `app/src/main/java/com/utechat/ui/chat/ChatRoomViewModel.java#L48-L98,L192-L249` (`SavedStateHandle`)
  - `app/src/main/java/com/utechat/ui/chat/MessageAdapter.java#L79-L185` (`ItemMessageInBinding` / `ItemMessageOutBinding`)
  - `app/src/main/java/com/utechat/data/MainThreadExecutor.java#L8-L19`
- **Mẫu áp dụng (`D-13`..`D-15`):**
  1. **Kiểm tra Wi-Fi vs Dữ liệu di động bằng `ConnectivityManager`:**
     ```java
     // Kiểm tra kết nối và phân biệt Wi-Fi (NOT_METERED) với dữ liệu di động trước khi tải gói ~30MB (D-15).
     Network active = connectivityManager.getActiveNetwork();
     NetworkCapabilities caps = active == null ? null : connectivityManager.getNetworkCapabilities(active);
     boolean hasInternet = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
     boolean isUnmetered = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
     ```
  2. **Không dùng Language ID (`D-13`):** Nhấn giữ tin `TEXT` trong `MessageAdapter` mở menu chọn trực tiếp:
     - `R.string.chat_translate_to_vi` (*"Dịch sang Tiếng Việt"*, `en -> vi`)
     - `R.string.chat_translate_to_en` (*"Dịch sang Tiếng Anh"*, `vi -> en`)
     - (Nếu đang hiện bản dịch: thêm `R.string.chat_translation_hide` *"Ẩn bản dịch"*).
  3. **Hiển thị trong bong bóng (`D-14`, `D-15`):**
     - `item_message_in.xml` & `item_message_out.xml` thêm khối `translation_box`: `View` kẻ mảnh `1dp`, nhãn `R.string.chat_translated_label` (*"Đã dịch"*), `TextView` nội dung bản dịch, nút `R.string.chat_translation_hide` (*"Ẩn bản dịch"*), và `LinearProgressIndicator` vô định (`android:indeterminate="true"`) kèm chữ `R.string.chat_translating_download` (*"Đang tải gói ngôn ngữ…"* — **tuyệt đối không hiển thị phần trăm ước lượng**).
     - `ChatRoomViewModel` giữ bản đồ `activeTranslationLangByClientId` trong `SavedStateHandle` để xoay màn hình vẫn giữ nguyên các bong bóng đang mở bản dịch.

### 4.3 XML DayNight, ViewBinding, Drawables & Strings
- **Tệp mẫu đối chiếu:**
  - `app/src/main/res/values/colors.xml#L8-L40` & `app/src/main/res/values-night/colors.xml#L5-L25`
  - `app/src/main/res/values/themes.xml#L4-L67`
  - `app/src/main/res/values/strings_chat.xml#L114-L142` & `values-vi/strings_chat.xml#L105-L124`
- **Quy chuẩn:** Mọi màu dùng `@color/ute_*` hoặc `?attr/colorSurface` / `?attr/colorOnSurface` / `?attr/colorOnSurfaceVariant` (tự động thích ứng Sáng/Tối); nút bấm trên màn gọi và trình phát media tối thiểu `48dp x 48dp`; mọi chuỗi mới của Chat/Media/Notification/Translate đặt trong `strings_chat.xml` (EN + VI), mọi chuỗi của Call đặt trong `strings_call.xml` (EN + VI).

---

## 5. Testing Patterns (Android JVM + Instrumentation)

### 5.1 Android JVM Unit Tests (`SendMessageWorkerMediaTest`, `NotificationAndSyncWorkerTest`, `CallSessionCoordinatorTest`, `MessageTranslationManagerTest`)
- **Tệp mẫu đối chiếu:**
  - `app/src/test/java/com/utechat/data/worker/SendMessageWorkerTest.java#L54-L405` (`InstantTaskExecutorRule`, Mockito `AppDatabase`/`SessionManager`/`WorkManager`, `Sleeper` giả, `syncCall(...)`)
  - `app/src/test/java/com/utechat/data/sync/SyncEngineAndApplierTest.java#L65-L120` (`FakeDelayScheduler`, `AtomicLong fakeClockMs`)
  - `app/src/test/java/com/utechat/ui/chat/ChatViewModelTest.java#L31-L146` (`SavedStateHandle`, `MessageAdapter.viewType`)
- **Quy chuẩn:** Inject `Runnable::run` cho `diskExecutor`/`mainExecutor`, inject `FakeDelayScheduler` + `AtomicLong` clock cho timeout 45s của `CallSessionCoordinator`, không đụng `Looper.getMainLooper()` thật hay `ArchTaskExecutor`.

### 5.2 Android Instrumentation Tests (`AppDatabaseMigrationTest`, `Phase6RoomDaoTest`, `MediaProviderAndUiTest`)
- **Tệp mẫu đối chiếu:**
  - `app/src/androidTest/java/com/utechat/data/local/AppDatabaseMigrationTest.java#L24-L87` (`MigrationTestHelper` kiểm `1 -> 2`)
  - `app/src/androidTest/java/com/utechat/data/local/MessagingRoomAndFtsTest.java#L33-L319` (`Room.inMemoryDatabaseBuilder`, `getOrAwaitValue` dùng `InstrumentationRegistry.getInstrumentation().runOnMainSync`)
  - `app/src/androidTest/java/com/utechat/FoundationNavigationTest.java` (Espresso)
- **Áp dụng cho `AppDatabaseMigrationTest`:**
  Mở rộng kiểm thử `helper.runMigrationsAndValidate(..., 3, true, AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)` từ cả bản v1 và v2 (đã có dòng `attachment_transfer` v2 và `message_translation` v2), xác nhận giữ nguyên dữ liệu cũ, 11 cột mới có giá trị mặc định hợp lệ (`direction = 'UPLOAD'`, `updatedAt = 0`), và trigger FTS4 vẫn hoạt động bình thường.

---

## 6. Shared Patterns & Anti-Patterns (Quy chuẩn bắt buộc cho Phase 6)

1. **100% Java 17 & Comment Tiếng Việt Sư phạm (`GEMINI.md`, `AGENTS.md`):**
   - **Bắt buộc:** Mọi file Java/XML/SQL/Keep tạo mới hoặc chỉnh sửa chỉ dùng Java 17 (tuyệt đối không tạo file `.kt`, không Coroutine/Flow/Compose/Hilt) và phải có comment Tiếng Việt súc tích, rõ bản chất từng biến, hàm, query, layout, migration để sinh viên đọc hiểu và giải trình trực tiếp khi bảo vệ đồ án.
2. **CẤM `ArchTaskExecutor` (`lintDebug` `RestrictedApi`):**
   - **Bắt buộc:** Mọi điều phối về main thread trong `app/src/main/**` dùng `MainThreadExecutor.INSTANCE` (`app/src/main/java/com/utechat/data/MainThreadExecutor.java`) hoặc `new Handler(Looper.getMainLooper())::post` (cho phép inject `Runnable::run` trong JVM test).
3. **Backend Tests chạy bằng `EmbeddedPostgres`, KHÔNG Docker:**
   - **Bắt buộc:** Theo mẫu `MessagingConcurrencyAndPolicyTest.java#L40-L59`, mọi integration test backend dùng `io.zonky.test:embedded-postgres:2.0.7`.
4. **Cô lập phiên nghiêm ngặt (`sessionKey`, `cleanupDepartingSession`):**
   - **Bắt buộc:** `SendMessageWorker`, `SyncWorker`, `NotificationHelper`, `CallSessionCoordinator`, `MessageTranslationManager` đều đối chiếu `sessionKey` / `versionKey` trước khi ghi Room, gửi mạng hay hiển thị UI/thông báo. Khi rời phiên (`logout`, `401`, `4401`, đổi tài khoản), `Injection.cleanupDepartingSession(...)` phải hủy cả `send-messages:<key>` lẫn `periodic-sync:<key>`, đóng WS, ngắt cuộc gọi, xóa toàn bộ thông báo và xóa thư mục `pending_media/`, `media_cache/`, `camera/`.
5. **Bảo mật Media, WebSocket & Coturn (`DEPLOYMENT_SECURITY.md`):**
   - **Anti-pattern cấm:** Không bao giờ gắn JWT hoặc query ticket vào URL (`/api/media/{id}?token=...` hay `/ws?token=...`); không dùng `TrustAll` hoặc bỏ qua kiểm tra hostname; không đưa `COTURN_SHARED_SECRET` vào APK; không mở `GET /api/media/{id}` thiếu kiểm tra thành viên hoặc `message.id > visible_from_message_id`.
   - **Bắt buộc:** Dùng `ApiClient.getMediaOkHttpClient()` (có `AuthInterceptor.BACKEND_HOST` guard) cho cả Retrofit, `Glide` và `Media3 ExoPlayer`, và chỉ chia sẻ tệp cache ra ngoài qua `FileProvider` + `FLAG_GRANT_READ_URI_PERMISSION`.
6. **Chống lỗi Runtime Android 14–16 (`targetSdk = 36`):**
   - **Anti-pattern cấm:** Không nâng `androidx.core:core` vượt quá `1.17.0` (`compileSdk = 36`); không thêm `mlkit:language-id` hoặc `play-services-maps`; không gọi `startForeground(..., FOREGROUND_SERVICE_MICROPHONE)` khi chưa được cấp `RECORD_AUDIO`; không giữ 2 `MediaPlayer` phát cùng lúc; không hiển thị `%` ước lượng khi tải model ML Kit (~30MB).

---

## 7. No Analog Found (Thành phần Greenfield & Mẫu chuẩn Java 17)

Các thành phần dưới đây chưa có file tương đồng trực tiếp trên `feature/phase-5-core`; Planner và Executor dùng các mẫu chuẩn Java 17 và đặc tả chi tiết trong [06-RESEARCH.md](06-RESEARCH.md) kết hợp Section 2–4 ở trên:

| Tệp mục tiêu | Lý do chưa có analog trên `feature/phase-5-core` | Tham chiếu chuẩn (`06-RESEARCH.md` & tài liệu này) |
|---|---|---|
| `backend/deploy/coturn/turnserver.conf` & `README.md` | Lần đầu cấu hình coturn TURN/TURNS trên secondary IP EC2 | [06-RESEARCH.md §5.2](06-RESEARCH.md#L302-L309), `DEPLOYMENT_SECURITY.md#L20` |
| `backend/src/main/resources/db/migration/V6__media_and_calls.sql` | Lần đầu bổ sung cột device hash, partial unique `CALL_BUSY` và `uq_messages_call_id` | [06-RESEARCH.md §3](06-RESEARCH.md#L169-L207), mục 2.1 ở trên |
| `MediaController`, `MediaService`, `MediaDtos` | Codebase chỉ mới có stub `MediaApi.java` rỗng phía Android; backend chưa có xử lý file đĩa & HTTP `Range` | [06-RESEARCH.md §5.1](06-RESEARCH.md#L244-L282), mục 2.2 ở trên |
| `TurnCredentialsService`, `CallController`, `CallService`, `CallDtos` | Codebase mới có bảng `calls` trong `V1` và payload stub `CALL_UPDATED` ở `SyncEventApplier` | [06-RESEARCH.md §5.2](06-RESEARCH.md#L284-L301), mục 2.4 ở trên |
| `res/xml/file_paths.xml`, `UteChatGlideModule`, `MediaPreparationHelper`, `VoiceRecorderHelper`, `MediaDownloadManager`, `LocationFetchHelper` | Lần đầu dùng `FileProvider`, `ExifInterface`, `Glide` `@GlideModule`, `MediaRecorder`, `FusedLocationProviderClient` | [06-RESEARCH.md §1, §6.1](06-RESEARCH.md#L62-L106,L315-L345), mục 3.2 ở trên |
| `ZoomableImageView`, `ImageViewerFragment`, `VideoPlayerFragment`, `VoiceBubblePlayer` | Lần đầu dùng `Media3 ExoPlayer` (`OkHttpDataSource`), custom pinch-zoom `Matrix`, single `MediaPlayer` | [06-RESEARCH.md §1, §6.1](06-RESEARCH.md#L62,L66,L341-L344), mục 3.2 ở trên |
| `NotificationHelper`, `ActiveRoomTracker`, `SyncWorker` | Lần đầu dùng `NotificationChannel`, `MessagingStyle`, `PendingIntent.FLAG_IMMUTABLE`, `PeriodicWorkRequest` | [06-RESEARCH.md §6.2](06-RESEARCH.md#L346-L364), mục 3.4 ở trên |
| `WebRtcCallEngine`, `StreamWebRtcCallEngine`, `CallSessionCoordinator`, `CallService`, `CallFragment`, `CallViewModel` | Lần đầu tích hợp `stream-webrtc-android:1.3.10`, `FOREGROUND_SERVICE_MICROPHONE` và audio routing | [06-RESEARCH.md §6.3](06-RESEARCH.md#L365-L377), mục 4.1 ở trên |
| `MessageTranslator`, `RealMlKitTranslator`, `FakeMessageTranslator`, `MessageTranslationManager` | Lần đầu tích hợp `com.google.mlkit:translate:17.0.3` & `ConnectivityManager` metered check | [06-RESEARCH.md §6.4](06-RESEARCH.md#L378-L393), mục 4.2 ở trên |
