# Báo cáo hoàn thành Plan 06-01: One-Way Docs/V6 Gate & Backend Media Storage, Range & Location Tracer

**Thời gian hoàn thành:** 2026-11-22  
**Trạng thái:** ✅ ĐÃ HOÀN THÀNH VÀ VERIFIED 100%

---

## 1. Mục tiêu đạt được
Plan 06-01 đã hoàn thành trọn vẹn cả 3 Task:
1. **Khóa một chiều D-10 & Đồng bộ tài liệu**:
   - `docs/API_CONTRACT.md`: Cập nhật hợp đồng gửi tin nhắn mở rộng (`TEXT`, `IMAGE`, `AUDIO`, `VIDEO`, `FILE`, `LOCATION`), API media upload/download HTTP Range, và hợp đồng cuộc gọi WebRTC 1-1.
   - `docs/FLOWS.md`: Cập nhật quy trình offline media 2 pha (`F-11`), vị trí gọn nhẹ qua `geo:` Intent ngoài (`F-18`), dịch tin nhắn trực tiếp EN<->VI không % ảo (`F-20`), và quy trình cuộc gọi WebRTC (`F-21`, `F-22`).
   - `docs/ERD.md`: Đồng bộ lược đồ CSDL PostgreSQL V6 và 11 cột bảng Room v3 `attachment_transfer`.
2. **PostgreSQL Migration V6 & Cấu hình**:
   - `backend/src/main/resources/db/migration/V6__media_and_calls.sql`: Thêm 6 cột định danh thiết bị và thời hạn cho bảng `calls`, partial unique index `uq_calls_active_caller`/`callee`, `uq_messages_call_id`, `upload_client_id` và `uq_attachments_upload_client`.
   - `.env.example`, `application.yml`, `application-dev.yml`: Cấu hình dung lượng multipart (55MB/60MB), `app.media` (image 10MB, audio 10MB, video 50MB, file 25MB), và `app.coturn`.
   - `GlobalExceptionHandler.java`: Bắt `MaxUploadSizeExceededException` trả về HTTP `413 FILE_TOO_LARGE`.
   - `FlywayMigrationTest.java`: Kiểm chứng migration V6 thành công trên EmbeddedPostgres, kiểm tra đủ cột, index và RLS.
3. **Triển khai Backend Media Storage, HTTP Range & Tracer Slice**:
   - `MediaDtos.java`: Record bất biến Java 17 cho upload response, meta và result.
   - `MediaUploadRateLimiter.java`: Sliding window log 20 uploads / 60 giây / user.
   - `MediaService.java`: Lưu trữ tệp cô lập `<MEDIA_STORAGE_DIR>/<yyyy>/<MM>/<uuid>.<ext>`, kiểm tra magic bytes (JPEG/PNG/WebP, AAC/M4A, MP4/WebM, PDF/ZIP/UTF-8), chống thực thi/script, chống APK, kiểm soát thời lượng audio $\le 300$s, idempotent replay qua `(uploader_id, upload_client_id)`, và RBAC tải tệp chặt chẽ (`message.id > visible_from_message_id`).
   - `MediaController.java`: Endpoint `POST /api/media` (multipart) và `GET /api/media/{id}` (HTTP Range 206/416, bearer token only, nosniff).
   - `MessageService.java`: Mở rộng `sendMessage` hỗ trợ `IMAGE`, `AUDIO`, `VIDEO`, `FILE`, `LOCATION` (kiểm tra biên độ tọa độ, khóa dòng attachment `FOR UPDATE`, chống gắn trùng file qua `uq_messages_attachment`).
   - `MediaStorageAndRangeApiTest.java`: Suite kiểm thử tích hợp toàn diện trên EmbeddedPostgres.

---

## 2. Danh sách file thay đổi & tạo mới
- `docs/API_CONTRACT.md`
- `docs/FLOWS.md`
- `docs/ERD.md`
- `.env.example`
- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/db/migration/V6__media_and_calls.sql`
- `backend/src/main/java/com/utechat/backend/exception/GlobalExceptionHandler.java`
- `backend/src/main/java/com/utechat/backend/dto/MediaDtos.java`
- `backend/src/main/java/com/utechat/backend/service/MediaUploadRateLimiter.java`
- `backend/src/main/java/com/utechat/backend/service/MediaService.java`
- `backend/src/main/java/com/utechat/backend/controller/MediaController.java`
- `backend/src/main/java/com/utechat/backend/service/MessageService.java`
- `backend/src/test/java/com/utechat/backend/config/FlywayMigrationTest.java`
- `backend/src/test/java/com/utechat/backend/media/MediaStorageAndRangeApiTest.java`

---

## 3. Xác minh kiểm thử (Verification)
Các bộ test sau đều pass 100% trên `EmbeddedPostgres`:
- `FlywayMigrationTest`
- `MessagingConcurrencyAndPolicyTest`
- `MediaStorageAndRangeApiTest`
Build status: **`BUILD SUCCESS`** (0 Failures, 0 Errors).
