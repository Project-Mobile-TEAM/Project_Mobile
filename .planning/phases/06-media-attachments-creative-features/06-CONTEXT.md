# Phase 6: Media Attachments & Creative Features - Context

**Gathered:** 2026-11-21
**Status:** Ready for planning

<domain>
## Phase Boundary

Phase 6 xây dựng trọn vẹn phân hệ **Đa phương tiện, Thông báo & Đồng bộ nền, và 2 Tính năng Sáng tạo** cho UTEChat (`REQ-messaging-media-attachments`, `REQ-notifications-sync`, `REQ-voice-call-webrtc`, `REQ-message-translation` — phụ trách bởi Thành viên 5, độc lập trong `feature/media`, `feature/call`, `feature/translate`), tiếp nối nền tảng Phase 5 trên `feature/phase-5-core` (`AppDatabase` v2 với `AttachmentTransferEntity`/`TransferDao` và `MessageTranslationEntity`/`TranslationDao`, `SendMessageWorker`, `SyncEngine`, `ChatRoomFragment` đã có placeholder cho tin non-TEXT):
1. **Đa phương tiện & Vị trí tọa độ nhẹ:** Upload/tải xuống an toàn qua `POST /api/media` và `GET /api/media/{id}` (HTTP `Range`, header `Authorization: Bearer`, không query ticket/JWT trong URL), hỗ trợ đủ 4 loại cốt lõi (Ảnh nén, Voice `.m4a`, Video, File tài liệu) + gửi Vị trí tọa độ nhẹ (`LOCATION` qua `geo:` Intent), mở ngoài bằng `FileProvider`, và tích hợp hàng đợi upload offline theo phiên vào `SendMessageWorker`.
2. **Thông báo & Đồng bộ nền 15 phút:** 2 `NotificationChannel` V1 (`messages` và `calls`), lọc thông báo tin của chính mình / hội thoại `muted` / phòng đang mở, xin quyền `POST_NOTIFICATIONS` trên Android 13+, và `PeriodicWorkRequest` 15 phút (best-effort, không FCM) gọi qua `SyncEngine` single-flight.
3. **Cuộc gọi thoại 1-1 WebRTC & coturn (Sáng tạo chính):** Signaling qua REST (`/api/calls/*`) + WebSocket `/ws`, sinh `iceServers[]` HMAC-SHA1 cho coturn từ biến môi trường (fallback STUN công khai cho local dev + `turnserver.conf` mẫu cho EC2), kiểm soát quyền bạn bè `ACCEPTED`/không block/`CALL_BUSY`/timeout 45s/`call.handled_elsewhere`/khóa signaling 2 thiết bị/chỉ 1 tin `CALL` idempotent, cùng màn hình gọi + `Foreground CallService` bọc sau `WebRtcCallEngine`.
4. **Dịch tin nhắn On-device ML Kit (Sáng tạo dự phòng):** Nhấn giữ tin `TEXT` chọn trực tiếp chiều dịch *"Dịch sang Tiếng Việt"* hoặc *"Dịch sang Tiếng Anh"* (không thêm thư viện Language Identification), tải gói ngôn ngữ (~30MB) có xác nhận dữ liệu di động và chỉ báo chờ vô định, hiển thị bản dịch ngay trong bong bóng tin nhắn và lưu cache vào Room `message_translation`.

*Lưu ý phối hợp nhánh:* Kế hoạch Phase 6 được chuẩn bị sẵn trong thời gian Phase 5 hoàn tất kiểm thử tay trên `feature/phase-5-core`; Thành viên 5 sẽ bắt đầu code sau khi người dùng xác nhận triển khai.

</domain>

<decisions>
## Implementation Decisions

