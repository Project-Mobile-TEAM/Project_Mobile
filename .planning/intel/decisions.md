# Synthesized Decisions (ADR)

> [!NOTE]
> **Historical Snapshot:** File này là snapshot trích xuất tự động tại thời điểm Ingest Docs ban đầu (2026-11-10). Nguồn chân lý hiện hành cho các quyết định kiến trúc là [docs/ARCHITECTURE_DECISIONS.md](../../docs/ARCHITECTURE_DECISIONS.md) và [.planning/PROJECT.md](../PROJECT.md).

## ADR-001: Định danh chỉ bằng số điện thoại
- source: docs/ARCHITECTURE_DECISIONS.md
- status: locked
- decision: Tài khoản định danh bằng số điện thoại chuẩn hoá theo định dạng E.164 (+84912345678). Không hỗ trợ đăng ký bằng email hay OAuth Gmail.
- scope: identity, authentication, user-management

## ADR-002: OTP là OTP demo (giả lập), không gửi SMS thật
- source: docs/ARCHITECTURE_DECISIONS.md
- status: locked
- decision: Server sinh OTP 6 chữ số, lưu bản băm, ghi nội dung vào demo_sms_outbox. Nằm sau interface OtpDelivery để có thể tích hợp SMS gateway thật sau này.
- scope: OTP, SMS, registration, verification

## ADR-003: Hai cách đăng nhập và thiết bị tin cậy
- source: docs/ARCHITECTURE_DECISIONS.md
- status: locked
- decision: Hỗ trợ hai phương thức đăng nhập: (A) SĐT + Mật khẩu, (B) SĐT + OTP. Thiết bị tin cậy vào thẳng; thiết bị lạ phải qua xác thực bổ sung. Thiết bị được gán deviceId ngẫu nhiên mã hoá bằng Keystore trên app, server chỉ lưu hash.
- scope: authentication, device-trust, security

## ADR-004: 2FA bằng TOTP kèm mã dự phòng
- source: docs/ARCHITECTURE_DECISIONS.md
- status: proposed
- decision: Tùy chọn bảo mật 2FA sử dụng TOTP chuẩn RFC 6238 (SHA-1, 6 chữ số, chu kỳ 30 giây, dung sai ±1 nhịp) và 8 mã dự phòng dùng một lần. Chỉ áp dụng cho đăng nhập bằng mật khẩu trên thiết bị lạ.
- scope: 2FA, TOTP, security

## ADR-005: Bước nhận diện bạn bè trên thiết bị lạ
- source: docs/ARCHITECTURE_DECISIONS.md
- status: locked
- decision: [v2] Nếu tài khoản có từ 3 bạn bè trong danh bạ trở lên, khi đăng nhập trên thiết bị lạ phải chọn đúng 3 bạn trong danh sách 15 người (3 bạn thật + 12 mồi ngẫu nhiên). Cho phép tối đa 3 lần thử trong cửa sổ 15 phút, mỗi lần sai xáo trộn thứ tự cùng bộ chứ không tạo bộ mới (chống tấn công giao thoa). Hết hạn 15 phút mới tạo bộ mới. (Friend challenge hoãn sang v2).
- scope: authentication-challenge, untrusted-device, security

## ADR-006: Server là Spring Boot
- source: docs/ARCHITECTURE_DECISIONS.md
- status: proposed
- decision: Backend xây dựng bằng Spring Boot 3 (Java 21), Spring Web, Spring Security (JWT), Spring Data JPA, Spring WebSocket thuần (không STOMP), Bean Validation, Flyway.
- scope: backend-framework, architecture

## ADR-007: CSDL là PostgreSQL do Supabase quản lý (mô hình M2)
- source: docs/ARCHITECTURE_DECISIONS.md
- status: locked
- decision: Backend server giữ toàn bộ nghiệp vụ logic. Supabase chỉ được dùng làm hạ tầng CSDL PostgreSQL qua kết nối TLS. Mọi bảng phải bật Row Level Security không cấp policy để khoá Data API công khai của Supabase.
- scope: database, PostgreSQL, Supabase

