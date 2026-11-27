# Roadmap: UTEChat

## Overview

Hành trình xây dựng ứng dụng chat di động UTEChat cho nhóm 5 người theo mô hình **Kiến trúc chống xung đột (Anti-Conflict Architecture)** và **Tiến độ bậc thang an toàn (Staggered Pipeline)**. 

- Nhóm trưởng đảm nhiệm **Phase 1** tạo lập CSDL Supabase 13 bảng V1 làm nguồn chân lý duy nhất.
- **Phase 2** thiết lập khung ứng dụng Walking Skeleton và "khóa chết" 6 điểm nóng gây conflict (Single Activity, Nested Nav Graphs, Split Resource Strings, Modular Retrofit APIs, Pre-wired Room Entities, chuẩn hóa thư viện nền tảng).
- **Phase 3 đến Phase 6** phân chia độc lập tương ứng cho 4 thành viên thực hiện các lát cắt dọc tính năng theo thứ tự phụ thuộc chặt chẽ: Auth $\rightarrow$ Contacts $\rightarrow$ Core Messaging $\rightarrow$ Media & Creative Features (kèm 2FA TOTP & Friend-step).
- **Phase 7** bổ sung các thao tác tương tác tin nhắn nâng cao: gỡ tin nhắn 24 giờ, xóa tin nhắn ở phía bạn, bày tỏ cảm xúc 6 emoji và tải/gỡ bộ nhãn dán để gửi sticker.
- **Phase 8** cả nhóm phối hợp kiểm thử toàn diện, tối ưu và đóng gói bài nộp đạt điểm tuyệt đối 100/100 theo rubric.

## Phases

- [x] **Phase 1: Database & Supabase Infrastructure** - Thiết lập CSDL PostgreSQL trên Supabase với đúng 13 bảng V1, khóa ngoại, RLS default deny, indexes và dữ liệu seed mẫu (Nhóm trưởng)
- [x] **Phase 2: Foundation & Anti-Conflict Architecture** - Khung app Android MVVM, backend kết nối Supabase, thông luồng /api/health và khóa 6 điểm nóng gây conflict (completed 2026-11-11)
- [x] **Phase 3: Authentication & Profile Management** - Đăng ký SĐT E.164, OTP demo, đăng nhập 2 chế độ (chống brute-force bằng auth_throttles: PHONE_DEVICE, PHONE), 2FA TOTP + nhận diện bạn bè, quản lý hồ sơ và quyền riêng tư, soft delete tài khoản, đa ngôn ngữ & DayNight (Thành viên 2)
- [x] **Phase 4: Contacts & Friendships** - Quản lý danh bạ bạn bè, tìm kiếm SĐT chính xác, gửi/nhận/chấp nhận/từ chối lời mời kết bạn (contact_requests), quản lý bạn bè và danh sách chặn (contacts) (Thành viên 3)
- [x] **Phase 5: Realtime & Offline-First Core Messaging** - Nhắn tin 1-1 (kiểm tra allow_messages_from_non_contacts) và nhóm qua REST + WebSocket thuần JSON mang syncEventId, hàng đợi Room WorkManager, đồng bộ sự kiện qua syncEventId và tìm kiếm FTS4 tiếng Việt không dấu (Thành viên 4)
- [x] **Phase 6: Media Attachments & Creative Features** - Gửi ảnh/voice/video/file qua FileProvider, gọi thoại 1-1 WebRTC giữa bạn bè ACCEPTED qua coturn (sáng tạo chính) và dịch tin nhắn ML Kit on-device (sáng tạo dự phòng) (Thành viên 5, completed 2026-11-27)
- [x] **Phase 7: Message Actions, Reactions & Stickers** - Gỡ tin nhắn trong vòng 24 giờ, xóa tin nhắn ở phía bạn, bày tỏ cảm xúc 6 emoji và tải/gỡ bộ nhãn dán để gửi sticker (kể cả qua hàng đợi offline)
- [ ] **Phase 8: Testing, Quality & Release Packaging** - Kiểm thử toàn bộ chức năng theo từng phase trên Android Studio (4 thành viên TV1–TV4 test đầy đủ kể cả phần Phase 6 của TV5; phần việc cho TV5 phân công riêng sau) (Cả nhóm)

