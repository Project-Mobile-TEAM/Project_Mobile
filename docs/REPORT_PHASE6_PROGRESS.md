# Báo cáo tiến độ Phase 6 — Media, Cuộc gọi thoại WebRTC, Vị trí & Thông báo (06-01 đến 06-05)

**Nhánh Git:** `feature/phase-6-base`  
**Ngày cập nhật:** 2026-11-23  
**Tiến độ hiện tại:** Hoàn thành, rà soát tuân thủ `.planning/` và kiểm chứng **5/9** plan (`06-01`, `06-02`, `06-03`, `06-04`, `06-05`) — Toàn bộ tầng **Backend** và tầng **Android Data/Worker/Sync/Notification** đã hoàn thiện, vá sạch các edge-case tích hợp và sẵn sàng chuyển sang giai đoạn UI & WebRTC client (`06-06` → `06-09`).

---

## 1. Tổng quan trạng thái từng Plan

| Plan | Phạm vi chính | Commit | Trạng thái | Kết quả kiểm thử |
|---|---|---|---|---|
| **06-01** | Tài liệu (`API_CONTRACT`, `FLOWS`, `ERD`), PostgreSQL Migration `V6`, Backend Media Storage + HTTP Range (`206`/`416`) + `LOCATION` | `e3d91f9` *(+ bản vá rà soát)* | ✅ Hoàn thành & đã rà soát | `MediaStorageAndRangeApiTest` (9/9 — đủ ma trận) + `FlywayMigrationTest` pass |
| **06-02** | Backend WebRTC 1-1 (`CallService`, `CallController`), Coturn HMAC-SHA1 (`TurnCredentialsService`, `turnserver.conf`), khóa báo hiệu WS 2 thiết bị & `call.keepalive` | `c3e0851` *(+ bản vá rà soát)* | ✅ Hoàn thành & đã rà soát | **313/313** backend tests pass (`BUILD SUCCESS`) |
| **06-03** | Khóa phụ thuộc Phase 6 (Java 17, `compileSdk = 36`), Manifest/FileProvider, `ApiClient` Media, Room v3 (`MIGRATION_2_3`, `3.json`), `TransferDao`/`TranslationDao` | `07c20b8` | ✅ Hoàn thành code | `testDebugUnitTest`, `compileDebugAndroidTestJavaWithJavac`, `lintDebug`, `lintRelease`, `assembleRelease` pass (**16 KB ELF aligned**); ⏳ chờ chạy `connectedDebugAndroidTest` & Release smoke trên thiết bị thật |
| **06-04** | Hàng đợi gửi Media 2 pha (`SendMessageWorker`), `MediaApi`/`MediaDtos`, `UteChatGlideModule`, nén ảnh EXIF, ghi âm `.m4a`, `MediaDownloadManager` + `FileProvider`, `LocationFetchHelper` (`geo:`) | `959ef1e` *(+ bản vá rà soát)* | ✅ Hoàn thành & đã rà soát | `MediaSendWorkerTest` (10/10) + `SendMessageWorkerTest` (8/8) + `MediaDownloadManagerTest` (2/2) pass |
| **06-05** | Thông báo 2 kênh (`messages`, `calls`), `ActiveRoomTracker`, `NotificationHelper`, `SyncWorker` 15 phút dùng chung single-flight `SyncEngine.syncBlocking`, chuỗi i18n EN/VI | `87c8006` *(+ bản vá rà soát)* | ✅ Hoàn thành & đã rà soát | **253/253** Android JVM tests (22 suites, gồm `NotificationAndSyncWorkerTest` 5/5, `SyncEngineAndApplierTest` 8/8) + `lintDebug` pass |
| **06-06** | Android WebRTC Engine, `CallService` (foreground microphone), `CallCoordinator` & `CallFragment` | — | ⏳ Chưa triển khai | Chờ kế tiếp |
| **06-07** | Android ML Kit Translate (`EN <-> VI`) & cache cục bộ `TranslationDao` | — | ⏳ Chưa triển khai | Chờ kế tiếp |
| **06-08** | Nối dây DI (`Injection`), `MainActivity` deep link thông báo, `RealChatRepository` cho Media/Location/Call/Notification | — | ⏳ Chưa triển khai | Chờ kế tiếp |
| **06-09** | Giao diện Phòng chat Phase 6 (bubble Ảnh/Audio/Video/File/Vị trí/Cuộc gọi, Media3 player, dịch tin nhắn, xem ảnh toàn màn hình) & kiểm thử cuối Phase | — | ⏳ Chưa triển khai | Chờ kế tiếp |

---

## 2. Chi tiết kỹ thuật các hạng mục đã hoàn thành

