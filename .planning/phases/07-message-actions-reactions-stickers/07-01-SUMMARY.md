---
phase: 07-message-actions-reactions-stickers
plan: "01"
status: complete
---

# 07-01 — Hợp đồng và thao tác tin nhắn

Commits trên `feature/phase-7-core` gồm `14d5f4d`, `7da4c8e`, `87dab77`.

Đồng bộ API, FLOWS và ERD. Flyway V9 thêm bảng cho thao tác tin nhắn và nhãn dán. Backend áp dụng quyền gỡ trong 24 giờ, xóa phía người dùng, thay hoặc bỏ cảm xúc và thu hồi quyền tải media khi gỡ. API lịch sử, xem trước và đồng bộ trả dữ liệu theo quyền của từng người.

## Kiểm chứng

`FlywayMigrationTest` xác nhận nâng từ V8 lên V9 và RLS. `MessageActionsAndReactionsApiTest` đạt 5/5 trên EmbeddedPostgres, bao gồm kiểm tra giới hạn 24 giờ, quyền hội thoại và dữ liệu trả về sau gỡ/xóa.
