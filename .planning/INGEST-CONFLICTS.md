# Conflict Detection & Reconciliation Report

> [!NOTE]
> **Trạng thái đối soát và đồng bộ hóa:** Báo cáo này ghi nhận việc giải quyết dứt điểm toàn bộ các điểm lệch giữa tài liệu quy hoạch (`.planning/`) và bộ tài liệu kỹ thuật (`docs/`) sau đợt rà soát cuối cùng, đưa toàn bộ tài liệu về trạng thái sẵn sàng 100% để lập PLAN và triển khai.

### 1. BLOCKERS (0)
Không có blocker kỹ thuật hoặc kiến trúc nào cản trở việc lập kế hoạch Phase 1.

### 2. WARNINGS (0)
Tất cả các điểm vênh, lệch tiền tố, lệch giao thức, phạm vi v1/v2, liên kết và trạng thái ADR đã được đồng thuận và chuẩn hóa đồng bộ. Không còn mâu thuẫn nào chưa được giải quyết.

### 3. CÁC QUYẾT ĐỊNH VÀ ĐỒNG BỘ ĐÃ KHÓA CỨNG (Authoritative Baseline)

| Hạng mục | Quy chuẩn kỹ thuật đã chốt | Tình trạng đồng bộ |
| :--- | :--- | :---: |
| **Giao thức mạng** | Gửi tin nhắn qua REST (`POST /api/conversations/{id}/messages`), nhận tin nhắn thời gian thực qua **WebSocket thuần JSON** (`/ws`), tuyệt đối **không dùng STOMP** (ADR-008). | **RESOLVED** |
| **Tiền tố & Endpoints API** | Tiền tố thống nhất toàn hệ thống là `/api` (không dùng `/api/v1`).<br>• Health probes: `GET /api/health` và `GET /api/health/ready`<br>• Đồng bộ bù tin: `GET /api/sync`<br>• Gửi tin: `POST /api/conversations/{id}/messages`<br>• Media: `POST /api/media` và `GET /api/media/{id}` | **RESOLVED** |
| **Phạm vi CSDL Supabase** | CSDL v1 gồm **10 bảng cốt lõi**: `users`, `user_devices`, `otp_challenges`, `auth_sessions`, `contacts`, `conversations`, `conversation_members`, `messages`, `attachments`, `calls`.<br>4 bảng mở rộng thuộc **v2**: `friend_challenges`, `totp_credentials`, `backup_codes`, `ai_usage`. | **RESOLVED** |
| **Bảo mật Service Key** | File mẫu cấu hình `SUPABASE_SETUP.md` và `.env.example` chỉ chứa placeholder (`SUPABASE_URL=`, `SUPABASE_ANON_KEY=`, `SUPABASE_SERVICE_ROLE_KEY=`). Tuyệt đối **không commit Service Key thật vào Git** (ADR-007). | **RESOLVED** |
| **Hai tính năng sáng tạo v1** | Cả hai chức năng đều thuộc phạm vi v1 (Phase 6):<br>1. **Gọi thoại 1-1 WebRTC** qua WebSocket signaling và coturn STUN/TURN (giải pháp secondary IP cho EC2 để coturn chiếm TCP 443 và UDP 3478/443 vượt tường lửa trường học).<br>2. **Dịch tin nhắn Anh–Việt bằng Google ML Kit Translation** (chạy on-device sau khi gói ngôn ngữ ~30MB được tải lần đầu, lưu Room `message_translation`). | **RESOLVED** |
| **Mở Media & Video ngoài** | Dùng `FileProvider` làm phương án duy nhất. App tải/cache file về bộ nhớ tạm bằng token rồi chia sẻ `content://` qua `FileProvider` kèm cờ `FLAG_GRANT_READ_URI_PERMISSION`. Bỏ hoàn toàn cơ chế `?ticket=` URL. | **RESOLVED** |
| **Android SDK Baseline** | Khóa cứng baseline trên toàn bộ repo:<br>• `minSdk 26`<br>• `compileSdk 35`<br>• `targetSdk 35`<br>Không nâng lên SDK 36. Trong `COURSE_ALIGNMENT.md` ghi nhận: "Điều chỉnh: dự án chốt SDK 35 để ổn định và phù hợp môi trường hiện tại". | **RESOLVED** |
| **Chuẩn hóa Friend Challenge (v2)** | Thiết kế kiến trúc v2 đồng bộ: Một bộ 15 ứng viên (3 bạn thật + 12 mồi); giữ nguyên bộ và đáp án trong cửa sổ 15 phút; mỗi lần sai chỉ xáo trộn thứ tự; không sinh bộ mới; nếu `auth_sessions` hết hạn sau 10 phút thì phiên mới tái sử dụng challenge đang hiệu lực. | **RESOLVED** |
| **Quản lý Thư viện Gradle** | Áp dụng nguyên tắc **Just-in-Time (JIT) dependencies**: Phase 2 chỉ cài đặt dependencies nền tảng (Retrofit, Room, Navigation, ViewBinding). WebRTC, ML Kit, Glide chỉ bổ sung khi bước vào phase sử dụng tương ứng (Phase 6). | **RESOLVED** |
| **Trạng thái ADR & Liên kết** | Toàn bộ các ADR nền tảng v1 đã chuyển sang `ACCEPTED`. Các ADR tính năng nâng cao (2FA, AI) mang trạng thái `DEFERRED_TO_V2`. Toàn bộ link tuyệt đối `file:///C:/Users/Admin/...` đã chuyển sang đường dẫn tương đối (relative paths). | **RESOLVED** |
| **Chuỗi phụ thuộc Roadmap** | Chuỗi phụ thuộc tuần tự nghiêm ngặt 7 Phase:<br>Phase 1 (DB) $\rightarrow$ Phase 2 (Skeleton) $\rightarrow$ Phase 3 (Auth) $\rightarrow$ Phase 4 (Contacts) $\rightarrow$ Phase 5 (Core Messaging) $\rightarrow$ Phase 6 (Media & Creative) $\rightarrow$ Phase 7 (Testing & Release). | **RESOLVED** |
