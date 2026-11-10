# Roadmap: UTEChat

## Overview

Hành trình xây dựng ứng dụng chat di động UTEChat từ nền tảng walking skeleton, module xác thực SĐT/OTP, danh bạ bạn bè, hệ thống chat thời gian thực offline-first, truyền thông đa phương tiện đến 2 tính năng sáng tạo (WebRTC Voice Call & On-device Translation) và bộ unit test toàn diện đạt điểm tuyệt đối 100/100 theo rubric môn học.

## Phases

- [ ] **Phase 1: Foundation & Walking Skeleton** - Khởi tạo hạ tầng backend Spring Boot kết nối Supabase, khung app Android MVVM và thông luồng kiểm tra /health
- [ ] **Phase 2: Authentication & Account Management** - Đăng ký SĐT E.164, OTP demo, đăng nhập 2 chế độ (mật khẩu/OTP), quản lý hồ sơ và soft delete tài khoản
- [ ] **Phase 3: Contacts & Friendships** - Quản lý danh bạ, tìm kiếm SĐT chính xác, gửi/nhận lời mời kết bạn và danh sách chặn
- [ ] **Phase 4: Realtime & Offline-First Core Messaging** - Nhắn tin 1-1 và nhóm thời gian thực qua WebSocket, hàng đợi WorkManager Room và tìm kiếm FTS4 tiếng Việt không dấu
- [ ] **Phase 5: Media Attachments & Notifications** - Tải lên và gửi nhận ảnh/voice note/video/file, kèm thông báo NotificationChannel và đồng bộ ngầm /sync
- [ ] **Phase 6: Creative Extensions (Voice Call & ML Kit)** - Cuộc gọi thoại WebRTC 1-1 (sáng tạo chính) và dịch tin nhắn on-device bằng ML Kit (sáng tạo dự phòng)
- [ ] **Phase 7: Testing, Quality & Release Packaging** - Bộ unit test JVM pass 100%, kiểm thử Room in-memory, tối ưu R8 và đóng gói bản nộp bài (APK, mã nguồn, tài liệu)

---

## Phase Details

### Phase 1: Foundation & Walking Skeleton
**Goal**: Thiết lập khung dự án Android (Java, Single Activity Navigation, View Binding, AppExecutors, Room) và Backend Spring Boot (kết nối Supabase PostgreSQL với Flyway migration), thông luồng API /health đầu tiên.
**Depends on**: Nothing (first phase)
**Requirements**: REQ-ui-standards
**Success Criteria** (what must be TRUE):
  1. Backend server khởi động thành công, chạy migration Flyway trên Supabase PostgreSQL và phản hồi API `GET /api/v1/health`.
  2. Ứng dụng Android khởi động hiển thị màn hình chính với BottomNavigationView theo kiến trúc Single Activity Navigation Component.
  3. Ứng dụng Android gọi API /health lên server qua Retrofit và hiển thị trạng thái kết nối thành công.
  4. Hỗ trợ chuyển đổi ngôn ngữ Anh / Việt và giao diện sáng / tối (Material 3 DayNight) mà không bị mất trạng thái khi xoay màn hình (orientation change).
**Plans**: TBD
**UI hint**: yes

### Phase 2: Authentication & Account Management
**Goal**: Hoàn thiện toàn bộ quy trình đăng ký số điện thoại E.164, xác thực OTP demo, đăng nhập 2 chế độ, lưu trữ JWT an toàn, quản lý hồ sơ cá nhân và xóa tài khoản an toàn.
**Depends on**: Phase 1
**Requirements**: REQ-auth-registration, REQ-auth-login, REQ-auth-account
**Success Criteria** (what must be TRUE):
  1. Người dùng có thể đăng ký tài khoản mới bằng số điện thoại E.164, xác thực bằng mã OTP demo (hiển thị trực tiếp trong giao diện/log) và đặt mật khẩu an toàn.
  2. Người dùng có thể đăng nhập bằng mật khẩu hoặc đăng nhập nhanh bằng OTP demo; JWT 30 ngày được lưu an toàn trong SharedPreferences mã hóa / Keystore.
  3. Người dùng có thể cập nhật thông tin cá nhân (họ tên, avatar), đổi mật khẩu và đăng xuất.
  4. Khi người dùng thực hiện xóa tài khoản (soft delete), quan hệ danh bạ hai chiều bị xóa sạch, token bị vô hiệu hóa lập tức.
**Plans**: TBD
**UI hint**: yes

