# Roadmap: UTEChat

## Overview

Hành trình xây dựng ứng dụng chat di động UTEChat cho nhóm 5 người theo mô hình **Kiến trúc chống xung đột (Anti-Conflict Architecture)** và **Tiến độ bậc thang an toàn (Staggered Pipeline)**. 
- Nhóm trưởng đảm nhiệm **Phase 1** tạo lập CSDL Supabase làm nguồn chân lý duy nhất.
- **Phase 2** thiết lập khung ứng dụng và "khóa chết" 6 điểm nóng gây conflict (Single Activity, Nested Nav Graphs, Split Resource Strings, Modular Retrofit APIs, Pre-wired Room Entities, chuẩn hóa thư viện nền tảng).
- **Phase 3 đến Phase 6** phân chia độc lập tương ứng cho 4 thành viên thực hiện các lát cắt dọc tính năng theo thứ tự phụ thuộc chặt chẽ: Auth $\rightarrow$ Contacts $\rightarrow$ Core Messaging $\rightarrow$ Media & Creative Features.
- **Phase 7** cả nhóm phối hợp kiểm thử toàn diện, tối ưu và đóng gói bài nộp đạt điểm tuyệt đối 100/100 theo rubric.

## Phases

- [ ] **Phase 1: Database & Supabase Infrastructure** - Thiết lập CSDL PostgreSQL trên Supabase, 10 bảng v1, khóa ngoại, RLS policies, indexes và dữ liệu seed mẫu (Nhóm trưởng)
- [ ] **Phase 2: Foundation & Anti-Conflict Architecture** - Khung app Android MVVM, backend kết nối Supabase, thông luồng /api/health và khóa 6 điểm nóng gây conflict
- [ ] **Phase 3: Authentication & Profile Management** - Đăng ký SĐT E.164, OTP demo, đăng nhập 2 chế độ, quản lý hồ sơ, soft delete tài khoản, đa ngôn ngữ & DayNight (Thành viên 2)
- [ ] **Phase 4: Contacts & Friendships** - Quản lý danh bạ, tìm kiếm SĐT chính xác, gửi/nhận lời mời kết bạn và danh sách chặn (Thành viên 3)
- [ ] **Phase 5: Realtime & Offline-First Core Messaging** - Nhắn tin 1-1 và nhóm qua REST + WebSocket thuần, hàng đợi Room WorkManager và tìm kiếm FTS4 tiếng Việt không dấu (Thành viên 4)
- [ ] **Phase 6: Media Attachments & Creative Features** - Gửi ảnh/voice/video/file, gọi thoại 1-1 WebRTC (sáng tạo chính) và dịch tin nhắn ML Kit on-device (sáng tạo dự phòng) (Thành viên 5)
- [ ] **Phase 7: Testing, Quality & Release Packaging** - Bộ unit test JVM pass 100%, kiểm thử Room in-memory, tối ưu R8 và đóng gói hồ sơ nộp bài (Cả nhóm)

---

## Phase Details

### Phase 1: Database & Supabase Infrastructure
**Goal**: Nhóm trưởng thiết lập toàn bộ hạ tầng CSDL PostgreSQL trên Supabase, định nghĩa các bảng thuộc phạm vi v1, khóa ngoại, RLS policies, chỉ mục và nạp dữ liệu mẫu chuẩn làm nền móng vững chắc cho cả nhóm.
**Depends on**: Nothing (bắt đầu trước tiên)
**Requirements**: Nền móng CSDL phục vụ toàn bộ các yêu cầu
**Phụ trách**: Nhóm trưởng (Database & Điều phối)
**Success Criteria** (what must be TRUE):
  1. Các bảng CSDL thuộc phạm vi v1 (10 bảng: `users`, `user_devices`, `otp_challenges`, `auth_sessions`, `contacts`, `conversations`, `conversation_members`, `messages`, `attachments`, `calls`) theo `docs/ERD.md` được tạo thành công trên Supabase với UUID v4, kiểu dữ liệu chuẩn và khóa ngoại cascade/set null chính xác.
  2. Kích hoạt Row Level Security (RLS) trên mọi bảng; đóng hoàn toàn Public Data API để đảm bảo chỉ backend `service_role` hoặc tài khoản hợp lệ mới có quyền truy cập.
  3. Tạo đầy đủ các chỉ mục B-tree (cho SĐT) và composite index `(conversation_id, created_at DESC)` cho truy vấn phân trang tin nhắn tốc độ cao.
  4. Nạp sẵn bộ dữ liệu mẫu chuẩn (Seed Data): các tài khoản test sẵn có (SĐT demo, OTP demo `123456`, mật khẩu đã băm BCrypt), danh bạ mẫu và tin nhắn mẫu.
  5. Xuất file mẫu cấu hình `.env.example` (chỉ chứa placeholder: `SUPABASE_URL=`, `SUPABASE_ANON_KEY=`, `SUPABASE_SERVICE_ROLE_KEY=`) và tài liệu hướng dẫn `SUPABASE_SETUP.md`; tuyệt đối KHÔNG đưa giá trị thật của service key vào Git theo đúng ADR-007.