### Đa phương tiện & Lưu trữ tệp (Media, Location, FileProvider, Offline Upload)
- **D-01:** Backend lưu trữ tệp đa phương tiện (`POST /api/media`) trên ổ đĩa cục bộ của server, cấu hình thư mục qua biến môi trường `MEDIA_STORAGE_DIR`, hỗ trợ HTTP `Range` streaming trực tiếp trên `GET /api/media/{id}` (bảo vệ bằng header `Authorization: Bearer`, kiểm tra thành viên hội thoại 403, không query ticket / không JWT trong URL — khớp `docs/FLOWS.md` F-18 & `docs/DEPLOYMENT_SECURITY.md`).
- **D-02:** Phạm vi loại tin nhắn đa phương tiện trong Phase 6 bao gồm đủ 4 loại cốt lõi (Ảnh nén cạnh dài ≤ 1600px / ≤ 10MB, Voice note `.m4a` AAC ≤ 5 phút / ≤ 10MB, Video ≤ 50MB, File tài liệu ≤ 25MB kiểm tra MIME cho phép) **cộng thêm** gửi Vị trí tọa độ nhẹ (`LOCATION` lấy tọa độ một lần qua `FusedLocationProviderClient`, hiển thị thẻ tọa độ và bấm mở Google Maps ngoài qua Intent `geo:` — không cần nhúng `SupportMapFragment` hay đăng ký Google Maps SDK API key).
- **D-03:** Trải nghiệm hiển thị & phát trên Android: xem Ảnh toàn màn hình trong app (zoom/pinch), phát Voice `.m4a` trực tiếp ngay trên bong bóng chat (play/pause + thanh tiến độ), phát Video trong app bằng `Media3 ExoPlayer` (kèm nút "Mở bằng app ngoài" qua `FileProvider` + `FLAG_GRANT_READ_URI_PERMISSION`), và tải/mở File tài liệu bằng app ngoài qua `FileProvider` sau khi tải về bộ nhớ cache bằng Bearer token.
- **D-04:** Hàng đợi gửi Media ngoại tuyến — tận dụng hàng đợi `SendMessageWorker` theo phiên đăng nhập của Phase 5 (`send-messages:<sessionKey>`): sao chép tệp vào thư mục riêng bền vững của app, lưu tiến độ trong Room `attachment_transfer` (`AttachmentTransferEntity`) và hiển thị tin ở trạng thái `SENDING`. Worker upload trước (`POST /api/media`), **lưu `mediaUrl` ngay khi nhận được** rồi gửi tin (`POST /api/conversations/{id}/messages`) với cùng `clientId`. Nếu đã có `mediaUrl`, lần thử lại dùng kết quả upload đã lưu (không upload trùng). Lỗi mạng thử lại có giới hạn theo quy tắc Phase 5; lỗi `413 FILE_TOO_LARGE` hoặc `415 UNSUPPORTED_TYPE` chuyển tin sang `FAILED` ngay và cho hàng đợi tiếp tục xử lý tin khác. Dọn tệp tạm sau khi server xác nhận đã nhận tin hoặc khi người dùng xóa tin chờ/lỗi. Bắt buộc kiểm thử 2 tình huống demo: (1) chọn tệp khi đang offline; (2) rớt mạng sau khi upload xong nhưng trước khi gửi tin thành công. — **Reversibility:** costly — mở rộng pipeline `SendMessageWorker` và trạng thái `attachment_transfer` dùng chung với Phase 5.
  - Dùng cùng `clientId` ở cả upload và gửi tin. Backend trả lại upload đã lưu theo `(uploaderId, clientId)` nếu phản hồi upload bị mất.

### Thông báo & Đồng bộ nền 15 phút (NotificationChannel & WorkManager)
- **D-05:** Quy tắc lọc và gom nhóm thông báo tin nhắn mới: không phát thông báo cho tin do chính mình gửi, tin từ hội thoại đang `muted = true`, hoặc tin thuộc đúng phòng chat (`conversationId`) đang mở trên màn hình; gom nhóm thông báo theo từng hội thoại (`NotificationCompat.MessagingStyle`), bấm vào mở thẳng `ChatRoomFragment` đúng hội thoại đó.
- **D-06:** Dùng đúng **2 `NotificationChannel`** trong V1: kênh `messages` báo tin nhắn mới (hiển thị tên người gửi và đoạn tóm tắt nội dung hoặc nhãn loại tệp) và kênh `calls` dành cho cuộc gọi thoại đến qua WebSocket (mức ưu tiên cao) và cuộc gọi nhỡ. Giữ lời mời kết bạn trong màn Danh bạ (không tạo kênh thông báo riêng cho lời mời kết bạn). Khi đăng xuất, hết phiên (`401`/`4401`) hoặc đổi tài khoản: hủy toàn bộ thông báo của phiên cũ và bỏ qua các kết quả đến muộn từ phiên đó.
- **D-07:** Quyền `POST_NOTIFICATIONS` trên Android 13+ (API 33+): xin quyền 1 lần duy nhất sau khi đăng nhập thành công vào `MainActivity`; nếu người dùng từ chối, không hỏi lặp lại làm phiền và ứng dụng vẫn đồng bộ dữ liệu bình thường.
- **D-08:** Đồng bộ nền định kỳ `PeriodicWorkRequest` 15 phút theo phiên (`ExistingPeriodicWorkPolicy.KEEP`, ràng buộc `NetworkType.CONNECTED`, hủy ngay khi logout/`401`/`4401`): Worker gọi qua đúng `SyncEngine` single-flight của Phase 5 để không tranh chấp con trỏ `sync_state` với WebSocket/foreground, chỉ phát thông báo sau khi áp dụng dữ liệu vào Room thành công (best-effort, không FCM).
  - Truyền khóa phiên qua WorkManager `inputData`. Chỉ giữ khóa điều phối khi đổi trạng thái, nhả trước HTTP/Room. Gắn phiên gốc vào thông báo và kiểm tra quyền hội thoại khi mở.