---

## Phase Details

### Phase 1: Database & Supabase Infrastructure

**Goal**: Nhóm trưởng thiết lập toàn bộ hạ tầng CSDL PostgreSQL trên Supabase, định nghĩa đúng 13 bảng thuộc phạm vi V1, khóa ngoại, RLS default deny (không tạo policy), chỉ mục và nạp dữ liệu mẫu chuẩn làm nền móng vững chắc cho cả nhóm.
**Depends on**: Nothing (bắt đầu trước tiên)
**Requirements**: Nền móng CSDL 13 bảng V1 phục vụ toàn bộ các yêu cầu
**Phụ trách**: Nhóm trưởng (Database & Điều phối)
**Success Criteria** (what must be TRUE):

  1. Các bảng CSDL thuộc phạm vi V1 (đúng 13 bảng: `users`, `user_devices`, `otp_challenges`, `auth_sessions`, `auth_throttles`, `contact_requests`, `contacts`, `conversations`, `conversation_members`, `messages`, `attachments`, `calls`, `sync_events`) theo `docs/ERD.md` được tạo thành công trên Supabase với UUID v4, kiểu dữ liệu chuẩn và khóa ngoại cascade/set null chính xác. Bảng `users` có cột `allow_messages_from_non_contacts BOOLEAN NOT NULL DEFAULT TRUE`.
  2. Bảng `auth_throttles` được tạo với khóa UNIQUE `(scope_type, scope_key_hash, purpose)` hỗ trợ `PHONE`, `DEVICE`, `PHONE_DEVICE`. Bảng `contact_requests` kiểm tra ràng buộc `requester_id <> addressee_id` và chống tạo lời mời ngược chiều khi đang PENDING. Bảng `contacts` quản lý quan hệ bạn bè (`blocked = false`) và danh sách chặn (`blocked = true`). Bảng `sync_events` tạo index `(user_id, id)`, `payload_json` JSONB và chính sách lưu trữ 30 ngày.
  3. Bật RLS trên mọi bảng ứng dụng, không tạo policy: anon/authenticated không đọc hoặc ghi được dòng; JDBC owner/BYPASSRLS của backend truy cập theo mô hình M2. Không đồng nhất RLS default deny với việc tắt toàn bộ HTTP Data API.
  4. Tạo đầy đủ các chỉ mục B-tree (cho SĐT, `(user_id, id)` trong `sync_events`) và composite index `(conversation_id, id DESC)` cho truy vấn phân trang tin nhắn tốc độ cao.
  5. Nạp sẵn bộ dữ liệu mẫu chuẩn (Seed Data): cung cấp dữ liệu vừa đủ (5 đến 10 tài khoản) để kiểm thử đầy đủ gửi/nhận lời mời kết bạn, danh bạ bạn bè, chặn/bỏ chặn, kiểm tra tắt nhận tin người lạ, chat 1-1, chat nhóm 3 người, chuẩn bị tài khoản cho upload ảnh/voice/video/file và gọi WebRTC ở Phase 6. Không seed storage_path giả hoặc tuyên bố nghiệp vụ media đã chạy.
  6. Xuất file mẫu cấu hình `.env.example` (chỉ chứa placeholder: `SUPABASE_URL=`, `SUPABASE_ANON_KEY=`, `SUPABASE_SERVICE_ROLE_KEY=`) và tài liệu hướng dẫn `SUPABASE_SETUP.md`; tuyệt đối KHÔNG đưa giá trị thật của service key vào Git theo đúng ADR-007.

**Plans**: 4/4 plans hoàn thành và nghiệm thu Supabase Cloud PASS

- [x] 01-01: Cấu hình và hướng dẫn an toàn
- [x] 01-02: DDL 13 bảng core và RLS
- [x] 01-03: Seed demo tách riêng
- [x] 01-04: SQL verifier và script chẩn đoán

