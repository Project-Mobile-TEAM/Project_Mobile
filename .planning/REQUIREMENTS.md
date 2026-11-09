# Requirements: UTEChat

**Defined:** 2026-11-10
**Core Value:** Trải nghiệm nhắn tin và gọi thoại ổn định, tin cậy với kiến trúc chuẩn mực theo đúng tài liệu giảng dạy, hỗ trợ offline-first mượt mà và bảo mật đa lớp.

## v1 Requirements

Requirements for initial release. Each maps to roadmap phases.

### Authentication & Account Security

- [ ] **REQ-auth-registration**: Đăng ký tài khoản bằng số điện thoại E.164, xác thực OTP demo và thiết lập mật khẩu
- [ ] **REQ-auth-login-password**: Đăng nhập mật khẩu với kiểm tra thiết bị tin cậy và cơ chế chống dò mật khẩu
- [ ] **REQ-auth-login-otp**: Đăng nhập bằng mã OTP không cần mật khẩu
- [ ] **REQ-auth-friend-challenge**: Thử thách nhận diện đúng 3 bạn bè trong 15 người khi đăng nhập trên thiết bị lạ
- [ ] **REQ-auth-totp-2fa**: Bảo vệ hai lớp (2FA) bằng TOTP chuẩn RFC 6238 và 8 mã dự phòng
- [ ] **REQ-auth-session-devices**: Quản lý danh sách thiết bị đang hoạt động và thu hồi token từ xa qua token_valid_after

### Contacts & Social

- [ ] **REQ-contacts-management**: Quản lý danh bạ, tìm kiếm bạn bè bằng SĐT chính xác, gửi yêu cầu kết bạn và chặn người dùng

### Core Messaging & Offline-first

- [ ] **REQ-messaging-realtime-send-receive**: Nhắn tin văn bản thời gian thực trong hội thoại 1-1 và nhóm qua WebSocket
- [ ] **REQ-messaging-offline-first**: Hàng đợi gửi tin nhắn offline-first với Room SQLite và chuỗi WorkManager send-messages
- [ ] **REQ-messaging-delivery-read-status**: Quản lý và hiển thị trạng thái đã gửi, đã nhận, đã đọc (watermark) và chỉ báo đang nhập
- [ ] **REQ-messaging-group-chat**: Tạo nhóm chat, thêm/xóa thành viên, chuyển quyền quản trị viên và chặn xem tin trước khi vào
- [ ] **REQ-messaging-search**: Tìm kiếm nội dung tin nhắn cục bộ nhanh chóng bằng Room FTS4

### Media & Notifications

- [ ] **REQ-messaging-media-attachments**: Gửi nhận đa phương tiện (ảnh nén, ghi âm voice, video, file, vị trí) với xác thực quyền tải
- [ ] **REQ-notifications-sync**: Thông báo tin mới qua NotificationChannel và tự động đồng bộ bù dữ liệu qua /sync khi kết nối lại

### Advanced Extensions

- [ ] **REQ-voice-call-webrtc**: Cuộc gọi thoại 1-1 thời gian thực bằng WebRTC (coturn STUN/TURN) và ghi nhận cuộc gọi nhỡ
- [ ] **REQ-ai-assistant**: Trợ lý ảo bot UTE AI thông minh phía server với hạn mức 30 yêu cầu/ngày
- [ ] **REQ-message-translation**: Dịch tin nhắn hai chiều Anh - Việt trực tiếp trên thiết bị bằng Google ML Kit Translation

### UI Standards & Accessibility

- [ ] **REQ-ui-standards**: Hỗ trợ đa ngôn ngữ (tiếng Việt, tiếng Anh), giao diện Material 3 DayNight và xoay màn hình

## v2 Requirements

### Extended Features

- **FEAT-call-video**: Cuộc gọi video 1-1 và cuộc gọi nhóm WebRTC
- **FEAT-msg-e2ee**: Mã hoá đầu cuối (End-to-End Encryption) cho các cuộc trò chuyện bí mật
- **FEAT-push-fcm**: Tích hợp Google Firebase Cloud Messaging (FCM) để nhận thông báo tức thì khi app đã bị tắt
- **FEAT-msg-reactions**: Thả biểu cảm cảm xúc (reactions) cho từng tin nhắn

## Out of Scope

| Feature | Reason |
|---------|--------|
| Mã hoá đầu cuối E2EE | Độ phức tạp cao, mô hình Cloud Chat Telegram phù hợp hơn với yêu cầu đồ án |
| Đăng nhập mạng xã hội | Yêu cầu nghiệp vụ định danh duy nhất bằng số điện thoại E.164 |
| Gửi SMS thật | Tốn kém chi phí viễn thông; thay thế hoàn hảo bằng OTP demo giả lập |
| Chỉnh sửa / Thu hồi tin nhắn | Nằm ngoài phạm vi chấm điểm của rubric môn học |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| REQ-ui-standards | Phase 1 | Pending |
| REQ-auth-registration | Phase 2 | Pending |
| REQ-auth-login-password | Phase 2 | Pending |
| REQ-auth-login-otp | Phase 2 | Pending |
| REQ-auth-friend-challenge | Phase 2 | Pending |
| REQ-auth-totp-2fa | Phase 2 | Pending |
| REQ-auth-session-devices | Phase 2 | Pending |
| REQ-contacts-management | Phase 3 | Pending |
| REQ-messaging-realtime-send-receive | Phase 4 | Pending |
| REQ-messaging-offline-first | Phase 4 | Pending |
| REQ-messaging-delivery-read-status | Phase 4 | Pending |
| REQ-messaging-group-chat | Phase 4 | Pending |
| REQ-messaging-search | Phase 4 | Pending |
| REQ-messaging-media-attachments | Phase 5 | Pending |
| REQ-notifications-sync | Phase 5 | Pending |
| REQ-voice-call-webrtc | Phase 6 | Pending |
| REQ-ai-assistant | Phase 6 | Pending |
| REQ-message-translation | Phase 6 | Pending |

**Coverage:**
- v1 requirements: 18 total
- Mapped to phases: 18
- Unmapped: 0 ✓

---
*Requirements defined: 2026-11-10*
*Last updated: 2026-11-10 sau quy trình Ingest Docs*
