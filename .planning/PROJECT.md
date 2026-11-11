# UTEChat — Ứng dụng Chat Di Động

## What This Is

UTEChat là hệ thống ứng dụng nhắn tin và gọi thoại thời gian thực cho nền tảng Android, phục vụ đồ án môn học Lập trình di động. Hệ thống bao gồm ứng dụng di động Android (Java, MVVM, Room, Offline-first) và dịch vụ backend trên đám mây (Spring Boot, Supabase PostgreSQL 13 bảng V1, WebSocket thuần JSON, coturn STUN/TURN).

## Core Value

Trải nghiệm nhắn tin và gọi thoại ổn định, tin cậy với kiến trúc chuẩn mực theo đúng tài liệu giảng dạy, hỗ trợ offline-first mượt mà và tối ưu hóa đạt trọn vẹn điểm tối đa (100/100) theo rubric chấm điểm của môn học.

## Business Context

- **Customer**: Giảng viên chấm điểm đồ án và sinh viên sử dụng ứng dụng trò chuyện
- **Revenue model**: Dự án học thuật / mã nguồn mở môn học
- **Success metric**: Đạt điểm tuyệt đối (100/100) theo rubric môn học; 100% test pass
- **Strategy notes**: Bám sát 7 slide bài giảng và rubric môn học (xem [COURSE_ALIGNMENT.md](../docs/COURSE_ALIGNMENT.md)). Tinh gọn các tính năng rườm rà (2FA TOTP, Thử thách bạn bè, Bot AI bên ngoài) để tập trung làm thật mượt 15 tính năng cốt lõi. Triển khai cả 2 tính năng sáng tạo trong V1: Gọi thoại 1-1 WebRTC qua WebSocket signaling và coturn (sáng tạo chính) và Dịch tin nhắn Anh–Việt bằng Google ML Kit Translation (sáng tạo dự phòng an toàn).

## Requirements

### Validated

Nền tảng Phase2: readiness Supabase, Room10 bảng và navigation đã kiểm thử. REQ-ui-standards còn phần Phase3.

### Active (15 Yêu cầu bám sát Rubric 100đ)

- [ ] **REQ-ui-standards**: Hỗ trợ giao diện Material 3 DayNight (chế độ sáng/tối), đa ngôn ngữ Anh - Việt và cơ chế giữ nguyên trạng thái khi xoay màn hình (orientation change).
- [ ] **REQ-auth-registration**: Đăng ký tài khoản bằng số điện thoại E.164, xác thực mã OTP demo và thiết lập mật khẩu bảo mật.
- [ ] **REQ-auth-login**: Đăng nhập bằng số điện thoại kèm mật khẩu hoặc OTP demo; kiểm soát chống brute-force bằng `auth_throttles` (`PHONE_DEVICE`, `PHONE`); cấp JWT an toàn (30 ngày) lưu trữ trong SharedPreferences mã hóa / Keystore.
- [ ] **REQ-auth-account**: Quản lý thông tin hồ sơ (cập nhật họ tên, ảnh đại diện avatar), cài đặt quyền riêng tư (`allowMessagesFromNonContacts`), đổi mật khẩu, đăng xuất và soft delete tài khoản (xóa sạch danh bạ hai chiều, giải phóng SĐT).
- [ ] **REQ-contacts-management**: Quản lý danh bạ bạn bè, tìm kiếm người dùng bằng số điện thoại chính xác, gửi/chấp nhận/từ chối/hủy lời mời kết bạn (`contact_requests`), quản lý bạn bè chính thức (`blocked = false`) và danh sách chặn (`blocked = true`) trong bảng `contacts`.
- [ ] **REQ-messaging-realtime**: Nhắn tin văn bản trực tiếp 1-1 theo thời gian thực: gửi qua REST (`POST /api/conversations/{id}/messages`), nhận qua WebSocket thuần JSON (`/ws`) mang `syncEventId`, kiểm tra quyền riêng tư người lạ (`CONTACT_REQUIRED`).
- [ ] **REQ-messaging-offline-first**: Hàng đợi gửi tin nhắn ngoại tuyến bền vững với Room SQLite, tự động thử lại và đồng bộ ngầm bằng WorkManager khi có kết nối mạng.
- [ ] **REQ-messaging-delivery-read-status**: Quản lý vòng đời trạng thái tin nhắn (SENDING -> SENT -> DELIVERED -> READ) và hiển thị chỉ báo đang gõ (typing indicator).
- [ ] **REQ-messaging-group-chat**: Tạo nhóm chat, thêm/xóa thành viên nhóm (chỉ thêm người đã `ACCEPTED`), phân quyền quản trị viên và cô lập lịch sử tin nhắn trước thời điểm gia nhập.
- [ ] **REQ-messaging-search**: Tìm kiếm nội dung tin nhắn cục bộ tốc độ cao trong Room SQLite FTS4 hỗ trợ tiếng Việt không dấu (chuẩn hóa `normalized_text`).
- [ ] **REQ-messaging-media-attachments**: Gửi và nhận tệp đa phương tiện: ảnh nén, ghi âm giọng nói (voice note), video file và tệp tài liệu; mở tệp ra app ngoài qua `FileProvider`.
- [ ] **REQ-notifications-sync**: Phát thông báo tin nhắn mới qua NotificationChannel khi online/WebSocket; đồng bộ nền best-effort qua WorkManager định kỳ 15 phút (không cam kết tức thời khi app bị force-stop); tự động đồng bộ bù qua `/api/sync?after=<syncEventId>`.
- [ ] **REQ-voice-call-webrtc**: *[Chức năng sáng tạo V1 - Ưu tiên chính]* Cuộc gọi thoại 1-1 thời gian thực giữa hai người dùng đã kết bạn `ACCEPTED` bằng WebRTC qua WebSocket signaling thuần và máy chủ trung chuyển STUN/TURN coturn (hỗ trợ cả TCP/TLS 443 và UDP 3478/443), kèm theo dõi cuộc gọi nhỡ.
- [ ] **REQ-message-translation**: *[Chức năng sáng tạo V1 - Dự phòng]* Dịch tin nhắn văn bản hai chiều Anh - Việt bằng Google ML Kit Translation (chạy on-device sau khi gói ngôn ngữ được tải lần đầu, lưu Room `message_translation`).
- [ ] **REQ-testing-quality**: Bộ Unit Test toàn diện cho Room DAO, Repository, ViewModel, Crypto, Mappers sử dụng JUnit4 & Mockito đạt tỷ lệ pass 100% trên JVM.

