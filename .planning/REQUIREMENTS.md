# Requirements: UTEChat

**Defined:** 2026-11-10
**Core Value:** Trải nghiệm nhắn tin và gọi thoại ổn định, tin cậy với kiến trúc chuẩn mực theo đúng tài liệu giảng dạy, hỗ trợ offline-first mượt mà và bám sát 100% thang điểm đánh giá (rubric 100 điểm).

## v1 Requirements (15 yêu cầu cốt lõi bám sát Rubric)

Mỗi yêu cầu ánh xạ trực tiếp đến các tiêu chí đánh giá trong Rubric môn học (100 điểm) và các phase trong roadmap.

### UI Standards & Accessibility (Tiêu chí 3 & 5: 15đ + 15đ)
- [ ] **REQ-ui-standards**: Hỗ trợ giao diện Material 3 DayNight (chế độ sáng/tối), đa ngôn ngữ Anh - Việt và cơ chế giữ nguyên trạng thái khi xoay màn hình (orientation change).

### Authentication & Account Management (Tiêu chí 2: Quản lý tài khoản)
- [ ] **REQ-auth-registration**: Đăng ký tài khoản bằng số điện thoại E.164, xác thực mã OTP demo và thiết lập mật khẩu bảo mật.
- [ ] **REQ-auth-login**: Đăng nhập bằng số điện thoại kèm mật khẩu hoặc OTP demo; cấp JWT an toàn (30 ngày) lưu trữ trong SharedPreferences mã hóa / Keystore.
- [ ] **REQ-auth-account**: Quản lý thông tin hồ sơ (cập nhật họ tên, ảnh đại diện avatar), đổi mật khẩu, đăng xuất và soft delete tài khoản (xóa sạch danh bạ hai chiều).

### Contacts & Social (Tiêu chí 2: Thêm người dùng bằng SĐT)
- [ ] **REQ-contacts-management**: Quản lý danh bạ bạn bè, tìm kiếm người dùng bằng số điện thoại chính xác, gửi/chấp nhận/từ chối lời mời kết bạn và chặn người dùng.

### Core Messaging & Offline-first (Tiêu chí 2: Chat trực tiếp, Chat nhóm, Tìm kiếm)
- [ ] **REQ-messaging-realtime**: Nhắn tin văn bản trực tiếp 1-1 theo thời gian thực: gửi qua REST (`POST /api/conversations/{id}/messages`), nhận qua WebSocket thuần (OkHttp JSON events qua `/ws`), không dùng STOMP theo đúng ADR-008.
- [ ] **REQ-messaging-offline-first**: Hàng đợi gửi tin nhắn ngoại tuyến bền vững với Room SQLite, tự động thử lại và đồng bộ ngầm bằng WorkManager khi có kết nối mạng.
- [ ] **REQ-messaging-delivery-read-status**: Quản lý vòng đời trạng thái tin nhắn (SENDING -> SENT -> DELIVERED -> READ) và hiển thị chỉ báo đang gõ (typing indicator).
- [ ] **REQ-messaging-group-chat**: Tạo nhóm chat, thêm/xóa thành viên nhóm, phân quyền quản trị viên và cô lập lịch sử tin nhắn trước thời điểm gia nhập.
- [ ] **REQ-messaging-search**: Tìm kiếm nội dung tin nhắn cục bộ tốc độ cao trong Room SQLite FTS4 hỗ trợ tiếng Việt không dấu (chuẩn hóa `normalized_text`).

### Multimedia Attachments & Sync (Tiêu chí 3: Tin nhắn multimedia và file)
- [ ] **REQ-messaging-media-attachments**: Gửi và nhận tệp đa phương tiện: ảnh nén, ghi âm giọng nói (voice note), video và tệp tài liệu với xác thực quyền truy cập an toàn.
- [ ] **REQ-notifications-sync**: Phát thông báo tin nhắn mới nội bộ qua NotificationChannel và tự động đồng bộ bù dữ liệu qua API `/api/sync` khi ứng dụng mở lại.

### Creative Extensions (Tiêu chí 4: Chức năng sáng tạo - 5đ)
- [ ] **REQ-voice-call-webrtc**: *[Chức năng sáng tạo 1 - Ưu tiên chính]* Cuộc gọi thoại 1-1 thời gian thực bằng WebRTC qua WebSocket signaling thuần và máy chủ trung chuyển STUN/TURN coturn (hỗ trợ cả TCP/TLS 443), kèm theo dõi cuộc gọi nhỡ.
- [ ] **REQ-message-translation**: *[Chức năng sáng tạo 2 - Dự phòng an toàn]* Dịch tin nhắn văn bản hai chiều Anh - Việt trực tiếp on-device bằng Google ML Kit Translation (chạy offline trên thiết bị sau khi tải gói ngôn ngữ ban đầu).

### Testing & Quality Assurance (Tiêu chí 5: Unit test chạy đạt toàn bộ - 15đ)
- [ ] **REQ-testing-quality**: Bộ Unit Test toàn diện cho Room DAO, Repository, ViewModel, Crypto, Mappers sử dụng JUnit4 & Mockito đạt tỷ lệ pass 100% trên JVM mà không cần thiết bị thật.

---