### Phase 3: Contacts & Friendships
**Goal**: Cho phép tìm kiếm người dùng mới bằng số điện thoại chính xác, kết nối danh bạ bạn bè và quản lý quyền riêng tư.
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
**Goal**: Xây dựng cốt lõi tính năng nhắn tin thời gian thực 1-1 và nhóm hỗ trợ gửi ngoại tuyến qua Room và WorkManager, cùng tìm kiếm FTS4 tiếng Việt.
**Depends on**: Phase 3
**Requirements**: REQ-messaging-realtime, REQ-messaging-offline-first, REQ-messaging-delivery-read-status, REQ-messaging-group-chat, REQ-messaging-search
**Success Criteria** (what must be TRUE):
  1. Người dùng có thể gửi và nhận tin nhắn văn bản tức thì qua kênh WebSocket STOMP trong hội thoại 1-1 và chat nhóm.
  2. Khi mất kết nối mạng, tin nhắn được lưu trạng thái SENDING trong Room và chuỗi WorkManager tự động gửi tuần tự lên server khi có mạng lại.
  3. Vòng đời trạng thái tin nhắn (SENDING -> SENT -> DELIVERED -> READ) và chỉ báo đang gõ (typing) hiển thị mượt mà theo thời gian thực (đảm bảo invariant `last_delivered >= last_read`).
  4. Quản trị viên nhóm có thể tạo nhóm, thêm/xóa thành viên; người mới không xem được tin nhắn trước thời điểm gia nhập.
  5. Người dùng có thể tìm kiếm nhanh nội dung tin nhắn cũ trong hội thoại thông qua Room FTS4 chuẩn hóa không dấu (`normalized_text`) mà không cần kết nối mạng.
**Plans**: TBD
**UI hint**: yes

### Phase 5: Media Attachments & Notifications
**Goal**: Mở rộng khả năng gửi nhận tệp đa phương tiện (ảnh, voice note, video, tài liệu), thông báo tin mới và cơ chế đồng bộ nền.
**Depends on**: Phase 4
**Requirements**: REQ-messaging-media-attachments, REQ-notifications-sync
**Success Criteria** (what must be TRUE):
  1. Người dùng có thể chọn ảnh nén, ghi âm giọng nói (voice note), quay/chọn video và gửi tệp tin tài liệu trong cuộc trò chuyện.
  2. Tệp đính kèm được tải lên server an toàn, kiểm tra MIME type; video có thể phát ngoài thông qua FileProvider chia sẻ quyền tạm thời.
  3. Thông báo tin mới xuất hiện trên NotificationChannel khi app ở chế độ nền.
  4. Dữ liệu tin nhắn bị bỏ lỡ được tự động đồng bộ đầy đủ thông qua API `/api/v1/sync` sau khi kết nối mạng được khôi phục.
**Plans**: TBD
**UI hint**: yes

### Phase 6: Creative Extensions (Voice Call & ML Kit)
**Goal**: Triển khai 2 tính năng sáng tạo song song: Gọi thoại WebRTC 1-1 (ưu tiên chính) và Dịch tin nhắn on-device bằng ML Kit (dự phòng an toàn).
**Depends on**: Phase 5
**Requirements**: REQ-voice-call-webrtc, REQ-message-translation
**Success Criteria** (what must be TRUE):
  1. Người dùng có thể thực hiện và nhận cuộc gọi thoại 1-1 thời gian thực qua WebRTC với coturn STUN/TURN (hỗ trợ cả TCP/TLS 443); hiển thị thông báo cuộc gọi nhỡ khi đối phương không nhấc máy.
  2. Người dùng có thể nhấn giữ tin nhắn bất kỳ để dịch tức thời hai chiều Anh - Việt bằng Google ML Kit Translation trực tiếp trên thiết bị (hoạt động 100% offline, không cần Internet).
  3. Nếu môi trường demo tại giảng đường gặp sự cố tường lửa chặn WebRTC, tính năng Dịch tin nhắn ML Kit đảm bảo bảo toàn 100% điểm sáng tạo (5 điểm) của rubric.
**Plans**: TBD
**UI hint**: yes

### Phase 7: Testing, Quality & Release Packaging
**Goal**: Kiểm thử toàn diện theo kim tự tháp kiểm thử, bảo mật OWASP Mobile và đóng gói hồ sơ nộp bài đạt chuẩn 100 điểm rubric.
**Depends on**: Phase 6
**Requirements**: REQ-testing-quality
**Success Criteria** (what must be TRUE):
  1. Toàn bộ test suite unit test JVM chạy lệnh `./gradlew testDebugUnitTest` đạt 100% tỷ lệ pass (Room DAO, Repository, ViewModel, Crypto, Mappers).
  2. Giao diện người dùng tuân thủ Material Design 3 rõ ràng, hỗ trợ đầy đủ tiếng Việt và tiếng Anh, xoay ngang/dọc mượt mà.
  3. Đóng gói đầy đủ hồ sơ nộp bài theo đúng Tiêu chí 1 của Rubric: Mã nguồn có thư mục `.git`, báo cáo Word, bộ ảnh chụp màn hình đầy đủ các luồng, và file APK cài đặt hoàn chỉnh.
**Plans**: TBD