### Cuộc gọi thoại 1-1 WebRTC & coturn (Voice Call & Signaling)
- **D-09:** Cấu hình STUN/TURN (`coturn`): Backend đọc cấu hình từ biến môi trường (`COTURN_URLS`, `COTURN_SHARED_SECRET`, `COTURN_TTL_SECONDS`) để sinh credential tạm thời chuẩn coturn REST (`HMAC-SHA1`) cho `iceServers[]` trong `POST /api/calls` và `POST /api/calls/{id}/accept`; hỗ trợ cả TCP/TLS 443 và UDP 3478/443; nếu môi trường local dev chưa bật coturn thì fallback STUN công khai để test nhanh trong LAN, đồng thời cung cấp file cấu hình mẫu `turnserver.conf` (và hướng dẫn chạy) cho EC2.
- **D-10:** Vòng đời cuộc gọi thoại 1-1 & bất biến signaling theo F-21 và F-22: Server chỉ cho phép gọi giữa hai người dùng đã kết bạn `ACCEPTED` và không bị chặn (`403 BLOCKED` ưu tiên trước `403 CONTACT_REQUIRED`, nhóm trả `400 CALL_UNSUPPORTED`). Khi một bên đang bận, trả HTTP `409 CALL_BUSY` và không đổ chuông. Sau 45 giây chưa có phản hồi, server tự chuyển `RINGING` sang `MISSED`. Thao tác nhận (`accept`), từ chối (`reject`) và hết hạn (`timeout`) phải cập nhật trạng thái **nguyên tử** để chỉ đúng một kết quả được ghi nhận. Khi một thiết bị nhận hoặc từ chối thành công, gửi `call.handled_elsewhere` tới các thiết bị còn lại của người nhận để dừng chuông. Sau khi nhận, **chỉ đúng hai thiết bị đang tham gia cuộc gọi** được phép trao đổi signaling (`call.offer`, `call.answer`, `call.ice`) cho `callId` đó. Với mỗi cuộc gọi đã lưu, chỉ ghi **đúng một tin nhắn `CALL`** khi kết thúc (`ENDED`/`MISSED`/`REJECTED`/`BUSY`/`FAILED`), kể cả khi yêu cầu kết thúc/từ chối được gửi lại (idempotent). Bắt buộc đồng bộ `docs/API_CONTRACT.md` và `docs/FLOWS.md` với các quy tắc này trước khi triển khai. — **Reversibility:** one-way — cập nhật và khóa hợp đồng REST/WebSocket signaling cuộc gọi trong `docs/API_CONTRACT.md` và `docs/FLOWS.md`.
  - Đồng hồ server quyết định hết hạn đổ chuông. Mỗi thiết bị đang kết nối ICE gửi keepalive 15 giây một lần. Khi một thiết bị không gia hạn trong 60 giây, server kết thúc cuộc gọi `FAILED/OFFLINE` và giải phóng trạng thái bận.