## ADR-008: Gửi bằng REST, nhận bằng WebSocket thuần
- source: docs/ARCHITECTURE_DECISIONS.md
- status: proposed
- decision: Gửi tin nhắn và tải media qua REST (idempotent theo clientId, thông qua WorkManager). Nhận tin mới, cập nhật trạng thái đã nhận/đọc, typing và tín hiệu cuộc gọi qua WebSocket /ws kèm JWT token.
- scope: network-protocol, REST, WebSocket

## ADR-009: Offline-first với Room và hàng đợi gửi
- source: docs/ARCHITECTURE_DECISIONS.md
- status: locked
- decision: Room SQLite là nguồn dữ liệu duy nhất (single source of truth) cho UI app. Tin gửi đi ghi vào Room với trạng thái SENDING và hàng đợi WorkManager 'send-messages' tuần tự gửi lên server (APPEND_OR_REPLACE, NetworkType.CONNECTED, backoff 10s, tối đa 3 lần).
- scope: offline-first, Room, WorkManager, message-sync

## ADR-010: Trạng thái đã nhận/đã đọc dùng mốc (watermark)
- source: docs/ARCHITECTURE_DECISIONS.md
- status: proposed
- decision: Quản lý trạng thái đã nhận và đã đọc theo mốc message id: last_delivered_message_id và last_read_message_id cho từng thành viên hội thoại thay vì lưu biên nhận riêng lẻ từng tin.
- scope: message-status, read-receipts

## ADR-011: Upload file qua REST, lưu ổ đĩa EC2
- source: docs/ARCHITECTURE_DECISIONS.md
- status: proposed
- decision: Upload media bằng multipart qua POST /api/media trả mediaUrl được bảo vệ theo phiên/token. File lưu trữ trên ổ đĩa EC2 (/opt/utechat/uploads). Giới hạn: ảnh 10MB, voice 10MB/5phút, video 50MB, file 25MB.
- scope: media-upload, file-storage

## ADR-012: Thông báo không dùng FCM
- source: docs/ARCHITECTURE_DECISIONS.md
- status: proposed
- decision: Thông báo tin nhắn mới khi app đang chạy hoặc nền qua NotificationChannel và WebSocket. Khi app bị hệ thống tắt hoàn toàn, dùng WorkManager định kỳ 15 phút gọi /sync để thông báo (không phụ thuộc Firebase FCM).
- scope: notifications, background-sync

## ADR-013: Gọi thoại trong app bằng WebRTC
- source: docs/ARCHITECTURE_DECISIONS.md
- status: proposed
- decision: Gọi thoại 1-1 qua WebRTC P2P/TURN, báo hiệu qua WebSocket. Trạng thái cuộc gọi (RINGING, ACTIVE, ENDED, MISSED) được quản lý và ghi bản ghi CALL vào hội thoại. Sử dụng coturn trên EC2.
- scope: WebRTC, VoIP, voice-call

## ADR-014: Dịch tin nhắn bằng ML Kit trên máy
- source: docs/ARCHITECTURE_DECISIONS.md
- status: proposed
- decision: Tích hợp tính năng dịch tin nhắn (Anh <-> Việt) trực tiếp trên thiết bị Android bằng Google ML Kit Translation. Dữ liệu bản dịch được lưu bộ nhớ cục bộ trong Room (message_translations).
- scope: ML-Kit, translation, on-device-AI

## ADR-015: Trợ lý AI là một người dùng bot phía server
- source: docs/ARCHITECTURE_DECISIONS.md
- status: proposed
- decision: Tài khoản bot hệ thống 'UTE AI' (is_bot = true). Tin gửi tới bot được server xử lý qua interface LlmClient kết nối API mô hình AI ngoài. Giới hạn 30 lượt/người/ngày, tối đa 2000 ký tự/lượt.
- scope: AI-bot, LLM, chatbot