### 2.1. Backend Media Storage, Range Streaming & Schema V6 (`06-01`)
- **Migration `V6__media_and_calls.sql`**: bổ sung 6 cột định danh thiết bị và liveness cho `public.calls` (`caller_device_id_hash`, `callee_device_id_hash`, `expires_at`, `updated_at`, `caller_alive_until`, `callee_alive_until`), cột `upload_client_id` cho `public.attachments`, và 5 partial unique/lookup index chống tranh chấp ở tầng CSDL (`uq_attachments_upload_client`, `uq_calls_active_caller`, `uq_calls_active_callee`, `idx_calls_ringing_expires`, `uq_messages_call_id`).
- **Media API (`MediaController`, `MediaService`, `MediaUploadRateLimiter`)**:
  - Lưu trữ cô lập theo đường dẫn `<MEDIA_STORAGE_DIR>/<yyyy>/<MM>/<uuid>.<ext>`, chống path traversal, làm sạch tên tệp gốc an toàn không gọi `Paths.get` trên chuỗi chưa tin cậy.
  - Kiểm tra magic bytes thực tế (JPEG/PNG/WebP VP8/VP8L/VP8X, ADTS AAC & ISOBMFF M4A đọc box `mvhd` với bảo vệ `boxSize < 8`, MP4/WebM, PDF, nhận diện chính xác OOXML `.docx`/`.xlsx`/`.pptx` vs ZIP, và UTF-8 text); từ chối tuyệt đối SVG/HTML (kể cả trên `kind=FILE` hoặc có UTF-8 BOM), PE/ELF/shebang và gói APK (`AndroidManifest.xml`) với `415 UNSUPPORTED_TYPE`; giới hạn dung lượng theo loại (`413 FILE_TOO_LARGE`) và tần suất `20 tệp / 60 giây / user` (`429 RATE_LIMITED`).
  - Upload idempotent theo `(uploader_id, upload_client_id)` kiểm tra trước rate limit (`201` mới / `200` lặp / `409` khác loại); khi đua đồng thời, tranh quyền hàng DB trước bằng `ON CONFLICT DO NOTHING RETURNING id`, chỉ luồng thắng cuộc mới công bố file lên đĩa và tự xóa file đã công bố nếu transaction rollback qua `TransactionSynchronization`.
  - Tải xuống `GET /api/media/{id}` chỉ nhận header `Authorization: Bearer`, kiểm tra tệp chưa gắn / thành viên đang hoạt động / rời nhóm / thêm lại nhóm (`message.id > visible_from_message_id`), hỗ trợ HTTP Range `206 Partial Content` và `416 Range Not Satisfiable` (vượt biên, sai cú pháp, multi-range).
- **`MessageService`**: hỗ trợ đầy đủ 6 loại tin từ client (`TEXT`, `IMAGE`, `AUDIO`, `VIDEO`, `FILE`, `LOCATION`), kiểm tra biên độ tọa độ, khóa hàng `attachments FOR UPDATE`, kiểm tra chủ sở hữu tệp (`403`), khớp loại (`400`), chống gắn trùng (`409`) và giữ nguyên kiểm tra `expectedVisibleFromMessageId` cho nhóm (`409 MEMBERSHIP_CHANGED`).

### 2.2. Backend Cuộc gọi thoại WebRTC 1-1 & Coturn (`06-02`)
- **Coturn EC2 (`backend/deploy/coturn/turnserver.conf`, `README.md`)**: cấu hình mẫu gắn Secondary Elastic IP (UDP/TCP `3478`, TURNS TLS 1.2/1.3 `443` không tranh cổng Nginx), xác thực `use-auth-secret` không lưu secret thật trong repo, và chặn đầy đủ dải IP nội bộ/SSRF (`127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`, loopback/link-local IPv6).
- **`TurnCredentialsService` & `CallRateLimiter`**: sinh credential tạm thời chuẩn coturn REST (`username = <expiry>:<userId>`, `credential = Base64(HMAC-SHA1)`), tự fallback về public STUN khi chạy local/LAN; giới hạn `5 cuộc gọi / 60 giây / user`.
- **`CallService` & `CallController`**:
  - Kiểm tra chính sách đúng thứ tự: `400 CALL_UNSUPPORTED` (GROUP / non-VOICE) → `403 BLOCKED` → `403 CONTACT_REQUIRED` → `429 RATE_LIMITED` → `409 CALL_BUSY`.
  - Khóa chống deadlock: `lockPair` → `users ORDER BY id FOR UPDATE` → partial unique index V6.
  - Chuyển trạng thái nguyên tử dưới `SELECT ... FOR UPDATE`; cắt mốc thời gian về micro giây (`ChronoUnit.MICROS`) khớp chính xác `TIMESTAMPTZ` của PostgreSQL; quét tự động `@Scheduled(fixedDelay = 5_000)` cho hạn đổ chuông 45s (`MISSED/TIMEOUT`, đính kèm `CallerInfoDto` trong payload `CALL_UPDATED` để Android dựng thông báo cuộc gọi nhỡ ổn định kể cả khác batch `/api/sync`) và liveness 60s khi `ACTIVE` (`FAILED/OFFLINE`).
  - Ghi duy nhất **1 tin nhắn `CALL`** trên mỗi cuộc gọi kết thúc nhờ `uq_messages_call_id` + `ON CONFLICT DO NOTHING`.