**Verification**: passed — 8/8 tiêu chí xác minh đã nghiệm thu thành công (xem 01-VERIFICATION.md và 01-UAT.md).
**UI hint**: no

### Phase 2: Foundation & Anti-Conflict Architecture

**Goal**: Walking Skeleton Android MVVM + Backend kết nối Supabase và khóa6 điểm xung đột.
**Depends on**: Phase 1
**Requirements**: REQ-ui-standards
**Phụ trách**: Nhóm trưởng & Cả nhóm
**Success Criteria**:

1. Java17, minSdk26/compileSdk36/targetSdk36, package com.utechat; dependencies tập trung.
2. Một MainActivity, ViewBinding, Fragment và BottomNavigation3 tab.
3. Chuỗi common/auth/chat/contacts/profile tách EN/VI; Media bổ sung Phase6.
4. nav_graph include nav_auth/nav_chat/nav_contacts/nav_profile theo CONTEXT.
5. Room10 bảng ERD mục4, không credential server; API interfaces Auth/Contact/Chat/Media riêng.
6. /api/health liveness, /api/health/ready truy vấn DB thật; Android gọi ready.

**Plans**: 3/3 complete

- [x] 02-01: Backend Maven và readiness Supabase
- [x] 02-02: Room10 bảng và repository seams
- [x] 02-03: Navigation, resources và live probe

**Verification**: passed - 02-VERIFICATION.md; REQ-ui-standards còn phần Phase3.
**UI hint**: yes

### Phase 3: Authentication & Profile Management

**Goal**: Xây dựng toàn bộ luồng đăng ký SĐT E.164, xác thực OTP demo, đăng nhập 2 chế độ (chống brute-force bằng auth_throttles: PHONE_DEVICE, PHONE), lưu trữ JWT an toàn, quản lý hồ sơ, quyền riêng tư và xóa tài khoản.
**Depends on**: Phase 2
**Requirements**: REQ-auth-registration, REQ-auth-login, REQ-auth-account, REQ-ui-standards
**Phụ trách**: Thành viên 2 (độc lập trong `feature/auth` và `feature/profile`)
**Success Criteria** (what must be TRUE):

  1. Người dùng có thể đăng ký tài khoản mới bằng số điện thoại E.164, xác thực bằng mã OTP demo và đặt mật khẩu an toàn.
  2. Người dùng có thể đăng nhập bằng mật khẩu hoặc đăng nhập nhanh bằng OTP demo; hệ thống kiểm soát thử sai bằng `auth_throttles` (`PHONE_DEVICE`, `PHONE`); JWT 30 ngày được cấp khi `state: COMPLETE` và lưu an toàn trong SharedPreferences mã hóa / Keystore.
  3. Người dùng có thể cập nhật thông tin cá nhân (họ tên, avatar), quản lý cài đặt quyền riêng tư nhận tin từ người lạ `allowMessagesFromNonContacts`, đổi mật khẩu và đăng xuất.
  4. Khi người dùng thực hiện xóa tài khoản (soft delete), quan hệ danh bạ và các dòng chặn hai chiều bị xóa sạch, giải phóng số điện thoại (`phone_e164 = NULL`), token bị vô hiệu hóa lập tức.
  5. Hỗ trợ chuyển đổi ngôn ngữ Anh / Việt và giao diện sáng / tối (Material 3 DayNight) mà không bị mất trạng thái khi xoay màn hình.

**Plans**: TBD
**UI hint**: yes

### Phase 4: Contacts & Friendships

