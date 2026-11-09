# UTEChat — Ứng dụng Chat Di Động

## What This Is

UTEChat là hệ thống ứng dụng nhắn tin và gọi thoại thời gian thực cho nền tảng Android, phục vụ đồ án môn học Lập trình di động. Hệ thống bao gồm ứng dụng di động Android (Java, MVVM, Room, Offline-first) và dịch vụ backend trên đám mây (Spring Boot, Supabase PostgreSQL, WebSocket, coturn STUN/TURN).

## Core Value

Trải nghiệm nhắn tin và gọi thoại ổn định, tin cậy với kiến trúc chuẩn mực theo đúng tài liệu giảng dạy, hỗ trợ offline-first mượt mà và bảo mật đa lớp.

## Business Context

- **Customer**: Giảng viên chấm điểm đồ án và sinh viên sử dụng ứng dụng trò chuyện
- **Revenue model**: Dự án học thuật / mã nguồn mở môn học
- **Success metric**: Đạt điểm tuyệt đối (100/100) theo rubric môn học; unit test vượt qua 100%
- **Strategy notes**: Bám sát 7 slide bài giảng và rubric môn học (xem [COURSE_ALIGNMENT.md](file:///C:/Users/Admin/Desktop/Project-Mobile/docs/COURSE_ALIGNMENT.md))

## Requirements

### Validated

(None yet — ship to validate)

### Active

- [ ] **REQ-auth-registration**: Đăng ký bằng số điện thoại E.164 và xác thực OTP demo
- [ ] **REQ-auth-login-password**: Đăng nhập mật khẩu với kiểm tra thiết bị và chống dò mật khẩu
- [ ] **REQ-auth-login-otp**: Đăng nhập bằng mã OTP không cần mật khẩu
- [ ] **REQ-auth-friend-challenge**: Thử thách nhận diện 3 bạn bè trong 15 người trên thiết bị lạ
- [ ] **REQ-auth-totp-2fa**: Bảo mật 2 lớp TOTP RFC 6238 kèm 8 mã dự phòng
- [ ] **REQ-auth-session-devices**: Quản lý danh sách thiết bị và thu hồi quyền từ xa
- [ ] **REQ-contacts-management**: Quản lý danh bạ, tìm kiếm SĐT chính xác và chặn người dùng
- [ ] **REQ-messaging-realtime-send-receive**: Nhắn tin 1-1 và nhóm thời gian thực qua REST và WebSocket
- [ ] **REQ-messaging-offline-first**: Hàng đợi gửi offline-first với Room và WorkManager
- [ ] **REQ-messaging-delivery-read-status**: Quản lý trạng thái đã nhận, đã đọc (watermark) và đang nhập
- [ ] **REQ-messaging-group-chat**: Tạo nhóm, quản lý thành viên và phân quyền quản trị
- [ ] **REQ-messaging-media-attachments**: Gửi nhận đa phương tiện (ảnh, voice, video, file, vị trí)
- [ ] **REQ-messaging-search**: Tìm kiếm tin nhắn cục bộ bằng Room FTS4
- [ ] **REQ-voice-call-webrtc**: Gọi thoại 1-1 qua WebRTC và ghi nhận cuộc gọi nhỡ
- [ ] **REQ-ai-assistant**: Trợ lý ảo bot UTE AI thông minh phía server (quota 30 req/ngày)
- [ ] **REQ-message-translation**: Dịch tin nhắn Anh <-> Việt trực tiếp trên máy bằng Google ML Kit
- [ ] **REQ-notifications-sync**: Thông báo NotificationChannel và đồng bộ ngầm khi kết nối lại
- [ ] **REQ-ui-standards**: Hỗ trợ đa ngôn ngữ (en/vi), Material 3 DayNight và xoay màn hình

### Out of Scope

- Mã hoá đầu cuối (End-to-End Encryption) — Kiến trúc Cloud Chat giống Telegram, mã hoá truyền tải HTTPS/WSS
- Thông báo đẩy khi app bị tắt hoàn toàn (FCM) — Không phụ thuộc Google FCM, dùng WorkManager định kỳ 15 phút
- Cuộc gọi nhóm và cuộc gọi video — Ngoài phạm vi phiên bản v1 đồ án
- Thu hồi / chỉnh sửa tin nhắn đã gửi — Không bắt buộc theo rubric
- Đăng nhập bằng mạng xã hội (Google, Facebook) — Chỉ định danh bằng số điện thoại chuẩn hoá E.164

## Context

- **Client Stack**: Android Java, Views + XML, View Binding, Single Activity Navigation Component, MVVM + LiveData, AppExecutors, Room SQLite, Retrofit + OkHttp + Gson.
- **Backend Stack**: Spring Boot 3 (Java 21), Spring Security JWT, Spring WebSocket thuần, Flyway migration, PostgreSQL trên Supabase (mô hình M2).
- **Hạ tầng**: 1 máy chủ AWS EC2 Ubuntu 24.04 (Nginx, Spring Boot systemd, coturn STUN/TURN, lưu trữ media).
- **Quy tắc phát triển**: Phân chia công việc theo lát cắt dọc độc lập, sử dụng Fake Repository khi backend chưa sẵn sàng.

## Constraints

- **Công nghệ Android**: Bắt buộc Java, Views, Room, LiveData, WorkManager. Không dùng Coroutine/Flow/Compose/Hilt để tuân thủ slide bài giảng.
- **Bảo mật Supabase**: Bắt buộc kích hoạt Row Level Security (RLS) trên mọi bảng, không mở policy để đóng hoàn toàn Data API công khai của Supabase.
- **Giới hạn dung lượng media**: Ảnh <= 10MB, Voice <= 10MB/5 phút, Video <= 50MB, File <= 25MB.
- **Xác thực token**: 1 JWT 30 ngày chứa userId và deviceId, không có refresh token. Thu hồi tức thì bằng token_valid_after.

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| ADR-001: Định danh bằng số điện thoại E.164 | Đơn giản hoá xác thực, tránh phụ thuộc email / OAuth | ✓ Good |
| ADR-002: OTP là OTP demo | Không tốn chi phí SMS gateway thực tế trong đồ án | ✓ Good |
| ADR-003: Hai cách đăng nhập & thiết bị tin cậy | Nâng cao trải nghiệm người dùng và an toàn thiết bị lạ | ✓ Good |
| ADR-005: Bước nhận diện bạn bè | Cơ chế xác thực sáng tạo độc đáo cho đồ án | ✓ Good |
| ADR-007: CSDL PostgreSQL Supabase (M2) | Tách biệt logic và CSDL, có dashboard quản trị trực quan | ✓ Good |
| ADR-009: Offline-first Room & WorkManager | Đảm bảo tin nhắn không bao giờ bị mất khi gián đoạn mạng | ✓ Good |
| ADR-018: Token auth & RBAC | Kiểm soát phân quyền ở tầng service, app không tự khai danh tính | ✓ Good |
| ADR-019: Một server chung AWS EC2 | Triển khai tập trung Nginx, Backend, Media, coturn | ✓ Good |
| ADR-021: UI i18n & DayNight | Đạt điểm tối đa tiêu chí giao diện và tiếp cận rubric | ✓ Good |
| ADR-023: Android tech stack bám slide | Tuân thủ 100% mẫu kiến trúc giảng dạy của môn học | ✓ Good |
| ADR-024: Kiểm thử kim tự tháp bám slide | Đạt điều kiện 'unit test chạy đạt toàn bộ' của rubric | ✓ Good |

---
*Last updated: 2026-11-10 sau quy trình Ingest Docs*