- **D-11:** Giao diện và vòng đời cuộc gọi thoại trên Android: màn hình gọi riêng (`CallFragment` trong `MainActivity`) kết hợp `Foreground CallService` giữ `PeerConnection` sống bền qua xoay màn hình, đầy đủ 3 nút điều khiển cốt lõi (Tắt/Bật mic, Loa ngoài/Loa trong, Kết thúc), đồng hồ đếm giờ `mm:ss`, chuông/rung khi nhận `call.incoming`, và kiểm tra/xin quyền `RECORD_AUDIO` trước khi gọi hoặc nhận máy.
  - Dịch vụ microphone chỉ bắt đầu từ thao tác gọi hoặc nhận khi màn hình gọi đang hiển thị và đã có quyền. Thử APK Release và TURN/TLS 443 sớm trên thiết bị demo.
- **D-12:** Thư viện WebRTC & seam kiểm thử: dùng artifact WebRTC Android ổn định trên Maven Central (`io.getstream:stream-webrtc-android`, tương thích `compileSdk = 36` và Java 17), bọc sau interface `WebRtcCallEngine` để unit test JVM kiểm thử toàn bộ state machine (`RINGING`/`CONNECTED`/`ENDED`/`MISSED`/`BUSY`/`CALL_BUSY`/`call.handled_elsewhere`/device signaling lock) và kiểm thử cuộc gọi thật 2 máy qua LAN/coturn. — **Reversibility:** costly — khóa abstraction `WebRtcCallEngine` và dependency WebRTC Android.

### Dịch tin nhắn On-device ML Kit (Translation UX & Model Management)
- **D-13:** Chiều dịch Anh – Việt: **không thêm thư viện nhận diện ngôn ngữ (Language Identification)** để giữ APK nhẹ và tránh nhận diện nhầm câu ngắn; trong menu nhấn giữ tin nhắn `TEXT`, cung cấp trực tiếp 2 lựa chọn *"Dịch sang Tiếng Việt"* và *"Dịch sang Tiếng Anh"*.
- **D-14:** Hiển thị & lưu cache bản dịch: hiển thị bản dịch ngay bên dưới nội dung gốc trong cùng bong bóng tin nhắn (đường kẻ mảnh phân tách, nhãn nhỏ *"Đã dịch"* và nút *"Ẩn bản dịch"*); chỉ áp dụng cho tin `TEXT` (không hiện cho `SYSTEM`, `CALL`, media không text); lưu cache bền vững trong Room `message_translation` (`MessageTranslationEntity`) theo `(message, targetLang)`.
- **D-15:** Chính sách tải gói ngôn ngữ ML Kit (~30MB): tải gói ngôn ngữ khi người dùng yêu cầu dịch lần đầu; cho phép tải trực tiếp qua Wi-Fi và **hỏi xác nhận nếu đang dùng dữ liệu di động**; hiển thị *"Đang tải gói ngôn ngữ…"* cùng chỉ báo chờ vô định (indeterminate progress — **không hiển thị phần trăm ước lượng**); nếu chưa có mạng hoặc tải thất bại, giữ nguyên tin gốc và cho thử lại; khi gói đã tải xong, dịch Anh–Việt 100% offline trên thiết bị và lưu kết quả vào Room.

### the agent's Discretion
- Bố cục chi tiết thẻ ảnh/video/voice/file/location, thiết kế cụ thể nút đính kèm trên khung soạn tin `ChatRoomFragment`, cấu trúc lưu file con theo ngày/UUID bên trong `MEDIA_STORAGE_DIR`, và chi tiết màn hình xem ảnh toàn màn hình / trình phát `Media3 ExoPlayer`.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing:**