**Goal**: Xây dựng module quản lý danh bạ bạn bè, tìm kiếm người dùng mới bằng số điện thoại chính xác, gửi/nhận/chấp nhận/từ chối lời mời kết bạn và quản lý quyền riêng tư.
**Depends on**: Phase 3
**Requirements**: REQ-contacts-management
**Phụ trách**: Thành viên 3 (độc lập trong `feature/contacts`)
**Success Criteria** (what must be TRUE):

  1. Người dùng có thể tìm kiếm chính xác người dùng khác bằng số điện thoại E.164 và gửi lời mời kết bạn (`POST /api/contact-requests`).
  2. Người dùng có thể xem danh sách lời mời kết bạn đến/đi (`GET /api/contact-requests`), chấp nhận (`POST /api/contact-requests/{id}/accept`, tự động tạo 2 bản ghi `contacts` với `blocked = false`), từ chối hoặc hủy lời mời.
  3. Danh sách bạn bè chính thức (`blocked = false`) được lưu và quan sát mượt mà từ Room SQLite trên thiết bị.
  4. Người dùng có thể chặn người lạ (tạo dòng block-only `blocked = true`) hoặc chặn bạn bè, bỏ chặn (unblock: xóa dòng block-only hoặc đưa bạn bè về `blocked = false`), và hủy kết bạn (xóa hai chiều trong transaction).

**Plans**: TBD
**UI hint**: yes

### Phase 5: Realtime & Offline-First Core Messaging

**Goal**: Xây dựng cốt lõi tính năng nhắn tin thời gian thực 1-1 và nhóm hỗ trợ gửi ngoại tuyến qua Room và WorkManager, đồng bộ qua syncEventId và tìm kiếm FTS4 tiếng Việt.
**Depends on**: Phase 4 & Phase 3
**Requirements**: REQ-messaging-realtime, REQ-messaging-offline-first, REQ-messaging-delivery-read-status, REQ-messaging-group-chat, REQ-messaging-search
**Phụ trách**: Thành viên 4 (độc lập trong `feature/chat`)
**Success Criteria** (what must be TRUE):

  1. Gửi tin nhắn qua REST (`POST /api/conversations/{id}/messages`) chỉ truyền các trường cần thiết (`clientId`, `type`, `text`, `mediaUrl`, `latitude`, `longitude`); nhận tin nhắn thời gian thực qua kênh WebSocket thuần JSON (`/ws`) mang `syncEventId` trong hội thoại 1-1 và chat nhóm.
  2. Kiểm tra chặt chẽ quyền riêng tư người lạ `allow_messages_from_non_contacts`: từ chối với HTTP 403 `CONTACT_REQUIRED` nếu người nhận tắt tính năng này mà người gửi chưa có quan hệ bạn bè `ACCEPTED` (kể cả khi hội thoại đã tạo từ trước).
  3. Khi mất kết nối mạng, tin nhắn được lưu trạng thái SENDING trong Room và chuỗi WorkManager `send-messages` tự động gửi tuần tự lên server khi có mạng lại.
  4. Vòng đời trạng thái tin nhắn (SENDING -> SENT -> DELIVERED -> READ) và chỉ báo đang gõ (typing) hiển thị mượt mà theo thời gian thực (đảm bảo invariant `last_delivered >= last_read`; DELIVERED = ít nhất 1 thiết bị của người nhận đã lưu vào Room).
  5. Quản trị viên nhóm có thể tạo nhóm, thêm/xóa thành viên; chỉ người dùng đã kết bạn `ACCEPTED` mới được thêm vào nhóm; người mới không xem được tin nhắn trước thời điểm gia nhập.
  6. Đồng bộ bắt kịp dữ liệu qua `GET /api/sync?after=<syncEventId>` từ bảng `sync_events` khi nối lại mạng; `messages.id` chỉ dùng cho phân trang lịch sử (`?before=<messageId>`).
  7. Người dùng có thể tìm kiếm nhanh nội dung tin nhắn cũ trong hội thoại thông qua Room FTS4 chuẩn hóa không dấu (`normalized_text`) mà không cần kết nối mạng.

**Plans**: 9 plans

Plans:
**Wave 1**

- [ ] 05-01-PLAN.md — Gate `D-06` mở rộng `docs/API_CONTRACT.md`, migration `V5` (`barrier_xid` + `sync_retention_state`) & tracer chat 1-1 `TEXT`

