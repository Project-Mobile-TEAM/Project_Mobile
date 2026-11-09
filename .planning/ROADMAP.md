# Roadmap: UTEChat

## Overview

Hành trình xây dựng ứng dụng chat di động UTEChat từ nền tảng walking skeleton, module xác thực đa thiết bị, danh bạ, hệ thống chat thời gian thực offline-first, truyền thông đa phương tiện đến các tính năng mở rộng nâng cao (WebRTC, AI, dịch máy) và kiểm thử toàn diện đạt điểm tối đa rubric.

## Phases

- [ ] **Phase 1: Foundation & Walking Skeleton** - Khởi tạo hạ tầng backend Spring Boot kết nối Supabase, khung app Android MVVM và thông luồng kiểm tra /health
- [ ] **Phase 2: Authentication & Account Security** - Đăng ký SĐT E.164, OTP demo, đăng nhập 2 chế độ, nhận diện bạn bè, 2FA TOTP và quản lý thiết bị
- [ ] **Phase 3: Contacts & Friendships** - Quản lý danh bạ, tìm kiếm SĐT chính xác, kết bạn và danh sách chặn
- [ ] **Phase 4: Realtime & Offline-First Core Messaging** - Nhắn tin 1-1 và nhóm thời gian thực qua WebSocket, hàng đợi WorkManager Room và tìm kiếm FTS4
- [ ] **Phase 5: Media Attachments & Notifications** - Tải lên và gửi nhận ảnh/voice/video/file/vị trí, kèm thông báo đẩy và đồng bộ ngầm
- [ ] **Phase 6: Advanced Extensions** - Cuộc gọi thoại WebRTC 1-1, dịch tin nhắn on-device bằng ML Kit và trợ lý thông minh UTE AI
- [ ] **Phase 7: Testing, Hardening & Release** - Bộ unit test JVM pass 100%, kiểm thử Room in-memory, UI Espresso, tối ưu R8 và đóng gói release

## Phase Details

### Phase 1: Foundation & Walking Skeleton
**Goal**: Thiết lập khung dự án Android (Java, Single Activity Navigation, View Binding, AppExecutors, Room) và Backend Spring Boot (kết nối Supabase PostgreSQL với Flyway migration), thông luồng API /health đầu tiên.
**Depends on**: Nothing (first phase)
**Requirements**: REQ-ui-standards
**Success Criteria** (what must be TRUE):
  1. Backend server khởi động thành công, chạy migration Flyway trên Supabase PostgreSQL và phản hồi API GET /api/health.
  2. Ứng dụng Android khởi động hiển thị màn hình chính với BottomNavigationView theo kiến trúc Single Activity Navigation Component.
  3. Ứng dụng Android gọi API /health lên server qua Retrofit và hiển thị trạng thái kết nối thành công.
  4. Hỗ trợ chuyển đổi ngôn ngữ Anh / Việt và giao diện sáng / tối (Material 3 DayNight) mà không bị mất trạng thái khi xoay màn hình.
**Plans**: TBD
**UI hint**: yes

### Phase 2: Authentication & Account Security
**Goal**: Hoàn thiện toàn bộ quy trình đăng ký, xác thực, quản lý phiên và bảo vệ bảo mật đa lớp trên thiết bị tin cậy và thiết bị lạ.
**Depends on**: Phase 1
**Requirements**: REQ-auth-registration, REQ-auth-login-password, REQ-auth-login-otp, REQ-auth-friend-challenge, REQ-auth-totp-2fa, REQ-auth-session-devices
**Success Criteria** (what must be TRUE):
  1. Người dùng có thể đăng ký tài khoản mới bằng số điện thoại E.164, xác thực bằng mã OTP demo và thiết lập mật khẩu an toàn.
  2. Người dùng có thể đăng nhập bằng mật khẩu hoặc OTP; thiết bị tin cậy vào thẳng hệ thống với JWT token 30 ngày lưu an toàn trong Keystore.
  3. Đăng nhập trên thiết bị lạ kích hoạt thành công thử thách nhận diện bạn bè (chọn đúng 3 bạn trong 15 người) hoặc yêu cầu mã 2FA TOTP.
  4. Người dùng có thể xem danh sách thiết bị đang đăng nhập và thu hồi quyền từ xa khiến thiết bị bị thu hồi bị đăng xuất lập tức.
**Plans**: TBD
**UI hint**: yes

### Phase 3: Contacts & Friendships
**Goal**: Cho phép tìm kiếm người dùng mới, kết nối danh bạ bạn bè và quản lý quyền riêng tư.
**Depends on**: Phase 2
**Requirements**: REQ-contacts-management
**Success Criteria** (what must be TRUE):
  1. Người dùng có thể tìm kiếm chính xác người dùng khác bằng số điện thoại E.164 và gửi lời mời kết bạn.
  2. Người dùng có thể xem danh sách lời mời kết bạn đến/đi, đồng ý hoặc từ chối kết bạn.
  3. Danh sách bạn bè được lưu và quan sát mượt mà từ Room SQLite trên thiết bị.
  4. Người dùng có thể chặn người dùng khác để ngăn mọi liên lạc và tin nhắn.
**Plans**: TBD
**UI hint**: yes