## v2 Requirements (Dành cho bản nâng cấp mở rộng)

- **FEAT-auth-totp-2fa**: Bảo vệ hai lớp (2FA) bằng ứng dụng TOTP (Google Authenticator) và mã dự phòng.
- **FEAT-auth-friend-challenge**: Thử thách xác thực chọn 3 bạn bè trong 15 người khi đăng nhập trên thiết bị lạ.
- **FEAT-ai-assistant**: Tích hợp bot AI trợ lý thông minh trả lời tự động trong ứng dụng.
- **FEAT-call-video**: Cuộc gọi video 1-1 và gọi video nhóm qua WebRTC.
- **FEAT-push-fcm**: Thông báo đẩy tức thời qua Firebase Cloud Messaging (FCM) khi tắt hoàn toàn ứng dụng.
- **FEAT-msg-reactions**: Thả biểu cảm cảm xúc (like, thả tim, haha) trên từng tin nhắn.
- **FEAT-msg-e2ee**: Mã hóa đầu cuối (End-to-End Encryption) cho các đoạn chat riêng tư.

---

## Out of Scope (Không thuộc phạm vi v1)

| Tính năng | Lý do loại bỏ khỏi v1 |
|-----------|------------------------|
| Xác thực 2FA TOTP & Thử thách bạn bè | Rườm rà, dễ gây tắc nghẽn luồng demo vấn đáp; đăng nhập OTP demo và mật khẩu đã đáp ứng 100% điểm rubric |
| Bot AI LLM phía server | Phụ thuộc bên thứ 3 (OpenAI/Gemini API key), dễ gặp lỗi quota, độ trễ cao và không thuộc rubric |
| Mã hoá đầu cuối E2EE | Độ phức tạp cao, mô hình Cloud Chat tiêu chuẩn giống Telegram phù hợp hơn với yêu cầu đồ án |
| Gửi SMS thật | Tốn kém chi phí viễn thông; thay thế hoàn hảo bằng OTP demo giả lập hiển thị mã |
| Đăng nhập mạng xã hội (Google, Facebook) | Rubric và nghiệp vụ yêu cầu định danh duy nhất bằng số điện thoại |

---

## Traceability (Ma trận ánh xạ Phase)

| Requirement | Phase thực hiện | Phụ trách | Trọng số Rubric | Status |
|-------------|-----------------|-----------|-----------------|--------|
| Nền móng CSDL | Phase 1 (Database & Supabase) | Nhóm trưởng | Nền tảng toàn hệ thống | Pending |
| REQ-ui-standards | Phase 2 (Foundation Skeleton) & Phase 3 | Nhóm trưởng & Cả nhóm | Tiêu chí 3 & 5 (15đ + 15đ) | Pending |
| REQ-auth-registration | Phase 3 (Auth & Profile) | Thành viên 2 | Tiêu chí 2 (20đ) | Pending |
| REQ-auth-login | Phase 3 (Auth & Profile) | Thành viên 2 | Tiêu chí 2 (20đ) | Pending |
| REQ-auth-account | Phase 3 (Auth & Profile) | Thành viên 2 | Tiêu chí 2 (20đ) | Pending |
| REQ-contacts-management | Phase 4 (Contacts) | Thành viên 3 | Tiêu chí 2 (20đ) | Pending |
| REQ-messaging-realtime | Phase 5 (Core Messaging) | Thành viên 4 | Tiêu chí 2 (20đ) | Pending |
| REQ-messaging-offline-first | Phase 5 (Core Messaging) | Thành viên 4 | Tiêu chí 2 & 5 (20đ + 15đ) | Pending |
| REQ-messaging-delivery-read-status | Phase 5 (Core Messaging) | Thành viên 4 | Tiêu chí 2 (20đ) | Pending |
| REQ-messaging-group-chat | Phase 5 (Core Messaging) | Thành viên 4 | Tiêu chí 2 (20đ) | Pending |
| REQ-messaging-search | Phase 5 (Core Messaging) | Thành viên 4 | Tiêu chí 2 (20đ) | Pending |
| REQ-messaging-media-attachments | Phase 6 (Media & Sáng tạo) | Thành viên 5 | Tiêu chí 3 (15đ) | Pending |
| REQ-notifications-sync | Phase 6 (Media & Sáng tạo) | Thành viên 5 | Tiêu chí 3 (15đ) | Pending |
| REQ-voice-call-webrtc | Phase 6 (Media & Sáng tạo) | Thành viên 5 | Tiêu chí 4: Sáng tạo chính (5đ) | Pending |
| REQ-message-translation | Phase 6 (Media & Sáng tạo) | Thành viên 5 | Tiêu chí 4: Sáng tạo dự phòng (5đ) | Pending |
| REQ-testing-quality | Phase 7 (Testing & Release) | Cả nhóm (Lead nghiệm thu) | Tiêu chí 5 (15đ) | Pending |

**Coverage:**
- v1 requirements: 15 total
- Mapped to phases: 15
- Unmapped: 0 ✓
- Tỷ lệ đáp ứng rubric: **100/100 điểm**

---
*Requirements defined: 2026-11-10*
*Last updated: 2026-11-10 sau khi cập nhật lộ trình 7 phase và kiến trúc chống xung đột*
