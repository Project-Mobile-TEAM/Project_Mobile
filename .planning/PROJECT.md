# UTEChat — Ứng dụng Chat Di Động

## What This Is

UTEChat là hệ thống ứng dụng nhắn tin và gọi thoại thời gian thực cho nền tảng Android, phục vụ đồ án môn học Lập trình di động. Hệ thống bao gồm ứng dụng di động Android (Java, MVVM, Room, Offline-first) và dịch vụ backend trên đám mây (Spring Boot, Supabase PostgreSQL, WebSocket, coturn STUN/TURN).

## Core Value

Trải nghiệm nhắn tin và gọi thoại ổn định, tin cậy với kiến trúc chuẩn mực theo đúng tài liệu giảng dạy, hỗ trợ offline-first mượt mà và tối ưu hóa đạt trọn vẹn điểm tối đa (100/100) theo rubric chấm điểm của môn học.

## Business Context

- **Customer**: Giảng viên chấm điểm đồ án và sinh viên sử dụng ứng dụng trò chuyện
- **Revenue model**: Dự án học thuật / mã nguồn mở môn học
- **Success metric**: Đạt điểm tuyệt đối (100/100) theo rubric môn học; 100% test pass
- **Strategy notes**: Bám sát 7 slide bài giảng và rubric môn học (xem [COURSE_ALIGNMENT.md](file:///C:/Users/Admin/Desktop/Project-Mobile/docs/COURSE_ALIGNMENT.md)). Tinh gọn các tính năng rườm rà (2FA TOTP, Thử thách bạn bè, Bot AI bên ngoài) để tập trung làm thật mượt 15 tính năng cốt lõi. Triển khai cả 2 tính năng sáng tạo trong v1: Gọi thoại 1-1 WebRTC qua WebSocket signaling và coturn (sáng tạo chính) và Dịch tin nhắn Anh–Việt bằng Google ML Kit Translation (sáng tạo dự phòng an toàn).

## Requirements

### Validated

(None yet — ship to validate)

### Active (15 Yêu cầu bám sát Rubric 100đ)

- [ ] **REQ-ui-standards**: Hỗ trợ giao diện Material 3 DayNight (chế độ sáng/tối), đa ngôn ngữ Anh - Việt và cơ chế giữ nguyên trạng thái khi xoay màn hình (orientation change).
- [ ] **REQ-auth-registration**: Đăng ký tài khoản bằng số điện thoại E.164, xác thực mã OTP demo và thiết lập mật khẩu bảo mật.
- [ ] **REQ-auth-login**: Đăng nhập bằng số điện thoại kèm mật khẩu hoặc OTP demo; cấp JWT an toàn (30 ngày) lưu trữ trong SharedPreferences mã hóa / Keystore.
- [ ] **REQ-auth-account**: Quản lý thông tin hồ sơ (cập nhật họ tên, ảnh đại diện avatar), đổi mật khẩu, đăng xuất và soft delete tài khoản (xóa sạch danh bạ hai chiều).
- [ ] **REQ-contacts-management**: Quản lý danh bạ bạn bè, tìm kiếm người dùng bằng số điện thoại chính xác, gửi/chấp nhận/từ chối lời mời kết bạn và chặn người dùng.
- [ ] **REQ-messaging-realtime**: Nhắn tin văn bản trực tiếp 1-1 theo thời gian thực: gửi qua REST, nhận qua WebSocket thuần JSON.
- [ ] **REQ-messaging-offline-first**: Hàng đợi gửi tin nhắn ngoại tuyến bền vững với Room SQLite, tự động thử lại và đồng bộ ngầm bằng WorkManager khi có kết nối mạng.
- [ ] **REQ-messaging-delivery-read-status**: Quản lý vòng đời trạng thái tin nhắn (SENDING -> SENT -> DELIVERED -> READ) và hiển thị chỉ báo đang gõ (typing indicator).
- [ ] **REQ-messaging-group-chat**: Tạo nhóm chat, thêm/xóa thành viên nhóm, phân quyền quản trị viên và cô lập lịch sử tin nhắn trước thời điểm gia nhập.
- [ ] **REQ-messaging-search**: Tìm kiếm nội dung tin nhắn cục bộ tốc độ cao trong Room SQLite FTS4 hỗ trợ tiếng Việt không dấu (chuẩn hóa `normalized_text`).
- [ ] **REQ-messaging-media-attachments**: Gửi và nhận tệp đa phương tiện: ảnh nén, ghi âm giọng nói (voice note), video và tệp tài liệu với xác thực quyền truy cập an toàn.
- [ ] **REQ-notifications-sync**: Phát thông báo tin nhắn mới nội bộ qua NotificationChannel và tự động đồng bộ bù dữ liệu qua API `/api/sync` khi ứng dụng mở lại.
- [ ] **REQ-voice-call-webrtc**: *[Chức năng sáng tạo v1 - Ưu tiên chính]* Cuộc gọi thoại 1-1 thời gian thực bằng WebRTC qua WebSocket signaling và coturn STUN/TURN, kèm theo dõi cuộc gọi nhỡ.
- [ ] **REQ-message-translation**: *[Chức năng sáng tạo v1 - Dự phòng]* Dịch tin nhắn văn bản hai chiều Anh - Việt bằng Google ML Kit Translation (chạy on-device sau khi gói ngôn ngữ được tải lần đầu).
- [ ] **REQ-testing-quality**: Bộ Unit Test toàn diện cho Room DAO, Repository, ViewModel, Crypto, Mappers sử dụng JUnit4 & Mockito đạt tỷ lệ pass 100% trên JVM.

### Out of Scope

- **Xác thực 2FA TOTP & Thử thách nhận diện bạn bè**: Hoãn sang v2 để đảm bảo quy trình demo đăng ký/đăng nhập nhanh gọn, không rủi ro khi hội đồng chấm thi.
- **Bot AI LLM phía server**: Tránh phụ thuộc vào dịch vụ bên thứ 3 và rủi ro quota/timeout khi demo trực tiếp.
- **Mã hoá đầu cuối (End-to-End Encryption)**: Kiến trúc Cloud Chat giống Telegram, mã hoá truyền tải HTTPS/WSS đã đáp ứng an toàn.
- **Thông báo đẩy khi app bị tắt hoàn toàn (FCM)**: Không phụ thuộc Google Services phức tạp, dùng WorkManager đồng bộ nền định kỳ 15 phút.
- **Cuộc gọi nhóm và cuộc gọi video**: Ngoài phạm vi phiên bản v1 đồ án.
- **Đăng nhập bằng mạng xã hội (Google, Facebook)**: Định danh duy nhất bằng số điện thoại chuẩn hoá E.164.

## Context

- **Client Stack**: Android Java, Views + XML, View Binding, Single Activity Navigation Component, MVVM + LiveData, AppExecutors, Room SQLite, Retrofit + OkHttp + Gson.
- **Android SDK**: `minSdk 26`, `compileSdk 35`, `targetSdk 35`.
- **Backend Stack**: Spring Boot 3 (Java 21), Spring Security JWT, Spring WebSocket thuần JSON, Flyway migration, PostgreSQL trên Supabase (mô hình M2).
- **Hạ tầng**: 1 máy chủ AWS EC2 Ubuntu 24.04 (Nginx, Spring Boot systemd, coturn STUN/TURN, lưu trữ media).
- **Quy tắc phát triển**: Phân chia công việc nhóm 5 người theo mô hình Kiến trúc chống xung đột (Anti-Conflict Architecture): Nhóm trưởng làm Phase 1 Database Supabase; Phase 2 hoàn thiện Base skeleton và khóa 6 điểm nóng; 4 thành viên độc lập phụ trách 4 module (Phase 3 đến Phase 6) theo tiến độ bậc thang an toàn.

## Constraints

- **Công nghệ Android**: Bắt buộc Java, Views, Room, LiveData, WorkManager. Không dùng Coroutine/Flow/Compose/Hilt để tuân thủ slide bài giảng.
- **Bảo mật Supabase**: Bắt buộc kích hoạt Row Level Security (RLS) trên mọi bảng, không mở policy để đóng hoàn toàn Data API công khai của Supabase.
- **Giới hạn dung lượng media**: Ảnh <= 10MB, Voice <= 10MB/5 phút, Video <= 50MB, File <= 25MB.
- **Xác thực token**: 1 JWT 30 ngày chứa userId và deviceId, không có refresh token. Thu hồi tức thì bằng token_valid_after.

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| ADR-001: Định danh bằng số điện thoại E.164 | Đơn giản hoá xác thực, tránh phụ thuộc email / OAuth | ✓ Good |
| ADR-002: OTP là OTP demo | Không tốn chi phí SMS gateway thực tế trong đồ án, thao tác nhanh khi demo | ✓ Good |
| ADR-003: Hai cách đăng nhập (Mật khẩu & OTP) | Nâng cao trải nghiệm người dùng theo đúng chuẩn ứng dụng chat hiện đại | ✓ Good |
| ADR-007: CSDL PostgreSQL Supabase (M2) | Tách biệt logic và CSDL, có dashboard quản trị trực quan | ✓ Good |
| ADR-009: Offline-first Room & WorkManager | Đảm bảo tin nhắn không bao giờ bị mất khi gián đoạn mạng | ✓ Good |
| ADR-010: Định nghĩa rõ ràng Watermark Receipts | last_delivered >= last_read; cập nhật read tự động nâng delivered | ✓ Good |
| ADR-013: WebRTC STUN/TURN có fallback TCP/TLS 443 | Vượt tường lửa Wi-Fi trường đại học khi demo gọi điện | ✓ Good |
| ADR-018: Token auth & RBAC | Kiểm soát phân quyền ở tầng service, app không tự khai danh tính | ✓ Good |
| ADR-019: Một server chung AWS EC2 | Triển khai tập trung Nginx, Backend, Media, coturn | ✓ Good |
| ADR-020: Room Migration Policy nghiêm ngặt | Chỉ dùng destructive migration trong môi trường debug; bảo toàn tin gửi offline | ✓ Good |
| ADR-021: UI i18n & DayNight | Đạt điểm tối đa tiêu chí giao diện và tiếp cận rubric | ✓ Good |
| ADR-023: Android tech stack bám slide, SDK 35, package `com.utechat` | Tuân thủ 100% mẫu kiến trúc giảng dạy của môn học, chốt SDK 35 ổn định và khớp package codebase | ✓ Good |
| ADR-024: Kiểm thử kim tự tháp bám slide | Đạt điều kiện 'unit test chạy đạt toàn bộ' của rubric (15 điểm) | ✓ Good |
| ADR-025: Kiến trúc chống xung đột cho nhóm 5 người | Khóa 6 điểm nóng (Single Activity, split strings, nested nav, modular API, pre-wired Room entities) và tiến độ bậc thang | ✓ Good |

---
*Last updated: 2026-11-10 sau khi tinh chỉnh tinh gọn bám sát Rubric 100đ*