### Architecture, Contracts & Security Specs
- `docs/API_CONTRACT.md` — Mục 2.4 (`messages` payload, `mediaUrl`, `attachmentId`), Mục 2.5 (`POST /media`, `GET /media/{id}` HTTP `Range` + `FileProvider`), Mục 2.6 (`POST /calls`, `/accept`, `/reject`, `/end`, `iceServers[]`), Mục 3 (WebSocket `call.incoming`, `call.accepted`, `call.ended`, `call.offer`, `call.answer`, `call.ice`, `call.handled_elsewhere`), Mục 4 (Rate limits: 20 uploads/min, 5 calls/min), Mục 5 (Error codes), Mục 6 (Notification policy & WorkManager 15p best-effort).
- `docs/FLOWS.md` — Mục 3 (`F-11` hàng đợi offline WorkManager gồm media upload trước gửi tin), Mục 6 (`F-18` ảnh/voice/video/file/vị trí, `FileProvider`), Mục 7 (`F-19` `NotificationChannel` & sync nền 15p; `F-20` Dịch tin nhắn ML Kit), Mục 8 (`F-21` Cuộc gọi thoại WebRTC & timeout 45s; `F-22` Cuộc gọi nhỡ), Mục 9 (`F-23` i18n, DayNight, giữ trạng thái cuộc gọi khi xoay màn hình).
- `docs/ERD.md` — Bảng server (`attachments`, `calls`, `messages`, `sync_events`) và Room entities (`attachment_transfer`, `message_translation`, `message`, `sync_state`).
- `docs/ARCHITECTURE_DECISIONS.md` — ADR-011 (Media & `FileProvider`), ADR-012 (Notification & sync 15p không FCM), ADR-013 (WebRTC & coturn TLS 443 secondary IP), ADR-014 (ML Kit on-device translation), ADR-018 (JWT & RBAC), ADR-020..ADR-026 (Migration, i18n/DayNight, Kiến trúc Android, Kiểm thử, Anti-conflict, Comment Tiếng Việt).
- `docs/DEPLOYMENT_SECURITY.md` — Bắt buộc HTTPS/WSS trên Release, không query ticket/JWT trong URL, Bearer header trên WS & `/api/media/{id}`, coturn TLS 443 trên secondary IP, kiểm quyền thành viên/bạn bè tại server.
- `.planning/phases/05-realtime-offline-first-core-messaging/05-CONTEXT.md` — D-05 (`SendMessageWorker` & `clientId`), D-07 (`SyncEngine` single-flight), D-15 (ranh giới `MessageAdapter` non-TEXT metadata).
- `GEMINI.md` & `AGENTS.md` — Ràng buộc 100% Java 17 (cấm Kotlin), Views+XML, MVVM LiveData, 100% comment Tiếng Việt sư phạm.

### Database & Existing Codebase Assets
- `backend/src/main/resources/db/migration/V1__initial_schema.sql` — Schema PostgreSQL bảng `attachments`, `calls`, `messages`, `sync_events`.
- `app/src/main/java/com/utechat/data/local/AppDatabase.java` — Room v2 với đủ 10 entities gồm `AttachmentTransferEntity` (`TransferDao`) và `MessageTranslationEntity` (`TranslationDao`).
- `app/src/main/java/com/utechat/data/worker/SendMessageWorker.java` — Chuỗi gửi tin theo phiên `send-messages:<sessionKey>`.
- `app/src/main/java/com/utechat/data/sync/SyncEngine.java` — Single-flight sync `/api/sync` và `/api/sync/bootstrap`.
- `app/src/main/java/com/utechat/data/sync/ChatWebSocketManager.java` — Kết nối `/ws` Bearer header, cần mở rộng chuyển tiếp frame `call.offer`/`call.answer`/`call.ice`/`call.incoming`/`call.accepted`/`call.ended`/`call.handled_elsewhere`.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **Room Entities & DAOs đã tạo sẵn từ Phase 2:** `AttachmentTransferEntity` / `TransferDao` (`attachment_transfer`) và `MessageTranslationEntity` / `TranslationDao` (`message_translation`) đã nằm trong `AppDatabase` (hiện đang ở `version = 2` từ Phase 5).
- **SQL Server Tables đã tạo sẵn từ Phase 1:** `V1__initial_schema.sql` đã có bảng `attachments` và `calls` cùng khóa ngoại gắn trên `messages` (`attachment_id`, `call_id`, `latitude`, `longitude`).
- **Offline Queue & Sync Engine từ Phase 5:** `SendMessageWorker` (`send-messages:<sessionKey>`), `SyncEngine` (single-flight), `SyncEventApplier`, `ChatWebSocketManager`, và `SessionManager` (hủy chuỗi/ngắt WS khi rời phiên).
- **UI Hooks từ Phase 5:** `MessageAdapter` đã tách view types hiển thị thẻ tóm tắt an toàn cho tin non-TEXT và giữ nguyên toàn bộ metadata `attachmentJson` trong `MessageEntity`.

