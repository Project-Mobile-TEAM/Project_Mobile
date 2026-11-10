# Conflict Detection & Reconciliation Report

> [!NOTE]
> **Trạng thái đối soát:** Báo cáo này ghi nhận việc giải quyết dứt điểm 9 điểm lệch giữa tài liệu quy hoạch (`.planning/`) và bộ tài liệu kỹ thuật (`docs/`) trước khi bước vào lập kế hoạch Phase 1.

### 1. BLOCKERS (0)
Không có blocker kiến trúc nào ngăn cản việc triển khai.

### 2. RECONCILED DISCREPANCIES (9 Điểm đã xử lý triệt để)

| STT | Điểm lệch ban đầu | Phương án giải quyết đã chốt | Tình trạng |
| :---: | :--- | :--- | :---: |
| **1** | **Giao thức WebSocket (STOMP vs WebSocket thuần)** | Chuẩn hóa theo ADR-008: Gửi tin qua REST (`POST /api/conversations/{id}/messages`), nhận tin thời gian thực qua WebSocket thuần (JSON payload qua `/ws`), **không dùng STOMP**. Đã sửa trong `REQUIREMENTS.md` và `ROADMAP.md`. | **RESOLVED** |
| **2** | **Tiền tố API (`/api/v1/` vs `/api/`) & `/health/ready`** | Chuẩn hóa toàn bộ tiền tố là `/api/...`. Bổ sung endpoint `GET /api/health` và `GET /api/health/ready` vào mục 2.0 của `API_CONTRACT.md`. Dùng `/api/sync` cho luồng đồng bộ. | **RESOLVED** |
| **3** | **Tính năng chuyển sang v2 vẫn hiển thị trong docs** | Giữ nguyên trong kiến trúc nhưng gắn nhãn `[v2 / Tùy chọn mở rộng - Không thuộc phạm vi v1]` tại `API_CONTRACT.md`, `FLOWS.md`, `ERD.md`, `README.md` và `ARCHITECTURE_DECISIONS.md`. | **RESOLVED** |
| **4** | **Số lượng bảng CSDL (8 bảng vs 14/15 bảng)** | Làm rõ phạm vi: CSDL v1 gồm **10 bảng cốt lõi** (`users`, `user_devices`, `otp_challenges`, `auth_sessions`, `contacts`, `conversations`, `conversation_members`, `messages`, `attachments`, `calls`); 4 bảng còn lại (`friend_challenges`, `totp_credentials`, `backup_codes`, `ai_usage`) thuộc phạm vi v2. | **RESOLVED** |
| **5** | **Rủi ro lộ Supabase Service Key** | Sửa `ROADMAP.md`: File `SUPABASE_SETUP.md` và `.env.example` chỉ chứa placeholder, tuyệt đối **KHÔNG commit Service Key thật vào Git** theo đúng ADR-007. | **RESOLVED** |
| **6** | **Khai báo thư viện sớm ở Phase 2 (Over-engineering)** | Áp dụng nguyên tắc Just-In-Time dependencies: Phase 2 chỉ cài đặt thư viện nền tảng (Retrofit, Room, Navigation, ViewBinding). WebRTC, ML Kit, Glide chỉ thêm khi tới đúng phase. | **RESOLVED** |
| **7** | **Thứ tự phụ thuộc (Dependencies) giữa các Phase** | Chuẩn hóa chuỗi phụ thuộc tuần tự: Phase 1 (DB) $\rightarrow$ Phase 2 (Skeleton) $\rightarrow$ Phase 3 (Auth) $\rightarrow$ Phase 4 (Contacts) $\rightarrow$ Phase 5 (Chat) $\rightarrow$ Phase 6 (Media & Sáng tạo) $\rightarrow$ Phase 7 (Testing & Release). | **RESOLVED** |
| **8** | **Mô tả ngoại tuyến của Google ML Kit Translation** | Làm rõ kỹ thuật: Hoạt động on-device (offline) **sau khi gói mô hình ngôn ngữ (Anh - Việt, ~30MB) đã được tải về lần đầu**. | **RESOLVED** |
| **9** | **Đồng bộ tài liệu intel/ và báo cáo xung đột** | Đã cập nhật `SYNTHESIS.md` và `INGEST-CONFLICTS.md` làm rõ snapshot lịch sử và đồng bộ với 15 requirements hiện hành. | **RESOLVED** |