**Plans**: TBD
**UI hint**: no

### Phase 2: Foundation & Anti-Conflict Architecture
**Goal**: Dựng bộ khung Walking Skeleton (Android MVVM + Backend kết nối Supabase) và áp dụng kiến trúc cô lập triệt để nhằm khóa cứng 6 điểm nóng gây conflict.
**Depends on**: Phase 1
**Requirements**: REQ-ui-standards
**Phụ trách**: Nhóm trưởng & Cả nhóm
**Success Criteria** (what must be TRUE):
  1. Khai báo và chuẩn hóa các dependencies nền tảng cốt lõi trong `build.gradle.kts` và `libs.versions.toml` (Retrofit, Gson, Room, Navigation Component, ViewBinding). Thư viện chuyên biệt (Glide, WebRTC, ML Kit) chỉ bổ sung khi bước vào phase tương ứng (Just-in-Time).
  2. Áp dụng Single Activity Architecture: Chỉ duy nhất `MainActivity`, toàn bộ màn hình là Fragment; hoàn thiện và khóa `AndroidManifest.xml`.
  3. Tách file tài nguyên chuỗi theo module: `strings_auth.xml`, `strings_contacts.xml`, `strings_chat.xml`, `strings_media.xml` để các thành viên không bao giờ sửa chung `strings.xml`.
  4. Cấu hình Nested Navigation Graphs (`nav_auth.xml`, `nav_contacts.xml`, `nav_chat.xml`, `nav_media.xml`) được include vào `nav_main.xml`.
  5. Khởi tạo sẵn `AppDatabase` và các Room Entity v1 mapping từ Supabase; tạo sẵn khung các Interface Retrofit riêng biệt (`AuthApi`, `ContactApi`, `ChatApi`, `MediaApi`).
  6. Backend Spring Boot kết nối thành công với Supabase PostgreSQL; ứng dụng Android gọi API `GET /api/health` và `GET /api/health/ready` hiển thị kết nối thành công.
**Plans**: TBD
**UI hint**: yes

### Phase 3: Authentication & Profile Management
**Goal**: Xây dựng toàn bộ luồng đăng ký SĐT E.164, xác thực OTP demo, đăng nhập 2 chế độ, lưu trữ JWT an toàn, quản lý hồ sơ cá nhân và xóa tài khoản.
**Depends on**: Phase 2
**Requirements**: REQ-auth-registration, REQ-auth-login, REQ-auth-account, REQ-ui-standards
**Phụ trách**: Thành viên 2 (độc lập trong `feature/auth` và `feature/profile`)
**Success Criteria** (what must be TRUE):
  1. Người dùng có thể đăng ký tài khoản mới bằng số điện thoại E.164, xác thực bằng mã OTP demo và đặt mật khẩu an toàn.
  2. Người dùng có thể đăng nhập bằng mật khẩu hoặc đăng nhập nhanh bằng OTP demo; JWT 30 ngày được lưu an toàn trong SharedPreferences mã hóa / Keystore.
  3. Người dùng có thể cập nhật thông tin cá nhân (họ tên, avatar), đổi mật khẩu và đăng xuất.
  4. Khi người dùng thực hiện xóa tài khoản (soft delete), quan hệ danh bạ hai chiều bị xóa sạch, token bị vô hiệu hóa lập tức.
  5. Hỗ trợ chuyển đổi ngôn ngữ Anh / Việt và giao diện sáng / tối (Material 3 DayNight) mà không bị mất trạng thái khi xoay màn hình.
**Plans**: TBD
**UI hint**: yes