**Wave 2** *(blocked on Wave 1 completion)*

- [ ] 05-02-PLAN.md — Backend chat nhóm (`SYSTEM` idempotent, `visible_from_message_id` `>`, bàn giao `ADMIN` 3 nhánh), `ReceiptService` & purge tài khoản
- [ ] 05-04-PLAN.md — Gate `D-14` Room v2 (`Migration(1, 2)`), `2.json`, FTS4 `safeMatchQuery` & DAOs (`ConversationListItem`, lọc `visibleFromMessageId`, bảo toàn watermark)

**Wave 3** *(blocked on Wave 2 completion)*

- [ ] 05-03-PLAN.md — Backend WebSocket `/ws` (Bearer-only, `afterCommit`, `4401`), Bootstrap `REPEATABLE READ`, `/api/sync` `barrier_xid` & Retention 30 ngày
- [ ] 05-05-PLAN.md — Android `ChatDtos`, `ChatApi`, `ChatWebSocketManager`, `SyncEventApplier` (Chat + 3 Contact events), single-flight `SyncEngine` & `TypingTracker`

**Wave 4** *(blocked on Wave 3 completion)*

- [ ] 05-06-PLAN.md — Android WorkManager `SendMessageWorker` (`send-messages`), `ChatRepository` seam (`observeConversationSummaries`, `observeAllUsersMap`), `RealChatRepository` & `Injection`

**Wave 5** *(blocked on Wave 4 completion)*

- [ ] 05-07-PLAN.md — Điều hướng `nav_chat.xml` (`D-02` `utechat://chat/direct/{peerUserId}`), `MainActivity` WS lifecycle, `strings_chat.xml` EN/VI, `ChatListFragment` (FTS4 toàn cục) & `NewChatFragment`

**Wave 6** *(blocked on Wave 5 completion)*

- [ ] 05-08-PLAN.md — `ChatRoomFragment`, `ChatRoomViewModel`, `MessageAdapter` 6 view types (thẻ tóm tắt non-TEXT, ẩn nút Phase 6), banner kết nối/403 & FTS4 trong phòng

**Wave 7** *(blocked on Wave 6 completion)*

- [ ] 05-09-PLAN.md — `GroupCreateFragment`, `GroupInfoFragment` (bàn giao `ADMIN`), `ChatViewModelTest`, `ChatNavigationAndSearchUiTest` (`ActivityScenario`) & nghiệm thu E2E 2 tài khoản

**UI hint**: yes

### Phase 6: Media Attachments & Creative Features

**Goal**: Xây dựng module gửi nhận tệp đa phương tiện kết hợp 2 tính năng sáng tạo độc lập: Gọi thoại WebRTC 1-1 và Dịch tin nhắn offline on-device bằng Google ML Kit.
**Depends on**: Phase 5
**Requirements**: REQ-messaging-media-attachments, REQ-notifications-sync, REQ-voice-call-webrtc, REQ-message-translation
**Phụ trách**: Thành viên 5 (độc lập trong `feature/media`, `feature/call`, `feature/translate`)
**Success Criteria** (what must be TRUE):

  1. Người dùng có thể chọn ảnh nén, ghi âm giọng nói (voice note), quay/chọn video, gửi tệp tài liệu và chia sẻ tọa độ mở bằng `geo:` trong cuộc trò chuyện; mở tệp video/file ngoài app qua `FileProvider` (không dùng query ticket).
  2. Thông báo tin mới xuất hiện trên NotificationChannel khi app đang hoạt động hoặc kết nối WebSocket còn duy trì; đồng bộ nền định kỳ 15 phút qua WorkManager theo cơ chế best-effort (không cam kết tức thời khi app bị force-stop); tự động đồng bộ bù dữ liệu qua API `/api/sync?after=<syncEventId>`.
  3. Người dùng có thể thực hiện và nhận cuộc gọi thoại 1-1 thời gian thực qua WebRTC với coturn STUN/TURN (hỗ trợ cả TCP/TLS 443 và UDP 3478/443); chỉ cho phép gọi giữa hai người dùng đã kết bạn `ACCEPTED` và không bị chặn (`CONTACT_REQUIRED`, `BLOCKED`); hiển thị thông báo cuộc gọi nhỡ.
  4. Người dùng có thể nhấn giữ tin `TEXT` để dịch hai chiều Anh - Việt bằng Google ML Kit Translation trực tiếp on-device (lưu Room `message_translation`).