### Established Patterns
- **100% Java 17 + Seam Architecture:** Mọi tầng đều giữ `Interface` + `Fake...` + `Real...` qua `Injection.java`; tách abstraction (`WebRtcCallEngine`, media/translator wrapper) để chạy unit test JVM 100% không cần thiết bị thật.
- **Bảo vệ cô lập phiên đăng nhập:** Mọi thông báo `NotificationChannel`, hàng đợi `SendMessageWorker`, `PeriodicWorkRequest`, tệp tạm upload và kết nối cuộc gọi/WS phải hủy hoặc bỏ qua kết quả đến muộn khi đăng xuất/`401`/`4401`/đổi tài khoản.

### Integration Points
- `ChatRoomFragment` / `ChatRoomViewModel` / `MessageAdapter`: bổ sung nút đính kèm (Ảnh, Voice, Video, File, Vị trí), nút gọi thoại trên toolbar chat 1-1, trình phát voice/video/ảnh toàn màn hình, mở file/`geo:` Intent, và menu nhấn giữ dịch tin `TEXT` + hiển thị bản dịch ngay trong bong bóng.
- `MainActivity`: xin quyền `POST_NOTIFICATIONS` 1 lần sau đăng nhập, đăng ký `PeriodicWorkRequest` 15 phút và điều hướng từ notification intent vào thẳng `ChatRoomFragment` hoặc `CallFragment`.
- `ChatWebSocketManager` & `SyncEventApplier`: xử lý sự kiện `call.*` (realtime signaling không lưu cursor vs sự kiện trạng thái `CALL_UPDATED` qua `sync_events`).

</code_context>

<specifics>
## Specific Ideas

- **An toàn Retry Media Upload (`D-04`):** Upload tệp trước, lưu `mediaUrl` xuống Room ngay khi nhận được rồi mới gửi tin với cùng `clientId`; khi retry dùng lại `mediaUrl` đã lưu để không upload trùng; kiểm thử bắt buộc 2 kịch bản: chọn tệp khi đang offline, và rớt mạng giữa lúc đã upload xong nhưng chưa kịp gửi tin nhắn.
- **Hai kênh thông báo & cô lập phiên (`D-06`):** Dùng đúng 2 kênh V1 (`messages` và `calls`), giữ lời mời kết bạn trong màn Danh bạ; hủy sạch thông báo cũ và bỏ qua phản hồi trễ khi đăng xuất/hết phiên/đổi tài khoản.
- **Bất biến WebRTC & Signaling (`D-10`):** Cập nhật nguyên tử cho accept/reject/45s timeout; `409 CALL_BUSY` không đổ chuông; `call.handled_elsewhere` dừng chuông thiết bị khác; sau khi nhận máy, khóa signaling chỉ cho đúng 2 thiết bị đang tham gia `callId`; chỉ ghi đúng 1 tin `CALL` khi kết thúc dù request gửi lại; đồng bộ rõ các quy tắc này vào `docs/API_CONTRACT.md` và `docs/FLOWS.md` trước khi triển khai.
- **Dịch ML Kit gọn nhẹ & trung thực tiến độ (`D-13`, `D-15`):** Không dùng thư viện Language Identification; cho chọn trực tiếp *"Dịch sang Tiếng Việt"* / *"Dịch sang Tiếng Anh"*; hỏi xác nhận khi tải model (~30MB) bằng dữ liệu di động; hiển thị *"Đang tải gói ngôn ngữ…"* kèm chỉ báo chờ vô định, không hiện % ước lượng giả lập.

</specifics>

<deferred>
## Deferred Ideas

- Thông báo đẩy FCM khi ứng dụng bị force-stop hoàn toàn (`FEAT-push-fcm`) → thuộc **V2**.
- Cuộc gọi video 1-1 và gọi nhóm (`FEAT-call-video`) → thuộc **V2**.
- Nhúng bản đồ `SupportMapFragment` trực tiếp trong app (cần Google Maps SDK API key) → không dùng trong V1 (dùng thẻ tọa độ + mở app ngoài `geo:`).
- Kênh thông báo riêng cho lời mời kết bạn → không dùng trong V1 (giữ thông báo/danh sách lời mời trong màn Danh bạ).

</deferred>

---

*Phase: 06-media-attachments-creative-features*
*Context gathered: 2026-11-21*