### Out of Scope (Hướng phát triển tương lai V2)

- **Xác thực 2FA TOTP & Thử thách nhận diện bạn bè**: Hoãn sang V2 để đảm bảo quy trình demo đăng ký/đăng nhập nhanh gọn, không rủi ro khi hội đồng chấm thi.
- **Bot AI LLM phía server**: Tránh phụ thuộc vào dịch vụ bên thứ 3 và rủi ro quota/timeout khi demo trực tiếp.
- **Mã hoá đầu cuối (End-to-End Encryption)**: Kiến trúc Cloud Chat giống Telegram, mã hoá truyền tải HTTPS/WSS đã đáp ứng an toàn.
- **Thông báo đẩy khi app bị tắt hoàn toàn (FCM)**: V1 không cam kết nhận thông báo tức thời khi app bị force-stop; FCM được chuyển sang định hướng V2.
- **Cuộc gọi nhóm và cuộc gọi video**: Ngoài phạm vi phiên bản V1 đồ án.
- **Đăng nhập bằng mạng xã hội (Google, Facebook)**: Định danh duy nhất bằng số điện thoại chuẩn hoá E.164.

## Context

- **Client Stack**: Android Java, Views + XML, View Binding, Single Activity Navigation Component, MVVM + LiveData, AppExecutors, Room SQLite, Retrofit + OkHttp + Gson.
- **Android SDK**: `minSdk 26`, `compileSdk 35`, `targetSdk 35`. Package chính thức: `com.utechat`.
- **Backend Stack**: Spring Boot 3 (Java 17), Spring Security JWT, Spring WebSocket thuần JSON, Flyway migration, PostgreSQL trên Supabase (mô hình M2, 13 bảng V1).
- **Hạ tầng**: 1 máy chủ AWS EC2 Ubuntu 24.04 (Nginx, Spring Boot systemd, coturn STUN/TURN có secondary IP cho cổng 443, lưu trữ media).
- **Quy tắc phát triển**: Phân chia công việc nhóm 5 người theo mô hình Kiến trúc chống xung đột (Anti-Conflict Architecture): Nhóm trưởng làm Phase 1 Database Supabase (13 bảng V1); Phase 2 hoàn thiện Base skeleton và khóa 6 điểm nóng; 4 thành viên độc lập phụ trách 4 module (Phase 3 đến Phase 6) theo tiến độ bậc thang an toàn.

## Constraints

- **Bảo mật khi host V1**: Bắt buộc tuân thủ [DEPLOYMENT_SECURITY.md](../docs/DEPLOYMENT_SECURITY.md) từ Phase 3 trở đi: Release HTTPS/WSS, URL cấu hình tập trung, Bearer handshake thay JWT trong URL, phân quyền REST/WS/media tại server, secret chỉ ở backend/CI và Spring chỉ nội bộ sau Nginx. Phase 7 kiểm thử hosted; tài liệu không đồng nghĩa đã triển khai đầy đủ.