## ADR-016: Tìm kiếm
- source: docs/ARCHITECTURE_DECISIONS.md
- status: proposed
- decision: Tìm kiếm tin nhắn chạy hoàn toàn trên Room cục bộ qua bảng ảo FTS4. Tìm kiếm người dùng trong danh bạ theo tên trên máy, tìm người lạ chỉ qua SĐT chính xác qua server.
- scope: search, Room-FTS

## ADR-017: Tin nhắn không mã hoá đầu cuối
- source: docs/ARCHITECTURE_DECISIONS.md
- status: proposed
- decision: Tin nhắn được mã hoá trên đường truyền (HTTPS/WSS) và lưu dạng bản rõ có kiểm soát truy cập tại server (mô hình Cloud Chat giống Telegram).
- scope: security, encryption

## ADR-018: Danh tính từ token, phân quyền ở service
- source: docs/ARCHITECTURE_DECISIONS.md
- status: locked
- decision: Server xác định AuthenticatedActor từ JWT (chứa userId, deviceId, iat, exp 30 ngày). App không gửi userId tự khai. Kiểm tra quyền ở tầng service. Thu hồi token lập tức qua token_valid_after và đóng WebSocket khi đăng xuất hoặc xóa thiết bị.
- scope: authentication, authorization, JWT, RBAC

## ADR-019: Một server chung trên AWS
- source: docs/ARCHITECTURE_DECISIONS.md
- status: locked
- decision: Sử dụng một instance EC2 Ubuntu 24.04: Nginx (reverse proxy HTTPS/WSS), server app qua systemd, coturn STUN/TURN. CSDL tại Supabase. Triển khai tự động bằng GitHub Actions.
- scope: infrastructure, AWS, deployment

## ADR-020: Quản lý schema bằng migration
- source: docs/ARCHITECTURE_DECISIONS.md
- status: proposed
- decision: Tắt tính năng tự động tạo bảng của ORM (ddl-auto=none). Quản lý toàn bộ cấu trúc CSDL bằng migration Flyway trong db/migration. Thay đổi cấu trúc dùng chung phải qua duyệt hợp đồng chung.
- scope: database-migration, Flyway

## ADR-021: Giao diện: i18n, chế độ tối, xoay màn hình
- source: docs/ARCHITECTURE_DECISIONS.md
- status: locked
- decision: Không hardcode văn bản giao diện (hỗ trợ đa ngôn ngữ en và vi). Áp dụng Material 3 DayNight cho chế độ sáng/tối. Xử lý xoay màn hình bằng ViewModel và SavedStateHandle. Vùng chạm tương tác tối thiểu 48dp.
- scope: UI, i18n, dark-mode, screen-orientation

## ADR-022: Quy trình làm việc nhóm
- source: docs/ARCHITECTURE_DECISIONS.md
- status: proposed
- decision: Một người phụ trách toàn bộ server kiêm giữ hợp đồng chung và phát triển module Auth Android. Bốn người còn lại chia các module Android độc lập với Fake repository. Quy trình pull request rà soát chéo vòng tròn.
- scope: workflow, git, team-collaboration

## ADR-023: Kiến trúc và công nghệ của app Android bám slide
- source: docs/ARCHITECTURE_DECISIONS.md
- status: locked
- decision: Ứng dụng Android chuẩn Java, View Binding, Single Activity + Navigation Component + Safe Args, MVVM + LiveData, AppExecutors (single disk thread), AppContainer thủ công, Room SQLite, Retrofit + OkHttp + Gson. Không dùng Coroutine/Flow/Compose/Hilt để tuân thủ giáo trình.
- scope: Android-stack, MVVM, Room, Retrofit

## ADR-024: Kiểm thử, chất lượng và phát hành bám slide
- source: docs/ARCHITECTURE_DECISIONS.md
- status: locked
- decision: Chiến lược kiểm thử: JUnit JVM cho unit test, Fake repository cho ViewModel, Mockito cho tương tác, Room in-memory androidTest cho DAO, Espresso cho UI. Lệnh gradlew testDebugUnitTest phải pass 100%. Đóng gói release với R8/ProGuard.
- scope: testing, QA, CI/CD, release