### Phase 4: Realtime & Offline-First Core Messaging
**Goal**: Xây dựng cốt lõi tính năng nhắn tin thời gian thực 1-1 và nhóm hỗ trợ gửi ngoại tuyến qua Room và WorkManager.
**Depends on**: Phase 3
**Requirements**: REQ-messaging-realtime-send-receive, REQ-messaging-offline-first, REQ-messaging-delivery-read-status, REQ-messaging-group-chat, REQ-messaging-search
**Success Criteria** (what must be TRUE):
  1. Người dùng có thể gửi và nhận tin nhắn văn bản tức thì qua kênh WebSocket trong hội thoại 1-1 và chat nhóm.
  2. Khi mất kết nối mạng, tin nhắn được lưu trạng thái SENDING trong Room và WorkManager tự động gửi tuần tự lên server khi có mạng lại.
  3. Mốc trạng thái đã nhận, đã đọc và chỉ báo đang nhập (typing) hiển thị chính xác theo thời gian thực.
  4. Quản trị viên nhóm có thể tạo nhóm, thêm/xóa thành viên; người mới không xem được tin nhắn trước thời điểm gia nhập.
  5. Người dùng có thể tìm kiếm nhanh nội dung tin nhắn cũ trong hội thoại thông qua Room FTS4 mà không cần mạng.
**Plans**: TBD
**UI hint**: yes

### Phase 5: Media Attachments & Notifications
**Goal**: Mở rộng khả năng gửi nhận tệp đa phương tiện, thông báo tin mới và cơ chế đồng bộ nền.
**Depends on**: Phase 4
**Requirements**: REQ-messaging-media-attachments, REQ-notifications-sync
**Success Criteria** (what must be TRUE):
  1. Người dùng có thể chụp/chọn ảnh, ghi âm giọng nói, gửi video, tệp tin và vị trí trong cuộc trò chuyện.
  2. Tệp đính kèm được tải lên server an toàn, kiểm tra MIME type và chỉ thành viên hội thoại mới có quyền tải về xem.
  3. Thông báo tin mới xuất hiện trên NotificationChannel khi app ở chế độ nền.
  4. Dữ liệu tin nhắn bị bỏ lỡ được tự động đồng bộ đầy đủ thông qua API /sync sau khi kết nối mạng được khôi phục.
**Plans**: TBD
**UI hint**: yes

### Phase 6: Advanced Extensions
**Goal**: Triển khai các tính năng nâng cao sáng tạo: Gọi thoại WebRTC, Dịch tin nhắn trên máy và Bot AI trợ lý thông minh.
**Depends on**: Phase 5
**Requirements**: REQ-voice-call-webrtc, REQ-ai-assistant, REQ-message-translation
**Success Criteria** (what must be TRUE):
  1. Người dùng có thể thực hiện và nhận cuộc gọi thoại 1-1 thời gian thực qua WebRTC với coturn STUN/TURN; ghi nhận cuộc gọi nhỡ khi không nghe máy.
  2. Người dùng có thể nhấn giữ tin nhắn bất kỳ để dịch nhanh hai chiều Anh - Việt bằng ML Kit trực tiếp trên thiết bị mà không cần Internet.
  3. Người dùng có thể nhắn tin tương tác với trợ lý ảo UTE AI để nhận phản hồi tự động trong hạn mức 30 yêu cầu/ngày.
**Plans**: TBD
**UI hint**: yes

### Phase 7: Testing, Hardening & Release
**Goal**: Kiểm thử toàn diện theo kim tự tháp kiểm thử, bảo mật OWASP Mobile và đóng gói bản phát hành release đạt chuẩn 100 điểm rubric.
**Depends on**: Phase 6
**Requirements**: REQ-auth-registration, REQ-auth-login-password, REQ-auth-login-otp, REQ-auth-friend-challenge, REQ-auth-totp-2fa, REQ-auth-session-devices, REQ-contacts-management, REQ-messaging-realtime-send-receive, REQ-messaging-offline-first, REQ-messaging-delivery-read-status, REQ-messaging-group-chat, REQ-messaging-media-attachments, REQ-messaging-search, REQ-voice-call-webrtc, REQ-ai-assistant, REQ-message-translation, REQ-notifications-sync, REQ-ui-standards
**Success Criteria** (what must be TRUE):
  1. Toàn bộ test suite unit test JVM chạy lệnh `./gradlew testDebugUnitTest` đạt 100% tỷ lệ pass.
  2. Kiểm thử tương tác ViewModel với Fake Repository và Mockito xác nhận tính đúng đắn của logic nghiệp vụ.
  3. Kiểm thử Room in-memory test trên Android Test và UI test Espresso hoạt động ổn định.
  4. Ứng dụng được biên dịch ở cấu hình release với R8 thu nhỏ mã nguồn và ký APK hoàn chỉnh, sẵn sàng nộp bài và demo.
**Plans**: TBD

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4 → 5 → 6 → 7

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Foundation & Walking Skeleton | 0/1 | Not started | - |
| 2. Authentication & Account Security | 0/1 | Not started | - |
| 3. Contacts & Friendships | 0/1 | Not started | - |
| 4. Realtime & Offline-First Core Messaging | 0/1 | Not started | - |
| 5. Media Attachments & Notifications | 0/1 | Not started | - |
| 6. Advanced Extensions | 0/1 | Not started | - |
| 7. Testing, Hardening & Release | 0/1 | Not started | - |