**Plans**: 9 plans

Plans:
**Wave 1**

- [ ] 06-01-PLAN.md — Gate `D-10` đồng bộ `API_CONTRACT.md`/`FLOWS.md`/`ERD.md`, migration `V6` (`calls` + `uq_messages_call_id`) & Backend tracer `POST /api/media` $\rightarrow$ `GET /api/media/{id}` `Range` $\rightarrow$ `POST /messages`

**Wave 2** *(blocked on Wave 1 completion)*

- [ ] 06-02-PLAN.md — Backend gọi thoại 1-1 WebRTC (`CALL_BUSY`, 45s timeout nguyên tử, 2-device signaling lock, 1 tin `CALL` idempotent) & `TurnCredentialsService` (`coturn` HMAC-SHA1 + `turnserver.conf`)
- [ ] 06-03-PLAN.md — Gate Room v3 (`MIGRATION_2_3` + `3.json`), Gradle (`media3`, `location`, `stream-webrtc`, `mlkit:translate`, `glide`), `AndroidManifest`/`FileProvider`, `ApiClient` & `MediaApi`/`CallApi`
- Kiểm tra sớm APK Release trên thiết bị demo và xác nhận cuộc gọi thử qua TURN/TLS 443 trước khi hoàn thiện giao diện tích hợp.

**Wave 3** *(blocked on Wave 2 completion)*

- [ ] 06-04-PLAN.md — Android `MediaPreparationHelper` (EXIF/1600px), `VoiceRecorderHelper`, `MediaDownloadManager`, `LocationFetchHelper` (`geo:`) & `SendMessageWorker` 2 pha lưu `mediaUrl`
- [ ] 06-05-PLAN.md — Thông báo 2 kênh (`messages`, `calls`), `ActiveRoomTracker`, `NotificationHelper` (`MessagingStyle`, cô lập phiên) & `SyncWorker` 15 phút qua `SyncEngine` single-flight
- [ ] 06-06-PLAN.md — Android `WebRtcCallEngine` (`stream-webrtc-android`), `CallSessionCoordinator`, `Foreground CallService` (`microphone` sau `RECORD_AUDIO`) & `CallFragment`/`CallViewModel` trong `MainActivity`
- [ ] 06-07-PLAN.md — Dịch tin nhắn on-device `RealMlKitTranslator` (EN$\leftrightarrow$VI không `language-id`), `MessageTranslationManager` (xác nhận dữ liệu di động, chờ vô định không %, cache Room)

**Wave 4** *(blocked on Wave 3 completion)*

- [ ] 06-08-PLAN.md — Nối `ChatWebSocketManager` `call.*`, `RealChatRepository`/`FakeChatRepository`, `Injection` (tách `MEDIA_IO`/`CALL_NETWORK`, dọn phiên) & `MainActivity` (`POST_NOTIFICATIONS`, deep-link), nút gọi thoại trong chat 1-1 và kiểm tra TURN/TLS 443 trên APK Release

**Wave 5** *(blocked on Wave 4 completion)*

- [ ] 06-09-PLAN.md — `ZoomableImageView`, `ImageViewerFragment`, `VideoPlayerFragment` (`Media3` + `FileProvider`), `VoiceBubblePlayer`, `ChatRoomFragment`/`MessageAdapter` (media/location/call/dịch inline) & cổng kiểm thử toàn phase
**UI hint**: yes

### Phase 7: Message Actions, Reactions & Stickers

