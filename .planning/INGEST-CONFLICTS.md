# Conflict Detection & Reconciliation Report

> [!NOTE]
> **Trạng thái đối soát và đồng bộ hóa:** Báo cáo này ghi nhận việc giải quyết dứt điểm toàn bộ các điểm lệch giữa tài liệu quy hoạch (`.planning/`) và bộ tài liệu kỹ thuật (`docs/`) sau đợt rà soát chuẩn hóa V1, đưa toàn bộ tài liệu về trạng thái sẵn sàng 100% để lập PLAN và triển khai.

### 1. BLOCKERS (0)
Không có blocker kỹ thuật hoặc kiến trúc nào cản trở việc lập kế hoạch Phase 1.

### 2. WARNINGS (0)
Các điểm vênh, lệch tiền tố, lệch giao thức và phạm vi V1/V2 đã được đồng thuận và chuẩn hóa. Quy tắc ưu tiên `BLOCKED`, đồng bộ trạng thái cuộc gọi, API danh sách chặn và ràng buộc attachment được ghi rõ trong các tài liệu hiện hành.

### 3. CÁC QUYẾT ĐỊNH VÀ ĐỒNG BỘ ĐÃ KHÓA CỨNG (Authoritative Baseline)

| Hạng mục | Quy chuẩn kỹ thuật đã chốt | Tình trạng đồng bộ |
| :--- | :--- | :---: |
| **Giao thức mạng** | Gửi tin nhắn qua REST (`POST /api/conversations/{id}/messages`), nhận tin nhắn thời gian thực qua **WebSocket thuần JSON** (`/ws`), tuyệt đối **không dùng STOMP** (ADR-008). | **RESOLVED** |
| **Tiền tố & Endpoints API** | Tiền tố thống nhất toàn hệ thống là `/api` (không dùng `/api/v1`).<br>• Health probes: `GET /api/health` và `GET /api/health/ready`<br>• Đồng bộ bù tin: `GET /api/sync?after=<syncEventId>`<br>• Gửi tin: `POST /api/conversations/{id}/messages`<br>• Media: `POST /api/media` và `GET /api/media/{id}` | **RESOLVED** |
| **Phạm vi CSDL Supabase** | CSDL V1 gồm **đúng 13 bảng cốt lõi**: `users`, `user_devices`, `otp_challenges`, `auth_sessions`, `auth_throttles`, `contact_requests`, `contacts`, `conversations`, `conversation_members`, `messages`, `attachments`, `calls`, `sync_events`.<br>Các bảng mở rộng `friend_challenges`, `totp_credentials`, `backup_codes`, `ai_usage` chỉ là định hướng V2, không nằm trong schema triển khai V1. | **RESOLVED** |
| **Bảo vệ Brute-force & Throttle** | Bảng `auth_throttles` kiểm soát thử sai và khóa tạm thời theo `(scope_type, scope_key_hash, purpose)`, không lưu số điện thoại hoặc device key thô. `scope_type` hỗ trợ `PHONE`, `DEVICE`, hoặc `PHONE_DEVICE` (`SHA-256(phone_e164 + ":" + device_id_hash)`). Khóa riêng biệt theo thiết bị, thiết bị mới không bị lây khóa; đổi mật khẩu/thu hồi qua `/account/devices` độc lập. | **RESOLVED** |
| **Danh bạ & Quan hệ Chặn** | Quản lý kết bạn qua `contact_requests` (`PENDING`, `ACCEPTED`, `REJECTED`, `CANCELED`, chống gửi ngược chiều trùng lặp). Bảng `contacts` với `blocked = false` đại diện bạn bè đã chấp nhận (tạo 2 dòng hai chiều trong cùng transaction; hủy kết bạn xóa cả hai chiều). Dòng `blocked = true` trong `contacts` đại diện quan hệ chặn một chiều (chặn bạn bè hoặc chặn người lạ không qua kết bạn; bỏ chặn xóa dòng chặn người lạ hoặc gán `blocked = false` nếu trước đó là bạn). | **RESOLVED** |
| **Chính sách Nhắn tin Người lạ** | Cột `users.allow_messages_from_non_contacts` (mặc định `true`). Khi `false`, chỉ bạn bè `ACCEPTED` mới được gửi tin nhắn 1-1 (người lạ bị từ chối bằng HTTP 403 `CONTACT_REQUIRED`). Thêm vào nhóm và gọi thoại WebRTC bắt buộc bạn bè `ACCEPTED` và không bị block. | **RESOLVED** |
| **Cơ chế Đồng bộ & Thông báo** | Bảng `sync_events` (`JSONB`, retention 30 ngày) ghi nhận toàn bộ sự kiện. WebSocket mang `syncEventId` khớp `sync_events`. Client dùng `lastSyncEventId` làm con trỏ `GET /api/sync?after=<syncEventId>`. Không dùng FCM; thông báo hiển thị qua `NotificationChannel` khi online và `WorkManager` đồng bộ nền 15 phút best-effort (không cam kết tức thời khi app bị force-stop). | **RESOLVED** |
| **Bảo mật Service Key** | File mẫu cấu hình `SUPABASE_SETUP.md` và `.env.example` chỉ chứa placeholder (`SUPABASE_URL=`, `SUPABASE_ANON_KEY=`, `SUPABASE_SERVICE_ROLE_KEY=`). Tuyệt đối **không commit Service Key thật vào Git** (ADR-007). | **RESOLVED** |
| **Hai tính năng sáng tạo V1** | Cả hai chức năng đều thuộc phạm vi V1 (Phase 6):<br>1. **Gọi thoại 1-1 WebRTC** qua WebSocket signaling và coturn STUN/TURN (giải pháp secondary IP cho EC2 để coturn chiếm TCP 443 và UDP 3478/443 vượt tường lửa trường học).<br>2. **Dịch tin nhắn Anh–Việt bằng Google ML Kit Translation** (chạy on-device sau khi gói ngôn ngữ ~30MB được tải lần đầu, lưu Room `message_translation`). | **RESOLVED** |
| **Mở Media & Video ngoài** | Dùng `FileProvider` làm phương án duy nhất. App tải/cache file về bộ nhớ tạm bằng token rồi chia sẻ `content://` qua `FileProvider` kèm cờ `FLAG_GRANT_READ_URI_PERMISSION`. Bỏ hoàn toàn cơ chế `?ticket=` URL. | **RESOLVED** |
| **Android SDK Baseline** | Khóa cứng baseline trên toàn bộ repo:<br>• `minSdk 26`<br>• `compileSdk 35`<br>• `targetSdk 35`<br>• Package: `com.utechat`<br>Không nâng lên SDK 36. Trong `COURSE_ALIGNMENT.md` ghi nhận: "Điều chỉnh: dự án chốt SDK 35 để ổn định và phù hợp môi trường hiện tại". | **RESOLVED** |
| **Quản lý Thư viện Gradle** | Áp dụng nguyên tắc **Just-in-Time (JIT) dependencies**: Phase 2 chỉ cài đặt dependencies nền tảng (Retrofit, Room, Navigation, ViewBinding). WebRTC, ML Kit, Glide chỉ bổ sung khi bước vào phase sử dụng tương ứng (Phase 6). | **RESOLVED** |
| **Trạng thái ADR & Liên kết** | Toàn bộ các ADR nền tảng V1 đã chuyển sang `ACCEPTED`. Các tính năng V2 chỉ ghi ở mục Future/V2. Toàn bộ link tuyệt đối đã chuyển sang đường dẫn tương đối (relative paths). | **RESOLVED** |
| **Chuỗi phụ thuộc Roadmap** | Chuỗi phụ thuộc tuần tự nghiêm ngặt 7 Phase:<br>Phase 1 (DB 13 bảng) $\rightarrow$ Phase 2 (Skeleton) $\rightarrow$ Phase 3 (Auth) $\rightarrow$ Phase 4 (Contacts) $\rightarrow$ Phase 5 (Core Messaging) $\rightarrow$ Phase 6 (Media & Creative) $\rightarrow$ Phase 7 (Testing & Release). | **RESOLVED** |