- **Công nghệ Android**: Bắt buộc Java, Views, Room, LiveData, WorkManager. Không dùng Coroutine/Flow/Compose/Hilt để tuân thủ slide bài giảng.
- **Bảo mật Supabase**: Bắt buộc kích hoạt Row Level Security (RLS) trên mọi bảng, không mở policy để đóng hoàn toàn Data API công khai của Supabase.
- **Quyền riêng tư & Chính sách gửi tin**: `users` có cột `allow_messages_from_non_contacts` (mặc định TRUE). Khi FALSE, người chưa kết bạn `ACCEPTED` không thể gửi tin nhắn 1-1 (HTTP 403 `CONTACT_REQUIRED`). Cuộc gọi thoại V1 bắt buộc hai bên đã kết bạn `ACCEPTED` và không bị block.
- **Giới hạn dung lượng media**: Ảnh $\le$ 10MB, Voice $\le$ 10MB/5 phút, Video file $\le$ 50MB, File $\le$ 25MB. Mở ngoài app qua `FileProvider`.
- **Xác thực token**: 1 JWT 30 ngày chứa `userId` và `deviceId`, không có refresh token. Thu hồi tức thì bằng `token_valid_after`.
- **Quy chuẩn Comment Code sư phạm (Educational In-Code Comments)**: Mọi thành viên / AI agent khi sinh mã nguồn (Android Java, XML layout/nav/strings, Backend Spring Boot, SQL migration, script) BẮT BUỘC phải comment Tiếng Việt chi tiết, súc tích, giải thích rõ ý nghĩa và mục đích logic của từng hàm, khối lệnh và cấu hình quan trọng. Tuyệt đối không viết code chay không chú thích. Mục tiêu: giúp sinh viên hiểu sâu kiến trúc, dễ dàng học code và tự tin giải trình/chỉnh sửa mã nguồn ngay lập tức khi giảng viên đặt câu hỏi vấn đáp trực tiếp trong buổi chấm đồ án.

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| ADR-001: Định danh bằng số điện thoại E.164 | Đơn giản hoá xác thực, tránh phụ thuộc email / OAuth | ✓ Good |
| ADR-002: OTP là OTP demo | Không tốn chi phí SMS gateway thực tế trong đồ án, thao tác nhanh khi demo | ✓ Good |
| ADR-003: Hai cách đăng nhập (Mật khẩu & OTP) | Chống brute-force bằng bảng `auth_throttles` (`PHONE_DEVICE`, `PHONE`), nâng cao trải nghiệm | ✓ Good |
| ADR-007: CSDL PostgreSQL Supabase (13 bảng V1) | Tách biệt logic và CSDL, có dashboard quản trị trực quan | ✓ Good |
| ADR-008: REST gửi tin + WebSocket thuần nhận + `sync_events` | Đồng bộ toàn diện qua `syncEventId`, WebSocket mang `syncEventId`, không STOMP | ✓ Good |
| ADR-009: Offline-first Room & WorkManager | Đảm bảo tin nhắn không bao giờ bị mất khi gián đoạn mạng | ✓ Good |
| ADR-010: Định nghĩa rõ ràng Watermark Receipts | last_delivered >= last_read; cập nhật read tự động nâng delivered | ✓ Good |
| ADR-011: FileProvider cho video/file | Mở media ra app ngoài an toàn bằng `content://` không lộ token | ✓ Good |
| ADR-012: Chính sách thông báo không FCM | NotificationChannel khi online; WorkManager sync nền 15p best-effort; FCM chuyển V2 | ✓ Good |
| ADR-013: WebRTC STUN/TURN có secondary IP TCP/TLS 443 | Vượt tường lửa Wi-Fi trường đại học khi demo gọi điện; chỉ gọi giữa bạn bè ACCEPTED | ✓ Good |
| ADR-014: ML Kit translation on-device | Dịch offline Anh - Việt an toàn, lưu bảng `message_translation` | ✓ Good |
| ADR-018: Token auth & RBAC | Kiểm soát phân quyền ở tầng service, app không tự khai danh tính | ✓ Good |
| ADR-019: Một server chung AWS EC2 | Triển khai tập trung Nginx, Backend, Media, coturn | ✓ Good |
| ADR-020: Quản lý schema bằng migration | Cho phép destructive migration ở debug; hoàn thiện trước bản demo | ✓ Good |
| ADR-021: UI i18n & DayNight | Đạt điểm tối đa tiêu chí giao diện và tiếp cận rubric | ✓ Good |
| ADR-023: Android tech stack bám slide, SDK 35, package `com.utechat` | Tuân thủ 100% mẫu kiến trúc giảng dạy của môn học, chốt SDK 35 ổn định và khớp package codebase | ✓ Good |
| ADR-024: Kiểm thử kim tự tháp bám slide | Đạt điều kiện 'unit test chạy đạt toàn bộ' của rubric (15 điểm) | ✓ Good |
| ADR-025: Kiến trúc chống xung đột cho nhóm 5 người | Khóa 6 điểm nóng và tiến độ bậc thang an toàn | ✓ Good |
| ADR-026: Chuẩn mực comment code Tiếng Việt sư phạm | Bắt buộc comment chi tiết ý nghĩa từng dòng/khối code giúp sinh viên dễ hiểu và tự tin vấn đáp khi thầy hỏi | ✓ Good |

---
*Last updated: 2026-11-10 sau khi tinh chỉnh tinh gọn và đồng bộ hóa chuẩn V1 13 bảng*