**Goal**: Bổ sung trọn vẹn 4 tính năng tương tác tin nhắn nâng cao trên nền tảng Phase 5–6: gỡ tin nhắn trong vòng 24 giờ, xóa tin nhắn ở phía bạn, bày tỏ cảm xúc bằng 6 emoji cố định và tải/gỡ bộ nhãn dán để gửi sticker (kể cả qua hàng đợi offline).
**Depends on**: Phase 6
**Requirements**: REQ-message-actions-recall-delete, REQ-message-reactions, REQ-message-stickers
**Phụ trách**: Cả nhóm / phân công từ `main` (`6eb9629`)
**Success Criteria** (what must be TRUE):

  1. Người gửi có thể gỡ tin nhắn của chính mình trong vòng 24 giờ (tính trên server, chặn `SYSTEM`/`CALL`, chặn khi đã rời nhóm hoặc bị chặn); tin đã gỡ giữ vị trí với dòng "Tin nhắn đã được gỡ", cập nhật preview danh sách chat, loại khỏi FTS4/bản dịch và vô hiệu hóa tải lại tệp media của tin đã gỡ.
  2. Người dùng có thể xóa tin nhắn ở phía mình (kể cả trong chế độ chỉ đọc/bị chặn), đồng bộ ẩn tin trên các thiết bị của tài khoản đó và tự động lùi preview danh sách chat về tin gần nhất chưa xóa.
  3. Người dùng có thể bày tỏ tối đa 1 cảm xúc từ 6 emoji cố định (`❤️`, `😂`, `👍`, `😮`, `😢`, `😡`) trên mỗi tin (đổi khi chọn emoji khác, bỏ khi chạm lại emoji đang dùng), chạm cụm cảm xúc xem danh sách người thả và đồng bộ realtime giữa các thiết bị.
  4. Backend cung cấp 2 bộ nhãn dán mẫu (tài khoản mới cài sẵn 1 bộ mặc định, bộ thứ 2 trong cửa hàng để tải/gỡ đồng bộ theo tài khoản); ảnh sticker lưu cache xem offline, sticker đã gửi vẫn hiện trong lịch sử sau khi gỡ bộ, hiển thị bong bóng sticker riêng và gửi được qua hàng đợi `SendMessageWorker` khi mất mạng.

**Plans**: 6 plans

**Trạng thái**: Sáu plan đã triển khai và qua kiểm chứng tự động trên `feature/phase-7-core`. Nghiệm thu hai thiết bị được theo dõi trong [07-VALIDATION.md](phases/07-message-actions-reactions-stickers/07-VALIDATION.md).

Plans:
**Wave 1**

- [x] 07-01-PLAN.md — Gate `D-13` đồng bộ `API_CONTRACT.md`/`FLOWS.md`/`ERD.md`, migration `V9` (5 bảng RLS + `attachments.revoked_at`) & Backend Recall 24h, Delete for Me, Reactions 6 emoji, thu hồi media, preview hội thoại & Sync/WS

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 07-02-PLAN.md — Backend 2 bộ nhãn dán mẫu (`ute_daily` mặc định + `ute_campus` cửa hàng, 12 PNG), REST Sticker Packs (install/uninstall theo tài khoản) & gửi tin `type=STICKER`
- [x] 07-03-PLAN.md — Gate Room v4 (`MIGRATION_3_4` + `4.json`), bảng `message_reaction`/`message_deletion`/`sticker_pack`/`sticker`, mở rộng `MessageEntity`/`ConversationEntity`/`MessageDao`/`ConversationDao`, DTOs & Retrofit APIs

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 07-04-PLAN.md — Android `SyncEventApplier` (`message.recalled`/`deleted_for_me`/`reaction_updated`), `NotificationHelper` loại bỏ thông báo tin đã gỡ, `SendMessageWorker` gửi `STICKER` offline, `StickerCacheManager`, `StickerRepository`/`ChatRepository` & `Injection`

**Wave 4** *(blocked on Wave 3 completion)*