### Phase 4: Contacts & Friendships
**Goal**: Xây dựng module quản lý danh bạ bạn bè, tìm kiếm người dùng mới bằng số điện thoại chính xác và quản lý quyền riêng tư.
**Depends on**: Phase 3
**Requirements**: REQ-contacts-management
**Phụ trách**: Thành viên 3 (độc lập trong `feature/contacts`)
**Success Criteria** (what must be TRUE):
  1. Người dùng có thể tìm kiếm chính xác người dùng khác bằng số điện thoại E.164 và gửi lời mời kết bạn.
  2. Người dùng có thể xem danh sách lời mời kết bạn đến/đi, đồng ý hoặc từ chối kết bạn.
  3. Danh sách bạn bè được lưu và quan sát mượt mà từ Room SQLite trên thiết bị.
  4. Người dùng có thể chặn người dùng khác để ngăn mọi liên lạc và tin nhắn.
**Plans**: TBD
**UI hint**: yes

### Phase 5: Realtime & Offline-First Core Messaging
**Goal**: Xây dựng cốt lõi tính năng nhắn tin thời gian thực 1-1 và nhóm hỗ trợ gửi ngoại tuyến qua Room và WorkManager, cùng tìm kiếm FTS4 tiếng Việt.
**Depends on**: Phase 4 & Phase 3
**Requirements**: REQ-messaging-realtime, REQ-messaging-offline-first, REQ-messaging-delivery-read-status, REQ-messaging-group-chat, REQ-messaging-search
**Phụ trách**: Thành viên 4 (độc lập trong `feature/chat`)
**Success Criteria** (what must be TRUE):
  1. Gửi tin nhắn qua REST (`POST /api/conversations/{id}/messages`) và nhận tin nhắn thời gian thực qua kênh WebSocket thuần JSON (`/ws`) trong hội thoại 1-1 và chat nhóm (không dùng STOMP theo ADR-008).
  2. Khi mất kết nối mạng, tin nhắn được lưu trạng thái SENDING trong Room và chuỗi WorkManager `send-messages` tự động gửi tuần tự lên server khi có mạng lại.
  3. Vòng đời trạng thái tin nhắn (SENDING -> SENT -> DELIVERED -> READ) và chỉ báo đang gõ (typing) hiển thị mượt mà theo thời gian thực (đảm bảo invariant `last_delivered >= last_read`).
  4. Quản trị viên nhóm có thể tạo nhóm, thêm/xóa thành viên; người mới không xem được tin nhắn trước thời điểm gia nhập.
  5. Người dùng có thể tìm kiếm nhanh nội dung tin nhắn cũ trong hội thoại thông qua Room FTS4 chuẩn hóa không dấu (`normalized_text`) mà không cần kết nối mạng.
**Plans**: TBD
**UI hint**: yes

### Phase 6: Media Attachments & Creative Features
**Goal**: Xây dựng module gửi nhận tệp đa phương tiện kết hợp 2 tính năng sáng tạo độc lập: Gọi thoại WebRTC 1-1 và Dịch tin nhắn offline on-device bằng Google ML Kit.
**Depends on**: Phase 5
**Requirements**: REQ-messaging-media-attachments, REQ-notifications-sync, REQ-voice-call-webrtc, REQ-message-translation
**Phụ trách**: Thành viên 5 (độc lập trong `feature/media`, `feature/call`, `feature/translate`)
**Success Criteria** (what must be TRUE):
  1. Người dùng có thể chọn ảnh nén, ghi âm giọng nói (voice note), quay/chọn video và gửi tệp tin tài liệu trong cuộc trò chuyện; video mở ngoài qua FileProvider.
  2. Thông báo tin mới xuất hiện trên NotificationChannel khi app ở chế độ nền; tự động đồng bộ bù dữ liệu qua API `/api/sync`.
  3. Người dùng có thể thực hiện và nhận cuộc gọi thoại 1-1 thời gian thực qua WebRTC với coturn STUN/TURN (hỗ trợ cả TCP/TLS 443); hiển thị thông báo cuộc gọi nhỡ.
  4. Người dùng có thể nhấn giữ tin nhắn bất kỳ để dịch tức thời hai chiều Anh - Việt bằng Google ML Kit Translation trực tiếp trên thiết bị (hoạt động on-device sau khi tải gói ngôn ngữ ban đầu).
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