- **WebSocket Signaling (`WebSocketSessionRegistry`, `SyncEventRealtimeDispatcher`, `ChatWebSocketHandler`)**:
  - Định tuyến `CALL_UPDATED` sang `call.incoming`, `call.accepted`, `call.ended`, và `call.handled_elsewhere` (gửi bằng `sendToUserExceptDevice`).
  - Khóa báo hiệu 2 thiết bị cho `call.offer`/`call.answer`/`call.ice` (chỉ chuyển tiếp giữa đúng `caller_device_id_hash` và `callee_device_id_hash` khi `ACTIVE` và lease còn hạn, bỏ qua thiết bị thứ 3, không ghi `sync_events`).
  - Xử lý `call.keepalive` gia hạn lease riêng từng bên lên `now + 60s` với giới hạn `1 lần / 10 giây / thiết bị`.

### 2.3. Android Dependencies, FileProvider & Room v3 Gate (`06-03`)
- **Phụ thuộc thuần Java 17 (`gradle/libs.versions.toml`, `app/build.gradle.kts`)**: khóa `core:1.17.0`, `activity:1.10.1`, `media3:1.11.1`, `play-services-location:21.4.0` (không có `play-services-maps`), `stream-webrtc-android:1.3.10`, `mlkit:translate:17.0.3` (không có `language-id`), `exifinterface:1.4.2`, và `glide:5.0.5` (điều chỉnh từ `5.0.10` vì `5.0.10` yêu cầu `compileSdk >= 37` trong AAR metadata, trong khi dự án khóa `compileSdk = 36`).
- **Bảo mật Manifest & R8**: khai báo `FileProvider` chỉ mở 3 thư mục cô lập (`pending_media/`, `media_cache/`, `camera/`), không xin quyền đọc bộ nhớ rộng (`READ_EXTERNAL_STORAGE`/`READ_MEDIA_*`), thêm `<queries>` (`geo:` và `*/*`), `MainActivity` `launchMode="singleTop"`, và keep rules R8 cho WebRTC/ML Kit/Glide/DTO.
- **Native 16 KB Page Alignment**: kiểm tra ELF trên bản Release APK xác nhận cả `libjingle_peerconnection_so.so` và `libtranslate_jni.so` (`arm64-v8a` & `x86_64`) đều đạt `0x4000` (16 KB aligned).
- **Room v3 (`AppDatabase`, `MIGRATION_2_3`, `3.json`)**: bổ sung 11 cột cho `attachment_transfer` với migration tường minh (không `fallbackToDestructiveMigration()`), mở rộng `TransferDao` và `TranslationDao`, viết đầy đủ `AppDatabaseMigrationTest` (`1->2`, `2->3`, `1->2->3`) và `Phase6RoomDaoTest`.

### 2.4. Android Offline Media Queue 2 Pha & Helpers (`06-04`)
- **`UteChatGlideModule` & `ApiClient`**: tích hợp `OkHttpUrlLoader` dùng `ApiClient.getMediaOkHttpClient()` (timeout 20s/60s/120s, `AuthInterceptor` khóa host backend, `resolveBackendMediaUrl` chống lộ Bearer token).
- **Helpers (`data/media/`)**:
  - `MediaPreparationHelper`: xoay ảnh theo EXIF, thu nhỏ $\le 1600$px, nén JPEG $\le 10$MB (loại bỏ EXIF GPS), sao chép video ($\le 50$MB) và tài liệu cho phép ($\le 25$MB, có fallback suy luận đuôi theo MIME SAF khi provider không trả phần mở rộng, cấm `.apk`) vào `filesDir/pending_media/`.
  - `VoiceRecorderHelper`: ghi âm `.m4a` AAC tối đa 5 phút / 10MB trực tiếp vào `pending_media/`.
  - `MediaDownloadManager`: tải bằng Bearer token vào tệp `.part` tách theo phiên, kiểm tra `session.runIfCurrent` trước khi công bố (có fallback khi filesystem không hỗ trợ `ATOMIC_MOVE`) vào `cacheDir/media_cache/` và `TransferDao`, chia sẻ qua `FileProvider` với `FLAG_GRANT_READ_URI_PERMISSION`, đã kiểm chứng JVM kịch bản đổi tài khoản giữa lúc đang tải (`MediaDownloadManagerTest`).
  - `LocationFetchHelper`: định vị 1 lần qua `FusedLocationProviderClient` và tạo `Intent` `geo:` mở bản đồ ngoài.