- [x] 07-05-PLAN.md — UI hợp nhất `MessageActionBottomSheet` (thay `showTranslationMenu`), `ReactionDetailsBottomSheet`, `StickerTrayView`, `StickerStoreDialog`, `MessageAdapter` (`TYPE_STICKER_OUT=6`/`IN=7`, chips cảm xúc, trạng thái đã gỡ), `ChatRoomFragment`/`ViewModel`, `ConversationListAdapter` & `strings_chat.xml` EN/VI

**Wave 5** *(blocked on Wave 4 completion)*

- [x] 07-06-PLAN.md — `ChatViewModelTest` Phase 7, `Phase7MessageActionsUiTest` & cổng kiểm thử tự động toàn Phase 7 (Backend Maven + Android JVM + Lint + AndroidTest compile)

**UI hint**: yes

### Phase 8: Testing, Quality & Release Packaging

**Goal**: Toàn nhóm phối hợp kiểm thử kim tự tháp, hoàn thiện chất lượng mã nguồn, chạy toàn bộ Unit Test và đóng gói hồ sơ nộp bài đạt chuẩn 100 điểm rubric.
**Depends on**: Phase 2, 3, 4, 5, 6, 7
**Requirements**: REQ-testing-quality
**Phụ trách**: Cả nhóm (Nhóm trưởng chủ trì nghiệm thu)
**Success Criteria** (what must be TRUE):

  1. Toàn bộ test suite unit test JVM chạy lệnh `./gradlew testDebugUnitTest` đạt 100% tỷ lệ pass (Room DAO, Repository, ViewModel, Crypto, Mappers).
  2. Kiểm thử Room in-memory test trên Android Test và UI test Espresso hoạt động ổn định.
  3. Giao diện người dùng tuân thủ Material Design 3 rõ ràng, hỗ trợ đầy đủ tiếng Việt và tiếng Anh, xoay ngang/dọc mượt mà.
  4. Đóng gói đầy đủ hồ sơ nộp bài theo đúng Tiêu chí 1 của Rubric: Mã nguồn có thư mục `.git`, báo cáo Word, bộ ảnh chụp màn hình đầy đủ các luồng, và file APK cài đặt hoàn chỉnh.

**Plans**: 4 plans

Plans:
**Wave 1** *(4 thành viên TV1–TV4 test song song toàn bộ chức năng trên Android Studio; phần Phase 6 của TV5 chia đều 4 mảng; phần việc TV5 sẽ phân công riêng sau)*

- [ ] 08-01-PLAN.md — **TV1 (Nhóm trưởng)**: Kiểm thử Nền tảng Phase 1–2, Cô lập phiên A → B, toàn bộ Phase 7 (Gỡ 24h / Xóa phía bạn / Cảm xúc / Sticker) & **Notifications / Muted / Sync nền** (1/4 Phase 6)
- [ ] 08-02-PLAN.md — **TV2**: Kiểm thử Phase 3 (`Splash`, Đăng ký/`CompleteProfile`, OTP, brute-force, **2FA/F-04**, Thiết bị/Thu hồi phiên từ xa, Hồ sơ, Đổi/Quên/Xóa/Hủy xóa TK, EN–VI, Light/Dark, Xoay màn hình) & **Dịch ML Kit** (1/4 Phase 6)
- [ ] 08-03-PLAN.md — **TV3**: Kiểm thử Phase 4 (Tìm SĐT E.164, Lời mời kết bạn, Danh bạ Room offline + mở chat, Chặn/Bỏ chặn/Hủy kết bạn 2 chiều) & **Gọi thoại WebRTC 1-1** (1/4 Phase 6)
- [ ] 08-04-PLAN.md — **TV4**: Kiểm thử Phase 5 (`NewChat`, Chat 1-1 & `CONTACT_REQUIRED`, WS banner, `SendMessageWorker` + lỗi `FAILED`, Trạng thái, Nhóm + Đổi tên/Phân quyền Admin + `visible_from`, `/api/sync` & tải tin cũ, FTS4 không dấu) & **Media / Vị trí `geo:`** (1/4 Phase 6)
