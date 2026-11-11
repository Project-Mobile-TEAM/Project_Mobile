# Roadmap: UTEChat

## Overview

Hành trình xây dựng ứng dụng chat di động UTEChat cho nhóm 5 người theo mô hình **Kiến trúc chống xung đột (Anti-Conflict Architecture)** và **Tiến độ bậc thang an toàn (Staggered Pipeline)**. 

- Nhóm trưởng đảm nhiệm **Phase 1** tạo lập CSDL Supabase 13 bảng V1 làm nguồn chân lý duy nhất.
- **Phase 2** thiết lập khung ứng dụng Walking Skeleton và "khóa chết" 6 điểm nóng gây conflict (Single Activity, Nested Nav Graphs, Split Resource Strings, Modular Retrofit APIs, Pre-wired Room Entities, chuẩn hóa thư viện nền tảng).
- **Phase 3 đến Phase 6** phân chia độc lập tương ứng cho 4 thành viên thực hiện các lát cắt dọc tính năng theo thứ tự phụ thuộc chặt chẽ: Auth $\rightarrow$ Contacts $\rightarrow$ Core Messaging $\rightarrow$ Media & Creative Features.
- **Phase 7** cả nhóm phối hợp kiểm thử toàn diện, tối ưu và đóng gói bài nộp đạt điểm tuyệt đối 100/100 theo rubric.

## Phases

- [x] **Phase 1: Database & Supabase Infrastructure** - Thiết lập CSDL PostgreSQL trên Supabase với đúng 13 bảng V1, khóa ngoại, RLS default deny, indexes và dữ liệu seed mẫu (Nhóm trưởng)
- [x] **Phase 2: Foundation & Anti-Conflict Architecture** - Khung app Android MVVM, backend kết nối Supabase, thông luồng /api/health và khóa 6 điểm nóng gây conflict (completed 2026-11-11)
- [ ] **Phase 3: Authentication & Profile Management** - Đăng ký SĐT E.164, OTP demo, đăng nhập 2 chế độ (chống brute-force bằng auth_throttles: PHONE_DEVICE, PHONE), quản lý hồ sơ và quyền riêng tư (allowMessagesFromNonContacts), soft delete tài khoản, đa ngôn ngữ & DayNight (Thành viên 2)
- [ ] **Phase 4: Contacts & Friendships** - Quản lý danh bạ bạn bè, tìm kiếm SĐT chính xác, gửi/nhận/chấp nhận/từ chối lời mời kết bạn (contact_requests), quản lý bạn bè và danh sách chặn (contacts) (Thành viên 3)
- [ ] **Phase 5: Realtime & Offline-First Core Messaging** - Nhắn tin 1-1 (kiểm tra allow_messages_from_non_contacts) và nhóm qua REST + WebSocket thuần JSON mang syncEventId, hàng đợi Room WorkManager, đồng bộ sự kiện qua syncEventId và tìm kiếm FTS4 tiếng Việt không dấu (Thành viên 4)
- [ ] **Phase 6: Media Attachments & Creative Features** - Gửi ảnh/voice/video/file qua FileProvider, gọi thoại 1-1 WebRTC giữa bạn bè ACCEPTED qua coturn (sáng tạo chính) và dịch tin nhắn ML Kit on-device (sáng tạo dự phòng) (Thành viên 5)
- [ ] **Phase 7: Testing, Quality & Release Packaging** - Bộ unit test JVM pass 100%, kiểm thử Room in-memory, tối ưu R8 và đóng gói hồ sơ nộp bài (Cả nhóm)

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
1. Java17, minSdk26/compileSdk35/targetSdk35, package com.utechat; dependencies tập trung.
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

**Plans**: TBD
**UI hint**: yes

### Phase 6: Media Attachments & Creative Features

**Goal**: Xây dựng module gửi nhận tệp đa phương tiện kết hợp 2 tính năng sáng tạo độc lập: Gọi thoại WebRTC 1-1 và Dịch tin nhắn offline on-device bằng Google ML Kit.
**Depends on**: Phase 5
**Requirements**: REQ-messaging-media-attachments, REQ-notifications-sync, REQ-voice-call-webrtc, REQ-message-translation
**Phụ trách**: Thành viên 5 (độc lập trong `feature/media`, `feature/call`, `feature/translate`)
**Success Criteria** (what must be TRUE):

  1. Người dùng có thể chọn ảnh nén, ghi âm giọng nói (voice note), quay/chọn video file và gửi tệp tin tài liệu trong cuộc trò chuyện; mở tệp video/file ngoài app qua `FileProvider` (không dùng query ticket).
  2. Thông báo tin mới xuất hiện trên NotificationChannel khi app đang hoạt động hoặc kết nối WebSocket còn duy trì; đồng bộ nền định kỳ 15 phút qua WorkManager theo cơ chế best-effort (không cam kết tức thời khi app bị force-stop); tự động đồng bộ bù dữ liệu qua API `/api/sync?after=<syncEventId>`.
  3. Người dùng có thể thực hiện và nhận cuộc gọi thoại 1-1 thời gian thực qua WebRTC với coturn STUN/TURN (hỗ trợ cả TCP/TLS 443 và UDP 3478/443); chỉ cho phép gọi giữa hai người dùng đã kết bạn `ACCEPTED` và không bị chặn (`CONTACT_REQUIRED`, `BLOCKED`); hiển thị thông báo cuộc gọi nhỡ.
  4. Người dùng có thể nhấn giữ tin nhắn bất kỳ để dịch tức thời hai chiều Anh - Việt bằng Google ML Kit Translation trực tiếp on-device (lưu Room `message_translation`).

**Plans**: TBD
**UI hint**: yes

### Phase 7: Testing, Quality & Release Packaging

**Goal**: Toàn nhóm phối hợp kiểm thử kim tự tháp, hoàn thiện chất lượng mã nguồn, chạy toàn bộ Unit Test và đóng gói hồ sơ nộp bài đạt chuẩn 100 điểm rubric.
**Depends on**: Phase 2, 3, 4, 5, 6
**Requirements**: REQ-testing-quality
**Phụ trách**: Cả nhóm (Nhóm trưởng chủ trì nghiệm thu)
**Success Criteria** (what must be TRUE):

  1. Toàn bộ test suite unit test JVM chạy lệnh `./gradlew testDebugUnitTest` đạt 100% tỷ lệ pass (Room DAO, Repository, ViewModel, Crypto, Mappers).
  2. Kiểm thử Room in-memory test trên Android Test và UI test Espresso hoạt động ổn định.
  3. Giao diện người dùng tuân thủ Material Design 3 rõ ràng, hỗ trợ đầy đủ tiếng Việt và tiếng Anh, xoay ngang/dọc mượt mà.
  4. Đóng gói đầy đủ hồ sơ nộp bài theo đúng Tiêu chí 1 của Rubric: Mã nguồn có thư mục `.git`, báo cáo Word, bộ ảnh chụp màn hình đầy đủ các luồng, và file APK cài đặt hoàn chỉnh.

**Plans**: TBD