- **`SendMessageWorker` 2 pha**: giữ nguyên 100% luồng `TEXT` Phase 5; hỗ trợ `LOCATION`; thực thi upload-trước-gửi-sau cho `IMAGE`/`AUDIO`/`VIDEO`/`FILE`, lưu ngay `mediaUrl` vào `TransferDao` + `MessageDao.updateAttachmentJson`, bỏ qua upload khi retry nếu đã có `mediaUrl`, xử lý `413`/`415` không làm kẹt hàng đợi, và xóa tệp tạm sau khi server xác nhận.

### 2.5. Thông báo 2 kênh & Đồng bộ nền 15 phút (`06-05`)
- **Chuỗi i18n (`strings_notifications.xml` EN & VI)** + **`ActiveRoomTracker`** (an toàn chuyển phòng với `clearIfMatches`).
- **`NotificationHelper`**:
  - Chỉ đăng ký **đúng 2 kênh V1** (`messages`, `calls`), không tạo kênh lời mời kết bạn.
  - Kiến trúc thuần Java tách riêng `NotificationSink` và `AndroidNotificationSink` (dùng `MessagingStyle` gom theo hội thoại + summary khi $\ge 2$ hội thoại, `PendingIntent` `FLAG_IMMUTABLE` định danh duy nhất theo `(sessionKey, destination)` trỏ về `MainActivity`, kiểm tra `canUseFullScreenIntent()` trên Android 14+ và `POST_NOTIFICATIONS` trên Android 13+).
  - Lọc nghiêm ngặt: tin tự gửi, tin đã đọc ở thiết bị khác (`serverId <= lastReadId`, kể cả `RECEIPT_UPDATED` đến sau trong cùng lô `/api/sync`), hội thoại `muted`, phòng đang mở, tin `CALL` trên kênh `messages`, bootstrap và phiên đã đổi; `cancelAll()` xóa sạch thông báo và bộ đệm RAM.
- **`SyncWorker` + `SyncEngine.syncBlocking` + `SyncEventApplier.applyBatchDetailed`**:
  - `SyncWorker` chạy định kỳ 15 phút (`periodic-sync:<sessionKey>`, `KEEP`, `NetworkType.CONNECTED`, lưu `sessionKey` trong `inputData`).
  - Dùng chung mutex `syncLock` với `SyncEngine.requestSync()` (thoát `drainLoop` nguyên tử, không bao giờ có 2 writer cùng ghi `sync_state`, kết quả gắn chặt với đúng phiên).
  - Bắn thông báo incremental chỉ **sau khi** transaction Room đã commit và phiên còn khớp; tự đối chiếu bổ sung `CALL_UPDATED(MISSED)` với `MESSAGE_CREATED(type=CALL)` cùng lô; `runBootstrap` tuyệt đối không phát thông báo.

---

## 3. Tổng hợp kết quả kiểm thử tự động

| Bộ kiểm thử | Lệnh chạy | Kết quả |
|---|---|---|
| **Backend (Full Suite)** | `cd backend && ./mvnw test` | **313 tests**, 0 failures, 0 errors (`BUILD SUCCESS`) |
| **Android JVM Unit Tests** | `./gradlew testDebugUnitTest --no-daemon` | **253 tests** (22 test suites), 0 failures, 0 errors (`BUILD SUCCESSFUL`) |
| **Android Lint & Release Build** | `./gradlew compileDebugAndroidTestJavaWithJavac lintDebug lintRelease assembleRelease --no-daemon` | **0 errors**, Release APK tạo thành công, thư viện native đạt 16 KB aligned (`0x4000`) |
| **Android Instrumented Tests** | `connectedDebugAndroidTest` (`AppDatabaseMigrationTest`, `Phase6RoomDaoTest`) | Đã biên dịch xanh (`compileDebugAndroidTestJavaWithJavac`); ⏳ cần chạy trên emulator/thiết bị thật khi kết nối ADB |

---

## 4. Ghi chú & Bước tiếp theo
1. **Kiểm thử trên thiết bị thật / emulator (từ `06-03`)**:
   - Chạy instrumentation Room v3 (`1->2->3`):
     ```bash
     ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.utechat.data.local.AppDatabaseMigrationTest,com.utechat.data.local.Phase6RoomDaoTest --no-daemon
     ```
   - Thực hiện Release APK smoke test trên thiết bị demo để xác nhận WebRTC khởi tạo và ML Kit tải model không bị R8 xóa class.
2. **Triển khai phần còn lại của Phase 6**: `06-06` (WebRTC Android & Call UI) → `06-07` (ML Kit Translate EN↔VI) → `06-08` (DI, `MainActivity` deep link, Repository wiring) → `06-09` (Chat Room Phase 6 UI & kiểm thử hoàn chỉnh).
